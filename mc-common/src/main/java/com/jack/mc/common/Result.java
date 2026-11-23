package com.jack.mc.common;

/**
 * 统一返回体。
 * <p>
 * 和商城项目保持同一套形状（code / message / data），前端换项目不用改解析逻辑。
 * 注意 code 是业务码，不是 HTTP 状态码——真正的 HTTP 状态码由异常处理器决定，
 * 两者分开才能让「接口语义」和「HTTP 语义」各自正确。
 */
public class Result<T> {

    private int code;
    private String message;
    private T data;

    public Result() {
    }

    public Result(int code, String message, T data) {
        this.code = code;
        this.message = message;
        this.data = data;
    }

    public static <T> Result<T> ok(T data) {
        return new Result<>(200, "success", data);
    }

    public static <T> Result<T> ok() {
        return new Result<>(200, "success", null);
    }

    public static <T> Result<T> fail(int code, String message) {
        return new Result<>(code, message, null);
    }

    public int getCode() {
        return code;
    }

    public void setCode(int code) {
        this.code = code;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public T getData() {
        return data;
    }

    public void setData(T data) {
        this.data = data;
    }
}
