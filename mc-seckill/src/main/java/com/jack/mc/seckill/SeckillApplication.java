package com.jack.mc.seckill;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 秒杀服务。
 * <p>
 * scanBasePackages 放大到 com.jack.mc，是为了让 mc-common 里的
 * GlobalExceptionHandler 被扫到——它不在本服务的包下，默认扫不到。
 * <p>
 * @EnableScheduling 是给库存预热定时任务用的。不加这个注解，
 * @Scheduled 方法不会报错，但也永远不会执行——属于典型的「静默失效」。
 */
@SpringBootApplication(scanBasePackages = "com.jack.mc")
@EnableDiscoveryClient
@EnableScheduling
public class SeckillApplication {

    public static void main(String[] args) {
        SpringApplication.run(SeckillApplication.class, args);
    }
}
