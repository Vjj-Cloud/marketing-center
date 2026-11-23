package com.jack.mc.seckill.controller;

import com.jack.mc.common.BusinessException;
import com.jack.mc.common.Result;
import com.jack.mc.common.web.RequireAdmin;
import com.jack.mc.seckill.entity.SeckillOutbox;
import com.jack.mc.seckill.enums.OutboxStatus;
import com.jack.mc.seckill.enums.RollbackOutcome;
import com.jack.mc.seckill.service.OutboxService;
import com.jack.mc.seckill.service.StockService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 本地消息表的人工处置入口。
 * <p>
 * <b>为什么必须有这个接口</b>：补偿任务重试耗尽后不再自动回滚库存
 * （见 {@link OutboxStatus#NEEDS_REVIEW}），这些记录必须有出口，
 * 否则「库存被永久占着」就成了新的漏损。
 * 用 SQL 手改也能做，但那是绕过业务规则改数据：
 * 没人记得清「回滚前必须再查一次订单」，一不小心就把已经成交的单也回滚了。
 * <p>
 * 整个类标 {@link RequireAdmin}：这不是普通用户能碰的东西。
 */
@RestController
@RequestMapping("/api/seckill/admin/outbox")
@RequireAdmin
public class OutboxAdminController {

    private static final Logger log = LoggerFactory.getLogger(OutboxAdminController.class);

    /** 列表单页上限，防止一次拉爆 */
    private static final int MAX_LIMIT = 200;

    private final OutboxService outboxService;
    private final StockService stockService;

    public OutboxAdminController(OutboxService outboxService, StockService stockService) {
        this.outboxService = outboxService;
        this.stockService = stockService;
    }

    /**
     * 待人工复核的记录列表。
     * <p>
     * 只看 status=4：这些是「重试耗尽、库存还没还」的条目，
     * 处理完一条少一条，可以作为运维的待办清单。
     */
    @GetMapping("/review")
    public Result<List<Map<String, Object>>> listReview(@RequestParam(defaultValue = "50") int limit) {
        int safeLimit = Math.min(Math.max(limit, 1), MAX_LIMIT);
        // 用 0 秒延迟取全量待复核，不走复查任务的 60 秒宽限
        List<SeckillOutbox> rows = outboxService.findByStatusBefore(
                OutboxStatus.NEEDS_REVIEW.getCode(), LocalDateTime.now(), safeLimit);
        List<Map<String, Object>> data = new ArrayList<>(rows.size());
        for (SeckillOutbox row : rows) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", row.getId());
            item.put("activityId", row.getActivityId());
            item.put("userId", row.getUserId());
            item.put("reservationId", row.getReservationId());
            item.put("retryCount", row.getRetryCount());
            item.put("lastError", row.getLastError());
            item.put("createdAt", row.getCreatedAt());
            item.put("updatedAt", row.getUpdatedAt());
            data.add(item);
        }
        return Result.ok(data);
    }

    /**
     * 人工确认后回滚库存。
     * <p>
     * <b>整个方法的核心是「不能被插队」</b>。上一版是
     * 「查订单 → 回滚 Redis → 无条件改状态」，三步之间各自有窗口：
     * 消费者可能在第二步之前把订单落库，复查任务可能同时把状态改成已消费，
     * 结果就是「库存已还 + 订单已建」，或者「状态显示已消费、库存已还」。
     * <p>
     * 现在用数据库做互斥点：
     * <ol>
     *   <li><b>CAS 认领</b>：{@code UPDATE ... SET status=5 WHERE id=? AND status=4}。
     *       抢不到说明别人正在处理或状态已变，直接 409；</li>
     *   <li>查订单——此刻这一行被我们独占，而消费者的落库事务里
     *       也会 {@code FOR UPDATE} 锁同一行（见 mc-order 的 OrderPersistService），
     *       所以「查订单」和「落库」不可能交错；</li>
     *   <li>回滚 Redis，按三态分别处理：成功/无需动作 → 3；
     *       <b>基础设施失败 → 退回 4</b>，绝不标记成已回滚。</li>
     * </ol>
     * 无论走哪条分支，状态都必须离开 5，否则这条记录既不会被复查任务扫到、
     * 也不会出现在待复核列表里，等于永久失联——所以用 try/finally 兜底。
     */
    @PostMapping("/{id}/rollback")
    public Result<Map<String, Object>> rollback(@PathVariable Long id) {
        SeckillOutbox outbox = outboxService.findById(id);
        if (outbox == null) {
            throw new BusinessException(404, "记录不存在");
        }
        if (outbox.getStatus() == null || outbox.getStatus() != OutboxStatus.NEEDS_REVIEW.getCode()) {
            throw new BusinessException(409, "只有待人工复核的记录才能执行回滚，当前状态=" + outbox.getStatus());
        }

        // ① CAS 认领：4 → 5。抢不到说明并发的另一个请求（或复查任务）已经动过这一行
        if (!outboxService.casStatus(id, OutboxStatus.NEEDS_REVIEW.getCode(),
                OutboxStatus.ROLLING_BACK.getCode())) {
            throw new BusinessException(409, "该记录正在被处理或状态已变更，请刷新后重试");
        }

        boolean settled = false;
        try {
            // ② 查订单：这一行已被我们锁住，消费者的落库事务会在这里阻塞
            if (outboxService.orderExists(outbox.getActivityId(), outbox.getUserId())) {
                if (outboxService.casStatus(id, OutboxStatus.ROLLING_BACK.getCode(),
                        OutboxStatus.CONSUMED.getCode())) {
                    settled = true;
                    log.warn("复核时发现订单已落库，转为已消费，未回滚库存 "
                                    + "outboxId={} activityId={} userId={}",
                            id, outbox.getActivityId(), outbox.getUserId());
                    throw new BusinessException(409,
                            "该用户已有订单（消息迟到落库），已转为已消费，无需回滚");
                }
                throw new BusinessException(409, "订单已出现，但记录状态已被其他流程修改，请刷新核对");
            }

            // ③ 回滚 Redis
            RollbackOutcome outcome = stockService.rollback(
                    outbox.getActivityId(), outbox.getUserId(), outbox.getReservationId());
            if (outcome == RollbackOutcome.FAILED) {
                // ★ 这是第三轮评审 P1 的核心：Redis 故障不能当成「已回滚」。
                //   退回待复核，让运维在 Redis 恢复后重试；库存此刻仍是「已扣未还」的真实状态。
                outboxService.casStatus(id, OutboxStatus.ROLLING_BACK.getCode(),
                        OutboxStatus.NEEDS_REVIEW.getCode());
                settled = true;
                log.error("人工回滚时 Redis 异常，记录退回待复核 outboxId={} activityId={} userId={}",
                        id, outbox.getActivityId(), outbox.getUserId());
                throw new BusinessException(503, "库存服务暂时不可用，库存未回滚，记录已保留在待复核列表，请稍后重试");
            }

            if (!outboxService.casStatus(id, OutboxStatus.ROLLING_BACK.getCode(),
                    OutboxStatus.ROLLED_BACK.getCode())) {
                throw new BusinessException(409, "库存已处理，但记录状态已被其他流程修改，请刷新后核对");
            }
            settled = true;
            log.warn("人工复核后回滚库存 outboxId={} activityId={} userId={} outcome={}",
                    id, outbox.getActivityId(), outbox.getUserId(), outcome);

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("outboxId", id);
            data.put("outcome", outcome.name());
            data.put("stockRestored", outcome == RollbackOutcome.RESTORED);
            // NOTHING_TO_DO 有两种成因：用户本就不在买家集合（重复回滚），
            // 或库存 key 已过期（活动结束）。都不是失败，但必须如实告诉操作者，
            // 否则他会以为库存已经加回去了。
            data.put("note", outcome == RollbackOutcome.RESTORED
                    ? "库存已加回"
                    : "库存 key 已过期或用户不在买家集合，无需加回");
            return Result.ok(data);
        } finally {
            if (!settled) {
                // 未预期的异常（比如查订单时数据库超时）：必须把记录退回待复核，
                // 否则它会永远卡在 5，既不在待复核列表里，也不会被复查任务扫到
                outboxService.casStatus(id, OutboxStatus.ROLLING_BACK.getCode(),
                        OutboxStatus.NEEDS_REVIEW.getCode());
                log.error("人工回滚过程异常，记录已退回待复核 outboxId={}", id);
            }
        }
    }
}
