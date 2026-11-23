package com.jack.mc.gateway.filter;

import com.jack.mc.common.auth.GatewaySigner;
import com.jack.mc.common.auth.JwtUtils;
import io.jsonwebtoken.Claims;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

/**
 * 网关鉴权：全系统唯一的身份入口。
 * <p>
 * 做三件事：
 * <ol>
 *   <li>校验 Authorization: Bearer &lt;jwt&gt;，拿到可信的 userId；</li>
 *   <li><b>先删除客户端自带的 X-User-Id / X-Gateway-*</b>，再写入网关自己的值。
 *       顺序不能反：如果只 set 不 remove，有些实现会保留原值形成两个同名头，
 *       下游 getHeader 取到手的是哪一个取决于容器实现——这就是越权的口子。</li>
 *   <li>给下游打一个 HMAC 签名（含时间戳），让下游能确认「这个 userId 是网关写的」，
 *       而不是攻击者直连 8082 自己写的。</li>
 * </ol>
 * <p>
 * 为什么身份校验只能放在网关：如果每个服务各自解析 token，一旦某个服务的
 * 密钥轮换没跟上，就出现「网关上能过、A 服务能过、B 服务过不了」的碎片化状态，
 * 排查成本极高。统一入口 + 下游只认签名，职责才清晰。
 */
@Component
public class AuthGlobalFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(AuthGlobalFilter.class);

    private static final String HEADER_USER = "X-User-Id";
    private static final String HEADER_ROLE = "X-User-Role";
    private static final String HEADER_TS = "X-Gateway-Ts";
    private static final String HEADER_SIGN = "X-Gateway-Sign";

    private final JwtUtils jwtUtils;
    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    @Value("${mc.auth.secret}")
    private String secret;

    @Value("${mc.auth.whitelist:/api/auth/**}")
    private String whitelistRaw;

    public AuthGlobalFilter(JwtUtils jwtUtils) {
        this.jwtUtils = jwtUtils;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getPath().value();

        // 白名单路径也要清掉客户端伪造的身份头，避免「不鉴权的接口反而能自定义身份」
        if (isWhitelisted(path)) {
            return chain.filter(exchange.mutate().request(stripIdentity(exchange.getRequest())).build());
        }

        String authorization = exchange.getRequest().getHeaders().getFirst("Authorization");
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            return reject(exchange, "未登录或 token 缺失");
        }

        Claims claims = jwtUtils.parse(authorization.substring(7).trim());
        if (claims == null) {
            return reject(exchange, "登录态已失效，请重新登录");
        }

        Long userId;
        try {
            userId = claims.get(JwtUtils.CLAIM_USER_ID, Long.class);
        } catch (Exception e) {
            // token 签名是对的但载荷里没有 uid，说明是别的系统签发的，不能放行
            log.warn("token 载荷缺少 uid, subject={}", claims.getSubject());
            return reject(exchange, "登录态无效");
        }
        if (userId == null || userId <= 0) {
            return reject(exchange, "登录态无效");
        }

        // 角色同样来自 token 载荷，并且必须被签名覆盖：
        // 只签 userId 的话，下游能验出「身份是真的」，却验不出「角色是真的」，
        // 直连时塞一个 X-User-Role: ADMIN 就能调管理接口。
        String role = JwtUtils.normalizeRole(readRole(claims));

        String ts = String.valueOf(System.currentTimeMillis() / 1000);
        String sign = GatewaySigner.sign(secret, String.valueOf(userId), role, ts);

        ServerHttpRequest request = exchange.getRequest().mutate()
                .headers(headers -> {
                    headers.remove(HEADER_USER);
                    headers.remove(HEADER_ROLE);
                    headers.remove(HEADER_TS);
                    headers.remove(HEADER_SIGN);
                    headers.set(HEADER_USER, String.valueOf(userId));
                    headers.set(HEADER_ROLE, role);
                    headers.set(HEADER_TS, ts);
                    headers.set(HEADER_SIGN, sign);
                })
                .build();

        return chain.filter(exchange.mutate().request(request).build());
    }

    /**
     * 取 token 里的角色。老 token（升级前签发）没有 role claim，
     * 一律按普通用户处理——放开成 ADMIN 是最糟糕的兼容方式。
     */
    private String readRole(Claims claims) {
        try {
            return claims.get(JwtUtils.CLAIM_ROLE, String.class);
        } catch (Exception e) {
            log.warn("token 载荷中的 role 无法解析，按普通用户处理 subject={}", claims.getSubject());
            return JwtUtils.ROLE_USER;
        }
    }

    private ServerHttpRequest stripIdentity(ServerHttpRequest request) {
        return request.mutate()
                .headers(headers -> {
                    headers.remove(HEADER_USER);
                    // 角色头也必须一起删：白名单接口同样不能被伪造身份或提权
                    headers.remove(HEADER_ROLE);
                    headers.remove(HEADER_TS);
                    headers.remove(HEADER_SIGN);
                })
                .build();
    }

    private boolean isWhitelisted(String path) {
        List<String> patterns = Arrays.stream(whitelistRaw.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
        return patterns.stream().anyMatch(p -> pathMatcher.match(p, path));
    }

    private Mono<Void> reject(ServerWebExchange exchange, String message) {
        exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        byte[] body = ("{\"code\":401,\"message\":\"" + message + "\",\"data\":null}")
                .getBytes(StandardCharsets.UTF_8);
        DataBuffer buffer = exchange.getResponse().bufferFactory().wrap(body);
        return exchange.getResponse().writeWith(Mono.just(buffer));
    }

    /**
     * 必须是负数且排在所有路由过滤器之前：RequestRateLimiter 要读 X-User-Id
     * 作为限流 key，如果鉴权后执行，限流器拿到的就是客户端伪造的值，
     * 换一个头就能绕过限流。
     */
    @Override
    public int getOrder() {
        return -100;
    }
}
