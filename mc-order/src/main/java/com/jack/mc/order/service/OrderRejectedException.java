package com.jack.mc.order.service;

/**
 * 订单被拒绝落库：本地消息表显示这笔抢购已经被人工回滚。
 * <p>
 * <b>为什么需要一个专门的异常类型（第三轮评审 P1）</b>：
 * 消费者对异常的处理是「返回 RECONSUME_LATER 让 MQ 重试」，
 * 但这条消息重试一万次也不会成功——它对应的预留已经被明确取消了。
 * 用独立类型把它和「库存不足」「数据库抖动」这类<b>可重试</b>失败区分开，
 * 消费者才能对前者直接确认消费、对后者继续重试。
 * <p>
 * 命名里带 Rejected 而不是 Exception 后缀的细节：读代码时一眼能看出这是
 * 「业务上主动拒绝」，不是「执行出错」。
 */
public class OrderRejectedException extends RuntimeException {

    public OrderRejectedException(String message) {
        super(message);
    }
}
