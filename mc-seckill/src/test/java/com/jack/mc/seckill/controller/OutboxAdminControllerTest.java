package com.jack.mc.seckill.controller;

import com.jack.mc.common.BusinessException;
import com.jack.mc.common.Result;
import com.jack.mc.seckill.entity.SeckillOutbox;
import com.jack.mc.seckill.enums.OutboxStatus;
import com.jack.mc.seckill.enums.RollbackOutcome;
import com.jack.mc.seckill.service.OutboxService;
import com.jack.mc.seckill.service.StockService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 人工处置入口的状态机。
 * <p>
 * 状态 5 是人工回滚期间的排他凭据。Redis 回滚失败或数据库落终态失败时，
 * 库存是否已经归还都是不确定的，因此记录必须继续停在 5，不能退回 4。
 * <p>
 * 允许的路径只有：
 * <ul>
 *   <li>4 → 5 → 3：首次回滚并得到明确结果；</li>
 *   <li>5 → 3：结果不确定后，在同一把锁下幂等重试；</li>
 *   <li>5 → 5：任何不确定性都保持锁，等待下一次重试。</li>
 * </ul>
 */
class OutboxAdminControllerTest {

    private static final long OUTBOX_ID = 42L;

    private OutboxService outboxService;
    private StockService stockService;
    private OutboxAdminController controller;

    @BeforeEach
    void setUp() {
        outboxService = mock(OutboxService.class);
        stockService = mock(StockService.class);
        controller = new OutboxAdminController(outboxService, stockService);
    }

    @Test
    void listShowsStalledRollingBack() {
        SeckillOutbox stuck = row(OUTBOX_ID, 19L, 1001L, OutboxStatus.ROLLING_BACK.getCode());
        SeckillOutbox review = row(OUTBOX_ID + 1, 19L, 1002L, OutboxStatus.NEEDS_REVIEW.getCode());
        when(outboxService.findByStatusBefore(eq(OutboxStatus.NEEDS_REVIEW.getCode()),
                any(LocalDateTime.class), anyInt())).thenReturn(List.of(review));
        when(outboxService.findByStatusBefore(eq(OutboxStatus.ROLLING_BACK.getCode()),
                any(LocalDateTime.class), anyInt())).thenReturn(List.of(stuck));

        Result<List<Map<String, Object>>> result = controller.listReview(50);

        assertEquals(2, result.getData().size());
        assertEquals("NEEDS_REVIEW", result.getData().get(0).get("state"));
        assertEquals("ROLLING_BACK_STALLED", result.getData().get(1).get("state"));
    }

    /**
     * 状态 4 不带时间下限（它是常规待办，越新越要看），
     * 状态 5 必须带 {@code now - 120s}：刚 CAS 成 5 的正常回滚不该进列表，
     * 否则运维一刷新就看到「卡住」的假警报。
     */
    @Test
    void stalledQueryOnlyCoversRowsIdleOverTwoMinutes() {
        when(outboxService.findByStatusBefore(anyInt(), any(LocalDateTime.class), anyInt()))
                .thenReturn(List.of());

        LocalDateTime before = LocalDateTime.now();
        controller.listReview(50);

        ArgumentCaptor<LocalDateTime> captor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(outboxService).findByStatusBefore(
                eq(OutboxStatus.ROLLING_BACK.getCode()), captor.capture(), eq(50));
        LocalDateTime cutoff = captor.getValue();
        assertTrue(cutoff.isBefore(before.minusSeconds(115)),
                "状态 5 的时间下界应约为 now-120s，实际 " + cutoff);
        assertTrue(cutoff.isAfter(before.minusSeconds(125)),
                "状态 5 的时间下界应约为 now-120s，实际 " + cutoff);
    }

