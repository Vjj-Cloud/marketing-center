-- =============================================================
-- 03 · 本地消息表状态语义补充（对应第二轮评审 P1「重试耗尽回滚」）
-- 执行：mysql -umall -p --default-character-set=utf8mb4 jianxuan_mall < 03_outbox_review_status.sql
--
-- 本脚本可重复执行：
--   · MODIFY COLUMN 本身幂等；
--   · ADD KEY 不是幂等的，这里先查 information_schema 再建（见下方说明）。
-- =============================================================

USE jianxuan_mall;

-- 新增状态 4 = 待人工复核。
-- 原来的 3（已回滚）语义太宽：重试耗尽就自动回滚，可能把
-- 「消息早已投递成功、只是消费者迟到」的单也回滚掉，形成超卖。
-- 现在 3 只允许由管理接口写入（人工确认无订单后），
-- 补偿任务耗尽重试只会进入 4，库存保留不动。
ALTER TABLE seckill_outbox
    MODIFY COLUMN status TINYINT NOT NULL DEFAULT 0
        COMMENT '0 待投递 1 已投递 2 已消费 3 人工回滚 4 待人工复核';

-- 兜底任务与复查任务都要按「状态 + 时间」捞记录。
--
-- 原版这里是裸的 ADD KEY，脚本头却写着「可重复执行」——第二次执行直接报
-- Duplicate key name 'idx_status_updated'，属于注释与事实不符（第四轮评审 P2）。
--
-- MySQL 8 的 ALTER TABLE 没有 IF NOT EXISTS 语法，只能在 information_schema
-- 里查一次再拼动态 SQL。'DO 0' 是合法的空语句，用来当「已存在就不做」的分支。
SET @has_idx := (
    SELECT COUNT(*)
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'seckill_outbox'
      AND index_name = 'idx_status_updated');

SET @ddl := IF(@has_idx = 0,
    'ALTER TABLE seckill_outbox ADD KEY idx_status_updated (status, updated_at)',
    'DO 0');

PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 运维日常入口：查看待人工复核的条目
-- SELECT id, activity_id, user_id, retry_count, last_error, created_at
-- FROM seckill_outbox WHERE status = 4 ORDER BY id;
