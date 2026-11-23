package com.jack.mc.common.message;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 秒杀成功后投递给订单服务的消息体。
 * <p>
 * 放在 mc-common 而不是各自模块里：生产者和消费者必须用同一份定义，
 * 各写一份迟早会出现字段名不一致（比如一边叫 price、一边叫 seckillPrice），
 * 表现是消费端反序列化后价格是 null，落库变 0 元单。
 * <p>
 * 只放「落库必需」的字段，不放商品名这类展示信息：
 * 消息体越瘦，MQ 存储和网络开销越小，且不会因为商品改名而需要重发历史消息。
 */
@Data
public class SeckillMessage {

    /**
     * 本次扣减的唯一预订号。
     * <p>
     * 只靠 userId + activityId 无法区分「旧的中止记录」和「本次重新扣减」。
     * 每次请求生成新的 reservationId，并同时写入 Redis pending 与 outbox，
     * 让孤儿恢复、入口确认和订单消费都能校验同一笔预订。
     */
    private String reservationId;

    private Long activityId;

    private Long userId;

    private Long productId;

    /** 秒杀价，落库时以它为准，不要在消费端重新查价格——价格可能已变 */
    private BigDecimal seckillPrice;

    /** 客户端幂等键，可选 */
    private String requestId;

    /** 投递时间戳，便于排查消息积压 */
    private Long sendTime;
}
