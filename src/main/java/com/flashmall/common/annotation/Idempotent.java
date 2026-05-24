package com.flashmall.common.annotation;

import java.lang.annotation.*;

/**
 * 幂等性注解
 *
 * 使用示例：
 * {@code @Idempotent}
 * 加在需要幂等保证的接口上（如下单接口）。
 *
 * 使用流程：
 * 1. 前端调用 GET /api/order/token 获取一次性幂等 token（UUID）
 * 2. token 存入 Redis，TTL = 5分钟
 * 3. 前端发起下单请求时，将 token 放在请求头 Idempotent-Token 中
 * 4. IdempotentAspect 拦截请求，原子地"校验 + 删除" token（Lua 脚本）
 * 5. token 有效则放行；无效（已用过/不存在）则拒绝，返回"重复请求"
 *
 * 为什么需要幂等性？
 * 用户网络抖动时，前端可能发送多次相同的下单请求。
 * 幂等性保证：无论请求发送多少次，最终只创建一个订单。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Idempotent {

    /** 请求头名称 */
    String headerName() default "Idempotent-Token";

    /** 提示信息 */
    String msg() default "请勿重复提交";
}
