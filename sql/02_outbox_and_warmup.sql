-- =============================================================
-- 02 · 一致性加固（对应代码评审 P1 第 3、4 条）
-- 执行：mysql -umall -p --default-character-set=utf8mb4 jianxuan_mall < 02_outbox_and_warmup.sql
-- 注意：本脚本只能执行一次（MySQL 8 不支持 ADD COLUMN IF NOT EXISTS）
-- =============================================================

USE jianxuan_mall;

-- -------------------------------------------------------------
-- 1. 活动表增加预热时间戳
--    作用：区分「从未预热」和「预热过但 key 丢了」。
--    前者是正常状态（活动还没到预热窗口），后者才是需要人工介入的事故。
--    没有这一列时，预热任务无法区分二者，只能对所有进行中的活动刷告警。
-- -------------------------------------------------------------
ALTER TABLE seckill_activity
    ADD COLUMN warmup_at DATETIME NULL DEFAULT NULL
        COMMENT '库存预热成功时间；NULL 表示从未预热过';

-- -------------------------------------------------------------
-- 2. 订单表增加业务订单号
--    主键自增 id 不对外暴露：连续数字会泄露真实单量，
--    且分库分表后主键不再全局有序。对外一律用 order_no。
-- -------------------------------------------------------------
ALTER TABLE seckill_order
    ADD COLUMN order_no VARCHAR(32) NOT NULL DEFAULT '' COMMENT '业务订单号（对外）' AFTER id;

-- 先回填再加唯一索引：多行空串会直接撞唯一键
UPDATE seckill_order SET order_no = CONCAT('SK', LPAD(id, 10, '0')) WHERE order_no = '';

ALTER TABLE seckill_order
    ADD UNIQUE KEY uk_order_no (order_no);

-- -------------------------------------------------------------
-- 3. 本地消息表（Transactional Outbox）
--    为什么需要：秒杀扣完 Redis 库存后要发 MQ，如果发送超时，
--    我们无法区分「broker 没收到」和「broker 收到了但响应丢了」。
--    前一种情况回滚是对的，后一种情况回滚就会超卖。
--    解法是把「要发消息」这件事先落库（同一个唯一键保证一人一单），
--    再由补偿任务按库里的记录重投——投递次数变成至少一次，
--    重复由消费端唯一索引兜住。
--
--    payload 存整条消息快照：重投时必须重发当初那条，
--    不能重新组装（期间商品可能改价，重组的消息金额就变了）。
-- -------------------------------------------------------------
CREATE TABLE IF NOT EXISTS seckill_outbox
(
    id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    activity_id   BIGINT UNSIGNED NOT NULL,
    user_id       BIGINT UNSIGNED NOT NULL,
    payload       VARCHAR(1024)   NOT NULL COMMENT '消息体快照（JSON）',
    status        TINYINT         NOT NULL DEFAULT 0 COMMENT '0 待投递 1 已投递 2 已消费 3 已回滚',
    retry_count   INT             NOT NULL DEFAULT 0 COMMENT '已重投次数',
    next_retry_at DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '下次可重投时间（退避）',
    last_error    VARCHAR(255)    NOT NULL DEFAULT '',
    created_at    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    -- 和 seckill_order 的 uk_user_activity 含义相同：一人一单，
    -- 在这里先挡一道，避免同一用户重复扣 Redis 后落两条 outbox
    UNIQUE KEY uk_user_activity (user_id, activity_id),
    KEY idx_status_retry (status, next_retry_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='秒杀本地消息表（事务性发件箱）';
