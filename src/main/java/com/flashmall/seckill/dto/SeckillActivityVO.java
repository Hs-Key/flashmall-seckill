package com.flashmall.seckill.dto;

import com.flashmall.product.entity.Product;
import com.flashmall.seckill.entity.SeckillActivity;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 秒杀活动 VO（包含商品信息）
 */
@Data
public class SeckillActivityVO {

    private Long activityId;
    private Long productId;
    private String productName;
    private String productImageUrl;
    private BigDecimal originalPrice;
    private BigDecimal seckillPrice;
    private Integer stock;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private Integer status;

    public static SeckillActivityVO of(SeckillActivity activity, Product product) {
        SeckillActivityVO vo = new SeckillActivityVO();
        vo.setActivityId(activity.getId());
        vo.setProductId(activity.getProductId());
        vo.setProductName(product.getName());
        vo.setProductImageUrl(product.getImageUrl());
        vo.setOriginalPrice(product.getPrice());
        vo.setSeckillPrice(activity.getSeckillPrice());
        vo.setStock(activity.getStock());
        vo.setStartTime(activity.getStartTime());
        vo.setEndTime(activity.getEndTime());
        vo.setStatus(activity.getStatus());
        return vo;
    }
}
