package com.jack.mc.seckill.service.impl;

import com.jack.mc.common.message.SeckillMessage;
import com.jack.mc.seckill.entity.SeckillOutbox;
import com.jack.mc.seckill.enums.OutboxStatus;
import com.jack.mc.seckill.mapper.SeckillOutboxMapper;
import com.jack.mc.seckill.service.OutboxService;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class OutboxServiceImpl implements OutboxService {

    /** 新建记录首次被补偿任务捞起的延迟，见 savePending 里的说明 */
    private static final long INITIAL_DISPATCH_DELAY_SECONDS = 3L;

    private final SeckillOutboxMapper outboxMapper;

    public OutboxServiceImpl(SeckillOutboxMapper outboxMapper) {
        this.outboxMapper = outboxMapper;
    }

    @Override
    public void savePending(SeckillMessage message, String payload) {
        SeckillOutbox outbox = new SeckillOutbox();
        outbox.setActivityId(message.getActivityId());
        outbox.setUserId(message.getUserId());
        outbox.setReservationId(message.getReservationId());
        outbox.setPayload(payload);
        outbox.setStatus(OutboxStatus.PENDING.getCode());
        outbox.setRetryCount(0);
        outbox.setReconcileCount(0);
        // 首次重投时间留 3 秒余量，而不是 NOW()。
        // 主流程在插入之后还要做「确认扣减 → 投递 MQ」，补偿任务若在这个瞬间
        // 就把同一行捞走重投，会和主流程形成两条投递路径；更要命的是
        // 「主流程判定失败、撤销这一行」与「补偿任务已经发出去」会撞在一起。
        // 3 秒足够覆盖一次 Redis 确认 + 一次同步发送（最坏 2 秒）。
        outbox.setNextRetryAt(LocalDateTime.now().plusSeconds(INITIAL_DISPATCH_DELAY_SECONDS));
        outbox.setLastError("");
        // 唯一键 uk_user_activity 冲突时这里会抛 DuplicateKeyException，
        // 由调用方决定语义（这里是「你已经抢到过了」）
        outboxMapper.insert(outbox);
    }

    @Override
    public boolean markSent(Long activityId, Long userId) {
        return outboxMapper.markSent(activityId, userId) > 0;
    }

    @Override
    public List<SeckillOutbox> findPendingToRetry(LocalDateTime now, int limit) {
        return outboxMapper.selectPending(now, limit);
    }

    @Override
    public List<SeckillOutbox> findSentWithoutOrder(LocalDateTime before, int limit) {
        return outboxMapper.selectSentBefore(before, limit);
    }

    @Override
    public void bumpRetry(Long id, int retryCount, LocalDateTime nextRetryAt, String lastError) {
        outboxMapper.bumpRetry(id, retryCount, nextRetryAt, lastError);
    }

    @Override
    public boolean casStatus(Long id, int from, int to) {
        return outboxMapper.casStatus(id, from, to) > 0;
    }

    @Override
    public boolean markAborted(Long activityId, Long userId, String reservationId) {
        return outboxMapper.markAborted(activityId, userId, reservationId) > 0;
    }

    @Override
    public boolean resetAbandoned(Long activityId, Long userId,
                                  String reservationId, String payload) {
        return outboxMapper.resetAbandoned(
                activityId, userId, reservationId, payload) > 0;
    }

    @Override
    public boolean resetToPending(Long id) {
        return outboxMapper.resetToPending(id) > 0;
    }

    @Override
    public boolean orderExists(Long activityId, Long userId) {
        return outboxMapper.countOrder(userId, activityId) > 0;
    }

    @Override
    public boolean exists(Long activityId, Long userId) {
        return outboxMapper.countByUserActivity(userId, activityId) > 0;
    }

    @Override
    public Integer findStatusByReservation(Long activityId, String reservationId) {
        return outboxMapper.selectStatusByReservation(activityId, reservationId);
    }

    @Override
    public SeckillOutbox findById(Long id) {
        return outboxMapper.selectById(id);
    }

    @Override
    public SeckillOutbox findByUserAndActivity(Long activityId, Long userId) {
        return outboxMapper.selectByUserActivity(userId, activityId);
    }

    @Override
    public List<SeckillOutbox> findByStatusBefore(int status, LocalDateTime before, int limit) {
        return outboxMapper.selectByStatusBefore(status, before, limit);
    }
}
