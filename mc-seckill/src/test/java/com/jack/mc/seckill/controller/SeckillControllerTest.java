package com.jack.mc.seckill.controller;

import com.jack.mc.common.BusinessException;
import com.jack.mc.seckill.service.SeckillService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 抢购入口的请求头校验。
 * <p>
 * X-Request-Id 会一路流进 {@code seckill_order.request_id VARCHAR(64)} 和
 * {@code seckill_outbox.payload VARCHAR(1024)}。不限长的话，一个超长请求头就能造出
 * 「Redis 库存已扣、outbox 写不进去」的悬挂记录 —— 那是要人工对账才能收拾的残局。
 */
class SeckillControllerTest {

    private SeckillService seckillService;
    private SeckillController controller;

    @BeforeEach
    void setUp() {
        seckillService = mock(SeckillService.class);
        controller = new SeckillController(seckillService);
    }

    @Test
    void rejectsRequestIdLongerThanColumn() {
        BusinessException e = assertThrows(BusinessException.class,
                () -> controller.seckill(1L, 1001L, "x".repeat(65)));

        assertEquals(400, e.getCode());
        verify(seckillService, never()).seckill(any(), any(), any());
    }

    @Test
    void acceptsRequestIdOfExactlyMaxLength() {
        controller.seckill(1L, 1001L, "x".repeat(64));

        verify(seckillService).seckill(1L, 1001L, "x".repeat(64));
    }

    @Test
    void blankRequestIdIsTreatedAsAbsent() {
        controller.seckill(1L, 1001L, "   ");

        // 空串按未传处理：request_id 列是 NOT NULL DEFAULT ''，无需塞一个空白串
        verify(seckillService).seckill(1L, 1001L, null);
    }

    @Test
    void surroundingWhitespaceIsTrimmed() {
        controller.seckill(1L, 1001L, "  req-1  ");

        verify(seckillService).seckill(1L, 1001L, "req-1");
    }

    @Test
    void rejectsMissingUserId() {
        BusinessException e = assertThrows(BusinessException.class,
                () -> controller.seckill(1L, null, "req-1"));

        assertEquals(400, e.getCode());
        verify(seckillService, never()).seckill(any(), any(), any());
    }
}
