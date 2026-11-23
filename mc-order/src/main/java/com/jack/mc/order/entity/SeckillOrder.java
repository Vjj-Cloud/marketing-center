package com.jack.mc.order.entity;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 秒杀订单，对应 seckill_order 表。
 * <p>
 * 没有用 MyBatis-Plus 的 BaseMapper，而是手写 @Insert / @Select：
 * 这个表的写入路径只有「消费者落库」一条，字段固定，手写 SQL 反而更容易
 * 看清「唯一键冲突时到底哪一列撞了」，也避免为了一个实体引入全套 MP 配置。
 */
@Data
public class SeckillOrder {

    private Long id;

    /** 业务订单号，对外唯一标识；自增 id 不暴露（连续数字会泄露真实单量） */
    private String orderNo;

    private Long activityId;

    private Long userId;

    private Long productId;

    private BigDecimal seckillPrice;

    /** 0 待支付 1 已支付 2 已取消 */
    private Integer status;

    private String requestId;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
