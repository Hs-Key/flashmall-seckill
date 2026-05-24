package com.flashmall.common.constant;

/**
 * RabbitMQ 常量
 * 统一管理所有交换机、队列、路由键名称
 */
public interface RabbitMQConst {

    // ==================== 秒杀下单 ====================
    /** 秒杀订单交换机（Direct）*/
    String SECKILL_ORDER_EXCHANGE = "seckill.order.exchange";

    /** 秒杀订单队列 */
    String SECKILL_ORDER_QUEUE = "seckill.order.queue";

    /** 秒杀订单路由键 */
    String SECKILL_ORDER_ROUTING_KEY = "seckill.order";

    // ==================== 订单超时取消（死信队列）====================
    /**
     * 订单延迟队列（正常队列，TTL 后变为死信）
     * 消息发送到此队列，无消费者消费，TTL 超时后路由到死信队列
     */
    String ORDER_DELAY_QUEUE = "order.delay.queue";

    /**
     * 死信交换机（DLX）
     * 超时消息转发到此交换机
     */
    String ORDER_DLX_EXCHANGE = "order.dlx.exchange";

    /**
     * 死信队列
     * 真正处理超时取消的消费者监听此队列
     */
    String ORDER_DLX_QUEUE = "order.dlx.queue";

    /** 死信路由键 */
    String ORDER_DLX_ROUTING_KEY = "order.timeout";

    // ==================== 公共死信队列（消费失败兜底）====================
    /** 消费失败的消息最终落入此队列，人工排查 */
    String COMMON_DEAD_LETTER_QUEUE = "common.dead.letter.queue";
    String COMMON_DEAD_LETTER_EXCHANGE = "common.dead.letter.exchange";
}
