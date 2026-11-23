package com.jack.mc.seckill.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jack.mc.seckill.entity.SeckillOutbox;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface SeckillOutboxMapper extends BaseMapper<SeckillOutbox> {

    /**
     * 取一批到期可投递的记录。
     * <p>
     * 只捞 status=0（待投递）且 next_retry_at 已到期的，靠 idx_status_retry 索引。
     * 加 LIMIT 是必须的：broker 长时间不可用时积压可能是几十万条，
     * 一次全捞出来会直接把内存打满。
     */
    @Select("""
            SELECT * FROM seckill_outbox
            WHERE status = 0 AND next_retry_at <= #{now}
            ORDER BY id
            LIMIT #{limit}
            """)
    List<SeckillOutbox> selectPending(@Param("now") LocalDateTime now, @Param("limit") int limit);

    /**
     * 已投递但长时间没确认消费的记录，用来对账。
     * 这些是「broker 说收到了，但订单表里可能没有」的中间态，
     * 必须主动核对，否则用户会永远卡在「排队中」。
     */
    @Select("""
            SELECT * FROM seckill_outbox
            WHERE status = 1 AND updated_at <= #{before}
            ORDER BY id
            LIMIT #{limit}
            """)
    List<SeckillOutbox> selectSentBefore(@Param("before") LocalDateTime before, @Param("limit") int limit);

    /**
     * 标记为已投递。
     * <p>
     * where 里带 status = 0 是刻意的：补偿任务和主流程可能同时想改这一条，
     * 带上状态条件后，只有先到的那次生效，后到的返回 0 —— 用它判断
     * 「这一条是不是已经被别人处理过了」，避免重复标记引发的日志噪音。
     */
    @Update("""
            UPDATE seckill_outbox
            SET status = 1, last_error = ''
            WHERE activity_id = #{activityId} AND user_id = #{userId} AND status = 0
            """)
    int markSent(@Param("activityId") Long activityId, @Param("userId") Long userId);

    /** 重投失败：累加次数、写入退避时间与错误摘要 */
    @Update("""
            UPDATE seckill_outbox
            SET retry_count = #{retryCount},
                next_retry_at = #{nextRetryAt},
                last_error = #{lastError}
            WHERE id = #{id} AND status = 0
            """)
    int bumpRetry(@Param("id") Long id,
                  @Param("retryCount") int retryCount,
                  @Param("nextRetryAt") LocalDateTime nextRetryAt,
                  @Param("lastError") String lastError);

    /**
     * 带旧状态条件的更新（CAS）。
     * <p>
     * <b>为什么所有状态流转都必须带上 from（第三轮评审 P2）</b>：
     * 无条件的 {@code UPDATE ... SET status = ?} 会跨状态覆盖——
     * 复查任务刚从 4 读到一条记录，管理员同时把它回滚成了 3，
     * 复查任务再把 3 覆盖成 2，记录就变成「已消费」而库存其实已经还了；
     * 反过来管理员把 4 覆盖成 3，而消费者刚落了库，就变成了「已回滚 + 有订单」。
     * <p>
     * 带上 from 之后，这类覆盖会直接更新 0 行，调用方据此知道自己来晚了。
     *
     * @return 影响行数，0 表示状态已被别人改过
     */
    @Update("UPDATE seckill_outbox SET status = #{to} WHERE id = #{id} AND status = #{from}")
    int casStatus(@Param("id") Long id, @Param("from") int from, @Param("to") int to);

    /**
     * 把一条「还没投递出去」的本地消息标记为已中止（6），返回是否成功。
     * <p>
     * 返回值就是「消息是否确定没发出去」的凭据：
     * 改成了 → 补偿任务的查询条件是 status=0，不可能再投，可以安全回滚库存；
     * 没改成 → 说明已经被标成 SENT，消息可能已在路上，绝不能回滚库存。
     * <p>
     * <b>为什么是墓碑而不是物理删除</b>：删除发生在「补偿任务已读到、正在发送」的瞬间时，
     * 消息会照常发出去，而消费者查不到任何预留记录 → 照常建单 → 库存已还 = 超卖。
     * 留一条 6，消费者在落库事务里读到就能拒绝，这个窗口才真正闭合。
     */
    @Update("""
            UPDATE seckill_outbox SET status = 6
            WHERE activity_id = #{activityId} AND user_id = #{userId}
              AND reservation_id = #{reservationId} AND status = 0
            """)
    int markAborted(@Param("activityId") Long activityId,
                    @Param("userId") Long userId,
                    @Param("reservationId") String reservationId);

    /**
     * 把一条「已不成立」的记录（人工回滚 3 / 入口中止 6）重置为待投递，让用户能重新参与。
     * <p>
     * 只对 status IN (3, 6) 生效——3=人工回滚、6=入口中止，
     * 两者都意味着这一单不成立、库存已经还了，用户重新抢购是合理的。
     * 而 0/1/2/4/5 都代表「这一单还有效或结果未知」，复用它们等于给同一个人下第二单。
     * <p>
     * 复用而不是新插一行，是因为表上有 uk_user_activity(user_id, activity_id) 唯一键，
     * 插不进去；而删掉旧行再插会丢掉「这条曾经被人工回滚过」的审计痕迹。
     * <p>
     * next_retry_at 同样延后 3 秒，和新建记录保持一致：主流程还要做确认与投递，
     * 补偿任务不能在这一瞬间就把这一行捞走。
     */
    @Update("""
            UPDATE seckill_outbox
            SET status = 0, reservation_id = #{reservationId},
                payload = #{payload}, retry_count = 0, reconcile_count = 0,
                next_retry_at = DATE_ADD(NOW(), INTERVAL 3 SECOND), last_error = ''
            WHERE activity_id = #{activityId} AND user_id = #{userId} AND status IN (3, 6)
            """)
    int resetAbandoned(@Param("activityId") Long activityId,
                       @Param("userId") Long userId,
                       @Param("reservationId") String reservationId,
                       @Param("payload") String payload);

    /**
     * 把「已投递但迟迟查不到订单」的记录打回待投递，让补偿任务重发一次。
     * <p>
     * 这是 at-least-once 的落点：宁可重复投递，也不能让消息静默消失。
     * where 带 status = 1，避免把刚刚被别的线程标记成已消费的记录又打回去。
     * <p>
     * 同时把 reconcile_count 加一（第四轮评审 P2）。这个计数是这条语句唯一的
     * 「前进量」——如果不记，调用方无法判断「这已经是第几次了」，
     * 消费者永久故障时就会 SENT → PENDING → SENT 无限循环。
     */
    @Update("""
            UPDATE seckill_outbox
            SET status = 0, reconcile_count = reconcile_count + 1, next_retry_at = NOW()
            WHERE id = #{id} AND status = 1
            """)
    int resetToPending(@Param("id") Long id);

    /**
     * 检查订单是否已经落库——重试耗尽后，这是决定「回滚还是认定成功」的唯一依据。
     * <p>
     * 不能只看 broker 的返回：发送超时可能意味着消息其实已经投递成功，
     * 此时消费者早已建单；如果还去回滚库存，就凭空多出一件货。
     */
    @Select("SELECT COUNT(*) FROM seckill_order WHERE user_id = #{userId} AND activity_id = #{activityId}")
    int countOrder(@Param("userId") Long userId, @Param("activityId") Long activityId);

    /**
     * 本地消息表里是否已有这条记录——孤儿扣减兜底任务用它区分两种情况：
     * <ul>
     *   <li>有记录：说明落库成功了，只是没来得及清标记，只清标记即可；</li>
     *   <li>没记录：说明进程倒在「扣完 Redis」和「写完 outbox」之间，这笔扣减是孤儿，
     *       必须回滚，否则用户永远抢不到第二件、库存也永远少一件。</li>
     * </ul>
     * 命中 uk_user_activity(user_id, activity_id) 的左前缀，不会全表扫。
     */
    @Select("SELECT COUNT(*) FROM seckill_outbox WHERE user_id = #{userId} AND activity_id = #{activityId}")
    int countByUserActivity(@Param("userId") Long userId, @Param("activityId") Long activityId);

    /**
     * 按预订号取这条预留的状态，没有记录返回 null。
     * <p>
     * <b>孤儿恢复必须看状态，不能只判「存在」（第四轮评审 P1）</b>：
     * 只看存在性的话，一条已经被判为不成立（3 人工回滚 / 6 入口中止）的记录
     * 也会被当成「落库成功了，只清标记」。
     * 而这两种状态恰恰意味着库存该还——极端崩溃顺序是
     * 「写 outbox → 改 6 → 进程在回滚 Redis 前挂掉」，
     * 孤儿任务若只清标记，这笔扣减就永久悬挂：库存少一件、买家标记还在、
     * 却没有任何可成交的消息，用户还会被判定为「已抢过」。
     * <p>
     * 返回 Integer 而不是 int，是为了把「没有记录」和「记录状态恰好为 0」
     * 区分开——这两种情况的后续动作完全相反。
     */
    @Select("""
            SELECT status FROM seckill_outbox
            WHERE activity_id = #{activityId} AND reservation_id = #{reservationId}
            LIMIT 1
            """)
    Integer selectStatusByReservation(@Param("activityId") Long activityId,
                                      @Param("reservationId") String reservationId);

    /** 活动是否仍有待投递、待消费或待人工处理的 outbox。 */
    @Select("""
            SELECT COUNT(*) FROM seckill_outbox
            WHERE activity_id = #{activityId}
              AND status IN (0, 1, 4, 5, 7)
            """)
    int countActiveByActivity(@Param("activityId") Long activityId);

    /** 按用户+活动取记录，供人工复核接口使用 */
    @Select("""
            SELECT * FROM seckill_outbox
            WHERE user_id = #{userId} AND activity_id = #{activityId}
            """)
    SeckillOutbox selectByUserActivity(@Param("userId") Long userId, @Param("activityId") Long activityId);

    @Select("SELECT * FROM seckill_outbox WHERE id = #{id}")
    SeckillOutbox selectById(@Param("id") Long id);

    /**
     * 按状态取一批待处理记录（待人工复核的复查用）。
     * 加 updated_at 条件是为了给消费者留出「迟到落库」的时间，
     * 刚标记成待复核就立刻复查，只会看到必然为空的订单表。
     */
    @Select("""
            SELECT * FROM seckill_outbox
            WHERE status = #{status} AND updated_at <= #{before}
            ORDER BY id
            LIMIT #{limit}
            """)
    List<SeckillOutbox> selectByStatusBefore(@Param("status") int status,
                                             @Param("before") LocalDateTime before,
                                             @Param("limit") int limit);
}
