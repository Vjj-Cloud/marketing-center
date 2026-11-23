package com.jack.mc.order.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 抢购结果查询视图。
 * <p>
 * 三个状态对应三种真实处境，前端必须能区分：
 * <ul>
 *   <li>SUCCESS：订单已落库，可以跳详情页；</li>
 *   <li>QUEUING：Redis 里有你的占位（确实抢到了），但订单还没落库——
 *       正常，几百毫秒内会变；</li>
 *   <li>NOT_RESERVED：Redis 里也没有，说明没抢到，或者消息重试耗尽已回滚。
 *       这两种情况对用户是一样的：没有订单。</li>
 * </ul>
 * 把「排队中」和「没有订单」混成一个结果，是秒杀类接口最常见的体验事故：
 * 用户明明抢到了，刷新一下变成「未抢到」，客服直接被投诉淹没。
 */
@Data
public class OrderResultVO {

    /** SUCCESS / QUEUING / NOT_RESERVED */
    private String status;

    private String orderNo;

    private Long activityId;

    private BigDecimal seckillPrice;

    /** 订单状态 0 待支付 1 已支付 2 已取消；未落库时为 null */
    private Integer orderStatus;

    private String message;

    private LocalDateTime createdAt;

    public static OrderResultVO of(String status, String message) {
        OrderResultVO vo = new OrderResultVO();
        vo.setStatus(status);
        vo.setMessage(message);
        return vo;
    }
}
