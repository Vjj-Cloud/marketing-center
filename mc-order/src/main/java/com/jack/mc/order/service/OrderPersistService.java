package com.jack.mc.order.service;

import com.jack.mc.common.constant.SeckillKeys;
import com.jack.mc.common.message.SeckillMessage;
import com.jack.mc.order.entity.OutboxGuardState;
import com.jack.mc.order.entity.SeckillOrder;
import com.jack.mc.order.mapper.OrderMapper;
import com.jack.mc.order.mapper.SeckillOutboxGuardMapper;
import com.jack.mc.order.mapper.StockMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * 订单落库。
 * <p>
 * <b>这个方法必须是独立 Bean，不能写成消费者类里的一个 @Transactional 方法。</b>
 * 因为消费者需要「捕获 DuplicateKeyException 后继续返回成功」，
 * 如果把 catch 写在同一方法内，事务已被标记 rollbackOnly，提交时会抛
 * UnexpectedRollbackException —— 表现是「幂等逻辑看起来没问题，
 * 但重复消息一来就报 500 并无限重试」。
 * 事务边界与异常捕获必须在两个 Bean 里，这是 Spring 事务代理的基本约束。
 * <p>
 * 一次事务里做三件事，顺序不能换：
 * <ol>
 *   <li>插订单——唯一索引先挡住重复消费；</li>
 *   <li>扣商品库存——保证不超卖真实库存；</li>
 *   <li>扣活动账本库存——用于对账。</li>
 * </ol>
 * 三步任一失败整笔回滚，消息会重投，最终要么全部成功、要么进死信队列等人处理。
 * 不允许出现「订单建了但库存没扣」这种中间态。
 */
@Service
public class OrderPersistService {

    private static final Logger log = LoggerFactory.getLogger(OrderPersistService.class);

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");

    /** 与 seckill_order.request_id VARCHAR(64) 对齐 */
    private static final int MAX_REQUEST_ID_LENGTH = 64;

    private static final int OUTBOX_CONSUMED = 2;

    /**
     * 本地消息表状态：人工已回滚。预留被明确取消，这一单绝不能再落库。
     * 与 mc-seckill 的 OutboxStatus.ROLLED_BACK 对应——跨服务，无法直接引用枚举，
     * 数字写在这里会更该被注释讲明白。
     */
    private static final int OUTBOX_ROLLED_BACK = 3;

    /**
     * 本地消息表状态：人工回滚执行中。此时库存可能正被归还，
     * 落库会立刻变成超卖，必须让路。
     */
    private static final int OUTBOX_ROLLING_BACK = 5;

    /**
     * 本地消息表状态：抢购已在入口中止（墓碑）。
     * <p>
     * 主流程在「扣减被兜底任务接管」时会撤销消息并归还库存，留一条 6 作为墓碑。
     * 消费者必须认这个墓碑：消息可能是补偿任务在中止之前就发出去的，
     * 此时订单落库就会和已经归还的库存撞成超卖。
     */
    private static final int OUTBOX_ABORTED = 6;

    private final OrderMapper orderMapper;
    private final StockMapper stockMapper;
    private final SeckillOutboxGuardMapper outboxGuardMapper;
    private final StringRedisTemplate redis;

    public OrderPersistService(OrderMapper orderMapper, StockMapper stockMapper,
                               SeckillOutboxGuardMapper outboxGuardMapper,
                               StringRedisTemplate redis) {
        this.orderMapper = orderMapper;
        this.stockMapper = stockMapper;
        this.outboxGuardMapper = outboxGuardMapper;
        this.redis = redis;
    }

