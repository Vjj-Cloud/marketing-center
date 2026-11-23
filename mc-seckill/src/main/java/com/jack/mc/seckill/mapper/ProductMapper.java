package com.jack.mc.seckill.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jack.mc.seckill.entity.Product;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface ProductMapper extends BaseMapper<Product> {

    /**
     * 对商品行加排他锁（SELECT ... FOR UPDATE），作为「同一商品活动发布」的互斥量。
     * <p>
     * 为什么需要：发布时的重叠校验是「先查后写」，两个运营同时发布不同活动时，
     * 两边都查不到对方的记录（都还没提交），于是双双通过校验，最终两场活动时间重叠。
     * 重叠的直接后果是两场活动都往同一个 Redis key 写库存，后写的覆盖先写的，
     * 库存凭空变多，直接超卖。
     * <p>
     * 为什么选商品行锁而不是分布式锁：
     * <ul>
     *   <li>发布活动本来就要写商品关联的数据，用同一行做互斥不需要额外组件；</li>
     *   <li>锁跟着事务走，事务提交/回滚自动释放，不会出现「进程挂了锁没释放」；</li>
     *   <li>Redis 分布式锁在 Redis 故障时要么放弃互斥、要么拒绝服务，两难。</li>
     * </ul>
     * 代价是并发度被限制到「每个商品串行发布」——但发布活动本来就是低频管理操作，
     * 这个代价可以忽略。
     */
    @Select("SELECT * FROM product WHERE id = #{id} AND deleted = 0 FOR UPDATE")
    Product lockById(@Param("id") Long productId);

    /** 发布活动时原子预留商品库存。 */
    @Update("""
            UPDATE product
            SET stock = stock - #{quantity}
            WHERE id = #{productId}
              AND status = 1
              AND deleted = 0
              AND stock >= #{quantity}
            """)
    int reserveStock(@Param("productId") Long productId,
                     @Param("quantity") int quantity);

    /** 活动结束后归还未售的预留库存。 */
    @Update("UPDATE product SET stock = stock + #{quantity} WHERE id = #{productId}")
    int releaseStock(@Param("productId") Long productId,
                     @Param("quantity") int quantity);
}
