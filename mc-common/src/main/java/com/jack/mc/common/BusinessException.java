package com.jack.mc.common;

/**
 * 业务异常。
 * <p>
 * code 直接复用 HTTP 状态码语义（400/403/404/409/429），
 * 由 GlobalExceptionHandler 原样写回响应，避免「业务失败也返回 200」的坑。
 */
public class BusinessException extends RuntimeException {

    private final int code;

    public BusinessException(String message) {
        this(400, message);
    }

    public BusinessException(int code, String message) {
        super(message);
        this.code = code;
    }

    public int getCode() {
        return code;
    }
}
