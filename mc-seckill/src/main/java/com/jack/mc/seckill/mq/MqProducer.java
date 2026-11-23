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
import org.springframework.scheduling.annotation.Scheduled;
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
 * <p>
 * 但「允许失败」不等于「失败就算了」：失败后靠 {@link #ensureStarted()} 每 30 秒重试，
 * 否则一次启动期的网络抖动会让这个进程的发送能力一直失效到下次重启。
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

    /**
     * 每次启动都新建一个实例，而不是复用字段里的那个。
     * <p>
     * {@code DefaultMQProducer.start()} 失败后内部状态可能停在不可恢复的位置，
     * 再对同一个对象调用 start() 很容易抛 "service state not OK"——
     * 那等于「重启路径」本身也永远起不来。新建一个对象是唯一确定的做法，
     * 订单消费者 {@code OrderConsumer} 用的也是这个套路。
     * <p>
     * volatile + 不为 null 即「已启动」：发送线程只读这两个字段，不加锁。
     */
    private volatile DefaultMQProducer producer;
    private volatile boolean started;

    public MqProducer(@Value("${rocketmq.name-server}") String namesrvAddr,
                      @Value("${rocketmq.producer.group}") String producerGroup) {
        this.namesrvAddr = namesrvAddr;
        this.producerGroup = producerGroup;
    }

    @PostConstruct
    public void start() {
        if (started) {
            return;
        }
        // 只在启动时串行化。发送路径不加锁：broker 挂掉时 start() 可能阻塞数秒，
        // 让抢购线程排队等它等于把 MQ 故障放大成接口超时
        synchronized (this) {
            if (started) {
                return;
            }
            DefaultMQProducer candidate = new DefaultMQProducer(producerGroup);
            candidate.setNamesrvAddr(namesrvAddr);
            candidate.setSendMsgTimeout(SEND_TIMEOUT_MS);
            candidate.setRetryTimesWhenSendFailed(RETRY_TIMES);
            try {
                candidate.start();
                this.producer = candidate;
                this.started = true;
                log.info("RocketMQ producer 启动成功, nameServer={}, group={}", namesrvAddr, producerGroup);
            } catch (Exception e) {
                // 不抛异常：MQ 不可用时服务仍应能启动，积压的消息由补偿任务重投
                log.error("RocketMQ producer 启动失败，30 秒后重试. nameServer={}", namesrvAddr, e);
                try {
                    candidate.shutdown();
                } catch (Exception ignore) {
                    // 启动失败的实例没什么可关的，关不掉也不影响接下来新建一个
                }
            }
        }
    }

    /**
     * 启动失败后的自愈路径（第七轮评审 P2）。
     * <p>
     * 没有这个任务时，一次客户端初始化异常（配置非法、client 实例创建失败）
     * 会让这个进程的发送能力<b>永久</b>失效，直到有人重启服务——
     * 表现是「补偿任务每 5 秒记一次重投失败，库存和消息都堆在本地消息表里」。
     * 订单消费者早就有 {@code ensureStarted()}，生产者这边一直缺一条对称的路。
     * <p>
     * <b>实测边界（别把这条当成「MQ 挂了会自愈」）</b>：停掉 NameServer 再启动服务，
     * {@code start()} 依然成功——它不依赖 NameServer 可达，只初始化客户端；
     * 真正失败的是之后的 {@code send()}（{@code No route info of this topic}），
     * 那个由本地消息表兜住。这条只兜「客户端没起来」这一类，属于防御性修复。
     * <p>
     * 30 秒的节奏是照抄消费者的：足够从偶发故障里恢复，又不会在长期故障里刷日志。
     */
    @Scheduled(fixedDelay = 30_000L, initialDelay = 30_000L)
    public void ensureStarted() {
        if (!started) {
            log.info("检测到 producer 未启动，尝试重新启动 nameServer={}", namesrvAddr);
            start();
        }
    }

    public boolean isStarted() {
        return started;
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
        DefaultMQProducer current = this.producer;
        if (current == null) {
            // 未启动：不在这里同步重试 start()，那会把秒级的启动耗时压到抢购线程上。
            // 补偿任务的 30 秒自愈会把它拉起来，期间消息都安全地留在本地消息表里
            log.debug("producer 未启动，消息留待补偿重投 topic={} key={}", topic, key);
            return false;
        }
        try {
            Message message = new Message(topic, tag, key, body.getBytes(StandardCharsets.UTF_8));
            SendResult result = current.send(message);
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
        DefaultMQProducer current = this.producer;
        this.started = false;
        this.producer = null;
        if (current == null) {
            return;
        }
        try {
            current.shutdown();
        } catch (Exception e) {
            log.warn("producer 关闭异常", e);
        }
    }
}
