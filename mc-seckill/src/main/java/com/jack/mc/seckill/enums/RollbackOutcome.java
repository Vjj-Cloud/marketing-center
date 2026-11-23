package com.jack.mc.seckill.enums;

/**
 * 库存回滚的执行结果。
 * <p>
 * <b>为什么必须三态（第三轮评审 P1）</b>：上一版 {@code rollback} 返回 boolean，
 * 把「用户本来就不在买家集合（无需回滚）」和「Redis 不可用（没做成）」混成同一个 false。
 * 管理接口拿到 false 无从分辨，只能照样把记录标成 ROLLED_BACK ——
 * 于是 Redis 抖一下，就出现「界面显示已回滚、库存其实一件没还」的记录，
 * 而这笔库存既不会自己回来，也没有任何任务会再碰它。
 * <p>
 * 三态把「业务上无需动作」和「基础设施失败」分开：
 * 前者可以安全收尾，后者必须留在待复核队列里让人重试。
 */
public enum RollbackOutcome {

    /** 确实把库存加回去了 */
    RESTORED,

    /**
     * 安全无操作：用户不在买家集合（重复回滚），或库存 key 已过期。
     * 两种情况都已经没有可回滚的东西，可以正常收尾。
     */
    NOTHING_TO_DO,

    /**
     * 基础设施失败：Redis 执行异常，库存状态未知。
     * <b>绝不能当成成功</b>——必须保持待复核状态，等 Redis 恢复后重试。
     */
    FAILED;

    public boolean isSuccess() {
        return this != FAILED;
    }
}
