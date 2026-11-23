package com.jack.mc.seckill.service.impl;

import com.jack.mc.common.BusinessException;
import com.jack.mc.seckill.dto.ActivityCreateRequest;
import com.jack.mc.seckill.entity.Product;
import com.jack.mc.seckill.entity.SeckillActivity;
import com.jack.mc.seckill.enums.ActivityStatus;
import com.jack.mc.seckill.mapper.ProductMapper;
import com.jack.mc.seckill.mapper.SeckillActivityMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 活动创建 / 发布的库存与时间校验。
 * <p>
 * 这两处都是「不报错的错误」：校验漏了不会抛异常，只会在线上变成
 * 一批卖不出去的库存（累计超额）或一个永远预热不了的活动。
 * 所以必须有测试钉住。
 */
class ActivityServiceImplTest {

    private SeckillActivityMapper activityMapper;
    private ProductMapper productMapper;
    private ActivityServiceImpl service;

    @BeforeEach
    void setUp() {
        activityMapper = mock(SeckillActivityMapper.class);
        productMapper = mock(ProductMapper.class);
        service = new ActivityServiceImpl(activityMapper, productMapper);
    }

    // ---------------- 创建 ----------------

    @Test
    void createRejectsStartTimeInPast() {
        // 「开始时间在过去」的活动能创建、能发布，但永远预热不了：
        // warmup 只允许未开始的活动写 Redis。这是实测复现过的 P0。
        when(productMapper.selectById(5L)).thenReturn(product(100));
        ActivityCreateRequest request = request(10,
                LocalDateTime.now().minusMinutes(30), LocalDateTime.now().plusHours(1));

        BusinessException e = assertThrows(BusinessException.class, () -> service.create(request));

        assertEquals(400, e.getCode());
        verify(activityMapper, never()).insert(any(SeckillActivity.class));
    }

    @Test
    void createRejectsStockAboveProductStock() {
        when(productMapper.selectById(5L)).thenReturn(product(100));
        ActivityCreateRequest request = request(101,
                LocalDateTime.now().plusMinutes(10), LocalDateTime.now().plusHours(1));

        BusinessException e = assertThrows(BusinessException.class, () -> service.create(request));

        assertEquals(400, e.getCode());
        verify(activityMapper, never()).insert(any(SeckillActivity.class));
    }

    @Test
    void createAcceptsValidWindow() {
        when(productMapper.selectById(5L)).thenReturn(product(100));
        when(activityMapper.insert(any(SeckillActivity.class))).thenAnswer(invocation -> {
            ((SeckillActivity) invocation.getArgument(0)).setId(99L);
            return 1;
        });
        ActivityCreateRequest request = request(50,
                LocalDateTime.now().plusMinutes(10), LocalDateTime.now().plusHours(1));

        assertEquals(99L, service.create(request));
    }

    // ---------------- 发布 ----------------

    @Test
    void publishRejectsWhenAllocatedStockLeavesNoRoom() {
        // 商品库存 100，未来已发布活动占用 80，本场还要 80 —— 排不下。
        // 放过去的后果：第二场的 60 件根本没货，消息会一直重试到进死信。
        when(productMapper.lockById(5L)).thenReturn(product(100));
        when(activityMapper.selectById(7L)).thenReturn(
                activity(ActivityStatus.DRAFT.getCode(), 80,
                        LocalDateTime.now().plusHours(2), LocalDateTime.now().plusHours(3)));
        when(activityMapper.sumAllocatedStock(eq(5L), eq(7L), any())).thenReturn(80);

        BusinessException e = assertThrows(BusinessException.class, () -> service.publish(7L));

        assertEquals(409, e.getCode());
        verify(activityMapper, never()).updateById(any(SeckillActivity.class));
    }

    @Test
    void publishAcceptsWhenWithinRemainingStock() {
        // 已占用 20，商品库存 100，本场 80 —— 刚好排得下，应该放行
        when(productMapper.lockById(5L)).thenReturn(product(100));
        when(activityMapper.selectById(7L)).thenReturn(
                activity(ActivityStatus.DRAFT.getCode(), 80,
                        LocalDateTime.now().plusHours(2), LocalDateTime.now().plusHours(3)));
        when(activityMapper.sumAllocatedStock(eq(5L), eq(7L), any())).thenReturn(20);
        when(productMapper.reserveStock(5L, 80)).thenReturn(1);
        when(activityMapper.updateById(any(SeckillActivity.class))).thenReturn(1);

        service.publish(7L);

        verify(activityMapper).updateById(any(SeckillActivity.class));
        verify(productMapper).reserveStock(5L, 80);
    }

    @Test
    void publishRollsBackWhenProductReservationCannotBeMade() {
        when(productMapper.lockById(5L)).thenReturn(product(100));
        when(activityMapper.selectById(7L)).thenReturn(
                activity(ActivityStatus.DRAFT.getCode(), 50,
                        LocalDateTime.now().plusHours(2), LocalDateTime.now().plusHours(3)));
        when(activityMapper.sumAllocatedStock(eq(5L), eq(7L), any())).thenReturn(0);
        when(productMapper.reserveStock(5L, 50)).thenReturn(0);

        BusinessException e = assertThrows(BusinessException.class, () -> service.publish(7L));

        assertEquals(409, e.getCode());
        verify(activityMapper, never()).updateById(any(SeckillActivity.class));
    }

    @Test
    void publishRejectsActivityWhoseStartTimeAlreadyPassed() {
        when(productMapper.lockById(5L)).thenReturn(product(100));
        when(activityMapper.selectById(7L)).thenReturn(
                activity(ActivityStatus.DRAFT.getCode(), 10,
                        LocalDateTime.now().minusMinutes(1), LocalDateTime.now().plusHours(1)));

        BusinessException e = assertThrows(BusinessException.class, () -> service.publish(7L));

        assertEquals(409, e.getCode());
        verify(activityMapper, never()).updateById(any(SeckillActivity.class));
        // 时间都没通过，不该再去查占用
        verify(activityMapper, never()).sumAllocatedStock(anyLong(), anyLong(), any());
    }

    // ---------------- fixture ----------------

    private Product product(int stock) {
        Product product = new Product();
        product.setId(5L);
        product.setName("测试商品");
        product.setPrice(new BigDecimal("100.00"));
        product.setStock(stock);
        product.setStatus(1);
        product.setDeleted(0);
        return product;
    }

    private SeckillActivity activity(int status, int totalStock,
                                     LocalDateTime start, LocalDateTime end) {
        SeckillActivity activity = new SeckillActivity();
        activity.setId(7L);
        activity.setProductId(5L);
        activity.setTitle("测试活动");
        activity.setSeckillPrice(new BigDecimal("50.00"));
        activity.setTotalStock(totalStock);
        activity.setAvailableStock(totalStock);
        activity.setStartTime(start);
        activity.setEndTime(end);
        activity.setStatus(status);
        activity.setVersion(0);
        return activity;
    }

    private ActivityCreateRequest request(int totalStock, LocalDateTime start, LocalDateTime end) {
        ActivityCreateRequest request = new ActivityCreateRequest();
        request.setProductId(5L);
        request.setTitle("测试活动");
        request.setSeckillPrice(new BigDecimal("50.00"));
        request.setTotalStock(totalStock);
        request.setStartTime(start);
        request.setEndTime(end);
        return request;
    }
}
