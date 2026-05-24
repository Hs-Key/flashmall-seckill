package com.flashmall.seckill.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 秒杀下单 MQ 消息体
 *
 * 包含创建订单所需的最少信息。
 * 不直接传整个对象，避免消息体过大，也利于消息的版本兼容。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SeckillOrderMessage implements Serializable {

    private Long userId;
    private Long activityId;
    private Long productId;

    /**
     * 幂等键 = userId + "_" + activityId
     * 消费者写入订单时作为唯一约束，防止 MQ 重投导致重复创建订单
     */
    private String idempotentKey;
}
