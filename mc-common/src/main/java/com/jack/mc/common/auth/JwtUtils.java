package com.jack.mc.common.auth;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

/**
 * JWT 签发与校验。
 * <p>
 * 放在 mc-common 是因为网关和业务服务都要用：网关负责校验（唯一可信的身份入口），
 * 业务服务负责签发（本项目的 /api/auth/token 只是联调用的模拟登录，
 * 真实项目里这一步在统一认证服务）。
 * <p>
 * 用对称密钥（HS256）而不是 RSA：单体拆出来的几个服务共享同一个密钥就够了，
 * 非对称签名的好处是「验签方拿不到签发密钥」，只有在对方是不可信第三方时才必要。
 */
@Component
public class JwtUtils {

    /** 用户 ID 的 claim 名，独立于 sub，避免 subject 里塞数字引发解析歧义 */
    public static final String CLAIM_USER_ID = "uid";

    /**
     * 角色 claim。
     * <p>
     * <b>角色必须写在 token 里，不能由下游自己查</b>：如果下游收到 userId 再去查
     * 一次「他是不是管理员」，热路径上每次都要多一次查询，而且等于让每个服务
     * 各自实现一遍权限判断，口径迟早不一致。签进 token、网关验签、下游只读，
     * 只有一条路径。
     */
    public static final String CLAIM_ROLE = "role";

    public static final String ROLE_USER = "USER";

    public static final String ROLE_ADMIN = "ADMIN";

    private final SecretKey key;

    private final long expireSeconds;

    public JwtUtils(@Value("${mc.auth.secret}") String secret,
                    @Value("${mc.auth.expire-seconds:7200}") long expireSeconds) {
        // HS256 要求密钥长度 >= 256 bit（32 字节），短了 jjwt 会直接抛
        // WeakKeyException。这个限制是有意义的：短密钥能被离线暴力破解。
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expireSeconds = expireSeconds;
    }

    public String issue(Long userId, String nickname, String role) {
        Date now = new Date();
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim(CLAIM_USER_ID, userId)
                .claim(CLAIM_ROLE, normalizeRole(role))
                .claim("nick", nickname == null ? "" : nickname)
                .issuedAt(now)
                .expiration(new Date(now.getTime() + expireSeconds * 1000))
                .signWith(key)
                .compact();
    }

    /**
     * 角色白名单化。
     * <p>
     * 任何来源（token 载荷、请求头）的角色字符串都必须过这一道：
     * 只认 USER / ADMIN，其它一律降级成 USER。
     * 不做归一化的话，<code>admin</code>（小写）、<code>ROLE_ADMIN</code>
     * 这类写法会因为大小写不一致而在某个服务上意外通过或意外失败——
     * 权限判断最怕的不是拒绝，是不一致。
     */
    public static String normalizeRole(String role) {
        if (ROLE_ADMIN.equalsIgnoreCase(role)) {
            return ROLE_ADMIN;
        }
        return ROLE_USER;
    }

    public static boolean isAdmin(String role) {
        return ROLE_ADMIN.equals(normalizeRole(role));
    }

    /**
     * 解析并校验签名与有效期。
     *
     * @return 载荷；签名错误、格式错误、已过期都返回 null
     * @throws JwtException 不向外抛——调用方（网关）只需要知道「这个 token 不可信」，
     *                      具体是过期还是伪造不影响处理方式（都是 401）
     */
    public Claims parse(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        try {
            return Jwts.parser().verifyWith(key).build()
                    .parseSignedClaims(token).getPayload();
        } catch (JwtException | IllegalArgumentException e) {
            return null;
        }
    }

    public long getExpireSeconds() {
        return expireSeconds;
    }
}
