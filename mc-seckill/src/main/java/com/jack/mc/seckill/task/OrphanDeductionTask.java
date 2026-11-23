package com.jack.mc.seckill.task;

import com.jack.mc.common.constant.SeckillKeys;
import com.jack.mc.seckill.enums.OutboxStatus;
import com.jack.mc.seckill.enums.RollbackOutcome;
import com.jack.mc.seckill.service.OutboxService;
import com.jack.mc.seckill.service.StockService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 孤儿扣减兜底任务：修复「Redis 扣了、数据库没落」的悬挂记录。
 * <p>
 * <b>为什么需要它</b>：抢购主流程是
 * <pre>Lua 扣 Redis  →  （进程在这里挂掉）  →  写 seckill_outbox  →  发 MQ</pre>
 * 中间那一步没有任何事务能跨越 Redis 与 MySQL，所以这个窗口永远存在。
 * 一旦命中，后果是永久且静默的：库存少了 1 件、该用户永远返回「重复购买」、
 * 而且没有 outbox、没有订单，任何基于数据库的对账都发现不了它。
 * <p>
 * 加分布式事务（2PC / Seata）能解决，但成本和复杂度远超收益；
 * 标准做法是让状态可被反向识别 —— 扣减时留痕，落库后擦除，剩下的是嫌疑。
 * <p>
 * <b>第三轮评审的 P0：光有留痕不够，还要有原子认领。</b>
 * 上一版的流程是「查 outbox → 查不到就回滚」，两步之间没有互斥。
 * 一个只是<b>慢</b>的请求（GC 停顿、连接池排队超过 60 秒宽限期）会被误判成孤儿：
 * 本任务把库存还回去，原请求随后恢复、照常写 outbox、发 MQ，
 * 订单最终落库 —— 库存已经还给别人了，一件货卖两次。
 * <p>
 * 现在的顺序是：
 * <ol>
 *   <li><b>原子认领</b>（{@code claimOrphan}）：仅当 pending 里的 score 确实超过宽限期，
 *       才用 SET NX 拿到 claim。这一步和「判断是否超时」在同一个 Lua 里，中间没有窗口；</li>
 *   <li>认领成功后查 outbox 并<b>判状态</b>：记录有效（0/1/2/4/5/7）→ 只清标记
 *       （这单其实是好的）；没有记录或已是 3（已回滚）/ 6（已中止）→ 回滚库存；</li>
 *   <li>释放认领。</li>
 * </ol>
 * 另一边，原请求在投递 MQ 之前调用 {@code confirmDeduct}，它会先看 claim 在不在：
 * 在就说明自己被接管了，立刻撤销 outbox 行并回滚，<b>不再投递</b>。
 * 两条路径通过同一个 key 形成互斥，谁先谁后都只有一个能继续。
 * <p>
 * <b>为什么不需要分布式锁</b>：认领本身就是锁（SET NX + 租约），而且比锁多一层语义——
 * 它同时是「身份凭据」，原请求能凭它判断自己是否已被接管。
 * 回滚本身也幂等（Lua 里只有 SREM 真删掉成员才 INCR），所以重复执行不会多还库存。
 */
@Component
public class OrphanDeductionTask {

    private static final Logger log = LoggerFactory.getLogger(OrphanDeductionTask.class);

    /**
     * 宽限期：扣减到落库正常只要几毫秒，留 60 秒是给
     * 「数据库慢查询、连接池排队、GC 停顿」留余量。
     * 太短会误伤正常请求（把已落库的扣减回滚，造成真实超卖），
     * 太长则悬挂记录多占一会儿库存。60 秒是保守取值。
     */
    private static final long GRACE_MILLIS = 60_000L;

    /**
     * 认领租约。单条处理只涉及一次 MySQL 查询和一次 Redis 回滚，
     * 30 秒远远够用；即使进程崩了，租约到期后下一轮可以重新认领，
     * 不会让这个活动永久卡住。
     */
    private static final long CLAIM_LEASE_MILLIS = 30_000L;

    /** 单轮每个活动最多处理的条数，防止某个活动积压时拖住整轮 */
    private static final int BATCH_PER_ACTIVITY = 200;

    private final StringRedisTemplate redis;
    private final OutboxService outboxService;
    private final StockService stockService;

    /** 认领令牌：多实例部署时区分「这个 claim 是谁加的」 */
    private final String instanceToken = UUID.randomUUID().toString();

    public OrphanDeductionTask(StringRedisTemplate redis, OutboxService outboxService,
                               StockService stockService) {
        this.redis = redis;
        this.outboxService = outboxService;
        this.stockService = stockService;
    }

    @Scheduled(fixedDelay = 30_000L, initialDelay = 30_000L)
    public void run() {
        try {
            List<String> keys = scanPendingKeys();
            if (keys.isEmpty()) {
                return;
            }
            long cutoff = System.currentTimeMillis() - GRACE_MILLIS;
            for (String key : keys) {
                Long activityId = SeckillKeys.activityIdOfPending(key);
                if (activityId == null) {
                    continue;
                }
                sweepOneActivity(key, activityId, cutoff);
            }
        } catch (Exception e) {
            // 定时任务抛异常会被调度器静默吞掉，必须自己接住并留痕
            log.error("孤儿扣减兜底任务异常", e);
        }
    }

