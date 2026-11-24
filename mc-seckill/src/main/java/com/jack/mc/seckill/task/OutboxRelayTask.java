package com.jack.mc.seckill.task;

import com.jack.mc.common.constant.MqTopics;
import com.jack.mc.common.constant.SeckillKeys;
import com.jack.mc.seckill.entity.SeckillOutbox;
import com.jack.mc.seckill.enums.OutboxStatus;
import com.jack.mc.seckill.mq.MqProducer;
import com.jack.mc.seckill.service.OutboxService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 本地消息表的补偿任务。
 * <p>
 * 干三件事，每 5 秒一轮：
 * <ol>
 *   <li><b>重投</b>：把 status=0（待投递）的记录重新投给 broker。
 *       主流程发送失败时不回滚库存，靠的就是这里兜底；</li>
 *   <li><b>对账</b>：status=1（已投递）超过 5 分钟还没有订单的，
 *       要么说明消费端挂了，要么说明消息真的丢了。先查订单表确认，
 *       真没有就打回待投递重发（消费端幂等，重复投递不会重复下单）。
 *       最多打回 {@link #MAX_RECONCILE} 次，之后转待人工复核——否则消费者
 *       永久故障时这条记录会一直循环，永远等不到人来看；</li>
 *   <li><b>复查</b>：status=4（待人工复核）里后来真的落了库的，自动转成已消费。
 *       这一步让「重试耗尽」不再是死路——迟到的消费者仍能被识别出来。</li>
 * </ol>
 * <p>
 * 为什么要和「发送失败立刻回滚」区分开：MQ 的发送超时无法区分
 * 「broker 没收到」和「broker 收到了但响应丢了」。前者该回滚，后者回滚就是超卖。
 * 把决策推迟到「能确认消费结果」的时刻，才可能做对。
 * <p>
 * <b>重试耗尽后不再自动回滚（第二轮评审 P1）</b>：详见
 * {@link OutboxStatus#NEEDS_REVIEW}。一句话——「此刻查不到订单」不等于
 * 「订单不会来」，按时间自动回滚会把迟到的成功变成超卖。
 */
@Component
public class OutboxRelayTask {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelayTask.class);

    /**
     * 单批处理条数。
     * <p>
     * 从 100 降到 20 是评审要求的：broker 故障时每条发送最坏耗时约 2 秒
     * （1 秒超时 + 1 次重试），100 条就是 200 秒，远超 20 秒的锁租期，
     * 锁会在处理途中过期，另一个实例同时进来把同一批再投一遍、再回滚一遍。
     * 20 条最坏 40 秒，仍然大于租期——所以续租是必须的，缩小批次只是把
     * 「一定会撞上」降成「大概率不会」。
     */
    private static final int BATCH_SIZE = 20;

    /** 最大重投次数。按指数退避算下来大约覆盖 10 分钟，足够熬过一次 broker 重启 */
    private static final int MAX_RETRY = 8;

    /** 已投递但超过这个时间还没订单，就要主动对账 */
    private static final long RECONCILE_DELAY_MINUTES = 5L;

    /**
     * 对账打回的最多次数。
     * <p>
     * 配合 5 分钟的对账延迟，大约 20 分钟还没落库就转人工。
     * 取值不能太小：消费者滚动重启、broker 主从切换都可能让消息晚到几分钟，
     * 提前判死等于把正常请求也丢进人工队列。
     */
    private static final int MAX_RECONCILE = 3;

    /** 待复核记录等多久再复查：给消费者留出迟到落库的时间 */
    private static final long REVIEW_DELAY_SECONDS = 60L;

    private static final Duration LOCK_TTL = Duration.ofSeconds(20);

    /**
     * 解锁必须比对 value：如果只是 DEL，可能删掉的是别人的锁——
     * 自己这轮跑超时、锁已过期被另一个实例拿走，然后自己再 DEL 一次，
     * 就把对方的锁放掉了，互斥形同虚设。
     */
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT = new DefaultRedisScript<>("""
            if redis.call('GET', KEYS[1]) == ARGV[1] then
                return redis.call('DEL', KEYS[1])
            end
            return 0
            """, Long.class);

    /**
     * 续租：同样是「先比对 value 再改过期时间」。
     * <p>
     * 不能直接 EXPIRE——锁已经过期并被别的实例拿走时，直接 EXPIRE 等于
     * 在给别人的锁续命，自己还以为持有锁，两边同时跑。
     */
    private static final DefaultRedisScript<Long> RENEW_SCRIPT = new DefaultRedisScript<>("""
            if redis.call('GET', KEYS[1]) == ARGV[1] then
                return redis.call('PEXPIRE', KEYS[1], ARGV[2])
            end
            return 0
            """, Long.class);

    private final OutboxService outboxService;
    private final MqProducer mqProducer;
    private final StringRedisTemplate redis;

    private final String instanceId = UUID.randomUUID().toString();

    public OutboxRelayTask(OutboxService outboxService, MqProducer mqProducer,
                           StringRedisTemplate redis) {
        this.outboxService = outboxService;
        this.mqProducer = mqProducer;
        this.redis = redis;
    }

    @Scheduled(fixedDelay = 5_000L)
    public void run() {
        if (!acquireLock()) {
            return;
        }
        try {
            // 三个子任务逐个返回「锁是否还在」。任何一个发现锁丢了，后面的一律不跑：
            // 无锁状态下的投递和对账会被别的实例重复执行，轻则重复投递，重则重复回滚。
            // 上一版只在 relayPending 内部 return，后续的 reconcileSent / reviewPending
            // 照样会在无锁状态下继续跑（第三轮评审 P2）。
            if (!relayPending()) {
                return;
            }
            if (!reconcileSent()) {
                return;
            }
            reviewPending();
        } catch (Exception e) {
            // 定时任务抛异常会被调度器吞掉（只是不再调度这一轮），
            // 但日志会丢，所以这里必须自己接住
            log.error("本地消息补偿任务异常", e);
        } finally {
            releaseLock();
        }
    }

    private boolean relayPending() {
        List<SeckillOutbox> pending = outboxService.findPendingToRetry(LocalDateTime.now(), BATCH_SIZE);
        for (SeckillOutbox outbox : pending) {
            // 每条发送前先确认自己还持有锁：处理途中锁过期的话必须立刻停手，
            // 否则另一个实例可能正在处理同一条，两边会重复投递、重复记账
            if (!renewLock()) {
                log.warn("补偿锁已失去，中止本轮处理 outboxId={}", outbox.getId());
                return false;
            }
            if (outbox.getRetryCount() >= MAX_RETRY) {
                giveUp(outbox);
                continue;
            }
            boolean sent = mqProducer.send(MqTopics.SECKILL_ORDER, MqTopics.TAG_CREATE,
                    outbox.getActivityId() + "-" + outbox.getUserId(), outbox.getPayload());
            if (sent) {
                // 带上这一轮读到的预订号：这一行可能已经被 resetAbandoned 复用给下一次抢购，
                // 只按「活动+用户」更新会把新预订误标成已投递（第七轮评审 P2）
                if (outboxService.markSent(outbox.getActivityId(), outbox.getUserId(),
                        outbox.getReservationId())) {
                    log.info("补偿重投成功 outboxId={} activityId={} userId={}",
                            outbox.getId(), outbox.getActivityId(), outbox.getUserId());
                } else {
                    log.warn("补偿消息已发送，但 outbox 状态已被其他流程修改 outboxId={}",
                            outbox.getId());
                }
                continue;
            }
            int next = outbox.getRetryCount() + 1;
            LocalDateTime nextAt = LocalDateTime.now().plusSeconds(backoffSeconds(next));
            outboxService.bumpRetry(outbox.getId(), next, nextAt, "broker 未确认，第 " + next + " 次重投失败");
            log.warn("补偿重投失败 outboxId={} retry={} nextAt={}", outbox.getId(), next, nextAt);
        }
        return true;
    }

    /**
     * 重试耗尽：先查订单表。
     * <p>
     * <b>查到订单</b> → 说明消息其实投递成功了，只是消费慢，标记已消费；
     * <br>
     * <b>查不到订单</b> → <b>不回滚库存</b>，标记待人工复核。
     * <p>
     * 为什么查不到也不回滚：这个判断依赖「消息一定没被消费」，
    * 而我们无法证明这一点——消费者可能积压、可能正在重试、
     * 消息可能落在 broker 的重试队列里。一旦它在标记之后才落库，
     * 库存已经还给别人了，就成了一件货卖两次。
    * 反过来，保留库存的最坏结果是「少卖一件」，人工退掉即可。
     * 少卖是可用性问题，超卖是资损，两者不对等。
     */
    private void giveUp(SeckillOutbox outbox) {
        if (outboxService.orderExists(outbox.getActivityId(), outbox.getUserId())) {
            if (outboxService.casStatus(outbox.getId(),
                    OutboxStatus.PENDING.getCode(), OutboxStatus.CONSUMED.getCode())) {
                log.warn("重试耗尽但订单已存在，标记为已消费 outboxId={} activityId={} userId={}",
                        outbox.getId(), outbox.getActivityId(), outbox.getUserId());
            } else {
                log.info("重试耗尽且订单已存在，但状态已被其他流程处理 outboxId={}",
                        outbox.getId());
            }
            return;
        }
        // CAS：只有还是待投递才标记。万一复查/人工已经动过这一行，就让它去处理
        if (outboxService.casStatus(outbox.getId(),
                OutboxStatus.PENDING.getCode(), OutboxStatus.NEEDS_REVIEW.getCode())) {
            log.error("【需人工复核】重投 {} 次仍未确认消费，库存保留未回滚 "
                            + "(outboxId={} activityId={} userId={})。"
                            + "请先确认该用户是否已有订单，确无订单再走管理接口回滚",
                    MAX_RETRY, outbox.getId(), outbox.getActivityId(), outbox.getUserId());
        } else {
            log.info("重试耗尽但状态已被其他流程处理 outboxId={}", outbox.getId());
        }
    }

    private boolean reconcileSent() {
        List<SeckillOutbox> stale = outboxService.findSentWithoutOrder(
                LocalDateTime.now().minusMinutes(RECONCILE_DELAY_MINUTES), BATCH_SIZE);
        for (SeckillOutbox outbox : stale) {
            if (!renewLock()) {
                log.warn("补偿锁已失去，中止对账 outboxId={}", outbox.getId());
                return false;
            }
            if (outboxService.orderExists(outbox.getActivityId(), outbox.getUserId())) {
                if (!outboxService.casStatus(outbox.getId(),
                        OutboxStatus.SENT.getCode(), OutboxStatus.CONSUMED.getCode())) {
                    log.info("对账发现订单，但 outbox 状态已变化 outboxId={}", outbox.getId());
                }
                continue;
            }
            // 消息投出去了但订单不在——可能是消费端宕机、或消息进了重试队列还没处理。
            // 打回待投递再发一次，消费端唯一索引会把重复吃掉。
            //
            // 但重投必须有终点（第四轮评审 P2）：broker 一直发送成功、消费者永久故障时，
            // 记录会在 SENT → PENDING → SENT 之间无限循环，既不会进待复核也不会告警，
            // 只是每 5 分钟刷一条日志。这里按 reconcile_count 兜住，
            // 超过上限就转人工——和 relayPending 里「重投耗尽转待复核」是同一个思路。
            if (outbox.getReconcileCount() != null
                    && outbox.getReconcileCount() >= MAX_RECONCILE) {
                if (outboxService.casStatus(outbox.getId(),
                        OutboxStatus.SENT.getCode(), OutboxStatus.NEEDS_REVIEW.getCode())) {
                    log.error("【需人工复核】已投递但对账 {} 次仍查不到订单，停止自动重投 "
                                    + "(outboxId={} activityId={} userId={})。"
                                    + "请检查消费端是否存活、该消息是否已进死信队列",
                            outbox.getReconcileCount(), outbox.getId(),
                            outbox.getActivityId(), outbox.getUserId());
                } else {
                    log.info("对账次数已达上限，但状态已被其他流程处理 outboxId={}", outbox.getId());
                }
                continue;
            }
            if (outboxService.resetToPending(outbox.getId())) {
                log.warn("已投递但查不到订单，打回重投 outboxId={} activityId={} userId={} 第 {} 次",
                        outbox.getId(), outbox.getActivityId(), outbox.getUserId(),
                        (outbox.getReconcileCount() == null ? 0 : outbox.getReconcileCount()) + 1);
            }
        }
        return true;
    }

    /**
     * 复查待人工复核的记录：如果订单后来还是落库了，自动转成已消费。
     * <p>
     * 这一步是「NEEDS_REVIEW 不会变成垃圾堆」的保证。真正需要人做的，
     * 只剩下「确实没落库」的那几条，而不是每一条重试耗尽的记录。
     */
    private boolean reviewPending() {
        List<SeckillOutbox> reviews = outboxService.findByStatusBefore(
                OutboxStatus.NEEDS_REVIEW.getCode(),
                LocalDateTime.now().minusSeconds(REVIEW_DELAY_SECONDS), BATCH_SIZE);
        for (SeckillOutbox outbox : reviews) {
            // 第三轮评审 P2：这里原来没有续租。一个批次最多 20 条，
            // 每条都要查一次订单表，慢查询叠加起来同样会超过 20 秒租期，
            // 锁过期后另一个实例进来，两边同时对同一批做状态流转。
            if (!renewLock()) {
                log.warn("补偿锁已失去，中止复查 outboxId={}", outbox.getId());
                return false;
            }
            if (outboxService.orderExists(outbox.getActivityId(), outbox.getUserId())) {
                // CAS：只把「待复核」改成「已消费」。若管理员刚好把它 CAS 成了 5（回滚执行中），
                // 这里更新 0 行，不会把正在回滚的记录改回「已消费」。
                if (outboxService.casStatus(outbox.getId(),
                        OutboxStatus.NEEDS_REVIEW.getCode(), OutboxStatus.CONSUMED.getCode())) {
                    log.info("待复核记录已确认落库，转为已消费 outboxId={} activityId={} userId={}",
                            outbox.getId(), outbox.getActivityId(), outbox.getUserId());
                } else {
                    log.info("待复核记录已有订单，但状态已被其他流程处理 outboxId={}",
                            outbox.getId());
                }
            }
        }
        return true;
    }

    /**
     * <b>这里原来有一个「状态 5 超时自动退回待复核」的兜底，第七轮评审 P0 已删除。</b>
     * <p>
     * 原逻辑：{@code status=5} 超过 120 秒未落终态就无条件 CAS 5→4，
     * 用来救「人工回滚的进程崩了、try/finally 没执行」的情况。
     * <p>
     * 它救不了的同时还会制造超卖：状态 5 本身是<b>排他凭据</b>——
     * 消费者落库时读到 5 会拒绝（{@code OrderPersistService}），
     * 所以「管理员持有 5」等于「此刻没人能建单」。而时间无法证明持有者已经死了：
     * 只要原线程只是慢（Redis 阻塞、GC、网关超时但线程仍在跑），
     * 到点被改回 4 之后消费者立刻可以落库，原线程随后照旧执行 Redis 回滚，
     * 就形成「库存已还 + 订单已建」。{@code finally} 拦不住已经被回收的记录。
     * <p>
     * 现在改成「不确定就交给人」：卡住的 5 会出现在管理端的待处理列表里
     * （{@code OutboxAdminController.listReview}），由运维确认原请求确实已死
     * 之后再调 {@code POST /admin/outbox/{id}/recover}，在状态 5 上幂等重试回滚。
     * 重试成功才进入 3，失败或结果不确定仍保持 5。
     * 时间只能用来<b>提示</b>，不能用来<b>决定</b>——这条和「重试耗尽不自动回滚」
     * 是同一条原则：少卖可以人工补，超卖是资损。
     */

    /** 指数退避：10s、20s、40s…… 上限 5 分钟，避免 broker 故障时把重试打成忙等 */
    private long backoffSeconds(int retryCount) {
        long seconds = 10L << Math.min(retryCount - 1, 5);
        return Math.min(seconds, 300L);
    }

    private boolean acquireLock() {
        try {
            Boolean ok = redis.opsForValue()
                    .setIfAbsent(SeckillKeys.outboxRelayLock(), instanceId, LOCK_TTL);
            return Boolean.TRUE.equals(ok);
        } catch (Exception e) {
            // Redis 不可用时不能直接放行：多实例会同时扫表重复投递。
            // 跳过本轮，等 Redis 恢复——补偿延迟几秒可以接受，重复投递不行。
            log.warn("获取补偿任务锁失败，跳过本轮", e);
            return false;
        }
    }

    /**
     * 续租：把锁的过期时间重置为完整租期。
     * <p>
     * 不续租的话，租期 20 秒 vs 单批最坏 40 秒（20 条 × 2 秒）必然穿透。
     * 续租失败返回 false，调用方必须立即停手——继续处理等于在无锁状态下跑。
     */
    private boolean renewLock() {
        try {
            Long renewed = redis.execute(RENEW_SCRIPT,
                    List.of(SeckillKeys.outboxRelayLock()),
                    instanceId, String.valueOf(LOCK_TTL.toMillis()));
            return renewed != null && renewed > 0;
        } catch (Exception e) {
            log.warn("续租补偿任务锁失败，本轮中止", e);
            return false;
        }
    }

    private void releaseLock() {
        try {
            redis.execute(UNLOCK_SCRIPT, List.of(SeckillKeys.outboxRelayLock()), instanceId);
        } catch (Exception e) {
            // 释放失败不影响正确性：锁有 TTL 会自动过期
            log.debug("释放补偿任务锁失败", e);
        }
    }
}
