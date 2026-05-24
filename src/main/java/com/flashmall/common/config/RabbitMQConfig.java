package com.flashmall.common.config;

import com.flashmall.common.constant.RabbitMQConst;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.Map;

/**
 * RabbitMQ 配置
 *
 * 声明所有的交换机（Exchange）、队列（Queue）和绑定（Binding）
 *
 * 本项目 RabbitMQ 使用了两种模式：
 *
 * 1. 秒杀异步下单（削峰填谷）
 *    生产者 ──> seckill.order.exchange ──> seckill.order.queue ──> 消费者
 *    好处：秒杀瞬时高并发请求，先快速入队返回"排队中"，然后消费者串行处理，DB 压力均摊
 *
 * 2. 订单超时取消（死信队列/DLX 模式）
 *    生产者 ──> order.delay.queue（TTL=30分钟，无消费者）
 *              ↓ 消息超时，变成死信
 *    order.dlx.exchange ──> order.dlx.queue ──> 超时取消消费者
 *    好处：不需要定时任务轮询，资源消耗极低，时间精度高
 */
@Slf4j
@Configuration
public class RabbitMQConfig {

    // ============================================================
    //  消息序列化器（JSON）
    // ============================================================

    @Bean
    public MessageConverter messageConverter() {
        // 消息体使用 JSON 序列化，而不是默认的 JDK 序列化
        return new Jackson2JsonMessageConverter();
    }

    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory,
                                         MessageConverter messageConverter) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(messageConverter);

        // 生产者消息确认：消息到达交换机后回调
        template.setConfirmCallback((correlationData, ack, cause) -> {
            if (!ack) {
                log.error("消息发送到交换机失败: {}, cause: {}", correlationData, cause);
                // 实际项目中此处可做重投或告警
            }
        });

        // 消息回退：消息到达交换机但无法路由到队列时回调
        template.setReturnsCallback(returned -> {
            log.error("消息路由失败: exchange={}, routingKey={}, message={}",
                returned.getExchange(), returned.getRoutingKey(),
                new String(returned.getMessage().getBody()));
        });

        return template;
    }

    // ============================================================
    //  1. 秒杀下单队列
    // ============================================================

    @Bean
    public DirectExchange seckillOrderExchange() {
        return ExchangeBuilder.directExchange(RabbitMQConst.SECKILL_ORDER_EXCHANGE)
                .durable(true)  // 持久化（重启 RabbitMQ 后不丢失）
                .build();
    }

    @Bean
    public Queue seckillOrderQueue() {
        return QueueBuilder.durable(RabbitMQConst.SECKILL_ORDER_QUEUE).build();
    }

    @Bean
    public Binding seckillOrderBinding() {
        return BindingBuilder
                .bind(seckillOrderQueue())
                .to(seckillOrderExchange())
                .with(RabbitMQConst.SECKILL_ORDER_ROUTING_KEY);
    }

    // ============================================================
    //  2. 订单超时取消（死信队列）
    // ============================================================

    /**
     * 死信交换机（DLX）
     * 接收超时/被拒绝的"死信"消息并转发到死信队列
     */
    @Bean
    public DirectExchange orderDlxExchange() {
        return ExchangeBuilder.directExchange(RabbitMQConst.ORDER_DLX_EXCHANGE)
                .durable(true)
                .build();
    }

    /**
     * 死信队列
     * 超时取消消费者监听此队列
     */
    @Bean
    public Queue orderDlxQueue() {
        return QueueBuilder.durable(RabbitMQConst.ORDER_DLX_QUEUE).build();
    }

    @Bean
    public Binding orderDlxBinding() {
        return BindingBuilder
                .bind(orderDlxQueue())
                .to(orderDlxExchange())
                .with(RabbitMQConst.ORDER_DLX_ROUTING_KEY);
    }

    /**
     * 订单延迟队列（正常队列，无消费者）
     *
     * 关键参数：
     *   x-dead-letter-exchange：消息超时后路由到哪个交换机
     *   x-dead-letter-routing-key：死信消息的路由键
     *   x-message-ttl：队列中所有消息的 TTL（30分钟）
     *
     * 注意：TTL 在此通过队列统一设置，保证所有订单超时时间一致。
     * 如果需要给每个消息设置不同的超时时间，可以在发送时设置 MessageProperties.expiration。
     */
    @Bean
    public Queue orderDelayQueue() {
        Map<String, Object> args = new HashMap<>();
        args.put("x-dead-letter-exchange", RabbitMQConst.ORDER_DLX_EXCHANGE);
        args.put("x-dead-letter-routing-key", RabbitMQConst.ORDER_DLX_ROUTING_KEY);
        args.put("x-message-ttl", 30 * 60 * 1000);  // 30分钟（毫秒）
        return QueueBuilder.durable(RabbitMQConst.ORDER_DELAY_QUEUE)
                .withArguments(args)
                .build();
    }

    // ============================================================
    //  3. 公共死信队列（消费失败兜底，人工排查）
    // ============================================================

    @Bean
    public FanoutExchange commonDeadLetterExchange() {
        return ExchangeBuilder.fanoutExchange(RabbitMQConst.COMMON_DEAD_LETTER_EXCHANGE)
                .durable(true)
                .build();
    }

    @Bean
    public Queue commonDeadLetterQueue() {
        return QueueBuilder.durable(RabbitMQConst.COMMON_DEAD_LETTER_QUEUE).build();
    }

    @Bean
    public Binding commonDeadLetterBinding() {
        return BindingBuilder
                .bind(commonDeadLetterQueue())
                .to(commonDeadLetterExchange());
    }
}
