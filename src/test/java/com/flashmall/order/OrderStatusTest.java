package com.flashmall.order;

import com.flashmall.common.enums.OrderStatusEnum;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 订单状态机测试
 *
 * 不依赖 Spring 上下文，纯单元测试。
 * 验证状态流转合法性，防止代码修改后破坏状态机逻辑。
 */
@DisplayName("订单状态机单元测试")
class OrderStatusTest {

    @Test
    @DisplayName("合法流转：待支付 → 已支付")
    void testPendingToPayd() {
        assertTrue(OrderStatusEnum.PENDING_PAY.canTransitionTo(OrderStatusEnum.PAID));
    }

    @Test
    @DisplayName("合法流转：待支付 → 已取消")
    void testPendingToCancel() {
        assertTrue(OrderStatusEnum.PENDING_PAY.canTransitionTo(OrderStatusEnum.CANCELLED));
    }

    @Test
    @DisplayName("合法流转：已支付 → 已发货")
    void testPaidToShipped() {
        assertTrue(OrderStatusEnum.PAID.canTransitionTo(OrderStatusEnum.SHIPPED));
    }

    @Test
    @DisplayName("合法流转：已发货 → 已完成")
    void testShippedToCompleted() {
        assertTrue(OrderStatusEnum.SHIPPED.canTransitionTo(OrderStatusEnum.COMPLETED));
    }

    @Test
    @DisplayName("非法流转：已完成 → 已取消（不允许）")
    void testCompletedToCancel() {
        assertFalse(OrderStatusEnum.COMPLETED.canTransitionTo(OrderStatusEnum.CANCELLED));
    }

    @Test
    @DisplayName("非法流转：已取消 → 已支付（不允许）")
    void testCancelledToPaid() {
        assertFalse(OrderStatusEnum.CANCELLED.canTransitionTo(OrderStatusEnum.PAID));
    }

    @Test
    @DisplayName("非法流转：已支付 → 待支付（不允许回退）")
    void testPaidToPending() {
        assertFalse(OrderStatusEnum.PAID.canTransitionTo(OrderStatusEnum.PENDING_PAY));
    }

    @Test
    @DisplayName("枚举解析：code=0 对应 PENDING_PAY")
    void testEnumOf() {
        assertEquals(OrderStatusEnum.PENDING_PAY, OrderStatusEnum.of(0));
        assertEquals(OrderStatusEnum.PAID, OrderStatusEnum.of(1));
        assertEquals(OrderStatusEnum.CANCELLED, OrderStatusEnum.of(4));
    }

    @Test
    @DisplayName("枚举解析：非法 code 抛出 IllegalArgumentException")
    void testEnumOfInvalid() {
        assertThrows(IllegalArgumentException.class, () -> OrderStatusEnum.of(99));
    }
}
