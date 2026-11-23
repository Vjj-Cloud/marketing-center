package com.jack.mc.seckill.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jack.mc.seckill.entity.SeckillActivity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface SeckillActivityMapper extends BaseMapper<SeckillActivity> {

    /**
     * 统计与给定时间区间重叠的「已发布」活动数。
     * <p>
     * 两个区间重叠的判定：a.start &lt; b.end AND b.start &lt; a.end，
     * 取反就是不重叠，所以 SQL 里写成 NOT (... OR ...)。
     * 同一商品如果有两场时间重叠的活动，两场都往 Redis 写库存，
     * 预热时后写的覆盖先写的，直接造成超卖——所以发布前必须拦住。
     * <p>
     * 注意：单靠这条查询拦不住并发。两个运营同时发布不同活动时，
     * 双方都查不到对方（都没提交），于是都通过。真正的互斥由
     * ActivityServiceImpl.publish 里的商品行锁提供，这里只是判定条件。
     */
    @Select("""
            SELECT COUNT(*) FROM seckill_activity
            WHERE product_id = #{productId}
              AND status = 1
              AND NOT (end_time <= #{startTime} OR start_time >= #{endTime})
            """)
    int countOverlap(@Param("productId") Long productId,
                     @Param("startTime") LocalDateTime startTime,
                     @Param("endTime") LocalDateTime endTime);

    /**
     * 汇总同一商品「还占用着库存」的活动还剩多少没卖。
     * <p>
     * 用于发布时的累计校验（第四轮评审 P1）。只比单场库存是不够的：
     * 商品库存 100，活动 A 预分配 80（10 点）、活动 B 预分配 80（12 点），
     * 两场时段不重叠所以都能发布；等 A 卖光 80 件，商品库存只剩 20，
     * B 预热时却仍然放行 80 —— 后 60 条消息会因真实库存不足反复重试直到进死信。
     * <p>
     * <b>为什么汇总 available_stock 而不是 total_stock</b>：商品库存只在订单落库时扣，
     * 活动账本也同步减，两者的「已售部分」是同一份。用 total_stock 汇总会把
     * 已经卖掉的数量重复计一次，结果是「卖得越多、越发布不了新活动」。
     * <p>
     * <b>为什么只算 end_time &gt; now 的</b>：已结束的活动不会再卖，
     * 它剩下的库存从未从 product.stock 里扣走，因此不构成占用。
     * <p>
     * excludeActivityId 用来把正在发布的活动自己排除掉（发布时它还是草稿，
     * 本来就不在 status = 1 里，传 0 或自己的 id 都可以）。
     */
    @Select("""
            SELECT COALESCE(SUM(available_stock), 0) FROM seckill_activity
            WHERE product_id = #{productId}
              AND status = 1
              AND end_time > #{now}
              AND id <> #{excludeActivityId}
              AND reserved_stock = 0
            """)
    int sumAllocatedStock(@Param("productId") Long productId,
                          @Param("excludeActivityId") Long excludeActivityId,
                          @Param("now") LocalDateTime now);

    /**
     * 记录预热成功时间。
     * <p>
     * 带 <code>warmup_at IS NULL</code> 条件：只在第一次预热时写，
     * 后续重复调用（每 30 秒一次）不会产生无意义的行更新和 binlog。
     */
    @Update("UPDATE seckill_activity SET warmup_at = NOW() WHERE id = #{id} AND warmup_at IS NULL")
    int markWarmedUp(@Param("id") Long id);

    /** 把所有已过结束时间且仍为 PUBLISHED 的活动收敛为 FINISHED */
    @Update("""
            UPDATE seckill_activity
            SET status = 2
            WHERE status = 1 AND end_time <= #{now}
            """)
    int finishExpired(@Param("now") LocalDateTime now);

    /**
     * 锁定活动行，供预留库存释放事务使用。
     */
    @Select("SELECT * FROM seckill_activity WHERE id = #{id} FOR UPDATE")
    SeckillActivity lockById(@Param("id") Long id);

    /**
     * 找出已结束、已经做过商品预留、且尚未释放的活动。
     * outbox 仍存在活动态时不能释放，否则迟到订单可能使用已经归还的库存。
     */
    @Select("""
            SELECT a.* FROM seckill_activity a
            WHERE a.status = 2
              AND a.reserved_stock > 0
              AND a.reservation_released_at IS NULL
              AND a.end_time <= #{now}
              AND NOT EXISTS (
                  SELECT 1 FROM seckill_outbox o
                  WHERE o.activity_id = a.id
                    AND o.status IN (0, 1, 4, 5, 7)
              )
            ORDER BY a.id
            LIMIT #{limit}
            """)
    List<SeckillActivity> findReleasableReservations(@Param("now") LocalDateTime now,
                                                     @Param("limit") int limit);

    @Update("""
            UPDATE seckill_activity
            SET reservation_released_at = #{releasedAt}
            WHERE id = #{id} AND reservation_released_at IS NULL
            """)
    int markReservationReleased(@Param("id") Long id,
                                @Param("releasedAt") LocalDateTime releasedAt);
}
