package com.jack.mc.seckill.service.impl;

import com.jack.mc.seckill.entity.SeckillActivity;
import com.jack.mc.seckill.enums.ActivityStatus;
import com.jack.mc.seckill.mapper.ProductMapper;
import com.jack.mc.seckill.mapper.SeckillActivityMapper;
import com.jack.mc.seckill.mapper.SeckillOutboxMapper;
import com.jack.mc.seckill.service.InventoryReservationReleaseService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
public class InventoryReservationReleaseServiceImpl implements InventoryReservationReleaseService {

    private static final Logger log =
            LoggerFactory.getLogger(InventoryReservationReleaseServiceImpl.class);

    private final SeckillActivityMapper activityMapper;
    private final SeckillOutboxMapper outboxMapper;
    private final ProductMapper productMapper;

    public InventoryReservationReleaseServiceImpl(SeckillActivityMapper activityMapper,
                                                  SeckillOutboxMapper outboxMapper,
                                                  ProductMapper productMapper) {
        this.activityMapper = activityMapper;
        this.outboxMapper = outboxMapper;
        this.productMapper = productMapper;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean release(Long activityId) {
        SeckillActivity activity = activityMapper.lockById(activityId);
        LocalDateTime now = LocalDateTime.now();
        if (activity == null
                || !ActivityStatus.FINISHED.is(activity.getStatus())
                || activity.getReservedStock() == null
                || activity.getReservedStock() <= 0
                || activity.getReservationReleasedAt() != null
                || activity.getEndTime().isAfter(now)) {
            return false;
        }

        // 只要还有待投递、待消费或待人工处理的 outbox，就仍可能有迟到订单，
        // 此时归还未售库存会让下一场活动把同一件货再卖一次。
        if (outboxMapper.countActiveByActivity(activityId) > 0) {
            return false;
        }

        int available = activity.getAvailableStock() == null ? 0 : activity.getAvailableStock();
        int remaining = Math.max(0, Math.min(available, activity.getReservedStock()));
        if (available != remaining) {
            log.error("活动预留库存数据异常，按预留上限归还 activityId={} reserved={} available={}",
                    activityId, activity.getReservedStock(), available);
        }
        if (remaining > 0
                && productMapper.releaseStock(activity.getProductId(), remaining) != 1) {
            throw new IllegalStateException("归还预留库存失败 productId=" + activity.getProductId());
        }
        if (activityMapper.markReservationReleased(activityId, now) != 1) {
            throw new IllegalStateException("标记预留释放失败 activityId=" + activityId);
        }

        log.info("活动预留库存已释放 activityId={} productId={} released={}",
                activityId, activity.getProductId(), remaining);
        return true;
    }
}
