package com.jack.mc.seckill.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jack.mc.common.BusinessException;
import com.jack.mc.common.constant.MqTopics;
import com.jack.mc.common.message.SeckillMessage;
import com.jack.mc.seckill.entity.SeckillActivity;
import com.jack.mc.seckill.entity.SeckillOutbox;
import com.jack.mc.seckill.enums.ActivityStatus;
import com.jack.mc.seckill.enums.DeductResult;
import com.jack.mc.seckill.enums.OutboxStatus;
import com.jack.mc.seckill.enums.RollbackOutcome;
import com.jack.mc.seckill.mapper.SeckillActivityMapper;
import com.jack.mc.seckill.mq.MqProducer;
import com.jack.mc.seckill.service.OutboxService;
import com.jack.mc.seckill.service.SeckillService;
import com.jack.mc.seckill.service.StockService;
import com.jack.mc.seckill.vo.SeckillResultVO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 抢购主流程。
 * <p>
 * <b>顺序是：校验 → Redis 扣减 → 落本地消息表 → 确认扣减 → 投递 MQ。</b>
 * 前四步任何一步失败都可以安全回滚（此时还没有消息流出去）；
 * 最后一步失败<b>不回滚</b>，因为「发送超时」包含了「broker 其实已经收到」，
 * 贸然回滚会造成超卖。这条消息留在本地消息表里，由补偿任务重投，
 * 消费端靠唯一索引把重复消息吃掉。
 * <p>
 * <b>「确认扣减」为什么单独一步</b>：Redis 扣减和 MySQL 落库之间隔着一次网络往返，
 * 进程恰好倒在中间时，会留下「库存已扣、用户在买家集合、但没有 outbox 也没有订单」
 * 的永久悬挂记录。所以扣减时在 Redis 里记一条待确认标记，落库成功后清掉；
 * 留在里面的由 {@code OrphanDeductionTask} 兜底回滚。这是第二轮评审补的 P0。
 */
@Service
public class SeckillServiceImpl implements SeckillService {

    private static final Logger log = LoggerFactory.getLogger(SeckillServiceImpl.class);

    /** 与 seckill_outbox.payload VARCHAR(1024) 对齐（VARCHAR 按字符计，不是字节） */
    private static final int MAX_PAYLOAD_LENGTH = 1024;

    private final SeckillActivityMapper activityMapper;
    private final StockService stockService;
    private final OutboxService outboxService;
    private final MqProducer mqProducer;
    private final ObjectMapper objectMapper;

    public SeckillServiceImpl(SeckillActivityMapper activityMapper,
                              StockService stockService,
                              OutboxService outboxService,
                              MqProducer mqProducer,
                              ObjectMapper objectMapper) {
        this.activityMapper = activityMapper;
        this.stockService = stockService;
        this.outboxService = outboxService;
        this.mqProducer = mqProducer;
        this.objectMapper = objectMapper;
    }