    /**
     * @throws org.springframework.dao.DuplicateKeyException 该用户对本活动已有订单（重复消费）
     */
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public SeckillOrder create(SeckillMessage message) {
        // 0) 先确认这笔预留仍然有效，并锁住那一行直到本事务提交。
        //
        //    为什么这一步不能省（第三轮评审 P1）：人工回滚的流程是
        //    「查订单 → 回滚 Redis → 改状态」，消费者完全可能在「查订单」之后
        //    才把订单插进来，最后库存还了、订单也落了。
        //    锁同一行之后，两个动作被数据库串行化：
        //      · 消费者先拿到锁 → 人工回滚的 CAS(4→5) 阻塞到本事务提交，
        //        提交后它再查订单就能看到这一单，于是转成已消费、不回滚；
        //      · 人工回滚先 CAS 成 5 → 消费者读到的状态是 5，直接拒绝落库。
        //    两种顺序都不会出现「库存已还 + 订单已建」。
        //
        //    用 READ_COMMITTED：锁一行不存在时 RR 会加间隙锁，
        //    可能把秒杀服务刚插入 outbox 的那一行挡住。
        OutboxGuardState outbox = outboxGuardMapper.lockState(
                message.getActivityId(), message.getUserId());
        if (outbox == null) {
            // 新链路的消息必须有本地预留。没有记录说明消息来源不可信，
            // 不能绕过 outbox 直接创建订单。
            throw new OrderRejectedException(
                    "缺少本地预留记录，拒绝落库 activityId=" + message.getActivityId()
                            + " userId=" + message.getUserId());
        }
        if (message.getReservationId() != null
                && !message.getReservationId().isBlank()
                && !message.getReservationId().equals(outbox.getReservationId())) {
            throw new OrderRejectedException(
                    "预订号不匹配，拒绝落库 activityId=" + message.getActivityId()
                            + " userId=" + message.getUserId()
                            + " expected=" + outbox.getReservationId()
                            + " actual=" + message.getReservationId());
        }

        int outboxStatus = outbox.getStatus() == null ? -1 : outbox.getStatus();
        if (outboxStatus == OUTBOX_CONSUMED) {
            return null;
        }
        if (outboxStatus == OUTBOX_ROLLED_BACK
                || outboxStatus == OUTBOX_ROLLING_BACK
                || outboxStatus == OUTBOX_ABORTED) {
            // 预留已被人工取消。重试没有意义，必须让消费者「确认消费」而不是重投，
            // 否则这条消息会在 MQ 里空转 16 次再进死信队列。
            throw new OrderRejectedException(
                    "预留已失效（人工回滚/入口中止），拒绝落库 activityId=" + message.getActivityId()
                            + " userId=" + message.getUserId() + " outboxStatus=" + outboxStatus);
        }

        // 在持有同一行锁的事务里先改成 CONSUMING(7)。
        // 入口的 markAborted 只允许更新 status=0；若消费者先认领，它更新 0 行，
        // 因而不会在订单已创建后再回滚库存。
        if (outboxGuardMapper.markConsuming(outbox.getId()) != 1) {
            throw new IllegalStateException("outbox 状态无法进入消费中 id=" + outbox.getId()
                    + " status=" + outboxStatus);
        }

        SeckillOrder order = new SeckillOrder();
        order.setOrderNo(nextOrderNo());
        order.setActivityId(message.getActivityId());
        order.setUserId(message.getUserId());
        order.setProductId(message.getProductId());
        order.setSeckillPrice(message.getSeckillPrice());
        order.setStatus(0);
        order.setRequestId(safeRequestId(message.getRequestId()));

        // 1) 插单，唯一索引兜重复
        orderMapper.insert(order);

        // 2) 扣真实库存。
        // 新活动在发布时已经预留过 product.stock，消费端只扣活动账本；
        // reserved_stock=0 是迁移前的历史活动，保持旧的逐单扣商品库存逻辑。
        Integer reservedStock = stockMapper.reservedStock(message.getActivityId());
        if (reservedStock == null || reservedStock <= 0) {
            int productRows = stockMapper.deductProductStock(message.getProductId());
            if (productRows == 0) {
                // 走到这里说明 Redis 说卖得出去、DB 说没货了。
                // 这是真正的数据不一致，必须让事务回滚 + 消息重试 + 告警，
                // 绝不能吞掉——吞掉的后果是「订单建了但商品库存没减少」。
                throw new IllegalStateException(
                        "商品库存不足，无法创建订单 productId=" + message.getProductId());
            }
        }

        // 3) 扣活动账本库存
        int activityRows = stockMapper.deductActivityStock(message.getActivityId());
        if (activityRows == 0) {
            throw new IllegalStateException("活动剩余库存不足，无法创建订单 activityId=" + message.getActivityId());
        }

        if (outboxGuardMapper.markConsumed(outbox.getId()) != 1) {
            throw new IllegalStateException("outbox 状态无法标记为已消费 id=" + outbox.getId());
        }

        log.info("订单落库成功 orderNo={} activityId={} userId={}",
                order.getOrderNo(), message.getActivityId(), message.getUserId());
        return order;
    }

    /**
     * request_id 是 {@code VARCHAR(64)}。秒杀入口已经限长（第四轮评审 P1），
     * 这里再截一次是防「绕过入口直接往 topic 投消息」：broker 在内网、没有鉴权，
     * 一条手工消息就能让插入抛 Data too long，而超长这种错误重试一万次也不会变好，
     * 只会把消息耗进死信队列。
     * <p>
     * 在这里截断是安全的：这一列只用于展示和排查，
     * 真正的幂等约束是 {@code uk_user_activity}，不依赖 request_id。
     * 入口那边则必须「拒绝」而不是截断——两个不同的请求 ID 被截成同一个，
     * 幂等键就失去意义了。
     */
    private String safeRequestId(String requestId) {
        if (requestId == null) {
            return "";
        }
        String trimmed = requestId.trim();
        return trimmed.length() <= MAX_REQUEST_ID_LENGTH
                ? trimmed
                : trimmed.substring(0, MAX_REQUEST_ID_LENGTH);
    }

    /**
     * 订单号 = SK + 日期 + 6 位当日序列。
     * <p>
     * 用 Redis INCR 而不是「时间戳 + 随机数」：后者在并发下会撞号，
     * 撞了就要靠唯一索引报错再重试，属于拿异常做正常流程控制；
     * 也不要用「时间戳 + userId」，同一用户同一秒两单会撞。
     * <p>
     * 事务回滚会浪费掉一个序列号（号段出现空洞），这是可接受的：
     * 订单号只要求唯一和单调递增，不要求连续。
     */
    private String nextOrderNo() {
        String day = LocalDate.now().format(DAY);
        String key = SeckillKeys.orderNoSeq(day);
        Long seq = redis.opsForValue().increment(key);
        if (seq != null && seq == 1L) {
            // 第一次自增时才设过期：每个 key 只存活两天，避免日积月累占满 Redis
            redis.expire(key, Duration.ofDays(2));
        }
        if (seq == null) {
            // INCR 理论上不会返回 null；真返回了说明 Redis 客户端异常，
            // 与其生成一个可能重复的号码，不如让消息重试
            throw new IllegalStateException("订单号发号器不可用");
        }
        return "SK" + day + String.format("%06d", seq);
    }
}
