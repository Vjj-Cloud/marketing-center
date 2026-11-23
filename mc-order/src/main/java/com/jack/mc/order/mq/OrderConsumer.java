package com.jack.mc.order.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jack.mc.common.constant.MqTopics;
import com.jack.mc.common.message.SeckillMessage;
import com.jack.mc.order.entity.SeckillOrder;
import com.jack.mc.order.mapper.OrderMapper;
import com.jack.mc.order.service.OrderPersistService;
import com.jack.mc.order.service.OrderRejectedException;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyContext;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.client.consumer.listener.MessageListenerConcurrently;
import org.apache.rocketmq.common.message.MessageExt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 秒杀订单消费者。
 * <p>
 * <b>幂等靠什么</b>：不靠消费位点，也不靠 Redis 的「消费过」标记
 * （那个会丢、会过期），而是靠数据库唯一索引 uk_user_activity。
 * 重复消息进来时 INSERT 直接撞唯一键，捕获 DuplicateKeyException 当成功返回。
 * 这是唯一能扛住「消息重投 + 消费者重启 + Redis 清空」三种情况的方案。
 * <p>
 * <b>为什么失败要返回 RECONSUME_LATER</b>：RocketMQ 会按退避策略重试
 * （默认 16 次，间隔从秒级到小时级），超过后进 %DLQ% 死信队列。
 * 如果这里 catch 住异常返回 CONSUME_SUCCESS，消息就永久丢了——
 * 用户抢到了却没有订单，还查不出原因。
 * <p>
 * <b>消费失败必须是可重试的</b>，所以 create 里遇到「库存不足」抛的是
 * IllegalStateException 而不是业务异常——业务异常代表「不该重试」。
 * <p>
 * <b>哪些错误不该重试（直接确认丢弃）</b>：
 * <ul>
 *   <li>消息体反序列化失败——脏消息，重试一万次结果一样；</li>
 *   <li>预留已失效被拒（OrderRejectedException）——库存已归还，这单不该存在。</li>
 * </ul>
 * 判断标准只有一条：<b>下次重试时输入变了没有</b>。
 * 输入不变（body 不变、预留状态不变）就该丢弃，输入会变（库存、订单号、临时故障）才值得重试。
 */
