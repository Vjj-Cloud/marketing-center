package com.jack.mc.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

/**
 * 网关。
 * <p>
 * <b>扫描范围为什么只到 com.jack.mc.gateway + com.jack.mc.common.auth</b>：
 * <ul>
 *   <li>不能扫整个 com.jack.mc：mc-common 里有 @RestControllerAdvice 和
 *       基于 Servlet 的 Filter，那是 Servlet 栈的东西。网关是 WebFlux，
 *       既没有 servlet-api 也没有 DispatcherServlet，扫进来会直接启动失败
 *       （<code>NoClassDefFoundError: jakarta/servlet/Filter</code>）。</li>
 *   <li>但要单独把 common.auth 扫进来：JwtUtils 是 @Component，
 *       不扫就注入不到 AuthGlobalFilter，报
 *       <code>No qualifying bean of type JwtUtils</code>。
 *       这个包只有 JwtUtils 和纯静态的 GatewaySigner，不碰 Servlet，是安全的。</li>
 * </ul>
 * 这条边界很脆：以后往 common.auth 里加任何 Servlet 相关的类，
 * 网关就会起不来。所以真要扩展，应该新开一个 mc-auth 模块，而不是往这个包里塞。
 */
@SpringBootApplication(scanBasePackages = {"com.jack.mc.gateway", "com.jack.mc.common.auth"})
@EnableDiscoveryClient
public class GatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }
}
