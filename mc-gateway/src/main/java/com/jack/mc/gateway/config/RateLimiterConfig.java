package com.jack.mc.gateway.config;

import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Mono;

/**
 * 限流 key 的解析策略。
 * <p>
 * 选「按 userId」而不是「按 IP」，原因：
 * <ul>
 *   <li>按 IP 会误伤：一个公司、一个学校、一个小区出口往往共享 NAT IP，
 *       一个人刷脚本会把几百个正常用户一起限死；</li>
 *   <li>按 IP 也防不住：攻击者换个代理 IP 就绕过，而 userId 是登录态绑定的，
 *       想换得先注册新账号，成本高得多。</li>
 * </ul>
 * <p>
 * AuthGlobalFilter 的 order 是 -100，早于这里执行，所以取到的 X-User-Id
 * 一定是网关校验过的值，不会被客户端伪造的请求头污染。
 */
@Configuration
public class RateLimiterConfig {

    @Bean
    public KeyResolver userKeyResolver() {
        return exchange -> {
            String userId = exchange.getRequest().getHeaders().getFirst("X-User-Id");
            if (userId != null && !userId.isBlank()) {
                return Mono.just("user:" + userId);
            }
            // 理论上到不了这里（鉴权已挡），兜底按 IP 分组，
            // 宁可粗一点也不能返回空——空 key 会让所有无身份请求共用一个桶
            String ip = exchange.getRequest().getRemoteAddress() == null
                    ? "unknown"
                    : exchange.getRequest().getRemoteAddress().getAddress().getHostAddress();
            return Mono.just("ip:" + ip);
        };
    }
}
