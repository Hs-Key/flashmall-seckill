package com.flashmall.product.service.impl;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.flashmall.common.constant.RedisKeyConst;
import com.flashmall.common.exception.BusinessException;
import com.flashmall.common.enums.ResultCode;
import com.flashmall.product.entity.Product;
import com.flashmall.product.mapper.ProductMapper;
import com.flashmall.product.service.ProductService;
import com.google.common.hash.BloomFilter;
import com.google.common.hash.Funnels;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * 商品服务实现
 *
 * 缓存三大问题解决方案：
 *
 * 1. 缓存穿透：查询不存在的商品，每次都打到 DB
 *    解决：Guava BloomFilter —— 系统启动时将所有商品ID加入过滤器，
 *    查询时先判断 ID 是否可能存在，不存在直接返回，不访问 Redis 和 DB
 *    特点：BloomFilter 可能误判（说存在但不一定存在），但说不存在一定不存在
 *
 * 2. 缓存击穿：热点 key 过期瞬间，大量并发请求同时打到 DB
 *    解决：Redisson 分布式锁 —— 缓存 miss 时，只允许一个线程去查 DB 重建缓存，
 *    其他线程等待该线程完成后再读缓存
 *
 * 3. 缓存雪崩：大量 key 同时过期，DB 压力骤增
 *    解决：随机 TTL —— 基础 TTL + 0~5分钟随机偏移，
 *    分散 key 的过期时间，避免集中过期
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProductServiceImpl extends ServiceImpl<ProductMapper, Product> implements ProductService {

    private final RedisTemplate<String, Object> redisTemplate;
    private final RedissonClient redissonClient;

    /** 基础缓存时间：30分钟 */
    private static final long BASE_CACHE_TTL = 30L;

    /** 随机偏移最大值：5分钟（防雪崩） */
    private static final long RANDOM_TTL_OFFSET = 5L;

    private static final Random RANDOM = new Random();

    /**
     * Bloom Filter（内存中的位图）
     * 参数说明：
     *   - expectedInsertions: 预计插入的元素数量（1万个商品）
     *   - fpp: 误判率 0.01（1%的概率误判"存在"）
     *
     * 内存占用估算：约 ~9.6KB（10000个元素，1%误判率）
     * 生产环境中可以用 Redis BitMap 代替，支持多实例共享
     */
    private BloomFilter<CharSequence> productBloomFilter;

    /**
     * 系统启动时初始化 Bloom Filter
     * @PostConstruct 在 Bean 依赖注入完成后立即执行
     */
    @PostConstruct
    @Override
    public void initBloomFilter() {
        log.info("开始初始化商品 Bloom Filter...");
        productBloomFilter = BloomFilter.create(
            Funnels.stringFunnel(StandardCharsets.UTF_8),
            10000,   // 预期商品数量
            0.01     // 误判率 1%
        );

        // 将所有商品 ID 加入过滤器
        List<Object> productIds = lambdaQuery()
            .select(Product::getId)
            .list()
            .stream()
            .map(p -> (Object) String.valueOf(p.getId()))
            .toList();

        productIds.forEach(id -> productBloomFilter.put((String) id));
        log.info("Bloom Filter 初始化完成，共加载 {} 个商品ID", productIds.size());
    }

    @Override
    public IPage<Product> listProducts(int page, int size) {
        return lambdaQuery()
            .eq(Product::getStatus, 1)
            .orderByDesc(Product::getCreatedAt)
            .page(new Page<>(page, size));
    }

    @Override
    public Product getProductById(Long productId) {
        // === 第一道防线：Bloom Filter 防穿透 ===
        // 如果商品 ID 肯定不存在（Bloom Filter 说不存在一定不存在），直接返回 null
        if (!productBloomFilter.mightContain(String.valueOf(productId))) {
            log.debug("Bloom Filter 拦截，商品不存在: {}", productId);
            return null;
        }

        String cacheKey = RedisKeyConst.PRODUCT_DETAIL + productId;

        // === 第二道防线：Redis 缓存 ===
        Product product = (Product) redisTemplate.opsForValue().get(cacheKey);
        if (product != null) {
            log.debug("缓存命中，商品ID: {}", productId);
            return product;
        }

        // === 第三道防线：分布式锁防击穿 ===
        // 缓存 miss，多个线程竞争同一把锁，只有一个线程能去查 DB 重建缓存
        String lockKey = RedisKeyConst.LOCK_PRODUCT_CACHE + productId;
        RLock lock = redissonClient.getLock(lockKey);

        try {
            // tryLock：尝试获取锁，最多等待3秒，锁持有最多10秒（防止死锁）
            boolean locked = lock.tryLock(3, 10, TimeUnit.SECONDS);
            if (!locked) {
                // 获取锁超时，等待后重试读缓存（此时另一个线程可能已经写好了缓存）
                log.warn("获取锁超时，商品ID: {}", productId);
                Thread.sleep(200);
                product = (Product) redisTemplate.opsForValue().get(cacheKey);
                return product;
            }

            // 获取锁成功后，再次检查缓存（double check）
            // 防止：A线程释放锁后，B线程拿到锁，但缓存已经由A写好了，B不需要再查 DB
            product = (Product) redisTemplate.opsForValue().get(cacheKey);
            if (product != null) {
                return product;
            }

            // 查询数据库
            product = lambdaQuery()
                .eq(Product::getId, productId)
                .eq(Product::getStatus, 1)
                .one();

            if (product == null) {
                throw new BusinessException(ResultCode.PRODUCT_NOT_FOUND);
            }

            // 写入缓存，TTL = 基础时间 + 随机偏移（防雪崩）
            long ttl = BASE_CACHE_TTL + RANDOM.nextLong(RANDOM_TTL_OFFSET);
            redisTemplate.opsForValue().set(cacheKey, product, Duration.ofMinutes(ttl));
            log.debug("商品写入缓存，ID: {}, TTL: {}分钟", productId, ttl);

            return product;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException("查询商品失败，请重试");
        } finally {
            // 只有持有锁的线程才能释放锁
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }
}
