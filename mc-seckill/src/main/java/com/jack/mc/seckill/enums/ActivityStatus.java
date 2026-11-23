package com.jack.mc.seckill.enums;

/**
 * 活动状态。
 * <p>
 * 用枚举而不是散落的魔法数字，是因为状态判断会出现在发布、预热、抢购、结束四个地方，
 * 只要有一处写错数字，就会出现「草稿活动也能抢」这种线上事故。
 */
public enum ActivityStatus {

    /** 草稿：可编辑，不预热，不可抢 */
    DRAFT(0),

    /** 已发布：预热库存，可抢 */
    PUBLISHED(1),

    /** 已结束：不再预热，抢购返回已结束 */
    FINISHED(2);

    private final int code;

    ActivityStatus(int code) {
        this.code = code;
    }

    public int getCode() {
        return code;
    }

    public boolean is(int code) {
        return this.code == code;
    }
}
