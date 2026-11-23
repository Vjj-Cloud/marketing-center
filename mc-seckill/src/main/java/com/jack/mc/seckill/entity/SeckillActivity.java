package com.jack.mc.seckill.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 秒杀活动。
 * <p>
 * 注意没有 @TableLogic：活动不做逻辑删除，只走状态流转（草稿→发布→结束）。
 * 秒杀记录一旦被物理删除，用户订单就对不上账了，所以这里必须靠状态而不是删除。
 */
@Data
@TableName("seckill_activity")
public class SeckillActivity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long productId;

    private String title;

    private BigDecimal seckillPrice;

    private Integer totalStock;

    /** DB 侧剩余库存，是「账本」；Redis 里的库存才是实际扣减的闸门 */
    private Integer availableStock;

    private LocalDateTime startTime;

    private LocalDateTime endTime;

    /** 0 草稿 1 已发布 2 已结束 */
    private Integer status;

    /** 乐观锁：发布、结束这类状态流转用它防并发覆盖 */
    @Version
    private Integer version;

    /**
     * 库存预热成功的时间。NULL 表示从未预热过。
     * <p>
     * 这一列让预热任务能区分两种「Redis 里没有库存」：
     * 从未预热（活动还没到预热窗口，正常） /
     * 预热过但 key 丢了（Redis 重启或内存淘汰，事故，需要人工介入）。
     * 没有它就分不清，只能对所有进行中的活动刷告警。
     */
    private LocalDateTime warmupAt;

    /**
     * 发布时已经从 product.stock 预留的数量。
     * 0 表示历史活动或未启用预留，订单落库时仍按旧逻辑扣商品库存。
     */
    private Integer reservedStock;

    /**
     * 未售预留库存已经归还 product.stock 的时间；NULL 表示尚未释放。
     */
    private LocalDateTime reservationReleasedAt;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
