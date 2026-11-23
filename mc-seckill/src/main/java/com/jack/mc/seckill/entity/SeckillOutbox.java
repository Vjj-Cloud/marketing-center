package com.jack.mc.seckill.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 本地消息表（Transactional Outbox）。
 * <p>
 * 存在意义：把「要发一条 MQ 消息」这个意图，和「已经扣了 Redis 库存」这个事实，
 * 用一条数据库记录绑定起来。只要这条记录在，消息迟早会被投出去；
 * 投递失败不再是「必须立刻回滚」的二选一，而是变成「稍后重试」。
 * <p>
 * 对应代码评审里的问题：MQ 发送抛异常时，broker 可能已经收到消息、只是响应超时。
 * 此时回滚库存 → 消费者照常创建订单 → 超卖。有了 outbox，
 * 发送结果未知就不回滚，交给补偿任务重投，消费端用唯一索引兜重复。
 */
@Data
@TableName("seckill_outbox")
public class SeckillOutbox {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long activityId;

    private Long userId;

    /** 本次扣减唯一预订号，和 Redis pending / MQ 消息中的值一致 */
    private String reservationId;

    /** 消息体快照。重投时必须发这一份，不能重新组装——期间价格可能已变 */
    private String payload;

    /** 0 待投递 / 1 已投递 / 2 已消费 / 3 人工回滚 / 4 待人工复核 / 5 回滚执行中 / 6 已中止 */
    private Integer status;

    private Integer retryCount;

    /**
     * 「已投递但查不到订单」被打回重投的次数。
     * <p>
     * 和 retry_count 分开记，因为两者的失败原因不同：retry_count 记的是
     * 「broker 没确认发送成功」，reconcile_count 记的是「发出去了但消费端不干活」。
     * 混用一个计数会让「broker 抖了 3 次 + 消费者挂了一次」直接跳过高阈值。
     * <p>
     * 这一列存在的意义是给对账一个终点（第四轮评审 P2）：
     * 没有它的话，消费者永久故障时记录会在 SENT → PENDING → SENT 之间无限循环，
     * 既不会进待复核，也不会告警，只会每 5 分钟刷一条日志。
     */
    private Integer reconcileCount;

    /** 退避后的下次可重投时间，避免 broker 故障时把重试打成忙等 */
    private LocalDateTime nextRetryAt;

    private String lastError;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
