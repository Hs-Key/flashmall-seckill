package com.flashmall.seckill.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("t_seckill_activity")
public class SeckillActivity implements Serializable {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long productId;

    private BigDecimal seckillPrice;

    private Integer stock;

    private LocalDateTime startTime;

    private LocalDateTime endTime;

    /** 0待开始 1进行中 2已结束 */
    private Integer status;

    /**
     * 乐观锁版本号（MyBatis-Plus @Version 注解）
     * 更新时自动添加 WHERE version = ? 条件
     * 如果 version 不匹配（被其他事务修改过），更新失败，需要重试
     * 这是防超卖的最后一道防线（兜底）
     */
    @Version
    private Integer version;

    private LocalDateTime createdAt;
}