    /**
     * 用 SCAN 而不是 KEYS：KEYS 会阻塞 Redis 单线程，在生产上等同于一次故障。
     * 这里 key 数量就是活动数量级，SCAN 的增量游标完全够用。
     */
    private List<String> scanPendingKeys() {
        List<String> keys = new ArrayList<>();
        try (Cursor<String> cursor = redis.scan(ScanOptions.scanOptions()
                .match(SeckillKeys.PENDING_PATTERN)
                .count(200)
                .build())) {
            cursor.forEachRemaining(keys::add);
        }
        return keys;
    }

    private void sweepOneActivity(String key, Long activityId, long cutoff) {
        // score <= cutoff：只处理「已经等了超过宽限期」的记录。
        // 传入 0 作为下界是安全的——时间戳不可能是负数。
        Set<String> stuck = redis.opsForZSet().rangeByScore(key, 0, cutoff, 0, BATCH_PER_ACTIVITY);
        if (stuck == null || stuck.isEmpty()) {
            return;
        }
        for (String member : stuck) {
            SeckillKeys.PendingReservation reservation =
                    SeckillKeys.parsePendingMember(member);
            if (reservation == null) {
                // 脏成员直接清掉，否则它会永远留在 ZSet 里每轮被扫到
                log.warn("待确认扣减集合里有非法成员，已清理 key={} member={}", key, member);
                redis.opsForZSet().remove(key, member);
                continue;
            }
            handleOne(activityId, reservation.userId(), reservation.reservationId(), cutoff);
        }
    }

    /**
     * 这条预留是否已经被明确判死（库存该还）。
     * <p>
     * <b>为什么是黑名单，不是白名单</b>：白名单（只认 0/1/2/4/5/7）看起来更严格，
     * 但以后一旦新增状态（比如「支付中」），它会被默认归入「失效」并把库存还回去，
     * 而那条记录可能对应一张真实订单——直接资损。黑名单的默认值是「不动」，
     * 最坏结果是悬挂多留一阵、由人工对账收尾。
     * <p>
     * 注意 null（根本没有记录）走的不是这里的分支，而是回滚：
     * 「没有记录」和「记录已失效」都该还库存，调用方要用
     * {@code status != null && !isReservationDead(status)} 来表达「只清标记」。
     */
    private boolean isReservationDead(Integer status) {
        return status != null
                && (status == OutboxStatus.ROLLED_BACK.getCode()
                || status == OutboxStatus.ABORTED.getCode());
    }

    /**
     * 处理单条疑似孤儿。
     * <p>
     * 顺序是「先认领、再查库、最后动手」，认领失败就什么都不做。
     * 少了第一步就是上一版的 bug：查库与回滚之间有窗口，
     * 慢请求会在这个窗口里把 outbox 写进去。
     * <p>
     * 包级可见（不是 private）是为了让单测能直接驱动这一条路径——
     * 走 {@link #run()} 需要 mock 掉 SortedOperations、Cursor 和 SCAN 游标，
     * 成本远高于收益，测起来的重点也跑偏了。
     */
    void handleOne(Long activityId, Long userId, String reservationId, long cutoff) {
        // ① 原子认领。拿不到说明：记录已被确认/回滚、还没到宽限期、或别的实例正在处理
        if (!stockService.claimOrphan(activityId, userId, reservationId,
                cutoff, CLAIM_LEASE_MILLIS, instanceToken)) {
            return;
        }
        try {
            // ② 认领之后再查 outbox —— 顺序不能反，反了窗口就回来了。
            //
            //    这里必须看「状态」而不只是「有没有这条记录」（第四轮评审 P1）：
            //    一条 status=6（入口中止）或 3（人工回滚）的记录同样存在，
            //    但它意味着库存**已经或应该被归还**。只按存在性判断就会走到
            //    「落库成功了，只清标记」分支，结果是库存少一件、买家标记被擦掉、
            //    却没有任何可成交的消息，用户还被判成「已抢过」。
            Integer status = outboxService.findStatusByReservation(activityId, reservationId);
            if (status != null && !isReservationDead(status)) {
                // 落库其实成功了，只是 confirmDeduct 没来得及执行（进程重启、Redis 抖动）。
                // 这种情况只清标记，绝不能回滚——回滚会让这一单凭空多出一件库存。
                stockService.clearPendingMarker(activityId, userId, reservationId);
                log.info("待确认扣减已有落库记录，仅清理标记 activityId={} userId={} reservationId={} status={}",
                        activityId, userId, reservationId, status);
                return;
            }

            // ③ 到这里有两种情况，处理方式相同：库存都该还回去。
            //    · 没有记录：进程倒在「扣完 Redis」和「写完 outbox」之间，纯粹的孤儿；
            //    · 记录已是 3/6：这一单被入口或人工判定为不成立，但进程在回滚 Redis 前挂了。
            //    回滚按 SREM 返回值判断是否真的加库存，所以重复执行不会多还一件。
            RollbackOutcome outcome = stockService.rollback(activityId, userId, reservationId);
            if (outcome == RollbackOutcome.FAILED) {
                // 回滚失败：认领会在租约到期后自动释放，下一轮重新处理
                log.error("【孤儿扣减回滚失败】Redis 异常，留待下一轮 "
                                + "activityId={} userId={} reservationId={}",
                        activityId, userId, reservationId);
                return;
            }
            log.error("【孤儿扣减已回滚】扣减后未落库，进程可能在扣减与落库之间中断。"
                            + "activityId={} userId={} reservationId={} outboxStatus={} outcome={}",
                    activityId, userId, reservationId, status, outcome);
        } finally {
            stockService.releaseClaim(activityId, reservationId, instanceToken);
        }
    }
}
