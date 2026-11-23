package com.jack.mc.seckill.enums;

/**
 * 秒杀扣减结果。取值与 Lua 脚本的返回值一一对应。
 * <p>
 * 不要用 int 在业务层传来传去：0 和 -1 这种魔法数字，一旦有人把
 * 「库存不足」和「重复购买」的判断写反，前端提示就会变成
 * 「您已购买过」却实际是卖光了，用户直接投诉。
 */
public enum DeductResult {

    /** 扣减成功，可以进入异步下单 */
    SUCCESS(1, "抢购成功，正在为您生成订单"),

    /** 库存不足 */
    SOLD_OUT(0, "手慢了，商品已抢完"),

    /** 该用户已经抢到过 */
    REPEATED(-1, "您已经抢到该商品，不能重复购买"),

    /** Redis 里没有库存，说明活动未预热 */
    NOT_WARMED(-2, "活动尚未开始"),

    /** 脚本返回了预期外的值 */
    UNKNOWN(99, "系统繁忙，请稍后重试");

    private final long code;
    private final String message;

    DeductResult(long code, String message) {
        this.code = code;
        this.message = message;
    }

    public long getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }

    public static DeductResult of(Long code) {
        if (code == null) {
            return UNKNOWN;
        }
        for (DeductResult r : values()) {
            if (r.code == code) {
                return r;
            }
        }
        return UNKNOWN;
    }
}
