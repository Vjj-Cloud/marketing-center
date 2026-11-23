-- =============================================================
-- 09 · 本地消息表补活动维度索引（对应第七轮评审 P1-1）
-- 执行：mysql -umall -p --default-character-set=utf8mb4 jianxuan_mall < 09_outbox_activity_index.sql
--
-- 本脚本可重复执行：ADD KEY 不是幂等的，这里先查 information_schema 再拼动态 SQL
-- （和 03 是同一个写法，MySQL 8 的 ALTER TABLE 没有 IF NOT EXISTS）。
-- =============================================================

USE jianxuan_mall;

-- 为什么需要这条索引：
--
-- seckill_outbox 上原有的索引没有一个能以 activity_id 打头——
--   · PRIMARY(id)
--   · uk_user_activity(user_id, activity_id)   ← 前导列是 user_id，按活动查用不上
--   · idx_status_retry(status, next_retry_at)
--   · idx_status_updated(status, updated_at)
--   · idx_reservation_id(reservation_id)
--
-- 而「按活动查 outbox」有两处热路径：
--   1. SeckillOutboxMapper.countActiveByActivity(activity_id, status IN (0,1,4,5,7))
--      —— 活动结束时判断「还有没有可能落库的单」，决定要不要归还预留库存；
--   2. SeckillActivityMapper.findReleasableReservations 里的 NOT EXISTS 子查询，
--      外层每扫到一个可释放的活动，内层就按 activity_id 探一次。
--      几十个活动就是几十次全表扫。
--
-- 少这条索引时，MySQL 只能用 status 打头的索引（status 基数极低），
-- 或者直接全表扫。outbox 是随订单量线性增长的表，压测/生产上几百万行时，
-- 「归还库存」这个事务会变成秒级大查询，而它持有 seckill_activity 的行锁——
-- 慢的不只是归还，还会挡住同一活动的其它状态流转。
--
-- 列序 (activity_id, status) 是刻意的：activity_id 用来把范围收窄到单个活动，
-- status 放在后面让 IN (0,1,4,5,7) 能在索引内完成过滤，不必回表。
SET @has_idx := (
    SELECT COUNT(*)
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'seckill_outbox'
      AND index_name = 'idx_activity_status');

SET @ddl := IF(@has_idx = 0,
    'ALTER TABLE seckill_outbox ADD KEY idx_activity_status (activity_id, status)',
    'DO 0');

PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 验证：
--   EXPLAIN SELECT COUNT(*) FROM seckill_outbox WHERE activity_id = 1 AND status IN (0,1,4,5,7);
--   期望 key = idx_activity_status，而不是 idx_status_retry / 全表
