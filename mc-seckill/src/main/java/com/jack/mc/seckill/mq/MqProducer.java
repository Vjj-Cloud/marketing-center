package com.jack.mc.seckill.mq;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.common.message.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * MQ 生产者封装。
 * <p>
 * 为什么不用 rocketmq-spring-boot-starter：
 * 1. starter 对 Boot 3.2 的自动配置有兼容坑，一旦踩上很难查；
 * 2. 自己封装能把「启动时机、发送超时、发送失败怎么兜」讲清楚，
 *    这是面试官真正会追问的地方。
 * <p>
 * producer.start() 允许失败：MQ 只是链路的一环，不是启动的必要条件。
 * MQ 挂了服务仍然要能起，抢购请求先写本地消息表，等 broker 恢复后由补偿任务重投。
 */
@Component
public class MqProducer {

    private static final Logger log = LoggerFactory.getLogger(MqProducer.class);

    /**
     * 单次发送超时 1 秒。
     * <p>
     * 原来给 3 秒是错的：3 秒 × (1 + 2 次重试) = 9 秒，已经超过网关的 5 秒响应超时。
     * 结果是网关先给用户返回 504，而后端还在重试，最后消息发成功了、订单也建了，
     * 用户却以为失败又下一单——超时时间必须小于上游超时，这是硬约束。
     */
    private static final int SEND_TIMEOUT_MS = 1000;

    /** 1 次重试 → 最坏 2 秒，加上 DB 与 Redis 约 0.2 秒，仍稳稳落在网关 5 秒内 */
    private static final int RETRY_TIMES = 1;

    private final String namesrvAddr;
    private final String producerGroup;
    private final DefaultMQProducer producer;

    public MqProducer(@Value("${rocketmq.name-server}") String namesrvAddr,
                      @Value("${rocketmq.producer.group}") String producerGroup) {
        this.namesrvAddr = namesrvAddr;
        this.producerGroup = producerGroup;
        this.producer = new DefaultMQProducer(producerGroup);
        this.producer.setNamesrvAddr(namesrvAddr);
        this.producer.setSendMsgTimeout(SEND_TIMEOUT_MS);
        this.producer.setRetryTimesWhenSendFailed(RETRY_TIMES);
    }

    @PostConstruct
    public void start() {
        try {
            producer.start();
            log.info("RocketMQ producer 启动成功, nameServer={}, group={}", namesrvAddr, producerGroup);
        } catch (Exception e) {
            // 不抛异常：MQ 不可用时服务仍应能启动，积压的消息由补偿任务重投
            log.error("RocketMQ producer 启动失败，MQ 相关功能将不可用. nameServer={}", namesrvAddr, e);
        }
    }

    /**
     * 同步发送，返回 broker 是否明确确认收到。
     * <p>
     * <b>必须检查 SendStatus，不能只看有没有抛异常</b>：
     * producer.send() 正常返回、但 SendStatus 是 FLUSH_DISK_TIMEOUT /
     * FLUSH_SLAVE_TIMEOUT / SLAVE_NOT_AVAILABLE 的情况是存在的——
     * 这时消息可能只写进了 PageCache 还没落盘，broker 重启就丢。
     * 不判断的话等于把「可能丢消息」当成「发送成功」。
     * <p>
     * 返回 false 不再意味着「必须回滚库存」，只意味着「还没确认」，
     * 由本地消息表的补偿任务重投。
     */
    public boolean send(String topic, String tag, String key, String body) {
        try {
            Message message = new Message(topic, tag, key, body.getBytes(StandardCharsets.UTF_8));
            SendResult result = producer.send(message);
            if (result == null || result.getSendStatus() != SendStatus.SEND_OK) {
                log.warn("MQ 未确认收到 topic={} key={} status={}",
                        topic, key, result == null ? "null" : result.getSendStatus());
                return false;
            }
            log.debug("MQ 发送成功 topic={} key={} msgId={}", topic, key, result.getMsgId());
            return true;
        } catch (Exception e) {
            // 超时也走这里。注意：超时 ≠ 没发出去，broker 可能已经收下了，
            // 只是响应在网络上丢了。所以调用方不能据此直接回滚库存。
            log.error("MQ 发送失败 topic={} key={}", topic, key, e);
            return false;
        }
    }

    public String getNamesrvAddr() {
        return namesrvAddr;
    }

    @PreDestroy
    public void shutdown() {
        try {
            producer.shutdown();
        } catch (Exception e) {
            log.warn("producer 关闭异常", e);
        }
    }
}
