package com.jack.mc.order.entity;

import lombok.Data;

/**
 * 订单落库事务内锁定并读取的 outbox 状态。
 */
@Data
public class OutboxGuardState {

    private Long id;

    private Integer status;

    private String reservationId;
}
