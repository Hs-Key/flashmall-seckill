package com.flashmall.order.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.flashmall.order.entity.Order;
import com.flashmall.seckill.dto.SeckillOrderMessage;

public interface OrderService {

    /**
     * 创建秒杀订单（由 MQ 消费者调用）
     * 包含 DB 乐观锁扣减库存（防超卖第三道防线）
     */
    void createSeckillOrder(SeckillOrderMessage message);

    /**
     * 模拟支付订单
     */
    Order payOrder(String orderId, Long userId);

    /**
     * 取消订单（手动取消）
     */
    Order cancelOrder(String orderId, Long userId);

    /**
     * 超时自动取消订单（由 MQ 死信队列消费者调用）
     */
    void timeoutCancelOrder(String orderId);

    /**
     * 查询用户订单列表
     */
    IPage<Order> listUserOrders(Long userId, int page, int size);

    /**
     * 通过幂等键查询订单（前端轮询订单状态用）
     */
    Order getOrderByIdempotentKey(String idempotentKey);

    /**
     * 获取订单详情
     */
    Order getOrderDetail(String orderId, Long userId);
}
