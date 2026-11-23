-- =============================================================
-- 08 · 商品库存预留与释放
-- 执行：
--   mysql -umall -p --default-character-set=utf8mb4 jianxuan_mall < 08_inventory_reservation.sql
-- 本脚本只能执行一次（ADD COLUMN 不是幂等操作）。
-- =============================================================

USE jianxuan_mall;

-- reserved_stock=0 表示历史活动：发布时没有占用 product.stock，
-- 订单落库仍按旧逻辑扣商品库存。新发布活动写入实际预留数量。
ALTER TABLE seckill_activity
    ADD COLUMN reserved_stock INT NOT NULL DEFAULT 0
        COMMENT '发布时从product.stock预留的数量；0表示历史/未预留活动'
        AFTER warmup_at,
    ADD COLUMN reservation_released_at DATETIME NULL DEFAULT NULL
        COMMENT '未售预留库存归还product.stock的时间'
        AFTER reserved_stock,
    ADD KEY idx_reservation_release
        (reserved_stock, reservation_released_at, end_time);
