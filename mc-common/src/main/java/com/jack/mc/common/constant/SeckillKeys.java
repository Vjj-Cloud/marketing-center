package com.jack.mc.common.constant;

/**
 * 秒杀相关 Redis key。
 * <p>
 * <b>key 名里的花括号不是装饰，是 Redis Cluster 的哈希槽标签。</b>
 * Redis Cluster 只对 key 中第一个 <code>{...}</code> 之间的内容做哈希：
 * <pre>
 *   seckill:stock:1      → 对整串 "seckill:stock:1" 取模，落某个槽
 *   seckill:bought:1     → 对整串 "seckill:bought:1" 取模，落另一个槽
 *   seckill:{1}:stock    → 只对 "1" 取模
 *   seckill:{1}:bought   → 只对 "1" 取模，与上一个必定同槽 ✓
 * </pre>
 * 扣减脚本一次操作两个 key，在 Cluster 下若不同槽会直接报
 * <code>CROSSSLOT Keys in request don't hash to the same slot</code>，
 * 而且单机环境永远测不出来——部署到 Cluster 才炸。
 * <p>
 * 同一个活动永远落在同一个槽，还有一个附带好处：该活动的所有库存操作
 * 都集中在一个节点上，不会因为 key 分散而放大跨节点开销。
 */
public final class SeckillKeys {

    private SeckillKeys() {
    }

    /** 剩余库存，String 类型。例：seckill:{1}:stock */
    public static String stock(Long activityId) {
        return "seckill:{" + activityId + "}:stock";
    }

    /** 已购买用户集合，Set 类型，成员是 userId。例：seckill:{1}:bought */
    public static String bought(Long activityId) {
        return "seckill:{" + activityId + "}:bought";
    }

    /**
     * 待确认的扣减记录，ZSet 类型，score 是扣减时刻（毫秒），member 是 userId。
     * 例：seckill:{1}:pending
     * <p>
     * <b>解决的是「扣了库存但没落库」的崩溃窗口</b>：主流程是
     * 「Lua 扣 Redis → 写 seckill_outbox」，进程若倒在两步之间，
     * 就会出现「库存少了 1、用户在买家集合里、但既没有 outbox 也没有订单」的
     * 永久悬挂记录——用户既拿不到货，也永远抢不到第二件。
     * <p>
     * 所以扣减成功时顺手在这个 ZSet 里记一笔（同一个 Lua，原子），
     * outbox 落库成功后再 ZREM 掉。留在里面的就是「扣了但没落库」的嫌疑记录，
     * 由 {@code OrphanDeductionTask} 兜底回滚。
     * <p>
     * 带 <code>{activityId}</code> 是为了和 stock / bought 同槽：
     * 三者必须能出现在同一个 Lua 里，否则 Cluster 下直接 CROSSSLOT。
     */
    public static String pendingDeduction(Long activityId) {
        return "seckill:{" + activityId + "}:pending";
    }

    /**
     * pending ZSet 的成员格式：userId:reservationId。
     * userId 是数字前缀，reservationId 是 UUID，不含冒号，因此可无歧义解析。
     */
    public static String pendingMember(Long userId, String reservationId) {
        return userId + ":" + reservationId;
    }

    /**
     * 解析 pending 成员。兼容升级前只有 userId 的旧成员：
     * 旧成员统一映射为 reservationId=legacy，可被兜底任务清理。
     */
    public static PendingReservation parsePendingMember(String member) {
        if (member == null || member.isBlank()) {
            return null;
        }
        int separator = member.indexOf(':');
        try {
            if (separator < 0) {
                return new PendingReservation(Long.parseLong(member), "legacy");
            }
            Long userId = Long.parseLong(member.substring(0, separator));
            String reservationId = member.substring(separator + 1);
            if (userId <= 0 || reservationId.isBlank()) {
                return null;
            }
            return new PendingReservation(userId, reservationId);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** pendingDeduction 的 key 前缀，供 SCAN 定位「哪些活动有待确认扣减」 */
    public static final String PENDING_PATTERN = "seckill:*:pending";

    /**
     * 孤儿扣减的「认领租约」，String 类型，带过期时间。例：seckill:{1}:orphan-claim:1001
     * <p>
     * <b>解决的是兜底任务与慢请求之间的竞态（第三轮评审 P0）</b>：
     * 原来的兜底流程是「查 outbox → 不存在就回滚」，两步之间没有任何互斥。
     * 如果原请求只是<b>慢</b>（GC 停顿、线程被挂起、数据库连接池排队超过 60 秒宽限期），
     * 兜底任务会判定它是孤儿并归还库存；原请求随后恢复，照常写 outbox、发 MQ，
     * 订单最终落库 —— 库存已经还回去了，一件货卖两次。
     * <p>
     * 所以把「判定为孤儿」这个动作做成一次<b>原子认领</b>：
     * <pre>
     * 兜底任务：仅当 pending 里这条记录的 score 已超过宽限期，才 SET NX 拿到 claim
     * 原请求  ：confirmDeduct 前先看 claim 在不在，在就说明已被接管，必须中止
     * </pre>
     * 认领带 TTL（租约）：任务进程若在处理途中挂掉，租约会过期，下一轮可以重新认领，
     * 不会把这个活动永久锁死。
     * <p>
     * 同样带 <code>{activityId}</code> 哈希标签，保证与 pending / stock / bought 同槽，
     * 能被同一个 Lua 脚本原子操作。
     */
    public static String orphanClaim(Long activityId, String reservationId) {
        return "seckill:{" + activityId + "}:orphan-claim:" + reservationId;
    }

    public record PendingReservation(Long userId, String reservationId) {
    }

    /** 从 pending key 反解 activityId，解析失败返回 null */
    public static Long activityIdOfPending(String key) {
        if (key == null) {
            return null;
        }
        int left = key.indexOf('{');
        int right = key.indexOf('}');
        if (left < 0 || right <= left + 1) {
            return null;
        }
        try {
            return Long.parseLong(key.substring(left + 1, right));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 补投任务的分布式锁。多实例部署时，同一时刻只允许一个实例扫描 outbox，
     * 否则同一条消息会被重复投递（虽然消费端幂等，但白白浪费 broker 容量）。
     */
    public static String outboxRelayLock() {
        return "seckill:outbox:relay:lock";
    }

    /**
     * 订单号发号器的每日序列。
     * 用 Redis INCR 而不是「时间戳 + 随机数」：后者在并发下单时可能撞号，
     * 撞了就要靠唯一索引报错再重试，属于用异常做正常流程控制。
     */
    public static String orderNoSeq(String day) {
        return "seckill:orderno:seq:" + day;
    }
}
