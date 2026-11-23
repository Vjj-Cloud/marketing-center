-- =============================================================
-- 07 · 活动 7「MQ故障演练」账本对账（第四轮评审遗留数据）
-- 执行：mysql -umall -p --default-character-set=utf8mb4 jianxuan_mall < 07_reconcile_activity7.sql
--
-- 本脚本可重复执行（结果只取决于数据，重复跑不会叠加）。
-- =============================================================

USE jianxuan_mall;

-- -------------------------------------------------------------
-- 现场：
--   活动 7  total_stock=5  available_stock=4  → 账本显示卖出 1 件
--   订单     seckill_order 里该活动 0 行
--   消息     seckill_outbox 里该活动 0 行
--   状态     status=2（已结束），warmup_at 有值
--
-- 差额来自演练期直接改库（把 available_stock 从 5 改成 4，模拟"已卖一件"），
-- 不是消费链路产生的 —— 链路只要扣了库存，就必然先落一行 outbox，
-- 而 outbox 唯一键 uk_user_activity 保证不会静默消失。
--
-- -------------------------------------------------------------
-- 为什么只改活动账本、不动商品库存：
--
--   订单落库是一个事务，顺序固定（OrderPersistService.create）：
--     ① 插 seckill_order ② 扣 product.stock ③ 扣 activity.available_stock ④ outbox → CONSUMED
--   ② ③ 都在 ① 之后，事务回滚会一起撤销。所以「没有订单行」⇒「商品库存没被扣」。
--   既然商品库存是干净的，把活动账本还回 5 就足以让两边重新自洽，
--   再去减/加 product.stock 反而是新的破坏。
--
--   如果将来确认演练期确实手工扣过 product.stock，那才需要补这一句（当前不执行）：
--     UPDATE product SET stock = stock + 1 WHERE id = 16;
-- -------------------------------------------------------------

-- 只在该活动确实没有任何订单时才修，避免误伤真实成交的数据
UPDATE seckill_activity a
SET a.available_stock = a.total_stock
WHERE a.id = 7
  AND a.title = 'MQ故障演练'
  AND NOT EXISTS (SELECT 1 FROM seckill_order o WHERE o.activity_id = a.id);

-- 核对：执行后应为「账本卖出 = 订单数」全部相等
SELECT a.id,
       a.title,
       a.total_stock,
       a.available_stock,
       (a.total_stock - a.available_stock) AS sold_by_ledger,
       (SELECT COUNT(*) FROM seckill_order o WHERE o.activity_id = a.id) AS orders
FROM seckill_activity a
ORDER BY a.id;
