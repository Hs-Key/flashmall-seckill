package com.flashmall.order.service.impl;

import cn.hutool.core.lang.Snowflake;
import cn.hutool.core.util.IdUtil;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.flashmall.common.constant.RabbitMQConst;
import com.flashmall.common.constant.RedisKeyConst;
import com.flashmall.common.enums.OrderStatusEnum;
import com.flashmall.common.enums.ResultCode;
import com.flashmall.common.exception.BusinessException;
import com.flashmall.order.entity.Order;
import com.flashmall.order.mapper.OrderMapper;
import com.flashmall.order.service.OrderService;
import com.flashmall.product.entity.Product;
import com.flashmall.product.mapper.ProductMapper;
import com.flashmall.seckill.dto.SeckillOrderMessage;
import com.flashmall.seckill.entity.SeckillActivity;
import com.flashmall.seckill.mapper.SeckillActivityMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 订单服务实现
 *
 * createSeckillOrder 是整个系统最关键的方法：
 * - 按值校验扣减 DB 库存（防超卖第三道防线）
 * - 幂等键唯一索引（防 MQ 重复消费）
 * - 发送超时取消消息到延迟队列
 *
 * 状态机流转通过 OrderStatusEnum.canTransitionTo() 强制校验，
 * 防止订单状态被非法修改（如已完成的订单不能再取消）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderServiceImpl extends ServiceImpl<OrderMapper, Order> implements OrderService {

    private final SeckillActivityMapper activityMapper;
    private final ProductMapper productMapper;
    private final StringRedisTemplate redisTemplate;
    private final RabbitTemplate rabbitTemplate;

    // 雪花算法 ID 生成器（workerId=1, dataCenterId=1）
    private final Snowflake snowflake = IdUtil.getSnowflake(1, 1);

    /**
     * 创建秒杀订单
     *
     * 事务保证：扣减 DB 库存 + 插入订单记录 在同一事务内，要么都成功，要么都回滚
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void createSeckillOrder(SeckillOrderMessage message) {
        // ===== 第三道防线：DB 按值校验扣减库存 =====
        SeckillActivity activity = activityMapper.selectById(message.getActivityId());
        if (activity == null || activity.getStatus() != 1) {
            log.warn("活动不存在或已结束: {}", message.getActivityId());
            // 回滚 Redis 库存（已由 Lua 扣减，但 DB 不创建订单）
            rollbackRedisStock(message.getActivityId());
            return;
        }

        // 按值校验扣减（WHERE stock > 0 已是行级原子的"判断+扣减"，无需 version 重试）
        // 正常情况下经过 Redis Lua 过滤后，到达 DB 的请求数 ≤ Redis 库存数，
        // 这里返回 0 几乎不会发生，作为最后兜底防超卖
        int updated = activityMapper.decreaseStockIfAvailable(activity.getId());
        if (updated == 0) {
            log.warn("DB 库存为 0，扣减失败: activityId={}", message.getActivityId());
            rollbackRedisStock(message.getActivityId());
            return;
        }

        // 查询商品信息（用于订单快照）
        Product product = productMapper.selectById(message.getProductId());

        // 创建订单
        Order order = new Order();
        order.setId(snowflake.nextIdStr());              // 雪花算法 ID
        order.setUserId(message.getUserId());
        order.setProductId(message.getProductId());
        order.setActivityId(message.getActivityId());
        order.setProductName(product.getName());
        order.setAmount(activity.getSeckillPrice());
        order.setStatus(OrderStatusEnum.PENDING_PAY.getCode());
        order.setIdempotentKey(message.getIdempotentKey());  // 幂等键（唯一索引约束）
        order.setCreatedAt(LocalDateTime.now());

        save(order);
        log.info("订单创建成功: orderId={}, userId={}", order.getId(), order.getUserId());

        // 发送超时取消消息到延迟队列（30分钟后变为死信，触发自动取消）
        rabbitTemplate.convertAndSend(
            "",  // 不指定交换机（使用默认交换机），直接发到队列
            RabbitMQConst.ORDER_DELAY_QUEUE,
            order.getId()
        );
        log.info("超时取消消息已发送: orderId={}", order.getId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Order payOrder(String orderId, Long userId) {
        Order order = getAndValidateOwnership(orderId, userId);

        OrderStatusEnum current = OrderStatusEnum.of(order.getStatus());
        if (!current.canTransitionTo(OrderStatusEnum.PAID)) {
            throw new BusinessException(ResultCode.ORDER_STATUS_ERROR);
        }

        order.setStatus(OrderStatusEnum.PAID.getCode());
        order.setPaidAt(LocalDateTime.now());
        updateById(order);

        log.info("订单支付成功: orderId={}", orderId);
        return order;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Order cancelOrder(String orderId, Long userId) {
        Order order = getAndValidateOwnership(orderId, userId);

        OrderStatusEnum current = OrderStatusEnum.of(order.getStatus());
        if (!current.canTransitionTo(OrderStatusEnum.CANCELLED)) {
            throw new BusinessException(ResultCode.ORDER_STATUS_ERROR);
        }

        order.setStatus(OrderStatusEnum.CANCELLED.getCode());
        updateById(order);

        releaseSeckillSlot(order.getActivityId(), order.getUserId());
        log.info("订单手动取消: orderId={}, userId={}", orderId, order.getUserId());
        return order;
    }

    /**
     * 超时自动取消（由死信队列消费者调用）
     *
     * 注意：此处不是由用户调用，所以不需要校验所有权。
     * 只有在"待支付"状态才取消，其他状态说明用户已支付，不需要取消。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void timeoutCancelOrder(String orderId) {
        Order order = getById(orderId);
        if (order == null) {
            log.warn("超时取消：订单不存在: {}", orderId);
            return;
        }

        // 只取消"待支付"状态的订单
        if (!OrderStatusEnum.PENDING_PAY.getCode().equals(order.getStatus())) {
            log.info("超时取消：订单已处理（status={}），跳过: {}", order.getStatus(), orderId);
            return;
        }

        order.setStatus(OrderStatusEnum.CANCELLED.getCode());
        updateById(order);

        releaseSeckillSlot(order.getActivityId(), order.getUserId());
        log.info("订单超时自动取消: orderId={}, userId={}", orderId, order.getUserId());
    }

    @Override
    public IPage<Order> listUserOrders(Long userId, int page, int size) {
        return lambdaQuery()
            .eq(Order::getUserId, userId)
            .orderByDesc(Order::getCreatedAt)
            .page(new Page<>(page, size));
    }

    @Override
    public Order getOrderByIdempotentKey(String idempotentKey) {
        return lambdaQuery()
            .eq(Order::getIdempotentKey, idempotentKey)
            .one();
    }

    @Override
    public Order getOrderDetail(String orderId, Long userId) {
        return getAndValidateOwnership(orderId, userId);
    }

    // ==================== 私有方法 ====================

    private Order getAndValidateOwnership(String orderId, Long userId) {
        Order order = getById(orderId);
        if (order == null) {
            throw new BusinessException(ResultCode.ORDER_NOT_FOUND);
        }
        if (!order.getUserId().equals(userId)) {
            throw new BusinessException(ResultCode.FORBIDDEN);
        }
        return order;
    }

    /**
     * 回滚 Redis 库存
     * 当订单取消（手动/超时）或创建失败时，将 Redis 中扣减的库存加回来
     */
    private void rollbackRedisStock(Long activityId) {
        String stockKey = RedisKeyConst.SECKILL_STOCK + activityId;
        redisTemplate.opsForValue().increment(stockKey);
        log.info("Redis 库存回滚: activityId={}", activityId);
    }

    /**
     * 释放一个秒杀名额：回滚库存 + 把用户从已购集合中移除
     * 取消订单（无论手动还是超时）后调用，让该用户可以再次参与同一活动。
     * 注意：仅在订单已实际占用库存（PENDING_PAY/PAID 等）时调用。
     */
    private void releaseSeckillSlot(Long activityId, Long userId) {
        rollbackRedisStock(activityId);
        String usersKey = RedisKeyConst.SECKILL_USERS + activityId;
        redisTemplate.opsForSet().remove(usersKey, String.valueOf(userId));
    }
}
