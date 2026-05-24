package com.flashmall.common.enums;

import lombok.Getter;

/**
 * 订单状态枚举（状态机）
 *
 * 状态流转：
 *   待支付(0) --> 已支付(1) --> 已发货(2) --> 已完成(3)
 *   待支付(0) --> 已取消(4)  （超时自动取消 or 手动取消）
 *   已支付(1) --> 已取消(4)  （退款取消）
 */
@Getter
public enum OrderStatusEnum {

    PENDING_PAY(0, "待支付"),
    PAID(1, "已支付"),
    SHIPPED(2, "已发货"),
    COMPLETED(3, "已完成"),
    CANCELLED(4, "已取消");

    private final Integer code;
    private final String desc;

    OrderStatusEnum(Integer code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public static OrderStatusEnum of(Integer code) {
        for (OrderStatusEnum status : values()) {
            if (status.code.equals(code)) {
                return status;
            }
        }
        throw new IllegalArgumentException("未知订单状态: " + code);
    }

    /**
     * 校验状态流转是否合法
     * 防止订单状态被非法修改（如：已完成 -> 待支付）
     */
    public boolean canTransitionTo(OrderStatusEnum target) {
        return switch (this) {
            case PENDING_PAY -> target == PAID || target == CANCELLED;
            case PAID        -> target == SHIPPED || target == CANCELLED;
            case SHIPPED     -> target == COMPLETED;
            default          -> false;
        };
    }
}
