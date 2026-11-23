package com.jack.mc.seckill.service.impl;

import com.jack.mc.common.BusinessException;
import com.jack.mc.common.constant.SeckillKeys;
import com.jack.mc.seckill.entity.SeckillActivity;
import com.jack.mc.seckill.enums.ActivityStatus;
import com.jack.mc.seckill.enums.DeductResult;
import com.jack.mc.seckill.enums.RollbackOutcome;
import com.jack.mc.seckill.mapper.SeckillActivityMapper;
import com.jack.mc.seckill.service.StockService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

@Service
public class StockServiceImpl implements StockService {

    private static final Logger log = LoggerFactory.getLogger(StockServiceImpl.class);

    private static final long MIN_TTL_SECONDS = 3600L;

    private final StringRedisTemplate redis;
    private final SeckillActivityMapper activityMapper;
    /**
     * 释放认领：比对 value 后才删。
     * <p>
     * 不能直接 DEL —— 自己的租约可能已经过期、claim 被另一个实例重新拿到，
     * 这一 DEL 就把对方的认领放掉了，互斥形同虚设。
     */
    private static final DefaultRedisScript<Long> RELEASE_CLAIM_SCRIPT = new DefaultRedisScript("""
            if redis.call('GET', KEYS[1]) == ARGV[1] then
                return redis.call('DEL', KEYS[1])
            end
            return 0
            """, Long.class);

    private final DefaultRedisScript<Long> deductScript;
    private final DefaultRedisScript<Long> rollbackScript;
    private final DefaultRedisScript<Long> orphanClaimScript;
    private final DefaultRedisScript<Long> confirmDeductScript;

    public StockServiceImpl(StringRedisTemplate redis,
                            SeckillActivityMapper activityMapper,
                            @Qualifier("seckillDeductScript") DefaultRedisScript<Long> deductScript,
                            @Qualifier("seckillRollbackScript") DefaultRedisScript<Long> rollbackScript,
                            @Qualifier("seckillOrphanClaimScript") DefaultRedisScript<Long> orphanClaimScript,
                            @Qualifier("seckillConfirmDeductScript") DefaultRedisScript<Long> confirmDeductScript) {
        this.redis = redis;
        this.activityMapper = activityMapper;
        this.deductScript = deductScript;
        this.rollbackScript = rollbackScript;
        this.orphanClaimScript = orphanClaimScript;
        this.confirmDeductScript = confirmDeductScript;
    }

    @Override
    public boolean warmup(Long activityId) {
        SeckillActivity activity = activityMapper.selectById(activityId);
        if (activity == null) {
            throw new BusinessException(404, "活动不存在");
        }
        if (!ActivityStatus.PUBLISHED.is(activity.getStatus())) {
            throw new BusinessException(409, "只有已发布的活动才能预热库存");
        }
        LocalDateTime now = LocalDateTime.now();
        if (now.isAfter(activity.getEndTime())) {
            log.info("活动已结束，跳过预热 activityId={}", activityId);
            return false;
        }

        /*
         * 只允许「尚未开始」的活动预热。这是上一版的一个漏洞：
         * 原条件放宽成「开始后 1 分钟内也算即将开始」，而
         * DB 的 available_stock 在秒杀过程中并不随 Redis 扣减变化（落库是异步的），
         * 所以一旦这 1 分钟内 Redis 库存 key 丢了，就会按 DB 的旧值重新灌入，
         * 把已经卖掉的库存重新变出来 —— 直接超卖。
         *
         * 严格限制为未开始后的代价是：进行中的活动库存真的丢了就补不了，
         * 只能人工对账。这是刻意接受的取舍 —— 停摆是可用性问题，超卖是资损问题。
         */
        if (!activity.getStartTime().isAfter(now)) {
            throw new BusinessException(409,
                    "活动已开始，不能用数据库库存重新预热（异步落库期间数据库值不反映真实剩余量）");
        }

        String stockKey = SeckillKeys.stock(activityId);
        long ttl = ttlSeconds(activity.getEndTime());

        // 这里必须用 setIfAbsent(SETNX)，不能用「先 hasKey 再 set」：
        // 后者两步之间有窗口，两个实例同时预热会互相覆盖；
        // 更要命的是活动进行中重启服务时，无条件 SET 会把已经扣到 30 的库存
        // 重置回初始的 100——这等于凭空变出 70 件货，属于直接资损。
        Boolean written = redis.opsForValue().setIfAbsent(
                stockKey,
                String.valueOf(activity.getAvailableStock()),
                Duration.ofSeconds(ttl));

        if (Boolean.TRUE.equals(written)) {
            // 记录「这个活动成功预热过」，供预热任务区分
            // 「从未预热」（正常，还没到窗口）和「预热过但 key 丢了」（事故）
            activityMapper.markWarmedUp(activityId);
            log.info("库存预热成功 activityId={} stock={} ttl={}s",
                    activityId, activity.getAvailableStock(), ttl);
            return true;
        }
        log.debug("库存已存在，跳过预热 activityId={} current={}", activityId, redis.opsForValue().get(stockKey));
        return false;
    }

