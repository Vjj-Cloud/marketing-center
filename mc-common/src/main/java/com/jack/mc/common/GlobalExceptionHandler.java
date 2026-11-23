package com.jack.mc.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 全局异常处理。
 * <p>
 * 四个要点，面试常被追问：
 * <ol>
 *   <li>业务异常返回真实 HTTP 状态码，不用「一律 200 + code」那种做法，
 *       否则网关、监控、前端拦截器全都判断不了成败；</li>
 *   <li><b>客户端错误必须映射成 4xx，不能落到兜底的 500</b>。
 *       一段语法错误的 JSON、一个 <code>/order/abc</code> 的路径参数，
 *       都是调用方的问题。如果统一返回 500，监控会把它算成服务端故障，
 *       告警一响运维半夜起来查，最后发现是对方传错了参数；</li>
 *   <li>兜底分支只接 Exception，且必须打日志——宁可日志多，不可线上查不到；</li>
 *   <li>兜底的日志内容不能回给客户端。把堆栈或 SQL 报错返回前端，
 *       等于把表结构送给攻击者。</li>
 * </ol>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Result<Void>> handleBusiness(BusinessException e) {
        HttpStatus status = HttpStatus.resolve(e.getCode());
        if (status == null) {
            status = HttpStatus.BAD_REQUEST;
        }
        return ResponseEntity.status(status).body(Result.fail(e.getCode(), e.getMessage()));
    }

    /** @Valid 校验失败 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Result<Void>> handleInvalid(MethodArgumentNotValidException e) {
        FieldError first = e.getBindingResult().getFieldError();
        String message = first == null ? "请求参数不合法" : first.getField() + " " + first.getDefaultMessage();
        return ResponseEntity.badRequest().body(Result.fail(400, message));
    }

    /** 表单/Query 参数绑定失败 */
    @ExceptionHandler(BindException.class)
    public ResponseEntity<Result<Void>> handleBind(BindException e) {
        FieldError first = e.getBindingResult().getFieldError();
        String message = first == null ? "请求参数不合法" : first.getField() + " " + first.getDefaultMessage();
        return ResponseEntity.badRequest().body(Result.fail(400, message));
    }

    /**
     * 请求体不是合法 JSON、或字段类型对不上（比如给数字字段传了 "abc"）。
     * <p>
     * 这类异常在没有任何专门处理器时，会被 Exception 兜底吃掉变成 500，
     * 是最典型的「客户端错误被当成服务端故障」。
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Result<Void>> handleUnreadable(HttpMessageNotReadableException e) {
        log.warn("请求体解析失败: {}", e.getMessage());
        return ResponseEntity.badRequest().body(Result.fail(400, "请求体格式错误，需要合法的 JSON"));
    }

    /** 路径参数 / Query 参数类型不匹配，例如 /api/order/result?activityId=abc */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Result<Void>> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        return ResponseEntity.badRequest()
                .body(Result.fail(400, "参数 " + e.getName() + " 类型不正确"));
    }

    /** 缺少必填请求头，例如没带 X-User-Id */
    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<Result<Void>> handleMissingHeader(MissingRequestHeaderException e) {
        return ResponseEntity.badRequest()
                .body(Result.fail(400, "缺少请求头 " + e.getHeaderName()));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<Result<Void>> handleMissingParam(MissingServletRequestParameterException e) {
        return ResponseEntity.badRequest()
                .body(Result.fail(400, "缺少参数 " + e.getParameterName()));
    }

    /** 方法不支持，例如对只支持 POST 的接口发了 GET */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Result<Void>> handleMethodNotSupported(HttpRequestMethodNotSupportedException e) {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .body(Result.fail(405, "接口不支持 " + e.getMethod() + " 方法"));
    }

    /** Content-Type 不支持，例如把 form 提交给 @RequestBody 接口 */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<Result<Void>> handleMediaType(HttpMediaTypeNotSupportedException e) {
        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
                .body(Result.fail(415, "不支持的 Content-Type：" + e.getContentType()));
    }

    /**
     * 路径不存在。Spring 6.1 起静态资源找不到会抛 NoResourceFoundException，
     * 它的父类 NoHandlerFoundException 覆盖了「没有匹配的 Controller」的情况。
     * <p>
     * 不处理的话，用户把 /api/order/result 拼成 /api/order/results，
     * 拿到的会是 500 而不是 404——前端会以为服务挂了。
     */
    @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
    public ResponseEntity<Result<Void>> handleNotFound(Exception e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Result.fail(404, "接口不存在"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<Void>> handleUnknown(Exception e) {
        log.error("未处理异常", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Result.fail(500, "系统繁忙，请稍后重试"));
    }
}
