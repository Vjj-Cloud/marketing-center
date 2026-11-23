package com.jack.mc.common.web;

import com.jack.mc.common.auth.GatewaySigner;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

/**
 * 下游服务的身份过滤器：只接受「经过网关签名」的请求。
 * <p>
 * <b>为什么必须有这一层</b>：网关把 userId 放进 X-User-Id 头传给下游，
 * 但 HTTP 头是客户端随便写的。只要 8082 端口可达（开发机上默认就是可达的），
 * 攻击者直连 8082 传 <code>X-User-Id: 1</code> 就能冒充任何人，
 * 「一人一单」直接失效。所以下游不能信任头，只能信任签名。
 * <p>
 * <b>为什么用 Filter 而不是 HandlerInterceptor</b>：本项目公共模块只有 spring-web，
 * 没有 spring-webmvc。而 Gateway 是 WebFlux 的，一旦让 spring-webmvc 进入它的
 * classpath，网关会直接启动失败（Spring Cloud Gateway 有硬性检查）。
 * Filter 只需要 jakarta.servlet-api，不引入 WebMvc，是唯一安全的选择。
 * <p>
 * <b>注意本包不会被网关扫描到</b>：网关的启动类是 com.jack.mc.gateway，
 * 组件扫描范围不含 com.jack.mc.common。因此这个 Filter 只在
 * mc-seckill / mc-order 这类 Servlet 服务里生效——这是刻意的。
 */
@Component
public class GatewayAuthFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(GatewayAuthFilter.class);

    private static final String HEADER_USER = "X-User-Id";
    private static final String HEADER_ROLE = "X-User-Role";
    private static final String HEADER_TS = "X-Gateway-Ts";
    private static final String HEADER_SIGN = "X-Gateway-Sign";

    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    private final String secret;

    private final long signWindowSeconds;

    private final List<String> whitelist;

    public GatewayAuthFilter(@Value("${mc.auth.secret}") String secret,
                             @Value("${mc.auth.sign-window-seconds:300}") long signWindowSeconds,
                             @Value("${mc.auth.whitelist:/api/auth/token,/api/seckill/ping,/api/order/ping}")
                             String whitelistRaw) {
        this.secret = secret;
        this.signWindowSeconds = signWindowSeconds;
        this.whitelist = Arrays.stream(whitelistRaw.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        // 只保护业务接口；/error 这类容器内部转发不拦，否则异常会被二次覆盖成 401
        if (!path.startsWith("/api/")) {
            return true;
        }
        return whitelist.stream().anyMatch(p -> pathMatcher.match(p, path));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String userId = request.getHeader(HEADER_USER);
        // 角色与 userId 一起验签：只签身份不签权限，攻击者直连时照样能塞 ADMIN
        String role = request.getHeader(HEADER_ROLE);
        String timestamp = request.getHeader(HEADER_TS);
        String sign = request.getHeader(HEADER_SIGN);

        if (!GatewaySigner.verify(secret, userId, role, timestamp, sign, signWindowSeconds)) {
            log.warn("拒绝未签名的下游请求 uri={} userId={} role={} remote={}",
                    request.getRequestURI(), userId, role, request.getRemoteAddr());
            reject(response, "身份校验失败，请通过网关访问");
            return;
        }
        chain.doFilter(request, response);
    }

    private void reject(HttpServletResponse response, String message) throws IOException {
        // 这里不能用异常处理器：Filter 在 DispatcherServlet 之前执行，
        // 抛出的异常到不了 @RestControllerAdvice，所以必须自己写响应
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(
                "{\"code\":401,\"message\":\"" + message + "\",\"data\":null}");
    }
}
