package com.flashmall.common.enums;

import lombok.Getter;

/**
 * 统一响应码枚举
 */
@Getter
public enum ResultCode {

    SUCCESS(200, "操作成功"),
    FAIL(400, "操作失败"),
    UNAUTHORIZED(401, "请先登录"),
    FORBIDDEN(403, "权限不足"),
    NOT_FOUND(404, "资源不存在"),
    INTERNAL_ERROR(500, "服务器内部错误"),

    // 用户相关
    USER_NOT_FOUND(1001, "用户不存在"),
    USER_ALREADY_EXISTS(1002, "用户名已存在"),
    PASSWORD_ERROR(1003, "用户名或密码错误"),
    USER_DISABLED(1004, "账号已被禁用"),
    TOKEN_EXPIRED(1005, "登录已过期，请重新登录"),
    TOKEN_INVALID(1006, "无效的Token"),

    // 商品相关
    PRODUCT_NOT_FOUND(2001, "商品不存在"),
    PRODUCT_STOCK_NOT_ENOUGH(2002, "库存不足"),

    // 秒杀相关
    SECKILL_NOT_FOUND(3001, "秒杀活动不存在"),
    SECKILL_NOT_STARTED(3002, "秒杀活动尚未开始"),
    SECKILL_ENDED(3003, "秒杀活动已结束"),
    SECKILL_STOCK_EMPTY(3004, "秒杀商品已售罄"),
    SECKILL_REPEAT(3005, "每人限购一件"),
    SECKILL_SYSTEM_BUSY(3006, "秒杀系统繁忙，请稍后重试"),

    // 订单相关
    ORDER_NOT_FOUND(4001, "订单不存在"),
    ORDER_STATUS_ERROR(4002, "订单状态异常，无法操作"),
    ORDER_ALREADY_PAID(4003, "订单已支付"),
    ORDER_ALREADY_CANCELLED(4004, "订单已取消"),

    // 接口限流
    ACCESS_LIMIT(5001, "操作过于频繁，请稍后再试"),

    // 幂等性
    IDEMPOTENT_TOKEN_MISSING(6001, "缺少幂等Token"),
    IDEMPOTENT_TOKEN_INVALID(6002, "幂等Token无效或已使用");

    private final Integer code;
    private final String message;

    ResultCode(Integer code, String message) {
        this.code = code;
        this.message = message;
    }
}
