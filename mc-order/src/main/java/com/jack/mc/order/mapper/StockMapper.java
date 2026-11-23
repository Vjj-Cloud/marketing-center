package com.jack.mc.order.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 真实库存扣减。
 * <p>
 * 两条 UPDATE 都带 <code>AND stock &gt;= 1</code> 这类条件，是刻意的：
 * 把「判断」和「扣减」合并成一条语句，让数据库的行锁保证原子性。
 * 如果写成「先 SELECT 判断够不够，再 UPDATE 扣减」，中间就有窗口，
 * 并发下会扣成负数。
 * <p>
 * 返回 0 行说明库存不足——这不是可以忽略的情况，而是数据不一致
 * （Redis 扣成功但 DB 不够），必须抛异常让消息重试并告警。
 */
@Mapper
public interface StockMapper {

    /**
     * 扣商品真实库存。
     * <p>
     * 为什么秒杀还要扣 product.stock：活动库存只是商品的「预留份额」，
     * 商品总库存是所有渠道共用的。不扣商品库存，就会出现
     * 「秒杀卖了 100 件，但商品页显示还有 120 件」的假库存。
     */
    @Update("""
            UPDATE product
            SET stock = stock - 1
            WHERE id = #{productId} AND stock >= 1 AND deleted = 0
            """)
    int deductProductStock(@Param("productId") Long productId);

    /**
     * 扣活动在 DB 侧的剩余库存。
     * <p>
     * 这一列是「账本」，用于对账：Redis 的库存是闸门，会因重启丢失，
     * 但订单一张张落库是事实。activity.available_stock 归零 + 订单数 = total_stock，
     * 三者对得上才说明没有超卖。
     */
    @Update("""
            UPDATE seckill_activity
            SET available_stock = available_stock - 1
            WHERE id = #{activityId} AND available_stock >= 1
            """)
    int deductActivityStock(@Param("activityId") Long activityId);

    /**
     * 大于 0 表示活动发布时已经从商品库存预留，
     * 消费端不能再次扣 product.stock。
     */
    @Select("SELECT reserved_stock FROM seckill_activity WHERE id = #{activityId}")
    Integer reservedStock(@Param("activityId") Long activityId);
}
