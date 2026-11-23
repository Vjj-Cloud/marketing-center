package com.jack.mc.seckill.task;

import com.jack.mc.seckill.entity.SeckillActivity;
import com.jack.mc.seckill.mapper.SeckillActivityMapper;
import com.jack.mc.seckill.service.InventoryReservationReleaseService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 定时归还未售的预留库存。
 */
@Component
public class InventoryReservationReleaseTask {

    private static final Logger log =
            LoggerFactory.getLogger(InventoryReservationReleaseTask.class);

    private static final int BATCH_SIZE = 50;

    private final SeckillActivityMapper activityMapper;
    private final InventoryReservationReleaseService releaseService;

    public InventoryReservationReleaseTask(SeckillActivityMapper activityMapper,
                                           InventoryReservationReleaseService releaseService) {
        this.activityMapper = activityMapper;
        this.releaseService = releaseService;
    }

    @Scheduled(fixedDelay = 30_000L, initialDelay = 30_000L)
    public void releaseFinishedReservations() {
        List<SeckillActivity> activities =
                activityMapper.findReleasableReservations(LocalDateTime.now(), BATCH_SIZE);
        for (SeckillActivity activity : activities) {
            try {
                releaseService.release(activity.getId());
            } catch (Exception e) {
                log.error("释放活动预留库存失败 activityId={}", activity.getId(), e);
            }
        }
    }
}
