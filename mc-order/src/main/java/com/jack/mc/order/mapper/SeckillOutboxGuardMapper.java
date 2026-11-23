package com.jack.mc.order.mapper;

import com.jack.mc.order.entity.OutboxGuardState;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 本地消息表的只读锁视图（订单服务侧）。
 * <p>
 * <b>为什么订单服务要直连秒杀服务的表</b>：这是一处刻意的妥协。
 * 两个服务共用同一个库（本项目的部署形态就是这样），
 * 而「人工回滚」和「迟到订单落库」必须互斥——如果不锁同一行，
 * 无论怎么调整顺序都存在「查完订单才发现又被插队」的窗口。
 * <p>
 * 真正拆库之后这里应该换成「调用秒杀服务的预留状态接口」或「共享的预留状态读模型」，
 * 语义不变：订单落库前必须确认「这笔预留仍然有效」。把它抽成一个独立 Mapper，
 * 就是为了将来替换实现时只动这一个文件。
 */
@Mapper
public interface SeckillOutboxGuardMapper {

    /**
     * 锁住这条预留记录并返回当前状态。
     * <p>
     * <b>必须用 FOR UPDATE，不能是普通 SELECT</b>：
     * 人工回滚的 CAS（4→5）要对同一行加排他锁，两边靠这把行锁串行化。
     * 普通读在可重复读隔离级别下读的是快照，看不到对方刚提交的状态，
     * 等于没有互斥——那是上一版「查订单与回滚之间有窗口」的同一个错误。
     * <p>
     * 返回 null 表示本地消息表里没有这一行（历史消息、或消息早于本地消息表）。
     * 这时无法判断预留是否被取消，按「正常」处理，交给唯一索引兜重复。
     */
    @Select("""
            SELECT id, status, reservation_id
            FROM seckill_outbox
            WHERE activity_id = #{activityId} AND user_id = #{userId}
            FOR UPDATE
            """)
    OutboxGuardState lockState(@Param("activityId") Long activityId,
                               @Param("userId") Long userId);

    /**
     * 在已持有的行锁内把消息认领为“消费中”。
     * 只允许从 PENDING/SENT/NEEDS_REVIEW 进入，防止入口墓碑被覆盖。
     */
    @Update("""
            UPDATE seckill_outbox
            SET status = 7
            WHERE id = #{id} AND status IN (0, 1, 4)
            """)
    int markConsuming(@Param("id") Long id);

    /** 订单、商品库存、活动库存均落库后，在同一个事务里关闭 outbox。 */
    @Update("UPDATE seckill_outbox SET status = 2 WHERE id = #{id} AND status = 7")
    int markConsumed(@Param("id") Long id);
}