    @Test
    void rollbackFromNeedsReviewMarksRolledBackAfterDefinitiveRedisResult() {
        SeckillOutbox row = row(OUTBOX_ID, 19L, 1001L, OutboxStatus.NEEDS_REVIEW.getCode());
        when(outboxService.findById(OUTBOX_ID)).thenReturn(row);
        when(outboxService.casStatus(OUTBOX_ID,
                OutboxStatus.NEEDS_REVIEW.getCode(), OutboxStatus.ROLLING_BACK.getCode()))
                .thenReturn(true);
        when(stockService.rollback(19L, 1001L, "r-" + OUTBOX_ID))
                .thenReturn(RollbackOutcome.RESTORED);
        when(outboxService.casStatus(OUTBOX_ID,
                OutboxStatus.ROLLING_BACK.getCode(), OutboxStatus.ROLLED_BACK.getCode()))
                .thenReturn(true);

        Result<Map<String, Object>> result = controller.rollback(OUTBOX_ID);

        assertEquals(OutboxStatus.ROLLED_BACK.getCode(), result.getData().get("status"));
        assertEquals("RESTORED", result.getData().get("outcome"));
    }

    @Test
    void rollbackFromRollingBackRetriesWithoutReopeningReview() {
        SeckillOutbox row = row(OUTBOX_ID, 19L, 1001L, OutboxStatus.ROLLING_BACK.getCode());
        when(outboxService.findById(OUTBOX_ID)).thenReturn(row);
        when(stockService.rollback(19L, 1001L, "r-" + OUTBOX_ID))
                .thenReturn(RollbackOutcome.NOTHING_TO_DO);
        when(outboxService.casStatus(OUTBOX_ID,
                OutboxStatus.ROLLING_BACK.getCode(), OutboxStatus.ROLLED_BACK.getCode()))
                .thenReturn(true);

        Result<Map<String, Object>> result = controller.rollback(OUTBOX_ID);

        assertEquals(OutboxStatus.ROLLED_BACK.getCode(), result.getData().get("status"));
        verify(outboxService, never()).casStatus(OUTBOX_ID,
                OutboxStatus.NEEDS_REVIEW.getCode(), OutboxStatus.ROLLING_BACK.getCode());
    }

    @Test
    void redisRollbackFailureKeepsExclusiveStateFive() {
        when(outboxService.findById(OUTBOX_ID))
                .thenReturn(row(OUTBOX_ID, 19L, 1001L, OutboxStatus.NEEDS_REVIEW.getCode()));
        when(outboxService.casStatus(OUTBOX_ID,
                OutboxStatus.NEEDS_REVIEW.getCode(), OutboxStatus.ROLLING_BACK.getCode()))
                .thenReturn(true);
        when(stockService.rollback(19L, 1001L, "r-" + OUTBOX_ID))
                .thenReturn(RollbackOutcome.FAILED);

        BusinessException e = assertThrows(BusinessException.class,
                () -> controller.rollback(OUTBOX_ID));

        assertEquals(503, e.getCode());
        verify(outboxService, never()).casStatus(OUTBOX_ID,
                OutboxStatus.ROLLING_BACK.getCode(), OutboxStatus.NEEDS_REVIEW.getCode());
    }

    @Test
    void databaseFailureAfterRedisSuccessKeepsExclusiveStateFive() {
        when(outboxService.findById(OUTBOX_ID))
                .thenReturn(row(OUTBOX_ID, 19L, 1001L, OutboxStatus.ROLLING_BACK.getCode()));
        when(stockService.rollback(19L, 1001L, "r-" + OUTBOX_ID))
                .thenReturn(RollbackOutcome.RESTORED);
        when(outboxService.casStatus(OUTBOX_ID,
                OutboxStatus.ROLLING_BACK.getCode(), OutboxStatus.ROLLED_BACK.getCode()))
                .thenThrow(new IllegalStateException("db unavailable"));

        assertThrows(IllegalStateException.class, () -> controller.rollback(OUTBOX_ID));

        verify(outboxService, never()).casStatus(OUTBOX_ID,
                OutboxStatus.ROLLING_BACK.getCode(), OutboxStatus.NEEDS_REVIEW.getCode());
    }

