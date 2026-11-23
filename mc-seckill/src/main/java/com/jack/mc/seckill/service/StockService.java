package com.jack.mc.seckill.service;

import com.jack.mc.seckill.enums.DeductResult;
import com.jack.mc.seckill.enums.RollbackOutcome;

import java.time.LocalDateTime;

public interface StockService {

    /**
     * 把活动库存写进 Redis。
     *
     * @return true 表示本次真的写入，false 表示已存在或无需预热
     */
    boolean warmup(Long activityId);

    /**
     * 原子扣减：判库存 + 判重复购买 + 扣减 + 记录买家 + 记录待确认，一次 RTT 完成。
     * <p>
     * endTime 由调用方传入而不是在这里再查一次库：秒杀接口在入口已经查过活动了，
     * 热路径上多一次主键查询，在 200 并发下会被放大成明显的排队延迟。
     * 这是「参数传递」和「重复查询」的取舍——热路径优先避免重复 IO。
     * <p>
     * 扣减成功后会留下一条「待确认」标记（见 {@link #confirmDeduct}），
     * 调用方必须在落库成功后清掉它，否则兜底任务会把这笔扣减当成孤儿回滚。
     */
    DeductResult deduct(Long activityId, Long userId, LocalDateTime activityEndTime,
                        String reservationId);

    /**
     * 确认扣减：这笔扣减已经有了对应的 outbox 记录，可以投递 MQ。
     * <p>
     * <b>返回值不是装饰，调用方必须处理 false（第三轮评审 P0）</b>：
     * false 表示兜底任务已经把这笔扣减认领为孤儿、库存可能已经还回去了。
     * 此时若继续投递 MQ，订单会落库而库存早已归还——一件货卖两次。
     * 正确做法是撤销刚写的 outbox 行 + 回滚库存 + 告知用户失败。
     * <p>
     * 为什么不能简单地 ZREM 后返回 true：ZREM 返回 0 无法区分
     * 「另一条重复请求确认过」和「兜底任务已经接管」，这两种情况的后续动作完全相反。
     *
     * @return true 可以投递；false 已被兜底任务接管，必须中止
     */
    boolean confirmDeduct(Long activityId, Long userId, String reservationId);

    /**
     * 仅清掉待确认标记，不检查认领状态。
     * <p>
     * 只给兜底任务用：它已经确认过「outbox 里有记录」，说明这笔扣减是正常的，
     * 只需要把遗留的标记擦掉。
     */
    void clearPendingMarker(Long activityId, Long userId, String reservationId);

    /**
     * 认领一笔疑似孤儿的扣减，成功后才有资格查 outbox 并决定是否回滚。
     * <p>
     * 「判断是否超过宽限期」和「写认领标记」在同一个 Lua 里完成，
     * 中间没有窗口。认领带过期时间（租约），持有者崩溃后会自动释放，
     * 不会把这个活动永久锁死。
     *
     * @param cutoffMillis 宽限期截止时间戳，pending 里 score 小于等于它才算超时
     * @param leaseMillis  租约时长
     * @param token        租约令牌（一般是实例 id），释放时要比对，避免删掉别人的 claim
     * @return true 认领成功
     */
    boolean claimOrphan(Long activityId, Long userId, String reservationId,
                        long cutoffMillis, long leaseMillis, String token);

    /** 释放认领（比对 token 后才删，避免误删别的实例的 claim） */
    void releaseClaim(Long activityId, String reservationId, String token);

    /**
     * 回滚：把库存、买家标记、待确认标记一起还原。<b>幂等</b>。
     * <p>
     * 返回三态而不是 boolean：boolean 会把「无需回滚」和「Redis 故障」混成一个 false，
     * 调用方据此标记状态时就会写出「显示已回滚、实际没还」的脏记录。
     */
    RollbackOutcome rollback(Long activityId, Long userId, String reservationId);

    /** 当前剩余库存，未预热返回 null */
    Long currentStock(Long activityId);

    /** 已抢到的人数，未预热返回 0 */
    Long boughtCount(Long activityId);
}
