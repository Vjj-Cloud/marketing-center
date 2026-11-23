package com.jack.mc.seckill.service;

import com.jack.mc.seckill.vo.SeckillResultVO;

public interface SeckillService {

    /**
     * 抢购。
     * <p>
     * 成功只是「扣到了库存 + 消息投递成功」，订单还没生成——真正的落库
     * 由订单服务消费 MQ 完成。所以返回值里没有 orderId，只有排队状态。
     */
    SeckillResultVO seckill(Long activityId, Long userId, String requestId);
}
