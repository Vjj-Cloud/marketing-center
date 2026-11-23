package com.jack.mc.seckill.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.jack.mc.seckill.dto.ActivityCreateRequest;
import com.jack.mc.seckill.vo.ActivityVO;

public interface ActivityService {

    /** 创建活动（草稿态），返回活动 id */
    Long create(ActivityCreateRequest request);

    /** 发布活动：草稿 → 已发布，发布后才能被预热和抢购 */
    void publish(Long activityId);

    /** 分页查询 */
    IPage<ActivityVO> page(int page, int size, Integer status);

    /** 活动详情 */
    ActivityVO detail(Long activityId);
}