@Component
public class OrderConsumer implements ApplicationRunner, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(OrderConsumer.class);

    private final String namesrvAddr;
    private final String consumerGroup;
    private final String topic;
    private final String tag;
    private final OrderPersistService persistService;
    private final OrderMapper orderMapper;
    private final ObjectMapper objectMapper;

    private volatile DefaultMQPushConsumer consumer;

    public OrderConsumer(@Value("${rocketmq.name-server}") String namesrvAddr,
                         @Value("${rocketmq.consumer.group}") String consumerGroup,
                         @Value("${rocketmq.consumer.topic}") String topic,
                         @Value("${rocketmq.consumer.tag:create}") String tag,
                         OrderPersistService persistService,
                         OrderMapper orderMapper,
                         ObjectMapper objectMapper) {
        this.namesrvAddr = namesrvAddr;
        this.consumerGroup = consumerGroup;
        this.topic = topic;
        this.tag = tag;
        this.persistService = persistService;
        this.orderMapper = orderMapper;
        this.objectMapper = objectMapper;
    }

    @Override
    public void run(ApplicationArguments args) {
        start();
    }

    /**
     * 启动失败不阻塞应用启动，但每 30 秒重试一次。
     * <p>
     * 如果启动即抛异常，整个订单服务会起不来，连查询接口也一起不可用；
     * 而 MQ 短暂不可用是很常见的事（broker 重启、网络抖动）。
     * 让服务先起来、消费能力后恢复，比整体不可用更好。
     */
    @Scheduled(fixedDelay = 30_000L, initialDelay = 30_000L)
    public void ensureStarted() {
        if (consumer == null) {
            log.info("检测到消费者未启动，尝试重新启动");
            start();
        }
    }

    private synchronized void start() {
        if (consumer != null) {
            return;
        }
        try {
            DefaultMQPushConsumer c = new DefaultMQPushConsumer(consumerGroup);
            c.setNamesrvAddr(namesrvAddr);
            // 只订阅下单 tag：同一个 topic 以后可能有取消、退款等 tag，
            // 用 * 订阅会让订单服务收到不该它处理的消息
            c.subscribe(topic, tag);
            // 并发消费：秒杀消息之间没有顺序要求（每单独立），
            // 用顺序消费反而会把吞吐压到单线程
            c.setConsumeThreadMin(20);
            c.setConsumeThreadMax(64);
            c.setConsumeMessageBatchMaxSize(1);
            // 显式指定重试次数，方便面试时讲清楚「重试 16 次后进死信队列」
            c.setMaxReconsumeTimes(16);

            c.registerMessageListener((MessageListenerConcurrently) this::consume);
            c.start();
            this.consumer = c;
            log.info("订单消费者启动成功 group={} topic={} tag={}", consumerGroup, topic, tag);
        } catch (Exception e) {
            log.error("订单消费者启动失败，30 秒后重试. nameServer={}", namesrvAddr, e);
        }
    }

    private ConsumeConcurrentlyStatus consume(List<MessageExt> messages, ConsumeConcurrentlyContext context) {
        for (MessageExt message : messages) {
            // ★ 反序列化单独拿出来，不能和业务异常混在同一个 catch 里。
            // body 非法（脏投递、上游序列化 bug、手工 mqadmin 投错）时，
            // 无论重试多少次 readValue 都会抛同样的异常——这是典型的「不可重试错误」。
            // 之前它落进下面的 catch (Exception) 返回 RECONSUME_LATER，
            // 结果一条脏消息按 10s/30s/1m/.../2h 的退避节奏重投 16 次，
            // 日志每隔几分钟刷一屏 JsonParseException，真正的故障被淹没。
            // 注意：并发消费下返回 RECONSUME_LATER 不会堵住位点（消息被转到重试 topic，
            // 其他消息照常消费），但这条脏消息会一直挂在重试队列里，
            // 直到 16 次耗尽进 %DLQ%，中间没人能解释它是什么。
            // 正确处理：记 error（要能告警）+ 确认消费（丢弃），让它尽快离开重试队列。
            SeckillMessage payload;
            try {
                payload = objectMapper.readValue(message.getBody(), SeckillMessage.class);
            } catch (Exception e) {
                log.error("消息体无法解析，确认消费并丢弃 msgId={} key={} reconsumeTimes={} body={}",
                        message.getMsgId(), message.getKeys(), message.getReconsumeTimes(),
                        preview(message.getBody()), e);
                continue;
            }
            if (payload == null) {
                log.error("消息体解析结果为 null，确认消费并丢弃 msgId={} key={}",
                        message.getMsgId(), message.getKeys());
                continue;
            }
            try {
                SeckillOrder created = persistService.create(payload);
                if (created == null) {
                    log.info("outbox 已消费，幂等跳过 msgId={} key={}",
                            message.getMsgId(), message.getKeys());
                }
            } catch (DuplicateKeyException e) {
                if (isRealDuplicate(payload)) {
                    // 幂等：这条消息之前已经被处理过（重投或重试导致）。
                    // 注意日志级别用 info 不用 error——重复消费是设计内的正常现象，
                    // 打成 error 会让真正的问题淹没在告警里。
                    log.info("重复消息，已幂等跳过 msgId={} key={}", message.getMsgId(), message.getKeys());
                } else {
                    // ★ 第二轮评审 P1：不能把所有 DuplicateKeyException 都当成重复消费。
                    // 订单表上有两个唯一键——uk_user_activity 和 uk_order_no。
                    // 撞前者是重复消息（幂等跳过），撞后者是「订单号重复」：
                    // Redis 发号器的 key 丢过一次、序列从头开始，新订单号撞上历史订单号。
                    // 这时直接确认成功，这单就被静默丢弃了 —— 用户抢到了却永远查不到订单，
                    // 而且日志里只有一条「重复消息」，完全看不出问题。
                    // 正确做法是让它重试：下一轮会重新取号，撞号大概率自愈。
                    log.error("唯一键冲突但查不到对应用户订单，疑似订单号撞号，等待重试 "
                                    + "msgId={} key={} reconsumeTimes={}",
                            message.getMsgId(), message.getKeys(), message.getReconsumeTimes(), e);
                    return ConsumeConcurrentlyStatus.RECONSUME_LATER;
                }
            } catch (OrderRejectedException e) {
                // 预留已失效（人工回滚 / 入口中止，库存已归还），这一单不能再落库。
                // 必须确认消费而不是 RECONSUME_LATER：重试一万次结果都一样，
                // 只会白占重试队列，最后进死信队列还要人去解释。
                // 打 error 是因为这属于「客户已经抢到、后来被人工退掉」的异常业务结果，
                // 运维应该能从告警里看到它。
                log.error("预留已失效，拒绝落库并确认消费 msgId={} key={} {}",
                        message.getMsgId(), message.getKeys(), e.getMessage());
            } catch (Exception e) {
                log.error("消费失败，等待 RocketMQ 重试 msgId={} reconsumeTimes={} body={}",
                        message.getMsgId(), message.getReconsumeTimes(),
                        preview(message.getBody()), e);
                // 立刻返回，让这一批整体重试；后面的消息下一轮再处理
                return ConsumeConcurrentlyStatus.RECONSUME_LATER;
            }
        }
        return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
    }

    /**
     * 日志里打消息体：限制长度并按 UTF-8 解码。
     * <p>
     * 两点考虑：一是脏消息的 body 可能很大（几 MB 的畸形报文能把日志盘写满），
     * 二是 {@code new String(byte[])} 用平台默认字符集，Windows 下是 GBK，
     * 中文会变乱码——排查时反而看不出原样。
     */
    private static String preview(byte[] body) {
        if (body == null) {
            return "null";
        }
        if (body.length <= 512) {
            return new String(body, StandardCharsets.UTF_8);
        }
        return new String(body, 0, 512, StandardCharsets.UTF_8) + "...(truncated, total=" + body.length + " bytes)";
    }

    /**
     * 判断这次唯一键冲突到底是「重复消息」还是「订单号撞号」。
     * <p>
     * 判据只有一个：<b>订单表里是不是真的有这个用户对这个活动的那一单</b>。
     * 有 → 说明之前消费成功过，这次是重复投递，可以确认；
     * 没有 → 说明冲突来自另一个唯一键（uk_order_no），必须重试。
     * <p>
     * 查库会不会有并发问题：这里是在事务外查询。{@code persistService.create}
     * 抛异常时事务已经回滚并释放，此刻读到的是已提交数据，
     * 不存在「读到未提交的脏单」的情况；而如果别的消费者刚提交了同一单，
     * 读到了正好说明该幂等跳过，也正确。
     * <p>
     * 唯一可能查错的场景是「并行两个线程同时处理同一条消息、A 已提交 B 还没提交」，
     * 此时 B 读到 0 行 → 判成撞号 → 重试一次，下一轮就能读到并幂等跳过。
     * 代价只是一次多余的重试，换来的是「绝不把撞号的单静默吞掉」。
     *
     * @return true 表示确实是重复消息
     */
    private boolean isRealDuplicate(SeckillMessage payload) {
        SeckillOrder existing = orderMapper.findByUserAndActivity(
                payload.getUserId(), payload.getActivityId());
        return existing != null;
    }

    @Override
    public void destroy() {
        if (consumer != null) {
            consumer.shutdown();
            log.info("订单消费者已关闭");
        }
    }
}
