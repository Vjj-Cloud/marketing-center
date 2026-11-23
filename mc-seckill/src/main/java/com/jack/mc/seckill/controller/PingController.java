package com.jack.mc.seckill.controller;

import com.jack.mc.common.Result;
import com.jack.mc.common.web.RequireAdmin;
import com.jack.mc.seckill.mq.MqProducer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 环境自检接口。
 * Day1 用它确认三件事：服务活着、注册到 Nacos 了、MQ 能发出去。
 * 业务接口写完后这些自检接口可以留着，压测时用来确认环境。
 */
@RestController
@RequestMapping("/api/seckill")
public class PingController {

    private final MqProducer mqProducer;

    @Value("${spring.application.name}")
    private String appName;

    public PingController(MqProducer mqProducer) {
        this.mqProducer = mqProducer;
    }

    @GetMapping("/ping")
    public Result<Map<String, Object>> ping() {
        return Result.ok(Map.of(
                "service", appName,
                "status", "up",
                "time", System.currentTimeMillis()
        ));
    }

    /**
     * 往 MQ 发一条探针消息。
     * <p>
     * <b>必须限管理员（第四轮评审 P2）</b>：这是唯一一个「任何登录用户都能触发
     * 一次 MQ 写入」的入口。虽然消费者不订阅 smoke 标签、没有业务副作用，
     * 但让普通用户能往共享 topic 里灌消息，属于可被滥用的攻击面——
     * 轻则刷爆 broker 存储，重则给后续排查制造噪音。
     */
    @RequireAdmin
    @GetMapping("/mq-test")
    public Result<String> mqTest() {
        boolean ok = mqProducer.send("SeckillOrderTopic", "smoke",
                "ping-" + System.currentTimeMillis(), "{\"smoke\":true}");
        return ok ? Result.ok("MQ 发送成功") : Result.fail(500, "MQ 发送失败，检查 broker 状态");
    }
}
