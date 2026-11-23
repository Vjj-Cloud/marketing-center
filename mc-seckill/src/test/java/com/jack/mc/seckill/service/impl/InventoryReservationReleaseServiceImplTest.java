package com.jack.mc.seckill.service.impl;

import com.jack.mc.seckill.entity.SeckillActivity;
import com.jack.mc.seckill.enums.ActivityStatus;
import com.jack.mc.seckill.mapper.ProductMapper;
import com.jack.mc.seckill.mapper.SeckillActivityMapper;
import com.jack.mc.seckill.mapper.SeckillOutboxMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InventoryReservationReleaseServiceImplTest {

    private SeckillActivityMapper activityMapper;
    private SeckillOutboxMapper outboxMapper;
    private ProductMapper productMapper;
    private InventoryReservationReleaseServiceImpl service;

    @BeforeEach
    void setUp() {
        activityMapper = mock(SeckillActivityMapper.class);
        outboxMapper = mock(SeckillOutboxMapper.class);
        productMapper = mock(ProductMapper.class);
        service = new InventoryReservationReleaseServiceImpl(
                activityMapper, outboxMapper, productMapper);
    }

    @Test
    void releasesUnsoldReservationAfterAllOutboxSettled() {
        when(activityMapper.lockById(7L)).thenReturn(activity(50, 20));
        when(outboxMapper.countActiveByActivity(7L)).thenReturn(0);
        when(productMapper.releaseStock(5L, 20)).thenReturn(1);
        when(activityMapper.markReservationReleased(any(), any())).thenReturn(1);

        assertTrue(service.release(7L));
        verify(productMapper).releaseStock(5L, 20);
        verify(activityMapper).markReservationReleased(any(), any());
    }

    @Test
    void keepsReservationWhileOutboxStillActive() {
        when(activityMapper.lockById(7L)).thenReturn(activity(50, 20));
        when(outboxMapper.countActiveByActivity(7L)).thenReturn(1);

        assertFalse(service.release(7L));
        verify(productMapper, never()).releaseStock(any(), anyInt());
        verify(activityMapper, never()).markReservationReleased(any(), any());
    }

    @Test
    void ignoresLegacyActivityWithoutReservation() {
        when(activityMapper.lockById(7L)).thenReturn(activity(0, 50));

        assertFalse(service.release(7L));
        verify(outboxMapper, never()).countActiveByActivity(any());
        verify(productMapper, never()).releaseStock(any(), anyInt());
    }

    @Test
    void doesNotReleaseTheSameReservationTwice() {
        SeckillActivity activity = activity(50, 20);
        activity.setReservationReleasedAt(LocalDateTime.now().minusMinutes(1));
        when(activityMapper.lockById(7L)).thenReturn(activity);

        assertFalse(service.release(7L));
        verify(outboxMapper, never()).countActiveByActivity(any());
        verify(productMapper, never()).releaseStock(any(), anyInt());
    }

    private SeckillActivity activity(int reservedStock, int availableStock) {
        SeckillActivity activity = new SeckillActivity();
        activity.setId(7L);
        activity.setProductId(5L);
        activity.setStatus(ActivityStatus.FINISHED.getCode());
        activity.setTotalStock(50);
        activity.setAvailableStock(availableStock);
        activity.setReservedStock(reservedStock);
        activity.setStartTime(LocalDateTime.now().minusHours(2));
        activity.setEndTime(LocalDateTime.now().minusHours(1));
        return activity;
    }
}
