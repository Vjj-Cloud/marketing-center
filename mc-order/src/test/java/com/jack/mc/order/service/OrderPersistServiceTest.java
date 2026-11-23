package com.jack.mc.order.service;

import com.jack.mc.common.message.SeckillMessage;
import com.jack.mc.order.entity.OutboxGuardState;
import com.jack.mc.order.entity.SeckillOrder;
import com.jack.mc.order.mapper.OrderMapper;
import com.jack.mc.order.mapper.SeckillOutboxGuardMapper;
import com.jack.mc.order.mapper.StockMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.mockito.InOrder;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OrderPersistServiceTest {

    private OrderMapper orderMapper;
    private StockMapper stockMapper;
    private SeckillOutboxGuardMapper outboxMapper;
    private StringRedisTemplate redis;
    private ValueOperations<String, String> valueOperations;
    private OrderPersistService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        orderMapper = mock(OrderMapper.class);
        stockMapper = mock(StockMapper.class);
        outboxMapper = mock(SeckillOutboxGuardMapper.class);
        redis = mock(StringRedisTemplate.class);
        valueOperations = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.increment(anyString())).thenReturn(1L);
        when(redis.expire(anyString(), any())).thenReturn(true);
        service = new OrderPersistService(orderMapper, stockMapper, outboxMapper, redis);
    }

    @Test
    void consumingTransitionsOutboxBeforeCreatingOrder() {
        SeckillMessage message = message("reservation-1");
        when(outboxMapper.lockState(1L, 1001L)).thenReturn(state(10L, 1, "reservation-1"));
        when(outboxMapper.markConsuming(10L)).thenReturn(1);
        when(outboxMapper.markConsumed(10L)).thenReturn(1);
        when(stockMapper.reservedStock(1L)).thenReturn(0);
        when(stockMapper.deductProductStock(2L)).thenReturn(1);
        when(stockMapper.deductActivityStock(1L)).thenReturn(1);

        SeckillOrder order = service.create(message);

        assertEquals("reservation-1", message.getReservationId());
        assertEquals(1L, order.getActivityId());
        InOrder inOrder = inOrder(outboxMapper, orderMapper);
        inOrder.verify(outboxMapper).markConsuming(10L);
        inOrder.verify(orderMapper).insert(order);
        verify(outboxMapper).markConsumed(10L);
    }

    @Test
    void reservedActivitySkipsSecondProductDeduction() {
        SeckillMessage message = message("reservation-1");
        when(outboxMapper.lockState(1L, 1001L)).thenReturn(state(10L, 1, "reservation-1"));
        when(outboxMapper.markConsuming(10L)).thenReturn(1);
        when(outboxMapper.markConsumed(10L)).thenReturn(1);
        when(stockMapper.reservedStock(1L)).thenReturn(50);
        when(stockMapper.deductActivityStock(1L)).thenReturn(1);

        service.create(message);

        verify(stockMapper, never()).deductProductStock(any());
        verify(stockMapper).deductActivityStock(1L);
    }

    @Test
    void failedConsumingCasStopsBeforeOrderInsert() {
        SeckillMessage message = message("reservation-1");
        when(outboxMapper.lockState(1L, 1001L)).thenReturn(state(10L, 1, "reservation-1"));
        when(outboxMapper.markConsuming(10L)).thenReturn(0);

        assertThrows(IllegalStateException.class, () -> service.create(message));
        verify(orderMapper, never()).insert(any());
    }

    @Test
    void mismatchedReservationIsRejectedBeforeOrderInsert() {
        SeckillMessage message = message("reservation-new");
        when(outboxMapper.lockState(1L, 1001L)).thenReturn(state(10L, 1, "reservation-old"));

        assertThrows(OrderRejectedException.class, () -> service.create(message));
        verify(orderMapper, never()).insert(any());
        verify(outboxMapper, never()).markConsuming(any());
    }

    @Test
    void alreadyConsumedOutboxIsIdempotentlySkipped() {
        SeckillMessage message = message("reservation-1");
        when(outboxMapper.lockState(1L, 1001L)).thenReturn(state(10L, 2, "reservation-1"));

        assertNull(service.create(message));
        verify(orderMapper, never()).insert(any());
    }

    @Test
    void rollbackInProgressRejectsLateMessage() {
        SeckillMessage message = message("reservation-1");
        when(outboxMapper.lockState(1L, 1001L)).thenReturn(state(10L, 5, "reservation-1"));

        assertThrows(OrderRejectedException.class, () -> service.create(message));
        verify(orderMapper, never()).insert(any());
    }

    private SeckillMessage message(String reservationId) {
        SeckillMessage message = new SeckillMessage();
        message.setReservationId(reservationId);
        message.setActivityId(1L);
        message.setUserId(1001L);
        message.setProductId(2L);
        message.setSeckillPrice(new BigDecimal("9.90"));
        return message;
    }

    private OutboxGuardState state(Long id, int status, String reservationId) {
        OutboxGuardState state = new OutboxGuardState();
        state.setId(id);
        state.setStatus(status);
        state.setReservationId(reservationId);
        return state;
    }
}
