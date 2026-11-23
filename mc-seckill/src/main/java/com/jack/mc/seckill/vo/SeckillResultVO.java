package com.jack.mc.seckill.vo;

import lombok.Data;

/**
 * 抢购结果。
 * <p>
 * 抢到之后订单还没落库（异步），所以不能返回 orderId，
 * 只能返回「排队中」。前端拿这个状态去轮询结果接口，
 * 这是秒杀异步化的必然结果，不能对用户承诺「秒出订单号」。
 */
@Data
public class SeckillResultVO {

    private boolean success;

    /** QUEUED 排队中 / SOLD_OUT 已抢光 / REPEATED 重复购买 */
    private String status;

    private String message;

    public static SeckillResultVO queued() {
        SeckillResultVO vo = new SeckillResultVO();
        vo.setSuccess(true);
        vo.setStatus("QUEUED");
        vo.setMessage("抢购成功，正在为您生成订单");
        return vo;
    }

    public static SeckillResultVO of(boolean success, String status, String message) {
        SeckillResultVO vo = new SeckillResultVO();
        vo.setSuccess(success);
        vo.setStatus(status);
        vo.setMessage(message);
        return vo;
    }
}
