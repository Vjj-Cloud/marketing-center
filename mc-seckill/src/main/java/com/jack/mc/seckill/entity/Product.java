package com.jack.mc.seckill.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 商城商品表的只读映射。
 * <p>
 * 营销中心不维护商品，只读商城的 product 表——这是「服务提供方」定位的体现：
 * 商品主数据只有一份，跨服务读同一个库是当前阶段的合理取舍（真的拆库之后
 * 这里应该换成 OpenFeign 调商品服务）。
 * <p>
 * 刻意不映射 description / cover 等大字段：活动校验只需要价格、库存、状态。
 */
@Data
@TableName("product")
public class Product {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String name;

    private BigDecimal price;

    private Integer stock;

    /** 1 上架 0 下架 */
    private Integer status;

    private Integer deleted;
}
