package com.jack.mc.seckill.task;

import com.jack.mc.seckill.enums.OutboxStatus;
import com.jack.mc.seckill.enums.RollbackOutcome;
import com.jack.mc.seckill.service.OutboxService;
import com.jack.mc.seckill.service.StockService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 孤儿扣减兜底任务的分支判定。
 * <p>
 * 重点在「outbox 里有没有记录」不能替代「记录是什么状态」：
 * 一条 status=6（入口中止）或 3（人工回滚）的记录同样存在，
 * 但它意味着库存该还。只看存在性会把这种扣减永久悬挂起来
 * —— 库存少一件、买家标记被擦、却没有任何可成交的消息。
 */
class OrphanDeductionTaskTest {

    private static final Long ACTIVITY_ID = 1L;
    private static final Long USER_ID = 1001L;
    private static final String RESERVATION_ID = "reservation-1";
    private static final long CUTOFF = System.currentTimeMillis() - 60_000L;

    private StockService stockService;
    private OutboxService outboxService;
    private OrphanDeductionTask task;

    @BeforeEach
    void setUp() {
        stockService = mock(StockService.class);
        outboxService = mock(OutboxService.class);
        task = new OrphanDeductionTask(mock(StringRedisTemplate.class), outboxService, stockService);
        when(stockService.claimOrphan(anyLong(), anyLong(), anyString(), anyLong(), anyLong(), anyString()))
                .thenReturn(true);
        when(stockService.rollback(anyLong(), anyLong(), anyString()))
                .thenReturn(RollbackOutcome.RESTORED);
    }

    @Test
    void abortedOutboxStillRollsBackStock() {
        // 极端崩溃顺序：写 outbox → 改 6 → 进程在回滚 Redis 前挂掉。
        when(outboxService.findStatusByReservation(ACTIVITY_ID, RESERVATION_ID))
                .thenReturn(OutboxStatus.ABORTED.getCode());

        task.handleOne(ACTIVITY_ID, USER_ID, RESERVATION_ID, CUTOFF);

        verify(stockService).rollback(ACTIVITY_ID, USER_ID, RESERVATION_ID);
        verify(stockService, never()).clearPendingMarker(anyLong(), anyLong(), anyString());
        verify(stockService).releaseClaim(eq(ACTIVITY_ID), eq(RESERVATION_ID), anyString());
    }

    @Test
    void rolledBackOutboxStillRollsBackStock() {
        when(outboxService.findStatusByReservation(ACTIVITY_ID, RESERVATION_ID))
                .thenReturn(OutboxStatus.ROLLED_BACK.getCode());

        task.handleOne(ACTIVITY_ID, USER_ID, RESERVATION_ID, CUTOFF);

        verify(stockService).rollback(ACTIVITY_ID, USER_ID, RESERVATION_ID);
        verify(stockService, never()).clearPendingMarker(anyLong(), anyLong(), anyString());
    }

    @Test
    void missingOutboxRollsBackStock() {
        // 真正的孤儿：扣完 Redis 就崩了，数据库里什么都没有
        when(outboxService.findStatusByReservation(ACTIVITY_ID, RESERVATION_ID)).thenReturn(null);

        task.handleOne(ACTIVITY_ID, USER_ID, RESERVATION_ID, CUTOFF);

        verify(stockService).rollback(ACTIVITY_ID, USER_ID, RESERVATION_ID);
        verify(stockService, never()).clearPendingMarker(anyLong(), anyLong(), anyString());
    }

    @Test
    void sentOutboxOnlyClearsMarker() {
        // 落库成功但没来得及确认扣减：这单是好的，回滚就是凭空多一件库存
        when(outboxService.findStatusByReservation(ACTIVITY_ID, RESERVATION_ID))
                .thenReturn(OutboxStatus.SENT.getCode());

        task.handleOne(ACTIVITY_ID, USER_ID, RESERVATION_ID, CUTOFF);

        verify(stockService).clearPendingMarker(ACTIVITY_ID, USER_ID, RESERVATION_ID);
        verify(stockService, never()).rollback(anyLong(), anyLong(), anyString());
    }

    @Test
    void consumingOutboxOnlyClearsMarker() {
        // 7 = 消费者事务进行中，订单可能正在写，绝不能回滚
        when(outboxService.findStatusByReservation(ACTIVITY_ID, RESERVATION_ID))
                .thenReturn(OutboxStatus.CONSUMING.getCode());

        task.handleOne(ACTIVITY_ID, USER_ID, RESERVATION_ID, CUTOFF);

        verify(stockService).clearPendingMarker(ACTIVITY_ID, USER_ID, RESERVATION_ID);
        verify(stockService, never()).rollback(anyLong(), anyLong(), anyString());
    }

    @Test
    void claimFailureDoesNothing() {
        // 认领失败说明已被别的实例处理 / 记录已确认 / 还没到宽限期，一律不动
        when(stockService.claimOrphan(anyLong(), anyLong(), anyString(), anyLong(), anyLong(), anyString()))
                .thenReturn(false);

        task.handleOne(ACTIVITY_ID, USER_ID, RESERVATION_ID, CUTOFF);

        verify(stockService, never()).rollback(anyLong(), anyLong(), anyString());
        verify(stockService, never()).clearPendingMarker(anyLong(), anyLong(), anyString());
        verify(stockService, never()).releaseClaim(anyLong(), anyString(), anyString());
        verify(outboxService, never()).findStatusByReservation(anyLong(), anyString());
    }

    @Test
    void rollbackFailureStillReleasesClaim() {
        // Redis 故障时认领必须释放，否则租约到期前这一条谁都处理不了
        when(outboxService.findStatusByReservation(ACTIVITY_ID, RESERVATION_ID)).thenReturn(null);
        when(stockService.rollback(anyLong(), anyLong(), anyString()))
                .thenReturn(RollbackOutcome.FAILED);

        task.handleOne(ACTIVITY_ID, USER_ID, RESERVATION_ID, CUTOFF);

        verify(stockService).releaseClaim(eq(ACTIVITY_ID), eq(RESERVATION_ID), anyString());
    }

    @Test
    void unexpectedErrorStillReleasesClaim() {
        when(outboxService.findStatusByReservation(ACTIVITY_ID, RESERVATION_ID))
                .thenThrow(new IllegalStateException("db down"));

        try {
            task.handleOne(ACTIVITY_ID, USER_ID, RESERVATION_ID, CUTOFF);
        } catch (IllegalStateException ignored) {
            // 异常继续往上抛，让 run() 的 try 接到并留痕
        }

        verify(stockService).releaseClaim(eq(ACTIVITY_ID), eq(RESERVATION_ID), anyString());
    }
}
