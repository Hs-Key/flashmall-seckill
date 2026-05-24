package com.flashmall.common.annotation;

import java.lang.annotation.*;

/**
 * 接口访问频率限制注解
 *
 * 使用示例：
 * {@code @AccessLimit(seconds = 5, maxCount = 3, msg = "操作过于频繁")}
 *
 * 原理：
 * 在 AccessLimitAspect 中，以"接口URI + 用户ID"为 key，
 * 在 Redis 中计数，{seconds} 秒内超过 {maxCount} 次则拒绝。
 *
 * 为什么不用 @RateLimiter（如 Guava）？
 * Guava 的 RateLimiter 是单机的，分布式环境下多实例之间无法共享计数。
 * 基于 Redis 的限流可以保证多实例下也能正确限制。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface AccessLimit {

    /** 时间窗口大小（秒） */
    int seconds() default 5;

    /** 时间窗口内允许的最大请求次数 */
    int maxCount() default 5;

    /** 超限时的提示信息 */
    String msg() default "操作过于频繁，请稍后再试";

    /** 是否需要登录（未登录时不限流，登录后才限流） */
    boolean needLogin() default true;
}
