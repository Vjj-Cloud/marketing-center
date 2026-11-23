package com.jack.mc.seckill.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.jack.mc.common.BusinessException;
import com.jack.mc.common.Result;
import com.jack.mc.common.web.RequireAdmin;
import com.jack.mc.seckill.dto.ActivityCreateRequest;
import com.jack.mc.seckill.service.ActivityService;
import com.jack.mc.seckill.service.StockService;
import com.jack.mc.seckill.vo.ActivityVO;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/seckill/activity")
public class ActivityController {

    /** 单页上限：不设上限的话，一个 size=100000 的请求就能把库拖垮 */
    private static final int MAX_PAGE_SIZE = 50;

    private final ActivityService activityService;
    private final StockService stockService;

    public ActivityController(ActivityService activityService, StockService stockService) {
        this.activityService = activityService;
        this.stockService = stockService;
    }

    /**
     * 创建活动——管理员操作。
     * <p>
     * 这类「运营后台才该有的动作」必须显式标 {@link RequireAdmin}：
     * 只要登录了就能建活动，等于任何用户都能给自己发优惠。
     */
    @PostMapping
    @RequireAdmin
    public Result<Long> create(@Valid @RequestBody ActivityCreateRequest request) {
        return Result.ok(activityService.create(request));
    }

    @PostMapping("/{id}/publish")
    @RequireAdmin
    public Result<Void> publish(@PathVariable Long id) {
        activityService.publish(id);
        return Result.ok();
    }

    /** 手动预热库存（幂等：已预热则跳过，不会把库存重置回初始值） */
    @PostMapping("/{id}/warmup")
    @RequireAdmin
    public Result<Map<String, Object>> warmup(@PathVariable Long id) {
        boolean written = stockService.warmup(id);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("written", written);
        data.put("redisStock", stockService.currentStock(id));
        return Result.ok(data);
    }

    /**
     * 库存对账视图：同时给出 DB、Redis、已购人数三个数。
     * 压测后用它一眼判断有没有超卖——
     * redisStock 归零、boughtCount 等于总库存、且两者相加等于 totalStock，才叫正确。
     */
    @GetMapping("/{id}/stock")
    @RequireAdmin
    public Result<Map<String, Object>> stock(@PathVariable Long id) {
        ActivityVO vo = activityService.detail(id);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("totalStock", vo.getTotalStock());
        data.put("dbAvailableStock", vo.getAvailableStock());
        data.put("redisStock", stockService.currentStock(id));
        data.put("boughtCount", stockService.boughtCount(id));
        return Result.ok(data);
    }

    @GetMapping("/list")
    public Result<IPage<ActivityVO>> list(@RequestParam(defaultValue = "1") int page,
                                          @RequestParam(defaultValue = "10") int size,
                                          @RequestParam(required = false) Integer status) {
        if (page < 1) {
            throw new BusinessException(400, "页码必须从 1 开始");
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new BusinessException(400, "每页条数必须在 1~" + MAX_PAGE_SIZE + " 之间");
        }
        return Result.ok(activityService.page(page, size, status));
    }

    @GetMapping("/{id}")
    public Result<ActivityVO> detail(@PathVariable Long id) {
        return Result.ok(activityService.detail(id));
    }
}
