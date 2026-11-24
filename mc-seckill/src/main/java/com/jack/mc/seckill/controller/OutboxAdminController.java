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

    /**
     * 状态 5 停留多久算「卡住」。
     * <p>
     * 一次正常的人工回滚是「一次查询 + 一次 Redis 调用」，毫秒级；
     * 超过 120 秒几乎只能是进程崩了、连接断了、或某个下游卡死。
     * 这个阈值只用于<b>提示</b>（列表里标 stalled），不再触发任何自动状态变更——
     * 理由见 {@link #recover}。
     */
    private static final int STALL_SECONDS = 120;

    private final OutboxService outboxService;
    private final StockService stockService;

    public OutboxAdminController(OutboxService outboxService, StockService stockService) {
        this.outboxService = outboxService;
        this.stockService = stockService;
    }

    /**
     * 待人工处理的记录列表。
     * <p>
     * 两类都列出来，用 {@code state} 区分：
     * <ul>
     *   <li>{@code NEEDS_REVIEW(4)}：重试耗尽、库存还没还，处理完一条少一条，是常规待办；</li>
     *   <li>{@code ROLLING_BACK(5)} 且停留超过 {@link #STALL_SECONDS}：回滚做到一半没落终态，
     *       {@code state} 为 {@code ROLLING_BACK_STALLED}。<b>第七轮评审 P0 新增</b>——
     *       原来这类记录会被定时任务无条件改回 4，现在改成列出来给人看，
     *       因为「时间到了」不等于「原来那次回滚已经不在跑了」。</li>
     * </ul>
     * {@code limit} 是「每类上限」而不是总数上限：卡住的 5 不该因为 4 太多而被截掉。
     */
    @GetMapping("/review")
    public Result<List<Map<String, Object>>> listReview(@RequestParam(defaultValue = "50") int limit) {
        int safeLimit = Math.min(Math.max(limit, 1), MAX_LIMIT);
        // 用 0 秒延迟取全量待复核，不走复查任务的 60 秒宽限
        List<SeckillOutbox> rows = new ArrayList<>(outboxService.findByStatusBefore(
                OutboxStatus.NEEDS_REVIEW.getCode(), LocalDateTime.now(), safeLimit));
        List<SeckillOutbox> stalled = outboxService.findByStatusBefore(
                OutboxStatus.ROLLING_BACK.getCode(),
                LocalDateTime.now().minusSeconds(STALL_SECONDS), safeLimit);
        rows.addAll(stalled);

        List<Map<String, Object>> data = new ArrayList<>(rows.size());
        for (SeckillOutbox row : rows) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", row.getId());
            item.put("activityId", row.getActivityId());
            item.put("userId", row.getUserId());
            item.put("reservationId", row.getReservationId());
            item.put("status", row.getStatus());
            item.put("state", stateOf(row.getStatus()));
            item.put("retryCount", row.getRetryCount());
            item.put("lastError", row.getLastError());
            item.put("createdAt", row.getCreatedAt());
            item.put("updatedAt", row.getUpdatedAt());
            data.add(item);
        }
        return Result.ok(data);
    }

    private String stateOf(Integer status) {
        if (status == null) {
            return "UNKNOWN";
        }
        if (status == OutboxStatus.NEEDS_REVIEW.getCode()) {
            return "NEEDS_REVIEW";
        }
        if (status == OutboxStatus.ROLLING_BACK.getCode()) {
            return "ROLLING_BACK_STALLED";
        }
        return "STATUS_" + status;
    }

    /**
     * 人工确认后回滚库存。
     * <p>
     * 状态 4 会先 CAS 到 5；状态 5 表示上一次回滚的结果不确定，可以直接幂等重试。
     * <b>一旦进入 5，本方法不再把状态退回 4。</b>
     * <p>
     * Redis 回滚异常代表「命令可能已经执行，只是响应丢了」，不能据此认为库存没还。
     * 这时必须继续用 5 挡住消费者；后续重试同一个 Lua 脚本是幂等的：
     * 上一次已经归还时，本次 `SREM` 返回 0，不会重复增加库存。
     * 只有拿到 RESTORED / NOTHING_TO_DO 的明确结果后，才允许 5 → 3。
     * <p>
     * 同理，Redis 已成功但最终 5 → 3 的数据库更新失败时也保留 5。
     * 后续重试会再次执行幂等回滚并重新尝试落终态，绝不会开放消费者落单。
     */
    @PostMapping("/{id}/rollback")
    public Result<Map<String, Object>> rollback(@PathVariable Long id) {
        SeckillOutbox outbox = requireOutbox(id);
        int status = outbox.getStatus() == null ? -1 : outbox.getStatus();

        if (status == OutboxStatus.NEEDS_REVIEW.getCode()) {
            // 4 → 5 是第一次认领；抢不到说明有人已经先动手。
            if (!outboxService.casStatus(id, OutboxStatus.NEEDS_REVIEW.getCode(),
                    OutboxStatus.ROLLING_BACK.getCode())) {
                throw new BusinessException(409, "该记录正在被处理或状态已变更，请刷新后重试");
            }
        } else if (status != OutboxStatus.ROLLING_BACK.getCode()) {
            throw new BusinessException(409,
                    "只有待人工复核或回滚执行中的记录才能回滚，当前状态=" + status);
        } else {
            log.warn("重试未完成的库存回滚 outboxId={} activityId={} userId={}",
                    id, outbox.getActivityId(), outbox.getUserId());
        }

        return resolveRollback(outbox);
    }

    /**
     * 显式重试一条已经卡住的状态 5。
     * <p>
     * 不先退回 4：那会瞬间允许消费者落库，而 Redis 侧可能已经归还过库存。
     * 这里保持在 5，直接执行幂等回滚；成功后才 5 → 3，失败则继续停在 5。
     * 即使原回滚线程仍在运行，两边并发执行也是安全的：
     * 消费者始终被 5 挡住，Redis 脚本按 `SREM` 返回值保证库存只还一次。
     * <p>
     * 只有停留超过 {@link #STALL_SECONDS} 的记录才允许走这里，避免刚进入 5
     * 的正常请求被误操作。
     */
    @PostMapping("/{id}/recover")
    public Result<Map<String, Object>> recover(@PathVariable Long id) {
        SeckillOutbox outbox = requireOutbox(id);
        if (outbox.getStatus() == null
                || outbox.getStatus() != OutboxStatus.ROLLING_BACK.getCode()) {
            throw new BusinessException(409,
                    "只有卡在回滚执行中(5)的记录才需要恢复，当前状态=" + outbox.getStatus());
        }
        if (!isStalled(outbox)) {
            throw new BusinessException(409,
                    "记录尚未达到 " + STALL_SECONDS + " 秒的卡住阈值，请稍后再试");
        }
        log.warn("人工重试卡住的库存回滚 outboxId={} activityId={} userId={}",
                id, outbox.getActivityId(), outbox.getUserId());
        return resolveRollback(outbox);
    }

    private SeckillOutbox requireOutbox(Long id) {
        SeckillOutbox outbox = outboxService.findById(id);
        if (outbox == null) {
            throw new BusinessException(404, "记录不存在");
        }
        return outbox;
    }

    private boolean isStalled(SeckillOutbox outbox) {
        return outbox.getUpdatedAt() != null
                && !outbox.getUpdatedAt().isAfter(
                        LocalDateTime.now().minusSeconds(STALL_SECONDS));
    }

    /**
     * 在状态 5 的保护下完成一次幂等回滚。
     * <p>
     * 这个方向单向且不可回退：4/5 → 5 → 3（或发现订单 → 2）。
     * 任何不确定性都保持 5，留待下一次重试或人工对账。
     */
    private Result<Map<String, Object>> resolveRollback(SeckillOutbox outbox) {
        Long id = outbox.getId();

        if (outboxService.orderExists(outbox.getActivityId(), outbox.getUserId())) {
            if (outboxService.casStatus(id, OutboxStatus.ROLLING_BACK.getCode(),
                    OutboxStatus.CONSUMED.getCode())) {
                log.warn("复核时发现订单已落库，转为已消费，未回滚库存 "
                                + "outboxId={} activityId={} userId={}",
                        id, outbox.getActivityId(), outbox.getUserId());
                throw new BusinessException(409,
                        "该用户已有订单（消息迟到落库），已转为已消费，无需回滚");
            }
            throw new BusinessException(409,
                    "订单已出现，但记录状态已被其他流程修改，请刷新核对");
        }

        RollbackOutcome outcome = stockService.rollback(
                outbox.getActivityId(), outbox.getUserId(), outbox.getReservationId());
        if (outcome == RollbackOutcome.FAILED) {
            // Redis 超时/断连并不等于 Lua 没执行。保持 5，消费者不能落单；
            // 后续重试同一个幂等脚本即可安全收敛。
            log.error("人工回滚结果不确定，保留状态 5 等待重试 "
                            + "outboxId={} activityId={} userId={}",
                    id, outbox.getActivityId(), outbox.getUserId());
            throw new BusinessException(503,
                    "库存回滚结果暂时无法确认，记录保持在回滚执行中，可稍后重试");
        }

        if (!outboxService.casStatus(id, OutboxStatus.ROLLING_BACK.getCode(),
                OutboxStatus.ROLLED_BACK.getCode())) {
            // 库存可能已经归还，但终态没写成。绝不能退回 4；
            // 保持在 5，下一次重试会得到 NOTHING_TO_DO 并再次尝试落 3。
            log.error("库存回滚已得到明确结果，但状态未落终态，保留状态 5 "
                            + "outboxId={} activityId={} userId={} outcome={}",
                    id, outbox.getActivityId(), outbox.getUserId(), outcome);
            throw new BusinessException(409,
                    "库存已处理，但记录状态未落终态；记录保持在回滚执行中，请稍后重试");
        }

        log.warn("人工复核后回滚库存 outboxId={} activityId={} userId={} outcome={}",
                id, outbox.getActivityId(), outbox.getUserId(), outcome);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("outboxId", id);
        data.put("status", OutboxStatus.ROLLED_BACK.getCode());
        data.put("outcome", outcome.name());
        data.put("stockRestored", outcome == RollbackOutcome.RESTORED);
        data.put("note", outcome == RollbackOutcome.RESTORED
                ? "库存已加回"
                : "库存 key 已过期或用户不在买家集合，无需加回");
        return Result.ok(data);
    }
}
