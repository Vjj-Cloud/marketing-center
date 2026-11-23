-- =============================================================
-- 06 · 对账次数（对应第四轮评审 P2「已投递但消费端长期不工作时无限重投」）
-- 执行：mysql -umall -p --default-character-set=utf8mb4 jianxuan_mall < 06_outbox_reconcile_count.sql
--
-- 本脚本可重复执行（列是否已存在同样查 information_schema 判断）。
-- =============================================================

USE jianxuan_mall;

-- 为什么单独一列，不复用 retry_count：
--   retry_count 记的是「broker 没确认发送成功」，reconcile_count 记的是
--   「发出去了但消费端不干活」。两种故障的重试节奏完全不同（前者是指数退避，
--   后者是 5 分钟一次对账），混用一个计数会让「broker 抖 3 次 + 消费者挂 1 次」
--   直接撞上阈值、提前转人工。
--
-- 没有这一列时，OutboxRelayTask 的 reconcileSent 会把记录在 SENT → PENDING →
-- SENT 之间无限循环：broker 一直发送成功，订单却永远不来，记录既不进待复核
-- 也不告警，只是每 5 分钟刷一条日志。
SET @has_col := (
    SELECT COUNT(*)
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'seckill_outbox'
      AND column_name = 'reconcile_count');

SET @ddl := IF(@has_col = 0,
    'ALTER TABLE seckill_outbox ADD COLUMN reconcile_count INT NOT NULL DEFAULT 0 '
        'COMMENT ''已投递但查不到订单、被打回重投的次数'' AFTER retry_count',
    'DO 0');

PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 核对：应该是 0 行（存量记录的该列会默认 0，不需要回填）
-- SELECT id, status, retry_count, reconcile_count FROM seckill_outbox
-- WHERE reconcile_count <> 0 ORDER BY id;
