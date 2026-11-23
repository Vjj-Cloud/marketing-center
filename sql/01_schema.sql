-- =============================================================
-- 闪购营销中心 · 表结构
-- 复用简选商城库 jianxuan_mall，只新增两张表，不动原有表
-- 执行：mysql -umall -p --default-character-set=utf8mb4 jianxuan_mall < 01_schema.sql
-- =============================================================

USE jianxuan_mall;

-- -------------------------------------------------------------
-- 秒杀活动表
-- 库存分两处存：DB 的 available_stock 是"账本"，Redis 的是"闸门"。
-- 两者不一致时以 DB 为准（Redis 丢了可以重新预热，DB 丢了钱就错了）。
-- -------------------------------------------------------------
CREATE TABLE IF NOT EXISTS seckill_activity (
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    product_id      BIGINT UNSIGNED NOT NULL COMMENT '关联 product.id',
    title           VARCHAR(100)    NOT NULL COMMENT '活动标题',
    seckill_price   DECIMAL(10, 2)  NOT NULL COMMENT '秒杀价，必须低于 product.price',
    total_stock     INT             NOT NULL COMMENT '活动总库存',
    available_stock INT             NOT NULL COMMENT 'DB 侧剩余库存（对账用，Redis 才是实际闸门）',
    start_time      DATETIME        NOT NULL,
    end_time        DATETIME        NOT NULL,
    status          TINYINT         NOT NULL DEFAULT 0 COMMENT '0 草稿 1 已发布 2 已结束',
    version         INT             NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
    created_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_status_start (status, start_time),
    KEY idx_product (product_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='秒杀活动';

-- -------------------------------------------------------------
-- 秒杀订单表
-- uk_user_activity 是"一人一单"的最终防线：
--   Redis 去重可能因重启/过期失效，唯一索引不会。
--   MQ 重复消费也靠它兜底，所以消费者可以放心地"先插后判"。
-- -------------------------------------------------------------
CREATE TABLE IF NOT EXISTS seckill_order (
    id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    activity_id   BIGINT UNSIGNED NOT NULL,
    user_id       BIGINT UNSIGNED NOT NULL,
    product_id    BIGINT UNSIGNED NOT NULL,
    seckill_price DECIMAL(10, 2)  NOT NULL,
    status        TINYINT         NOT NULL DEFAULT 0 COMMENT '0 待支付 1 已支付 2 已取消',
    request_id    VARCHAR(64)     NOT NULL DEFAULT '' COMMENT '客户端幂等键',
    created_at    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_activity (user_id, activity_id),
    KEY idx_activity (activity_id),
    KEY idx_user (user_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='秒杀订单';
