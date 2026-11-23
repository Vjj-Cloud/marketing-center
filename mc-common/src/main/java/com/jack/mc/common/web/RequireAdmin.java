package com.jack.mc.common.web;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记「只有管理员能调」的接口。
 * <p>
 * <b>为什么需要它</b>：第二轮评审指出，系统此前只有「认证」没有「授权」——
 * 只要登录了，任何人都能创建活动、发布活动、预热库存。
 * 认证回答的是「你是谁」，授权回答的是「你能不能干这件事」，两者缺一不可。
 * <p>
 * <b>为什么用注解 + 拦截器，而不是在方法体里 if 判断</b>：
 * <ol>
 *   <li>拦截器在 Controller 方法之前执行，权限判断天然早于业务校验，
 *       普通用户请求一个不存在的活动 id 时返回 403 而不是 400
 *       （不泄露「这个 id 存在不存在」这一信息）；</li>
 *   <li>写成注解是「声明式」的，新增管理接口只要加一行，不会漏写判断；</li>
 *   <li>权限逻辑集中在一处，改规则不用翻遍所有 Controller。</li>
 * </ol>
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequireAdmin {
}
