package com.jack.mc.common.web;

import com.jack.mc.common.auth.JwtUtils;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 管理员权限拦截器：执行 {@link RequireAdmin} 的声明。
 * <p>
 * <b>执行顺序的讲究</b>：拦截器在 HandlerAdapter 调用 Controller 之前触发，
 * 所以权限判断永远早于参数校验和业务校验。这一点在评审里被单独指出：
 * 普通用户请求 <code>POST /api/seckill/activity/999999/publish</code> 时，
 * 业务层会先抛「活动不存在」(400)，等于告诉攻击者「这个 id 不存在」；
 * 改成先鉴权后校验，返回的是 403，不泄露任何资源存在性信息。
 * <p>
 * <b>角色从哪来</b>：只读网关写入的 X-User-Role 头，而且这个头被 HMAC 签名覆盖
 * （见 {@code GatewaySigner}）。直连 8082 自己塞 <code>X-User-Role: ADMIN</code>
 * 会因为签名对不上被 {@code GatewayAuthFilter} 拒在门外，走不到这里。
 * <p>
 * <b>为什么不读 JWT 自己解析</b>：下游再解析一次 token 意味着密钥要在每个服务
 * 各存一份、各自处理过期与轮换，一旦某个服务密钥没跟上就出现
 * 「网关能过、A 服务能过、B 服务过不了」的碎片化状态。身份只认网关。
 */
@Component
public class AdminInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(AdminInterceptor.class);

    /** 与 GatewayAuthFilter / 网关写入的头名保持一致 */
    public static final String HEADER_ROLE = "X-User-Role";

    public static final String HEADER_USER = "X-User-Id";

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
                             Object handler) throws IOException {
        if (!(handler instanceof HandlerMethod method)) {
            // 静态资源、错误页转发等不是 HandlerMethod，直接放行
            return true;
        }
        boolean required = method.getMethodAnnotation(RequireAdmin.class) != null
                || method.getBeanType().isAnnotationPresent(RequireAdmin.class);
        if (!required) {
            return true;
        }

        String role = request.getHeader(HEADER_ROLE);
        if (JwtUtils.isAdmin(role)) {
            return true;
        }

        log.warn("拒绝非管理员访问管理接口 uri={} userId={} role={}",
                request.getRequestURI(), request.getHeader(HEADER_USER), role);
        reject(response, "需要管理员权限");
        return false;
    }

    private void reject(HttpServletResponse response, String message) throws IOException {
        // 与 GatewayAuthFilter 同理：拦截器也在 DispatcherServlet 的异常处理链之外，
        // 抛异常到不了 @RestControllerAdvice，必须自己写响应体
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(
                "{\"code\":403,\"message\":\"" + message + "\",\"data\":null}");
    }
}
