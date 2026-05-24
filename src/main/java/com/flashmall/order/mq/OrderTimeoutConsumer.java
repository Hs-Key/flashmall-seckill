package com.flashmall.order.mq;

import com.flashmall.common.constant.RabbitMQConst;
import com.flashmall.order.service.OrderService;
import com.rabbitmq.client.Channel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 订单超时取消消费者（死信队列消费者）
 *
 * 死信队列工作原理：
 * 1. 订单创建时，往 order.delay.queue（正常队列）发送一条含 orderId 的消息
 * 2. 正常队列没有消费者，消息在 TTL（30分钟）后过期，变成"死信"
 * 3. RabbitMQ 将死信路由到 order.dlx.exchange → order.dlx.queue
 * 4. 本消费者监听 order.dlx.queue，收到消息后执行超时取消逻辑
 *
 * 为什么不用定时任务？
 * - 定时任务需要扫描全表（大量订单时性能差）
 * - 定时任务有延迟（每分钟扫描一次，最多延迟60秒）
 * - 死信队列：零轮询，时间精度高，与业务完全解耦
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderTimeoutConsumer {

    private final OrderService orderService;

    @RabbitListener(queues = RabbitMQConst.ORDER_DLX_QUEUE)
    public void handleOrderTimeout(Message rawMessage, Channel channel) throws IOException {
        long deliveryTag = rawMessage.getMessageProperties().getDeliveryTag();
        String orderId = new String(rawMessage.getBody(), StandardCharsets.UTF_8);

        log.info("收到订单超时取消消息: orderId={}", orderId);

        try {
            orderService.timeoutCancelOrder(orderId);
            channel.basicAck(deliveryTag, false);
            log.info("订单超时取消处理完成: orderId={}", orderId);
        } catch (Exception e) {
            log.error("订单超时取消处理失败: orderId={}, error={}", orderId, e.getMessage(), e);
            // 失败时不重新入队（避免死循环），转入公共死信队列人工处理
            channel.basicNack(deliveryTag, false, false);
        }
    }
}