    @Override
    public SeckillResultVO seckill(Long activityId, Long userId, String requestId) {
        SeckillActivity activity = activityMapper.selectById(activityId);
        if (activity == null) {
            throw new BusinessException(404, "活动不存在");
        }
        // 状态和时间窗口在这里挡掉，不让无效流量进到 Redis。
        // 顺序是刻意的：先便宜的判断（内存比较），再做贵的操作（Redis 脚本、DB、MQ）。
        if (!ActivityStatus.PUBLISHED.is(activity.getStatus())) {
            throw new BusinessException(409, "活动未开始或已结束");
        }
        LocalDateTime now = LocalDateTime.now();
        if (now.isBefore(activity.getStartTime())) {
            throw new BusinessException(409, "活动尚未开始");
        }
        if (now.isAfter(activity.getEndTime())) {
            throw new BusinessException(409, "活动已结束");
        }

        // 每次请求生成独立预订号。Redis pending、outbox 和 MQ 消息都携带它，
        // 用来区分同一用户的不同尝试，避免旧记录干扰本次恢复。
        String reservationId = UUID.randomUUID().toString();
        DeductResult deduct = stockService.deduct(
                activityId, userId, activity.getEndTime(), reservationId);
        if (deduct != DeductResult.SUCCESS) {
            // 卖光、重复购买都是正常业务结果，用 200 + 状态码表达，不是异常
            return SeckillResultVO.of(false, deduct.name(), deduct.getMessage());
        }

        SeckillMessage message = new SeckillMessage();
        message.setReservationId(reservationId);
        message.setActivityId(activityId);
        message.setUserId(userId);
        message.setProductId(activity.getProductId());
        message.setSeckillPrice(activity.getSeckillPrice());
        message.setRequestId(requestId);
        message.setSendTime(System.currentTimeMillis());

        String payload;
        try {
            payload = objectMapper.writeValueAsString(message);
        } catch (Exception e) {
            // 序列化失败说明代码有问题，此时消息还没产生，回滚是安全的
            log.error("秒杀消息序列化失败 activityId={} userId={}", activityId, userId, e);
            stockService.rollback(activityId, userId, reservationId);
            throw new BusinessException(500, "系统繁忙，请稍后重试");
        }

        // 消息体的落点是 seckill_outbox.payload VARCHAR(1024)。
        // 超了会在 insert 时抛「Data too long」，而那时 Redis 库存已经扣掉了，
        // 只能靠回滚兜、用户白抢一次。入口已经限制了 requestId 长度，
        // 这里是最后一道保险：真超了就地失败，别留给数据库报错。
        if (payload.length() > MAX_PAYLOAD_LENGTH) {
            log.error("秒杀消息体超过 outbox.payload 容量，已回滚 activityId={} userId={} length={}",
                    activityId, userId, payload.length());
            stockService.rollback(activityId, userId, reservationId);
            throw new BusinessException(500, "系统繁忙，请稍后重试");
        }

        // --- 第一步：把「要下单」这件事落库 ---
        try {
            outboxService.savePending(message, payload);
        } catch (DuplicateKeyException e) {
            // Redis 的去重集合可能因为过期/重启丢了，但数据库唯一键不会。
            // 走到这里说明 outbox 里已经有这个用户对这个活动的记录了，但有两种完全不同的情况：
            //   · 已经成交/在途 —— 确实是重复购买，回滚 Redis 后返回 REPEATED；
            //   · 已被人工回滚（ROLLED_BACK）—— 说明那一单被人工判定为不成立、
            //     库存也还回去了，用户理应可以重新参与。这时若一律返回 REPEATED，
            //     等于告诉他「你买过了」，而实际上他什么都没买到（第三轮评审 P2）。
            SeckillOutbox existing = outboxService.findByUserAndActivity(activityId, userId);
            if (existing != null && existing.getStatus() != null
                    && (existing.getStatus() == OutboxStatus.ROLLED_BACK.getCode()
                        || existing.getStatus() == OutboxStatus.ABORTED.getCode())
                    && outboxService.resetAbandoned(
                            activityId, userId, reservationId, payload)) {
                log.info("原记录已不成立（人工回滚或入口中止），复用该行重新参与 "
                                + "activityId={} userId={} outboxId={}",
                        activityId, userId, existing.getId());
                // 复用成功，直接往下走（跳过「新建行」这一步）
            } else {
                log.warn("本地消息表唯一键冲突，判定为重复购买 activityId={} userId={} status={}",
                        activityId, userId, existing == null ? null : existing.getStatus());
                stockService.rollback(activityId, userId, reservationId);
                return SeckillResultVO.of(false, DeductResult.REPEATED.name(),
                        DeductResult.REPEATED.getMessage());
            }
        } catch (Exception e) {
            // 库都写不进去，这一单不能成立，回滚 Redis
            log.error("本地消息落库失败 activityId={} userId={}", activityId, userId, e);
            stockService.rollback(activityId, userId, reservationId);
            throw new BusinessException(500, "系统繁忙，请稍后重试");
        }

        // --- 第二步：确认这次扣减仍归自己 ---
        // ★ 第三轮评审 P0 的落点。若兜底任务已经把这条 pending 认领为孤儿，
        //   说明它认为这笔扣减不会有对应的落库记录、并且可能已经把库存还回去了。
        //   这时继续发 MQ 就会造出「库存已还 + 订单照建」的超卖。
        //
        //   注意 confirmDeduct 被放在了「落库之后」：
        //   放在落库之前的话，一旦确认成功、进程随后崩溃，Redis 里没有任何待确认标记，
        //   outbox 又还没写 —— 这笔扣减就彻底失联了。先落库再确认，
        //   崩溃只会留下「有 outbox、有 pending」的正常可恢复状态。
        if (!stockService.confirmDeduct(activityId, userId, reservationId)) {
            return abortAfterClaimed(activityId, userId, message, payload);
        }

        // --- 第二步：投递 MQ ---
        // key 用 activityId-userId：MQ 控制台按 key 能直接查出某个用户的下单消息，
        // 排查「用户说抢到了但没订单」时非常省事
        boolean sent = mqProducer.send(MqTopics.SECKILL_ORDER, MqTopics.TAG_CREATE,
                activityId + "-" + userId, payload);

        if (sent) {
            try {
                outboxService.markSent(activityId, userId, reservationId);
            } catch (Exception e) {
                // 标记失败不影响结果：记录还是 PENDING，补偿任务会再投一次，
                // 消费端幂等，多投一次不会多出订单
                log.warn("标记已投递失败，交给补偿任务重投 activityId={} userId={}", activityId, userId, e);
            }
        } else {
            // 关键语义变化：这里不回滚，也不报错。
            // 发送超时时 broker 可能已经收到，回滚就会超卖；
            // 消息已在本地消息表里，几秒后补偿任务会重投，用户最终拿到订单。
            log.warn("MQ 未确认收到，转入补偿重投 activityId={} userId={}", activityId, userId);
        }

        log.info("抢购成功 activityId={} userId={} mqConfirmed={}", activityId, userId, sent);
        return SeckillResultVO.queued();
    }
    /**
     * 扣减已被兜底任务接管（或被判定为不可信）时的收尾。
     * <p>
     * 原则只有一条：<b>库存的最终状态必须和「消息会不会被投递」保持一致</b>。
     * <ul>
     *   <li>能撤销 outbox 行（还是 PENDING）→ 消息一定不会出去 → 回滚库存，让用户重试；</li>
     *   <li>撤销失败（补偿任务已把它标成 SENT）→ 消息可能已经在路上 →
     *       <b>绝不能回滚</b>，否则订单落库时库存已被归还，就是超卖；</li>
     *   <li>撤销成功但 Redis 回滚失败 → 库存还扣着，这时反过来要把 outbox 行补回去，
     *       让消息继续投递：订单落库正好与「已扣未还」的库存相抵，状态仍然自洽。</li>
     * </ul>
     * 三种分支都不允许出现「库存已还 + 订单照建」，也不允许出现「库存扣着 + 消息丢失」。
     */
    private SeckillResultVO abortAfterClaimed(Long activityId, Long userId,
                                              SeckillMessage message, String payload) {
        boolean aborted;
        try {
            // 条件更新（status=0 → 6）：如果补偿任务已经把它改成 SENT，
            // 这里返回 false，我们就知道消息可能已经出去了。
            // 用墓碑而不是物理删除：删掉之后消费者查不到预留记录会照常建单，
            // 而库存已经还了 —— 那是最难查的一种超卖。
            aborted = outboxService.markAborted(activityId, userId, message.getReservationId());
        } catch (Exception e) {
            log.error("撤销本地消息失败，保守起见不回滚库存 activityId={} userId={}",
                    activityId, userId, e);
            return SeckillResultVO.queued();
        }

        if (!aborted) {
            log.error("【关键】扣减已被兜底任务接管，但本地消息已非待投递状态，消息可能已在路上，"
                    + "保留库存不回滚 activityId={} userId={}", activityId, userId);
            return SeckillResultVO.queued();
        }

        RollbackOutcome outcome = stockService.rollback(
                activityId, userId, message.getReservationId());
        if (outcome == RollbackOutcome.FAILED) {
            // 库存没还回去，消息却被撤销了 —— 必须把消息补回来，
            // 否则这笔库存就永久少一件且没有任何记录能对出来
            log.error("回滚失败但消息已中止，把记录放回待投递让订单继续落库 "
                    + "activityId={} userId={}", activityId, userId);
            try {
                // 6 → 0：库存还扣着，消息必须继续走，订单落库正好与已扣库存相抵
                outboxService.resetAbandoned(
                        activityId, userId, message.getReservationId(), payload);
            } catch (Exception e) {
                log.error("【严重】恢复本地消息失败，需要人工对账 activityId={} userId={}",
                        activityId, userId, e);
            }
            return SeckillResultVO.queued();
        }

        log.warn("扣减已被兜底任务接管，已撤销本地消息并回滚库存 activityId={} userId={} outcome={}",
                activityId, userId, outcome);
        return SeckillResultVO.of(false, "ABORTED", "本次抢购未成功，请重新尝试");
    }
}
