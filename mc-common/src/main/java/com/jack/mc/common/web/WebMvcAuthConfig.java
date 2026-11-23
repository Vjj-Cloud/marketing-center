package com.jack.mc.common.web;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 注册权限拦截器。
 * <p>
 * <b>为什么这个类不会被网关加载</b>：网关的启动类是
 * <code>scanBasePackages = {"com.jack.mc.gateway", "com.jack.mc.common.auth"}</code>，
 * 只扫到 <code>common.auth</code>，扫不到 <code>common.web</code>。
 * 这是刻意的——<code>common.web</code> 里全是 Servlet/WebMvc 的东西，
 * 一旦进入 WebFlux 网关的 classpath，Gateway 会在启动时直接报错。
 * 网关只需要 <code>common.auth</code> 里的工具类，不需要这层过滤器与拦截器。
 * <p>
 * <code>WebMvcConfigurer</code> 属于 spring-webmvc，mc-common 里该依赖是
 * <code>optional</code> 的：本体不传递，由真正用到它的 Servlet 服务自己引入。
 */
@Configuration
public class WebMvcAuthConfig implements WebMvcConfigurer {

    private final AdminInterceptor adminInterceptor;

    public WebMvcAuthConfig(AdminInterceptor adminInterceptor) {
        this.adminInterceptor = adminInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 只拦业务接口：/error 这类容器内部转发不能拦，
        // 否则一个真实异常会被二次覆盖成 403，排查时完全看不出原始错误
        registry.addInterceptor(adminInterceptor)
                .addPathPatterns("/api/**");
    }
}
