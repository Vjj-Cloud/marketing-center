package com.jack.mc.seckill.service;

import com.jack.mc.common.message.SeckillMessage;
import com.jack.mc.seckill.entity.SeckillOutbox;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 本地消息表的读写入口。
 * <p>
 * 单独抽一层是为了让 SeckillServiceImpl 只表达「业务意图」，
 * 不掺 SQL 细节；补偿任务也只依赖这一层，两边不会各写一套更新语句。
 */
public interface OutboxService {

    /**
     * 落一条待投递记录。
     *
     * @throws org.springframework.dao.DuplicateKeyException 该用户对本活动已有记录
     */
    void savePending(SeckillMessage message, String payload);

    /** 标记为已投递（只有 status=0 时生效），返回是否更新成功 */
    boolean markSent(Long activityId, Long userId);

    List<SeckillOutbox> findPendingToRetry(LocalDateTime now, int limit);

    List<SeckillOutbox> findSentWithoutOrder(LocalDateTime before, int limit);

    void bumpRetry(Long id, int retryCount, LocalDateTime nextRetryAt, String lastError);

    /**
     * 带旧状态条件的更新，返回是否更新成功。
     * <p>
     * 状态流转一律走这里，不要用无条件 UPDATE：两个任务（复查、人工回滚）
     * 可能同时处理同一条记录，无条件更新会让后到的覆盖先到的。
     */
    boolean casStatus(Long id, int from, int to);

    /**
     * 把一条尚未投递的本地消息标记为已中止（墓碑），返回是否成功。
     * <p>
     * 返回值是「消息会不会被发出去」的凭据，秒杀主流程靠它决定能不能回滚库存。
     */
    boolean markAborted(Long activityId, Long userId, String reservationId);

    /** 把已不成立的记录（人工回滚 3 / 入口中止 6）重置为待投递，让用户重新参与 */
    boolean resetAbandoned(Long activityId, Long userId, String reservationId, String payload);

    /** 打回待投递，用于「已投递但查不到订单」的对账场景 */
    boolean resetToPending(Long id);

    boolean orderExists(Long activityId, Long userId);

    /** 本地消息表里是否已有这条记录（孤儿扣减判定用） */
    boolean exists(Long activityId, Long userId);

    /**
     * 本次预订这条 outbox 记录的状态；没有记录返回 null。
     * <p>
     * 孤儿恢复不能只问「有没有记录」，必须看状态：
     * 3（人工回滚）/ 6（入口中止）说明库存该还，只清标记会让扣减永久悬挂。
     */
    Integer findStatusByReservation(Long activityId, String reservationId);

    /** 按 id 取记录，供人工复核接口使用 */
    SeckillOutbox findById(Long id);

    /** 按用户+活动取记录 */
    SeckillOutbox findByUserAndActivity(Long activityId, Long userId);

    /** 按状态取一批记录，用于复查待人工复核的条目 */
    List<SeckillOutbox> findByStatusBefore(int status, LocalDateTime before, int limit);
}
