package com.jack.mc.common.auth;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * 网关到下游服务的身份签名。
 * <p>
 * <b>要解决的问题</b>：网关校验完 JWT 后，把 userId 放进 X-User-Id 传给下游。
 * 如果下游无条件信任这个头，攻击者绕过网关直连 8082 端口，
 * 自己塞一个 X-User-Id 就能冒充任意用户——一人一单形同虚设。
 * <p>
 * <b>做法</b>：网关在转发时附带时间戳与 HMAC 签名
 * <pre>sign = HMAC-SHA256(secret, userId + "|" + role + "|" + timestamp)</pre>
 * 下游用同一个密钥验签，并检查时间戳在 ±300 秒内（防重放）。
 * 攻击者不知道密钥就伪造不出签名，因此拿不到有效身份。
 * <p>
 * <b>role 为什么必须进签名串</b>：这是第二轮评审补的。如果只签 userId，
 * 下游能验出「这个 userId 是真的」，但验不出「这个角色是真的」——
 * 攻击者直连时照样可以塞 <code>X-User-Role: ADMIN</code>，
 * 于是「管理员才能发布活动」变成一句空话。
 * 凡是下游会据以做决策的字段，都必须被签名覆盖，不能只签身份不签权限。
 * <p>
 * <b>为什么还要校验时间戳</b>：只验签名的话，一次抓包得到的
 * (userId, role, timestamp, sign) 可以被无限重放。加时间窗后，
 * 该四元组最多在窗口内有效；窗口越短越安全，但要容忍机器间时钟偏差，
 * 5 分钟是常见折中。
 * <p>
 * 更彻底的做法是内网隔离（下游只监听内网网卡）+ mTLS，
 * 但那是运维层面的约定，代码层面留这道签名能防住「端口暴露」这类常见事故。
 */
public final class GatewaySigner {

    private static final String ALGORITHM = "HmacSHA256";

    private GatewaySigner() {
    }

    public static String sign(String secret, String userId, String role, String timestamp) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM));
            String payload = userId + "|" + nullToUser(role) + "|" + timestamp;
            byte[] raw = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(raw);
        } catch (Exception e) {
            // 算法固定存在、密钥长度任意，这里实际上不会失败；
            // 真失败了也绝不能让请求「降级为通过」
            throw new IllegalStateException("计算网关签名失败", e);
        }
    }

    /**
     * 校验签名 + 时间窗。
     * <p>
     * 比较签名用 {@link MessageDigest#isEqual} 而不是 String.equals：
     * 后者一旦发现第一个不同字符就返回，攻击者可以通过响应时间差
     * 逐字节猜出正确签名（timing attack）。isEqual 是恒定时间比较。
     */
    public static boolean verify(String secret, String userId, String role, String timestamp,
                                 String sign, long windowSeconds) {
        if (userId == null || timestamp == null || sign == null) {
            return false;
        }
        long ts;
        try {
            ts = Long.parseLong(timestamp);
        } catch (NumberFormatException e) {
            return false;
        }
        long now = System.currentTimeMillis() / 1000;
        if (Math.abs(now - ts) > windowSeconds) {
            return false;
        }
        String expected = sign(secret, userId, role, timestamp);
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                sign.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 签名侧和验签侧必须对「role 缺省」取同一个值，否则老客户端不带 role 时
     * 两边算出的签名串不一样，会表现成「随机 401」。
     */
    private static String nullToUser(String role) {
        return (role == null || role.isBlank()) ? JwtUtils.ROLE_USER : role;
    }
}
