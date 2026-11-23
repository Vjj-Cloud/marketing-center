package com.jack.mc.common.constant;

/**
 * MQ Topic / Tag 常量。
 * <p>
 * 放在 mc-common 而不是生产者所在的模块：生产者和消费者必须引用同一个常量。
 * 各写一份的后果是「消息发出去了但没人消费」，而 RocketMQ 不会报任何错，
 * 只能靠查 Topic 列表和消费位点才能发现——这类问题排查成本极高。
 */
public final class MqTopics {

    /** 秒杀成功 → 异步创建订单 */
    public static final String SECKILL_ORDER = "SeckillOrderTopic";

    public static final String TAG_CREATE = "create";

    private MqTopics() {
    }
}