    @Override
    public DeductResult deduct(Long activityId, Long userId, LocalDateTime activityEndTime,
                               String reservationId) {
        long ttl = ttlSeconds(activityEndTime);
        Long result = redis.execute(
                deductScript,
                List.of(SeckillKeys.stock(activityId),
                        SeckillKeys.bought(activityId),
                        SeckillKeys.pendingDeduction(activityId)),
                String.valueOf(userId),
                String.valueOf(ttl),
                // 时间戳由应用传入而不是脚本里调 TIME：要和兜底任务的判断用同一个时钟，
                // 混用 Redis 与 JVM 时钟时，两边的漂移会表现成「明明没超时却被判超时」
                String.valueOf(System.currentTimeMillis()),
                reservationId);

        return DeductResult.of(result);
    }

    /**
     * 确认扣减。
     * <p>
     * <b>这里是第三轮评审 P0 的落点</b>：返回值必须被调用方处理。
     * false 表示兜底任务已经认领了这笔扣减（判定为孤儿并可能已归还库存），
     * 调用方若继续投递 MQ，就会造出「库存已还 + 订单照建」的超卖。
     * <p>
     * Redis 异常时返回 <b>false</b> 而不是 true：无法确认自己是否仍是这笔扣减的
     * 唯一主人时，宁可让这一单失败（用户重试即可），也不能冒超卖的风险。
     * 此时 outbox 行会被调用方撤销，不会留下需要重投的记录。
     */
    @Override
    public boolean confirmDeduct(Long activityId, Long userId, String reservationId) {
        try {
            Long confirmed = redis.execute(confirmDeductScript,
                    List.of(SeckillKeys.pendingDeduction(activityId),
                            SeckillKeys.orphanClaim(activityId, reservationId)),
                    SeckillKeys.pendingMember(userId, reservationId));
            return confirmed != null && confirmed > 0;
        } catch (Exception e) {
            log.error("确认扣减失败，按「不可信」处理 activityId={} userId={}", activityId, userId, e);
            return false;
        }
    }

    @Override
    public void clearPendingMarker(Long activityId, Long userId, String reservationId) {
        try {
            redis.opsForZSet().remove(SeckillKeys.pendingDeduction(activityId),
                    SeckillKeys.pendingMember(userId, reservationId));
        } catch (Exception e) {
            // 清不掉不影响正确性：留着只会让兜底任务下一轮再查一次 outbox，
            // 查到记录仍在，继续只清标记
            log.warn("清理待确认扣减标记失败 activityId={} userId={}", activityId, userId, e);
        }
    }

