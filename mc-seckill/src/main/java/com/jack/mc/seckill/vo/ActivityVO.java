package com.jack.mc.seckill.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 活动展示对象。
 * <p>
 * 单独建 VO 而不是直接返回实体：实体里的 version、deleted 这类字段
 * 不该出现在接口响应里；且前端需要 productName、discount 这些商品表的信息，
 * 必须在这里拼装。
 */
@Data
public class ActivityVO {

    private Long id;

    private Long productId;

    private String productName;

    /** 商品原价，用于前端展示划线价 */
    private BigDecimal originalPrice;

    private BigDecimal seckillPrice;

    /** 折扣，如 6.7 表示 6.7 折；原价为 0 时为 null */
    private BigDecimal discount;

    private Integer totalStock;

    private Integer availableStock;

    /**
     * 这场活动在发布时从 {@code product.stock} 预留走的件数（历史活动为 0）。
     * <p>
     * 和对账一起暴露，是因为「活动账本对不对」和「商品库存有没有被多扣/漏还」
     * 是两个互相独立的结论：只看 available_stock 无法发现双扣或少还。
     */
    private Integer reservedStock;

    /**
     * 商品当前的真实库存（{@code product.stock}）。
     * <p>
     * 单独放一个字段而不是让调用方再去查商品：对账必须能在<b>同一次快照</b>里
     * 同时看到活动账本和商品库存，分两次请求看到的可能是两个时刻的数据。
     */
    private Integer productStock;

    /** 预留库存是否已归还（非空即已归还）。活动结束后只归还未售部分，见 InventoryReservationReleaseService */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime reservationReleasedAt;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime startTime;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime endTime;

    private Integer status;

    private String statusText;
}
