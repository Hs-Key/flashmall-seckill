package com.flashmall.seckill.mq;

import com.flashmall.common.constant.RabbitMQConst;
import com.flashmall.order.service.OrderService;
import com.flashmall.seckill.dto.SeckillOrderMessage;
import com.rabbitmq.client.Channel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * 秒杀下单消费者
 *
 * 关键配置（application.yml 中）：
 *   prefetch: 1 —— 每次只取一条消息，处理完才取下一条（串行处理，防超卖）
 *   acknowledge-mode: manual —— 手动 ACK，处理失败时消息不丢失
 *
 * 消息处理流程：
 * 1. 接收消息
 * 2. 调用 OrderService 创建订单（包含 DB 乐观锁扣减库存）
 * 3. 创建成功：手动 ACK，消息从队列移除
 * 4. 重复消息（幂等键冲突）：ACK（不重试，幂等保证）
 * 5. 其他异常：NACK（不重新入队），转入死信队列，人工排查
 *
 * 为什么要手动 ACK？
 * 自动 ACK 模式下，消息一旦投递给消费者就会从队列删除，
 * 如果消费者处理过程中宕机，消息就丢失了。
 * 手动 ACK 保证：只有成功处理后才确认，未确认的消息重投。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SeckillOrderConsumer {

    private final OrderService orderService;

    @RabbitListener(queues = RabbitMQConst.SECKILL_ORDER_QUEUE)
    public void handleSeckillOrder(SeckillOrderMessage message, Channel channel,
                                   Message rawMessage) throws IOException {
        long deliveryTag = rawMessage.getMessageProperties().getDeliveryTag();
        log.info("接收到秒杀下单消息: userId={}, activityId={}, idempotentKey={}",
            message.getUserId(), message.getActivityId(), message.getIdempotentKey());

        try {
            orderService.createSeckillOrder(message);
            // 处理成功，手动 ACK
            channel.basicAck(deliveryTag, false);
            log.info("订单创建成功，idempotentKey={}", message.getIdempotentKey());
        } catch (DuplicateKeyException e) {
            // 幂等键重复 = 重复消息，直接 ACK（不需要重试）
            log.warn("重复消息，幂等键已存在: {}", message.getIdempotentKey());
            channel.basicAck(deliveryTag, false);
        } catch (Exception e) {
            log.error("订单创建失败: {}", e.getMessage(), e);
            // NACK，且不重新入队（requeue=false），消息会转入死信队列
            // 为什么不重新入队？防止消息因 Bug 一直重试，阻塞队列
            channel.basicNack(deliveryTag, false, false);
        }
    }
}
