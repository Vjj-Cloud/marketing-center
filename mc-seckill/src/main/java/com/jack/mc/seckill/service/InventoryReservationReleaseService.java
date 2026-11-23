package com.jack.mc.seckill.service;

/**
 * 释放活动已经不再可能成交的预留商品库存。
 */
public interface InventoryReservationReleaseService {

    /**
     * 在数据库事务内复查并释放一场活动尚未售出的预留库存。
     *
     * @return true 表示本次完成释放；false 表示条件尚未满足
     */
    boolean release(Long activityId);
}
