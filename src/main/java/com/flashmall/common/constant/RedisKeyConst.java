package com.flashmall.common.constant;

/**
 * Redis Key 常量
 * 统一管理所有 Key 前缀，避免 Key 冲突和拼写错误
 *
 * 命名规范：{业务}:{子业务}:{标识}
 */
public interface RedisKeyConst {

    // ==================== 用户 ====================
    /** refresh token: user:refresh:{userId} */
    String USER_REFRESH_TOKEN = "user:refresh:";

    /** 登录失败次数: user:login:fail:{username} */
    String USER_LOGIN_FAIL = "user:login:fail:";

    // ==================== 商品 ====================
    /** 商品详情缓存: product:detail:{productId} */
    String PRODUCT_DETAIL = "product:detail:";

    /** 商品列表缓存: product:list */
    String PRODUCT_LIST = "product:list";

    // ==================== 秒杀 ====================
    /** 秒杀库存: seckill:stock:{activityId} */
    String SECKILL_STOCK = "seckill:stock:";

    /** 已购买用户集合: seckill:users:{activityId} */
    String SECKILL_USERS = "seckill:users:";

    /** 秒杀活动信息缓存: seckill:activity:{activityId} */
    String SECKILL_ACTIVITY = "seckill:activity:";

    // ==================== 订单 ====================
    /** 幂等 token: idempotent:token:{token} */
    String IDEMPOTENT_TOKEN = "idempotent:token:";

    // ==================== 接口限流 ====================
    /** 接口访问计数: limit:{uri}:{userId} */
    String ACCESS_LIMIT = "limit:";

    // ==================== 分布式锁 ====================
    /** 分布式锁前缀: lock:{业务} */
    String LOCK_PRODUCT_CACHE = "lock:product:cache:";
    String LOCK_SECKILL_INIT  = "lock:seckill:init:";
}
