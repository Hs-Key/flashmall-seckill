package com.flashmall.seckill.mq;

import com.flashmall.common.constant.RabbitMQConst;
import com.flashmall.seckill.dto.SeckillOrderMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * 秒杀订单 MQ 生产者
 *
 * 职责：将秒杀成功后的下单消息发送到 RabbitMQ
 *
 * 消息可靠性保证：
 * - publisher-confirm：消息到达 Exchange 后 Broker 回调确认
 * - publisher-returns：消息未能路由到 Queue 时回调
 * - CorrelationData：每条消息附带唯一 ID，便于追踪
 *
 * 为什么异步下单（削峰填谷）？
 * 秒杀瞬间可能有数千并发，如果同步写 DB 会压垮数据库。
 * 将下单消息放入 MQ，立即返回"排队中"给用户，
 * 消费者以数据库可承受的速率串行处理，DB 压力均摊。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SeckillOrderProducer {

    private final RabbitTemplate rabbitTemplate;

    public void sendOrderMessage(SeckillOrderMessage message) {
        String messageId = UUID.randomUUID().toString();
        CorrelationData correlationData = new CorrelationData(messageId);

        rabbitTemplate.convertAndSend(
            RabbitMQConst.SECKILL_ORDER_EXCHANGE,
            RabbitMQConst.SECKILL_ORDER_ROUTING_KEY,
            message,
            correlationData
        );

        log.info("秒杀下单消息已发送: userId={}, activityId={}, messageId={}",
            message.getUserId(), message.getActivityId(), messageId);
    }
}
