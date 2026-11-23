-- =============================================================
-- 孤儿扣减的原子认领
-- KEYS[1] = seckill:{activityId}:pending        待确认扣减 ZSet
-- KEYS[2] = seckill:{activityId}:orphan-claim:{userId}  认领租约
-- ARGV[1] = pending member（userId:reservationId）
-- ARGV[2] = 宽限期截止时间戳（毫秒），score <= 它才算「超时未确认」
-- ARGV[3] = 租约令牌（实例 id），写进 claim 的 value，释放时要比对
-- ARGV[4] = 租约时长（毫秒）
--
-- 返回值：1 = 认领成功，可以继续查 outbox 并决定是否回滚
--        0 = 不该动它（记录已不存在 / 还没到宽限期 / 已被别人认领）
--
-- ★ 这个脚本解决的是第三轮评审的 P0：
--   原来的流程是「先查 outbox，再回滚」，两步之间没有互斥。
--   一个只是慢（GC 停顿、连接池排队超过 60 秒）的请求会被判成孤儿：
--   兜底任务把库存还回去，原请求随后恢复、写 outbox、发 MQ，
--   订单照样落库 —— 库存还了、货又卖了，直接超卖。
--
--   把判定做成「原子认领 + 租约」之后：
--     · 兜底任务必须先拿到 claim 才能动手；
--     · 原请求在投递 MQ 之前调用 confirmDeduct，它会检查 claim 在不在，
--       在就说明自己被接管了，立刻中止并撤销 outbox 行；
--     · 两者通过同一个 key 形成互斥，谁先谁后都只有一个能继续。
--
-- ★ 为什么 score 的超时判断要在脚本里做：
--   如果在 Java 里先读 ZSCORE 再执行 SETNX，中间又是一个窗口——
--   两次调用之间记录可能已经被 confirmDeduct 删掉，认领会「认」到一个已经完成的扣减，
--   然后去回滚一笔正常订单的库存。
--
-- ★ 为什么 claim 单独一个 key 而不是复用 pending 的 score：
--   score 只能存数字，表达不了「被谁认领」；而释放时必须能判断
--   「这个 claim 是不是我加的」，否则任务超时后会把别的实例的 claim 删掉。
-- =============================================================

local score = redis.call('ZSCORE', KEYS[1], ARGV[1])
if not score then
    -- 记录已经不在了：要么 confirmDeduct 正常清掉，要么回滚过了。
    -- 无论哪种，都不该再动手。
    return 0
end

if tonumber(score) > tonumber(ARGV[2]) then
    -- 还没到宽限期（或者刚刚被重新扣减过），继续等
    return 0
end

-- SET NX：已有 claim 说明别的实例正在处理同一条，直接让路
local ok = redis.call('SET', KEYS[2], ARGV[3], 'NX', 'PX', ARGV[4])
if not ok then
    return 0
end

return 1
