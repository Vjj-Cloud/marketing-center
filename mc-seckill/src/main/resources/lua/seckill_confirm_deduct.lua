-- =============================================================
-- 确认扣减：把「这笔扣减已经有对应的 outbox 记录」这个事实落到 Redis
-- KEYS[1] = seckill:{activityId}:pending
-- KEYS[2] = seckill:{activityId}:orphan-claim:{reservationId}
-- ARGV[1] = pending member（userId:reservationId）
--
-- 返回值：1 = 确认成功，调用方可以继续投递 MQ
--        0 = 这笔扣减已被兜底任务接管，**必须中止**，不能再投递
--
-- ★ 为什么不能只是 ZREM（第三轮评审 P0）：
--   ZREM 是幂等的，返回 0 时无法区分两种情况：
--     a) 另一条重复请求已经确认过（正常）；
--     b) 兜底任务判定它是孤儿、已经把库存还回去了（危险）。
--   情况 b 下如果继续发 MQ，订单会落库，而库存早已还给别人 —— 超卖。
--
--   所以先看 claim：claim 存在 = 有人正在按「孤儿」处置这笔扣减，
--   此时无论 pending 还在不在，都必须停手。
--   claim 不存在时才真正 ZREM，并用 ZREM 的返回值作为凭据：
--   真的删掉了（1）才说明这笔扣减从头到尾归我，可以投递。
--
-- ★ 和孤儿认领脚本的互斥关系：
--   两边都只操作这两个 key，Redis 单线程执行 Lua，
--   所以「读 claim → 改 pending」与「读 score → 写 claim」不会交错，
--   不存在「都以为自己是赢家」的中间态。
-- =============================================================

if redis.call('EXISTS', KEYS[2]) == 1 then
    -- 已被兜底任务认领：立即中止，不要碰 pending（让它去回滚并清理）
    return 0
end

return redis.call('ZREM', KEYS[1], ARGV[1])
