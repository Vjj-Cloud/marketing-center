package com.jack.mc.seckill.controller;

import com.jack.mc.common.BusinessException;
import com.jack.mc.common.Result;
import com.jack.mc.seckill.entity.SeckillOutbox;
import com.jack.mc.seckill.enums.OutboxStatus;
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
 * 这一轮（第七轮评审 P0）改的是「状态 5 怎么离开」：
 * 原来超时 120 秒会被定时任务无条件改回 4，而 5 本身是排他凭据——
 * 改回 4 就等于允许消费者落库，原回滚线程若还活着、随后又归还了 Redis 库存，
 * 就是「库存已还 + 订单已建」。
 * <p>
 * 所以现在只有两条离开路径：接口自己的 finally（同进程内），
 * 和运维确认后显式调用 {@code /recover}。这里锁住这两点：
 * <ul>
 *   <li>卡住的 5 必须出现在 {@code /review} 列表里，否则它既不会被处理、
 *       也不会被人看到，等于永久失联；</li>
 *   <li>{@code /recover} 只能从 5 出发，且必须走 CAS——不能把它从 3/2 覆盖回来。</li>
 * </ul>
 */
class OutboxAdminControllerTest {

    private static final long OUTBOX_ID = 42L;

    private OutboxService outboxService;
    private OutboxAdminController controller;

    @BeforeEach
    void setUp() {
        outboxService = mock(OutboxService.class);
        StockService stockService = mock(StockService.class);
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
    void recoverMovesStalledRowBackToReview() {
        when(outboxService.findById(OUTBOX_ID))
                .thenReturn(row(OUTBOX_ID, 19L, 1001L, OutboxStatus.ROLLING_BACK.getCode()));
        when(outboxService.casStatus(OUTBOX_ID,
                OutboxStatus.ROLLING_BACK.getCode(), OutboxStatus.NEEDS_REVIEW.getCode()))
                .thenReturn(true);

        Result<Map<String, Object>> result = controller.recover(OUTBOX_ID);

        assertEquals(OutboxStatus.NEEDS_REVIEW.getCode(), result.getData().get("status"));
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
        verify(outboxService, never()).casStatus(anyLong(), anyInt(), anyInt());
    }

    /** 原线程刚好在这一刻落终态时，CAS 更新 0 行，必须如实报「你来晚了」而不是覆盖 */
    @Test
    void recoverReportsWhenStateChangedConcurrently() {
        when(outboxService.findById(OUTBOX_ID))
                .thenReturn(row(OUTBOX_ID, 19L, 1001L, OutboxStatus.ROLLING_BACK.getCode()));
        when(outboxService.casStatus(OUTBOX_ID,
                OutboxStatus.ROLLING_BACK.getCode(), OutboxStatus.NEEDS_REVIEW.getCode()))
                .thenReturn(false);

        BusinessException e = assertThrows(BusinessException.class,
                () -> controller.recover(OUTBOX_ID));

        assertEquals(409, e.getCode());
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
        return outbox;
    }
}
