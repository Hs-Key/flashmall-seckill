package com.flashmall.order.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("t_order")
public class Order implements Serializable {

    /** 订单ID（雪花算法生成，字符串存储，全局唯一） */
    @TableId
    private String id;

    private Long userId;

    private Long productId;

    private Long activityId;

    private String productName;

    private BigDecimal amount;

    /**
     * 订单状态（状态机）：
     * 0=待支付, 1=已支付, 2=已发货, 3=已完成, 4=已取消
     * 通过 OrderStatusEnum.canTransitionTo() 校验状态流转合法性
     */
    private Integer status;

    /**
     * 幂等键 = userId + "_" + activityId
     * DB 唯一索引约束，保证同一用户对同一活动只能创建一个订单
     * 即使 MQ 重投（消息重复消费），也只会创建一个订单
     */
    private String idempotentKey;

    private LocalDateTime createdAt;

    private LocalDateTime paidAt;
}