    @Override
    public boolean claimOrphan(Long activityId, Long userId, String reservationId,
                               long cutoffMillis, long leaseMillis, String token) {
        try {
            Long claimed = redis.execute(orphanClaimScript,
                    List.of(SeckillKeys.pendingDeduction(activityId),
                            SeckillKeys.orphanClaim(activityId, reservationId)),
                    SeckillKeys.pendingMember(userId, reservationId),
                    String.valueOf(cutoffMillis),
                    token,
                    String.valueOf(leaseMillis));
            return claimed != null && claimed > 0;
        } catch (Exception e) {
            // Redis 异常时不能认领：认领不到就不会动手，下轮再试
            log.warn("认领孤儿扣减失败 activityId={} userId={}", activityId, userId, e);
            return false;
        }
    }

    @Override
    public void releaseClaim(Long activityId, String reservationId, String token) {
        try {
            redis.execute(RELEASE_CLAIM_SCRIPT,
                    List.of(SeckillKeys.orphanClaim(activityId, reservationId)), token);
        } catch (Exception e) {
            // 释放失败不影响正确性：租约到期会自动消失
            log.debug("释放孤儿扣减认领失败 activityId={} reservationId={}",
                    activityId, reservationId, e);
        }
    }

    /**
     * 回滚库存。
     * <p>
     * 返回值改成三态，是因为调用方需要区分「业务上无需动作」和「Redis 挂了」：
     * 前者可以收尾，后者必须保持待复核状态——否则会出现
     * 「状态显示已回滚、库存实际没还」的记录，而这条记录不会再被任何任务碰到。
     */
    @Override
    public RollbackOutcome rollback(Long activityId, Long userId, String reservationId) {
        try {
            Long restored = redis.execute(rollbackScript,
                    List.of(SeckillKeys.stock(activityId),
                            SeckillKeys.bought(activityId),
                            SeckillKeys.pendingDeduction(activityId)),
                    String.valueOf(userId),
                    SeckillKeys.pendingMember(userId, reservationId));
            if (restored == null) {
                return RollbackOutcome.FAILED;
            }
            if (restored > 0) {
                log.warn("库存已回滚 activityId={} userId={}", activityId, userId);
                return RollbackOutcome.RESTORED;
            }
            // -1 与 0 都是「已经没有可回滚的东西」：
            //   0  用户不在买家集合（重复回滚）
            //  -1  用户被移出集合了，但库存 key 已过期（活动结束），无处可还
            // 第一版在这里无条件 INCR，同一用户回滚两次库存就多两件——库存虚增是直接资损。
            if (restored == -1) {
                log.warn("回滚时库存 key 已过期，用户已解绑但库存无处可还 activityId={} userId={}",
                        activityId, userId);
            } else {
                log.info("回滚无需执行（用户不在买家集合）activityId={} userId={}", activityId, userId);
            }
            return RollbackOutcome.NOTHING_TO_DO;
        } catch (Exception e) {
            // ★ 这里绝不能返回 NOTHING_TO_DO：Redis 故障时库存状态是未知的，
            //   当成「无需回滚」就等于把一笔没还的库存永久丢掉。
            log.error("库存回滚失败，状态未知 activityId={} userId={}", activityId, userId, e);
            return RollbackOutcome.FAILED;
        }
    }

    @Override
    public Long currentStock(Long activityId) {
        String value = redis.opsForValue().get(SeckillKeys.stock(activityId));
        return value == null ? null : Long.parseLong(value);
    }

    @Override
    public Long boughtCount(Long activityId) {
        Long size = redis.opsForSet().size(SeckillKeys.bought(activityId));
        return size == null ? 0L : size;
    }

    /**
     * TTL 取「活动结束时间 + 1 小时」，留一小时给订单落库和结果查询。
     * 不设永不过期：活动结束后这些 key 就是垃圾，留着只会把 Redis 内存吃满。
     * <p>
     * 下限 1 小时是防御性的：如果活动结束时间已经过去（比如手动预热了过期活动），
     * Duration 会算出负数，EXPIRE 传负数在 Redis 里等于立刻删除，
     * 表现为「预热完 key 就没了」，很难查。
     */
    private long ttlSeconds(LocalDateTime endTime) {
        long seconds = Duration.between(LocalDateTime.now(), endTime).getSeconds() + 3600;
        return Math.max(seconds, MIN_TTL_SECONDS);
    }
}
