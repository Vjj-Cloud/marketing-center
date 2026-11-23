package com.jack.mc.order.mapper;

import com.jack.mc.order.entity.SeckillOrder;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface OrderMapper {

    /**
     * 落一单。
     * <p>
     * <b>这里故意不做「先查再插」</b>：先查后插在并发下必然有窗口——
     * 两个消费者（消息重投导致）同时查到「没有」，然后同时插入，双双成功。
     * 正确做法是直接插，让数据库唯一索引 uk_user_activity 来判重，
     * 撞了就捕获 DuplicateKeyException 当成功处理（幂等消费）。
     * <p>
     * 这也是「唯一索引是并发兜底」的典型用法：应用层的判重只能优化性能，
     * 正确性必须由数据库约束保证。
     */
    @Insert("""
            INSERT INTO seckill_order
                (order_no, activity_id, user_id, product_id, seckill_price, status, request_id)
            VALUES
                (#{orderNo}, #{activityId}, #{userId}, #{productId}, #{seckillPrice}, #{status}, #{requestId})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(SeckillOrder order);

    @Select("""
            SELECT * FROM seckill_order
            WHERE user_id = #{userId} AND activity_id = #{activityId}
            """)
    SeckillOrder findByUserAndActivity(@Param("userId") Long userId,
                                       @Param("activityId") Long activityId);

    @Select("SELECT * FROM seckill_order WHERE order_no = #{orderNo}")
    SeckillOrder findByOrderNo(@Param("orderNo") String orderNo);
}
