package com.flashmall.product.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.flashmall.product.entity.Product;

public interface ProductService {

    /**
     * 分页查询商品列表
     */
    IPage<Product> listProducts(int page, int size);

    /**
     * 查询商品详情（带缓存）
     * 缓存策略：
     *   1. 命中 Bloom Filter：不存在直接返回 null（防穿透）
     *   2. 命中 Redis 缓存：直接返回（防击穿）
     *   3. 缓存未命中：加 Redisson 分布式锁后查 DB，写入缓存（防击穿）
     */
    Product getProductById(Long productId);

    /**
     * 初始化 Bloom Filter（系统启动时调用）
     * 将所有商品 ID 加载到布隆过滤器中
     */
    void initBloomFilter();
}
