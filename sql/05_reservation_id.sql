-- =============================================================
-- 05 · 每次扣减的唯一预订号
-- 执行：
--   mysql -umall -p --default-character-set=utf8mb4 jianxuan_mall < 05_reservation_id.sql
-- 本脚本只能执行一次（ADD COLUMN 不是幂等操作）。
-- =============================================================

USE jianxuan_mall;

ALTER TABLE seckill_outbox
    ADD COLUMN reservation_id VARCHAR(64) NOT NULL DEFAULT ''
        COMMENT '本次扣减唯一预订号；Redis pending、outbox、MQ 消息使用同一个值'
        AFTER user_id;

-- 历史数据没有预订号，回填为稳定且唯一的 legacy-id。
UPDATE seckill_outbox
SET reservation_id = CONCAT('legacy-', id)
WHERE reservation_id = '';

ALTER TABLE seckill_outbox
    ADD KEY idx_reservation_id (reservation_id),
    MODIFY COLUMN status TINYINT NOT NULL DEFAULT 0
        COMMENT '0待投递 1已投递 2已消费 3人工回滚 4待人工复核 5回滚执行中 6已中止 7消费中';
