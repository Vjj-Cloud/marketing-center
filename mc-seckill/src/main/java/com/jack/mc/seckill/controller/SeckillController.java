package com.jack.mc.seckill.controller;

import com.jack.mc.common.BusinessException;
import com.jack.mc.common.Result;
import com.jack.mc.seckill.service.SeckillService;
import com.jack.mc.seckill.vo.SeckillResultVO;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 抢购接口。
 * <p>
 * 用户身份从网关透传的两个请求头里取：{@code X-User-Id} 与 {@code X-User-Sign}。
 * 直连本服务时这两个头由 {@link com.jack.mc.common.web.GatewayAuthFilter} 验签，
 * 伪造身份会在过滤器就被拒 401，走不到这里。
 * <p>
 * <b>X-Request-Id 必须限长（第四轮评审 P1）</b>：它会一路写进
 * {@code seckill_order.request_id VARCHAR(64)}，同时被序列化进
 * {@code seckill_outbox.payload VARCHAR(1024)}。不限长的话，一个超长请求头足以
 * 造出「Redis 库存已扣 → outbox 写不进去 → 用户什么都没拿到」的悬挂记录，
 * 或让订单插入因字段超长失败、消息被反复重投到死信。
 * 在入口拒绝最便宜的，也最容易让调用方看懂。
 */
@RestController
@RequestMapping("/api/seckill")
public class SeckillController {

    /**
     * 与 {@code seckill_order.request_id VARCHAR(64)} 对齐。
     * <p>
     * 校验放在 DTO 注解之外单独做，是因为这里是请求头而不是请求体：
     * {@code @Size} 要生效得在类上开 {@code @Validated} 并把
     * {@code ConstraintViolationException} 接进全局异常处理，
     * 为一个长度检查铺这套机制不划算（漏接还会退化成 500）。
     */
    private static final int MAX_REQUEST_ID_LENGTH = 64;

    private final SeckillService seckillService;

    public SeckillController(SeckillService seckillService) {
        this.seckillService = seckillService;
    }

    @PostMapping("/order/{activityId}")
    public Result<SeckillResultVO> seckill(@PathVariable Long activityId,
                                           @RequestHeader(value = "X-User-Id", required = false) Long userId,
                                           @RequestHeader(value = "X-Request-Id", required = false) String requestId) {
        if (userId == null || userId <= 0) {
            throw new BusinessException(400, "缺少用户标识");
        }
        return Result.ok(seckillService.seckill(activityId, userId, normalizeRequestId(requestId)));
    }

    /**
     * 校验并规整幂等键。
     * <p>
     * <b>超长一律拒绝，不能截断</b>：截断会把两个不同的请求 ID 变成同一个值，
     * 本来用来「标识这一次尝试」的键就直接失去意义，比不传还糟。
     * 空串按未传处理（{@code request_id} 列 NOT NULL DEFAULT ''）。
     */
    private String normalizeRequestId(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.length() > MAX_REQUEST_ID_LENGTH) {
            throw new BusinessException(400,
                    "X-Request-Id 长度不能超过 " + MAX_REQUEST_ID_LENGTH + " 个字符");
        }
        return trimmed;
    }
}
