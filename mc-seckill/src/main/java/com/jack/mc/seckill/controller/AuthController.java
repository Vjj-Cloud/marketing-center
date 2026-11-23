package com.jack.mc.seckill.controller;

import com.jack.mc.common.Result;
import com.jack.mc.common.auth.JwtUtils;
import com.jack.mc.seckill.dto.TokenRequest;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 联调用的模拟登录。
 * <p>
 * <b>这不是真实登录</b>：真实项目里账号密码校验、验证码、风控都在独立的认证服务，
 * 这里只负责「用 userId 换一个 JWT」，目的是让鉴权链路可测——
 * 否则网关一开鉴权，所有接口都调不通，反而没法验证并发正确性。
 * <p>
 * <b>为什么要加开关（第二轮评审 P1）</b>：这个接口没有任何凭据校验，
 * 只要服务可达，任何人都能签发任意 userId、任意角色的 token，
 * 「一人一单」「管理员才能发布活动」全都会被绕过。
 * 但直接删掉又会让联调和压测无法进行，所以改成
 * <code>mc.auth.mock-login-enabled=true</code> 才注册这个 Bean，
 * 默认不配置即关闭。生产环境不配这一项，这个接口连路由都不存在（404，不是 403）。
 * <p>
 * <b>为什么用 CONDITIONAL 而不是 @Profile("dev")</b>：profile 是「环境」概念，
 * 而这是「危险开关」概念。用 profile 控制的话，只要有人用
 * <code>--spring.profiles.active=dev</code> 起了生产实例就全开了；
 * 用属性控制则必须显式在配置文件里写下这一行，评审和 diff 时看得见。
 * <p>
 * 把这个类放在秒杀服务而不是网关：网关是 WebFlux 的，不便写业务接口；
 * 而且它只是临时设施，跟着一个服务走更好清理。
 */
@RestController
@RequestMapping("/api/auth")
@ConditionalOnProperty(prefix = "mc.auth", name = "mock-login-enabled", havingValue = "true")
public class AuthController {

    private final JwtUtils jwtUtils;

    public AuthController(JwtUtils jwtUtils) {
        this.jwtUtils = jwtUtils;
    }

    @PostMapping("/token")
    public Result<Map<String, Object>> token(@Valid @RequestBody TokenRequest request) {
        String role = JwtUtils.normalizeRole(request.getRole());
        String token = jwtUtils.issue(request.getUserId(), request.getNickname(), role);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("token", token);
        data.put("userId", request.getUserId());
        data.put("role", role);
        data.put("expiresIn", jwtUtils.getExpireSeconds());
        return Result.ok(data);
    }

    /**
     * 回显当前身份。
     * <p>
     * 这个接口的价值在排查：它返回的 userId / role 都来自网关写入的头，
     * 如果前端明明登录了 A 用户却查到 B，一眼就能看出是网关透传的问题，
     * 而不是去翻业务代码。
     */
    @GetMapping("/me")
    public Result<Map<String, Object>> me(@RequestHeader("X-User-Id") Long userId,
                                          @RequestHeader(value = "X-User-Role", required = false) String role) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("userId", userId);
        data.put("role", JwtUtils.normalizeRole(role));
        return Result.ok(data);
    }
}
