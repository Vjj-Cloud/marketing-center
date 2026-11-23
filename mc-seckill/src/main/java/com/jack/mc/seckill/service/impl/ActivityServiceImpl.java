package com.jack.mc.seckill.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.jack.mc.common.BusinessException;
import com.jack.mc.seckill.dto.ActivityCreateRequest;
import com.jack.mc.seckill.entity.Product;
import com.jack.mc.seckill.entity.SeckillActivity;
import com.jack.mc.seckill.enums.ActivityStatus;
import com.jack.mc.seckill.mapper.ProductMapper;
import com.jack.mc.seckill.mapper.SeckillActivityMapper;
import com.jack.mc.seckill.service.ActivityService;
import com.jack.mc.seckill.vo.ActivityVO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class ActivityServiceImpl implements ActivityService {

    private static final Logger log = LoggerFactory.getLogger(ActivityServiceImpl.class);

    private final SeckillActivityMapper activityMapper;
    private final ProductMapper productMapper;

    public ActivityServiceImpl(SeckillActivityMapper activityMapper, ProductMapper productMapper) {
        this.activityMapper = activityMapper;
        this.productMapper = productMapper;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long create(ActivityCreateRequest request) {
        Product product = loadOnSaleProduct(request.getProductId());

        // 秒杀价必须低于原价。否则「秒杀」比原价还贵，属于配置事故，
        // 上线后会被用户截图投诉，所以要在入口拦死。
        if (request.getSeckillPrice().compareTo(product.getPrice()) >= 0) {
            throw new BusinessException(400, "秒杀价必须低于商品原价 " + product.getPrice());
        }

        // 活动库存不能超过商品实际库存。超了就是「卖出了没有的货」，
        // 后面无论怎么扣 Redis 都圆不回来。
        // 注意这里只做单场上限：草稿不占库存、可以随便改，
        // 「累计不能超额」必须放到发布时判（见 publish 里的 sumAllocatedStock），
        // 否则运营提前一天建草稿都会被正在跑的活动挡住。
        if (request.getTotalStock() > product.getStock()) {
            throw new BusinessException(400, "活动库存不能超过商品当前库存 " + product.getStock());
        }

        validateTimeWindow(request.getStartTime(), request.getEndTime());

        SeckillActivity activity = new SeckillActivity();
        activity.setProductId(product.getId());
        activity.setTitle(request.getTitle());
        activity.setSeckillPrice(request.getSeckillPrice());
        activity.setTotalStock(request.getTotalStock());
        activity.setAvailableStock(request.getTotalStock());
        activity.setStartTime(request.getStartTime());
        activity.setEndTime(request.getEndTime());
        activity.setStatus(ActivityStatus.DRAFT.getCode());
        activity.setVersion(0);

        activityMapper.insert(activity);
        log.info("创建秒杀活动 id={} productId={} price={} stock={}",
                activity.getId(), product.getId(), activity.getSeckillPrice(), activity.getTotalStock());
        return activity.getId();
    }

    /**
     * 发布活动。
     * <p>
     * <b>为什么隔离级别要显式设成 READ_COMMITTED</b>：
     * MySQL 默认是 REPEATABLE READ，事务内第一次普通 SELECT 会建立快照，
     * 之后所有普通 SELECT 都读这个快照。而发布流程是「先读活动 → 抢锁 →
     * 再查重叠」。抢锁（SELECT ... FOR UPDATE）是当前读，能看到别人刚提交的数据，
     * 但紧接着的 countOverlap 是普通读，仍然用事务开始时的旧快照——
     * 于是加锁等于白加，照样查不到别人刚发布的活动。
     * 这个坑非常隐蔽：加锁代码看起来完全正确，只有并发压测才会暴露。
     * <p>
     * <b>为什么用商品行锁做互斥</b>：见 ProductMapper.lockById 的注释。
     * 简化成一句话——「先查后写」必须有互斥量，否则查询和写入之间永远有窗口。
     */
    @Override
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public void publish(Long activityId) {
        SeckillActivity snapshot = activityMapper.selectById(activityId);
        if (snapshot == null) {
            throw new BusinessException(404, "活动不存在");
        }

        // 先拿锁，再判定。锁的粒度是「商品」，不是「活动」：
        // 需要互斥的是同一个商品的活动排期，不同商品之间互不影响。
        Product product = productMapper.lockById(snapshot.getProductId());
        if (product == null) {
            throw new BusinessException(404, "商品不存在，无法发布活动");
        }

        // 锁内重新读一次活动：等锁期间状态可能已经被别的运营改掉
        SeckillActivity activity = activityMapper.selectById(activityId);
        if (activity == null) {
            throw new BusinessException(404, "活动不存在");
        }
        if (ActivityStatus.FINISHED.is(activity.getStatus())) {
            throw new BusinessException(409, "活动已结束，不能再次发布");
        }
        if (!ActivityStatus.DRAFT.is(activity.getStatus())) {
            throw new BusinessException(409, "活动已发布，请勿重复操作");
        }

        // 开始时间已过的活动永远预热不了：warmup 只允许「尚未开始」的活动写 Redis，
        // 理由见 StockServiceImpl.warmup 的注释（进行中按 DB 值重灌会超卖）。
        // 所以这种活动一旦发布出去就是个死局——列表里显示「已发布」，
        // 用户抢购却永远是 NOT_WARMED。必须在发布这一步拦死。
        if (!activity.getStartTime().isAfter(LocalDateTime.now())) {
            throw new BusinessException(409,
                    "活动开始时间已过，无法预热库存，请新建活动或调整开始时间");
        }

        if (!Objects.equals(product.getStatus(), 1)) {
            throw new BusinessException(409, "商品已下架，不能发布活动");
        }
        if (activity.getSeckillPrice().compareTo(product.getPrice()) >= 0) {
            throw new BusinessException(409, "商品原价已变化，秒杀价必须低于当前原价");
        }
        if (product.getStock() == null || product.getStock() < activity.getTotalStock()) {
            throw new BusinessException(409, "商品当前库存不足，不能发布该活动。当前库存="
                    + (product.getStock() == null ? 0 : product.getStock()));
        }

        // 单场放得下不等于排得下：同一商品的多场未来活动会各自往 Redis 灌库存，
        // 商品库存却是同一份。这里按商品汇总「未来已发布活动还占着多少」再判一次，
        // 否则第二场会放行一批根本不存在的货，超出的消息最终全进死信队列。
        // 锁已经在手（商品行锁），两个运营并发发布同一商品会被串行化。
        int allocated = activityMapper.sumAllocatedStock(
                activity.getProductId(), activity.getId(), LocalDateTime.now());
        int remaining = (product.getStock() == null ? 0 : product.getStock()) - allocated;
        if (activity.getTotalStock() > remaining) {
            throw new BusinessException(409,
                    "商品库存不足以排下本场活动：当前库存 " + product.getStock()
                            + " 件，未来已发布活动已占用 " + allocated
                            + " 件，本场最多可分配 " + Math.max(remaining, 0) + " 件");
        }

        // 创建时校验过一次，但「创建→发布」之间可能已经有别的活动发布成功，
        // 所以这里必须重新校验。校验放在发布而不是创建，是因为草稿可以随便改，
        // 真正有副作用的是发布这个动作。
        if (activityMapper.countOverlap(activity.getProductId(),
                activity.getStartTime(), activity.getEndTime()) > 0) {
            throw new BusinessException(409, "该商品在此时段已有进行中的活动，请调整时间");
        }

        // 真正从商品可售库存中预留，而不只是记一个活动配置。
        // product 行已经 FOR UPDATE，所以这里的条件扣减与累计校验处于同一互斥区。
        if (productMapper.reserveStock(activity.getProductId(), activity.getTotalStock()) != 1) {
            throw new BusinessException(409, "商品库存已被其他渠道占用，当前无法预留");
        }

        SeckillActivity update = new SeckillActivity();
        update.setId(activity.getId());
        update.setStatus(ActivityStatus.PUBLISHED.getCode());
        update.setReservedStock(activity.getTotalStock());
        update.setVersion(activity.getVersion());

        // 带版本号更新：返回 0 说明期间被别人改过（比如另一个运营同时点了发布/结束），
        // 这种情况必须报冲突，不能当成功——否则状态会被静默覆盖。
        // 行锁已经保证了同商品的发布串行，这里的乐观锁是第二道防线：
        // 它同时还能挡住「发布」和「结束活动」这类不同操作的并发。
        int rows = activityMapper.updateById(update);
        if (rows == 0) {
            throw new BusinessException(409, "活动状态已被其他操作修改，请刷新后重试");
        }
        log.info("发布秒杀活动 id={} productId={}", activity.getId(), activity.getProductId());
    }

    @Override
    public IPage<ActivityVO> page(int page, int size, Integer status) {
        LambdaQueryWrapper<SeckillActivity> wrapper = new LambdaQueryWrapper<SeckillActivity>()
                .eq(status != null, SeckillActivity::getStatus, status)
                .orderByDesc(SeckillActivity::getId);

        Page<SeckillActivity> result = activityMapper.selectPage(new Page<>(page, size), wrapper);
        if (result.getRecords().isEmpty()) {
            // 空页直接返回同尺寸的空 Page，别去拼商品；同时保留 total，
            // 否则前端拿到的总数会变成 0，翻页组件直接错乱
            return new Page<ActivityVO>(result.getCurrent(), result.getSize(), result.getTotal());
        }

        // 一次性把商品查回来，避免 20 条活动查 20 次商品的 N+1
        Map<Long, Product> productMap = batchLoadProducts(result.getRecords());
        return result.convert(a -> toVO(a, productMap.get(a.getProductId())));
    }

    @Override
    public ActivityVO detail(Long activityId) {
        SeckillActivity activity = activityMapper.selectById(activityId);
        if (activity == null) {
            throw new BusinessException(404, "活动不存在");
        }
        Product product = productMapper.selectById(activity.getProductId());
        return toVO(activity, product);
    }

    // ------------------------------------------------------------------

    private Product loadOnSaleProduct(Long productId) {
        Product product = productMapper.selectById(productId);
        // deleted 字段没有配 @TableLogic（那是商城项目的约定），这里手动判
        if (product == null || Objects.equals(product.getDeleted(), 1)) {
            throw new BusinessException(404, "商品不存在");
        }
        if (!Objects.equals(product.getStatus(), 1)) {
            throw new BusinessException(400, "商品已下架，不能创建活动");
        }
        if (product.getStock() == null || product.getStock() <= 0) {
            throw new BusinessException(400, "商品库存不足，不能创建活动");
        }
        return product;
    }

    private void validateTimeWindow(LocalDateTime start, LocalDateTime end) {
        if (!start.isBefore(end)) {
            throw new BusinessException(400, "开始时间必须早于结束时间");
        }
        LocalDateTime now = LocalDateTime.now();
        if (end.isBefore(now)) {
            throw new BusinessException(400, "结束时间不能早于当前时间");
        }
        // 开始时间必须在未来。允许填过去的时间会造出一个「能发布、但永远预热不了」
        // 的活动：warmup 只给未开始的活动灌库存，于是它挂在「已发布」状态上，
        // 库存一件也卖不出去，还没有任何自动恢复路径。入口直接拒绝最省事。
        if (!start.isAfter(now)) {
            throw new BusinessException(400, "开始时间必须晚于当前时间");
        }
    }

    private Map<Long, Product> batchLoadProducts(List<SeckillActivity> activities) {
        List<Long> productIds = activities.stream()
                .map(SeckillActivity::getProductId)
                .distinct()
                .toList();
        if (productIds.isEmpty()) {
            return Collections.emptyMap();
        }
        return productMapper.selectBatchIds(productIds).stream()
                .collect(Collectors.toMap(Product::getId, Function.identity()));
    }

    private ActivityVO toVO(SeckillActivity activity, Product product) {
        ActivityVO vo = new ActivityVO();
        vo.setId(activity.getId());
        vo.setProductId(activity.getProductId());
        vo.setSeckillPrice(activity.getSeckillPrice());
        vo.setTotalStock(activity.getTotalStock());
        vo.setAvailableStock(activity.getAvailableStock());
        vo.setStartTime(activity.getStartTime());
        vo.setEndTime(activity.getEndTime());
        vo.setStatus(activity.getStatus());
        vo.setStatusText(statusText(activity.getStatus()));

        if (product != null) {
            vo.setProductName(product.getName());
            vo.setOriginalPrice(product.getPrice());
            vo.setDiscount(calcDiscount(activity.getSeckillPrice(), product.getPrice()));
        }
        return vo;
    }

    /**
     * 折扣计算必须显式指定 scale 和舍入模式。
     * BigDecimal.divide 不传这两个参数时，遇到除不尽的数（如 10/3）直接抛
     * ArithmeticException，而不是像 double 那样给你一个近似值。
     * 这是 BigDecimal 最经典的坑：测试数据凑巧除得尽，上线就崩。
     */
    private BigDecimal calcDiscount(BigDecimal seckillPrice, BigDecimal originalPrice) {
        if (originalPrice == null || originalPrice.compareTo(BigDecimal.ZERO) <= 0) {
            return null;
        }
        return seckillPrice.multiply(BigDecimal.TEN)
                .divide(originalPrice, 1, RoundingMode.HALF_UP);
    }

    private String statusText(Integer status) {
        if (ActivityStatus.DRAFT.is(status)) {
            return "草稿";
        }
        if (ActivityStatus.PUBLISHED.is(status)) {
            return "已发布";
        }
        if (ActivityStatus.FINISHED.is(status)) {
            return "已结束";
        }
        return "未知";
    }
}
