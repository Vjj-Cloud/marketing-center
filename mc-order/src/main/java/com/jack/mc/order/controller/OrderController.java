package com.jack.mc.order.controller;

import com.jack.mc.common.Result;
import com.jack.mc.common.constant.SeckillKeys;
import com.jack.mc.order.entity.SeckillOrder;
import com.jack.mc.order.mapper.OrderMapper;
import com.jack.mc.order.vo.OrderResultVO;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 订单查询。
 * <p>
 * 抢购接口只返回「排队中」，真正的订单号在这里查——这是异步下单的必然结果，
 * 也是对用户诚实：不能承诺「秒出订单号」，因为消息可能还在路上。
 * <p>
 * 查询为什么不放在秒杀服务：订单数据的归属方是订单服务。
 * 让秒杀服务直连订单表虽然在本项目里做得到（同一个库），
 * 但那会让「服务边界」变成一句空话，一旦真的拆库就会全部失效。
 */
@RestController
@RequestMapping("/api/order")
public class OrderController {

    private final OrderMapper orderMapper;
    private final StringRedisTemplate redis;

    public OrderController(OrderMapper orderMapper, StringRedisTemplate redis) {
        this.orderMapper = orderMapper;
        this.redis = redis;
    }

    @GetMapping("/result")
    public Result<OrderResultVO> result(@RequestParam Long activityId,
                                        @RequestHeader("X-User-Id") Long userId) {
        SeckillOrder order = orderMapper.findByUserAndActivity(userId, activityId);
        if (order != null) {
            return Result.ok(toSuccess(order));
        }

        // 订单还没落库。判断用户到底有没有抢到，要看 Redis 的买家集合：
        // 在集合里 = 已经扣了库存、消息还在路上 → 排队中
        // 不在集合里 = 根本没抢到（或重试耗尽已回滚） → 无记录
        Boolean reserved = redis.opsForSet().isMember(SeckillKeys.bought(activityId), String.valueOf(userId));
        if (Boolean.TRUE.equals(reserved)) {
            return Result.ok(OrderResultVO.of("QUEUING", "订单生成中，请稍后刷新"));
        }
        return Result.ok(OrderResultVO.of("NOT_RESERVED", "未查询到该活动的抢购记录"));
    }

    @GetMapping("/ping")
    public Result<Map<String, Object>> ping() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("service", "mc-order");
        return Result.ok(data);
    }

    private OrderResultVO toSuccess(SeckillOrder order) {
        OrderResultVO vo = new OrderResultVO();
        vo.setStatus("SUCCESS");
        vo.setOrderNo(order.getOrderNo());
        vo.setActivityId(order.getActivityId());
        vo.setSeckillPrice(order.getSeckillPrice());
        vo.setOrderStatus(order.getStatus());
        vo.setCreatedAt(order.getCreatedAt());
        vo.setMessage(switch (order.getStatus() == null ? -1 : order.getStatus()) {
            case 0 -> "订单已生成，待支付";
            case 1 -> "订单已支付";
            case 2 -> "订单已取消";
            default -> "订单状态异常";
        });
        return vo;
    }
}
