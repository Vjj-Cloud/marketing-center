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

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime startTime;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime endTime;

    private Integer status;

    private String statusText;
}
