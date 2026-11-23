package com.jack.mc.seckill.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 创建活动请求。
 * <p>
 * 校验注解必须与 DDL 对齐，否则「用户输入越界」会从 400 变成 500：
 * title VARCHAR(100) → @Size(max = 100)
 * seckill_price DECIMAL(10,2) → @Digits(integer = 6, fraction = 2)
 *   （6 位整数是项目的全局约定，商品单价上限 999999.99）
 * total_stock INT → @Max(99999)，避免把库存填成 21 亿
 */
@Data
public class ActivityCreateRequest {

    @NotNull(message = "商品不能为空")
    private Long productId;

    @NotBlank(message = "活动标题不能为空")
    @Size(max = 100, message = "活动标题不能超过 100 字")
    private String title;

    @NotNull(message = "秒杀价不能为空")
    @DecimalMin(value = "0.01", message = "秒杀价必须大于 0")
    @Digits(integer = 6, fraction = 2, message = "秒杀价格式不正确")
    private BigDecimal seckillPrice;

    @NotNull(message = "活动库存不能为空")
    @Min(value = 1, message = "活动库存至少为 1")
    @Max(value = 99999, message = "活动库存过大")
    private Integer totalStock;

    @NotNull(message = "开始时间不能为空")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime startTime;

    @NotNull(message = "结束时间不能为空")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime endTime;
}
