-- =============================================================
-- 秒杀原子扣减
-- KEYS[1] = seckill:{activityId}:stock    剩余库存
-- KEYS[2] = seckill:{activityId}:bought   已购买用户集合
-- KEYS[3] = seckill:{activityId}:pending  待确认扣减（ZSet，score=毫秒时间戳）
-- ARGV[1] = userId
-- ARGV[2] = 买家集合与待确认集合的 TTL（秒），<=0 表示不设
-- ARGV[3] = 当前时间戳（毫秒）
-- ARGV[4] = reservationId（本次扣减唯一预订号）
--
-- 返回值：1 扣减成功 / 0 库存不足 / -1 重复购买 / -2 库存未预热
--
-- 为什么这段必须用 Lua：
--   「判断库存 → 判断是否买过 → 扣库存 → 记录用户」是四个操作，
--   拆成四次 Redis 调用的话，两个并发请求可能同时读到 stock=1，
--   然后各自 DECR，库存变成 -1，卖出两件只减了一次。
--   Redis 执行 Lua 期间不会插入其他命令，这四步天然是一个整体。
--
-- 顺序为什么是「先判库存，再 SADD，最后 DECR」：
--   如果先 SADD 再判库存，库存不足时就得把用户从集合里删掉（SREM）做补偿，
--   多一步补偿就多一个可能失败的环节。先判库存可以把失败挡在最前面，
--   此时还没有任何状态被修改。
--
-- KEYS[3] 是第二轮评审补的「待确认标记」：
--   扣减发生在 Redis，落库发生在 MySQL，中间隔着一次网络往返。
--   进程恰好倒在两步之间时，就会出现「库存少了、用户在买家集合里、
--   但没有 outbox 也没有订单」的悬挂记录——用户拿不到货，也永远抢不到第二件。
--   把这一笔记进 ZSet（和 DECR 在同一个 Lua 里，原子），落库成功后 ZREM，
--   留在集合里的就是嫌疑记录，交给 OrphanDeductionTask 兜底。
--   代价是每次扣减多一次 ZADD，属于可接受的开销。
-- =============================================================

local stock = redis.call('GET', KEYS[1])
if not stock then
    return -2
end

if tonumber(stock) <= 0 then
    return 0
end

-- SADD 返回 1 表示此前不在集合里（首次购买），0 表示已购买过
local added = redis.call('SADD', KEYS[2], ARGV[1])
if added == 0 then
    -- 用户已经抢到过，直接返回，库存一分不动
    return -1
end

redis.call('DECR', KEYS[1])

-- 记录「这次扣减还没有对应的落库」，score 用于让兜底任务判断「已经等了多久」。
-- member 用 userId:reservationId，避免把旧 outbox 记录误认为本次扣减。
redis.call('ZADD', KEYS[3], ARGV[3], ARGV[1] .. ':' .. ARGV[4])

-- 买家集合在第一次 SADD 时才创建，此时没有 TTL，补上避免永久占内存。
-- TTL < 0 说明没设过期时间（-1 永不过期 / -2 不存在）。
if tonumber(ARGV[2]) > 0 then
    if redis.call('TTL', KEYS[2]) < 0 then
        redis.call('EXPIRE', KEYS[2], ARGV[2])
    end
    if redis.call('TTL', KEYS[3]) < 0 then
        redis.call('EXPIRE', KEYS[3], ARGV[2])
    end
end

return 1
