package com.jack.mc.order;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 订单服务：消费秒杀 MQ，落库，提供结果查询。
 * <p>
 * scanBasePackages 放大到 com.jack.mc，是为了让 mc-common 里的
 * GlobalExceptionHandler 和 GatewayAuthFilter 被扫到。
 * <p>
 * @EnableScheduling 是给消费者的「启动失败重试」用的：
 * MQ 短暂不可用时服务照常启动，由定时任务把消费者拉起来。
 */
@SpringBootApplication(scanBasePackages = "com.jack.mc")
@EnableDiscoveryClient
@EnableScheduling
public class OrderApplication {

    public static void main(String[] args) {
        SpringApplication.run(OrderApplication.class, args);
    }
}