    @Test
    void recoverRetriesStalledRollbackWithoutReturningToReview() {
        when(outboxService.findById(OUTBOX_ID))
                .thenReturn(row(OUTBOX_ID, 19L, 1001L, OutboxStatus.ROLLING_BACK.getCode()));
        when(stockService.rollback(19L, 1001L, "r-" + OUTBOX_ID))
                .thenReturn(RollbackOutcome.RESTORED);
        when(outboxService.casStatus(OUTBOX_ID,
                OutboxStatus.ROLLING_BACK.getCode(), OutboxStatus.ROLLED_BACK.getCode()))
                .thenReturn(true);

        Result<Map<String, Object>> result = controller.recover(OUTBOX_ID);

        assertEquals(OutboxStatus.ROLLED_BACK.getCode(), result.getData().get("status"));
        verify(stockService).rollback(19L, 1001L, "r-" + OUTBOX_ID);
        verify(outboxService, never()).casStatus(OUTBOX_ID,
                OutboxStatus.ROLLING_BACK.getCode(), OutboxStatus.NEEDS_REVIEW.getCode());
    }

    @Test
    void recoverRejectsFreshRollingBack() {
        SeckillOutbox fresh = row(OUTBOX_ID, 19L, 1001L, OutboxStatus.ROLLING_BACK.getCode());
        fresh.setUpdatedAt(LocalDateTime.now());
        when(outboxService.findById(OUTBOX_ID)).thenReturn(fresh);

        BusinessException e = assertThrows(BusinessException.class,
                () -> controller.recover(OUTBOX_ID));

        assertEquals(409, e.getCode());
        verify(stockService, never()).rollback(anyLong(), anyLong(), any());
    }

    @Test
    void recoverRejectsMissingRow() {
        when(outboxService.findById(OUTBOX_ID)).thenReturn(null);

        BusinessException e = assertThrows(BusinessException.class,
                () -> controller.recover(OUTBOX_ID));

        assertEquals(404, e.getCode());
    }

    /** 已经落终态（3 已回滚 / 2 已消费）的记录绝不能被「恢复」回 4，否则等于重新打开一单 */
    @Test
    void recoverRejectsRowThatIsNotRollingBack() {
        when(outboxService.findById(OUTBOX_ID))
                .thenReturn(row(OUTBOX_ID, 19L, 1001L, OutboxStatus.ROLLED_BACK.getCode()));

        BusinessException e = assertThrows(BusinessException.class,
                () -> controller.recover(OUTBOX_ID));

        assertEquals(409, e.getCode());
        verify(stockService, never()).rollback(anyLong(), anyLong(), any());
    }

    /** 原线程刚好先落终态时，必须如实报冲突；库存可能已处理，状态仍不能退回 4。 */
    @Test
    void recoverReportsWhenStateChangedConcurrently() {
        when(outboxService.findById(OUTBOX_ID))
                .thenReturn(row(OUTBOX_ID, 19L, 1001L, OutboxStatus.ROLLING_BACK.getCode()));
        when(stockService.rollback(19L, 1001L, "r-" + OUTBOX_ID))
                .thenReturn(RollbackOutcome.RESTORED);
        when(outboxService.casStatus(OUTBOX_ID,
                OutboxStatus.ROLLING_BACK.getCode(), OutboxStatus.ROLLED_BACK.getCode()))
                .thenReturn(false);

        BusinessException e = assertThrows(BusinessException.class,
                () -> controller.recover(OUTBOX_ID));

        assertEquals(409, e.getCode());
        verify(outboxService, never()).casStatus(OUTBOX_ID,
                OutboxStatus.ROLLING_BACK.getCode(), OutboxStatus.NEEDS_REVIEW.getCode());
    }

    private SeckillOutbox row(Long id, Long activityId, Long userId, int status) {
        SeckillOutbox outbox = new SeckillOutbox();
        outbox.setId(id);
        outbox.setActivityId(activityId);
        outbox.setUserId(userId);
        outbox.setReservationId("r-" + id);
        outbox.setStatus(status);
        outbox.setRetryCount(3);
        outbox.setLastError("broker 未确认");
        outbox.setUpdatedAt(LocalDateTime.now().minusMinutes(3));
        return outbox;
    }
}
