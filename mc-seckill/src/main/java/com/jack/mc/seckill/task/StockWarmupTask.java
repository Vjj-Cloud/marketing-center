package com.jack.mc.seckill.task;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jack.mc.common.BusinessException;
import com.jack.mc.seckill.entity.SeckillActivity;
import com.jack.mc.seckill.enums.ActivityStatus;
import com.jack.mc.seckill.mapper.SeckillActivityMapper;
import com.jack.mc.seckill.service.StockService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 库存预热任务。
 * <p>
 * 两种触发：启动时补灌一次（ApplicationRunner），之后每 30 秒扫一次即将开始的活动。
 * <p>
 * <b>核心安全规则：只用「尚未开始」的活动预热。</b>
 * 因为 DB 的 available_stock 在秒杀过程中不随 Redis 扣减而变化（落库是异步的），
 * 对进行中的活动用 DB 值预热，等于把已经卖掉的库存重新变出来。
 * 上一版把窗口放宽到「开始后 1 分钟内也算即将开始」，正好踩中这条：
 * 那 1 分钟里如果 Redis 库存 key 丢了（重启、淘汰、误删），
 * 就会按旧值灌回去，直接超卖。现在严格限制为 start_time &gt; now。
 * <p>
 * 代价是：进行中的活动库存真丢了就补不了，只能人工对账。
 * 这是刻意接受的取舍——停摆是可用性问题，超卖是资损问题。
 */
@Component
public class StockWarmupTask implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(StockWarmupTask.class);

    /** 提前预热窗口：活动开始前 10 分钟就把库存准备好，避免开抢瞬间才写 Redis */
    private static final long WARMUP_AHEAD_MINUTES = 10L;

    /** 活动开始多久后还没库存，就认定是事故（给预热窗口留出提交延迟的余量） */
    private static final long ALERT_GRACE_SECONDS = 60L;

    private final SeckillActivityMapper activityMapper;
    private final StockService stockService;

    /** 已告警过的活动，避免同一个事故每 30 秒刷一条 error 日志 */
    private final Set<Long> alerted = ConcurrentHashMap.newKeySet();

    public StockWarmupTask(SeckillActivityMapper activityMapper, StockService stockService) {
        this.activityMapper = activityMapper;
        this.stockService = stockService;
    }

    @Override
    public void run(ApplicationArguments args) {
        log.info("启动补灌：检查需要预热的活动");
        warmupUpcoming();
    }

    @Scheduled(fixedDelay = 30_000L)
    public void warmupUpcoming() {
        LocalDateTime now = LocalDateTime.now();
        int finished = activityMapper.finishExpired(now);
        if (finished > 0) {
            log.info("已将 {} 个到期活动标记为已结束", finished);
        }
        warmupNotStarted(now);
        alertMissingStock(now);
    }

    /** 1. 尚未开始的活动：DB 值与实际一致，可以安全预热 */
    private void warmupNotStarted(LocalDateTime now) {
        List<SeckillActivity> upcoming = activityMapper.selectList(
                new LambdaQueryWrapper<SeckillActivity>()
                        .eq(SeckillActivity::getStatus, ActivityStatus.PUBLISHED.getCode())
                        // 严格大于 now：一旦开始就不再用 DB 值补
                        .gt(SeckillActivity::getStartTime, now)
                        .lt(SeckillActivity::getStartTime, now.plusMinutes(WARMUP_AHEAD_MINUTES))
                        .gt(SeckillActivity::getEndTime, now));

        for (SeckillActivity activity : upcoming) {
            try {
                stockService.warmup(activity.getId());
            } catch (BusinessException e) {
                // 查到之后、调用之前刚好开始，属于正常的边界竞争，不是故障
                log.debug("跳过预热 activityId={} 原因={}", activity.getId(), e.getMessage());
            } catch (Exception e) {
                // 单个活动预热失败不能影响其他活动
                log.error("预热失败 activityId={}", activity.getId(), e);
            }
        }
    }

    /**
     * 2. 进行中但没有库存 key 的活动：只告警，不自动补。
     * <p>
     * 加了 60 秒宽限：刚开抢的一瞬间缓存可能还没写完，属于正常现象；
     * 超过 1 分钟仍然没有，才是真的丢了。
     */
    private void alertMissingStock(LocalDateTime now) {
        List<SeckillActivity> running = activityMapper.selectList(
                new LambdaQueryWrapper<SeckillActivity>()
                        .eq(SeckillActivity::getStatus, ActivityStatus.PUBLISHED.getCode())
                        .le(SeckillActivity::getStartTime, now)
                        .gt(SeckillActivity::getEndTime, now));

        for (SeckillActivity activity : running) {
            boolean missing = stockService.currentStock(activity.getId()) == null;
            if (!missing) {
                // 恢复了就清掉标记，下次再丢还能重新告警
                alerted.remove(activity.getId());
                continue;
            }
            if (!now.isAfter(activity.getStartTime().plusSeconds(ALERT_GRACE_SECONDS))) {
                continue;
            }
            if (!alerted.add(activity.getId())) {
                continue;
            }
            log.error("【需人工介入】进行中的活动库存丢失，DB 值不可信（异步落库中），"
                            + "请检查 Redis 持久化与对账。activityId={} dbStock={} warmupAt={}",
                    activity.getId(), activity.getAvailableStock(), activity.getWarmupAt());
        }
    }
}
