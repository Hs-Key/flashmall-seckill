package com.flashmall.seckill.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.flashmall.common.constant.RedisKeyConst;
import com.flashmall.common.enums.ResultCode;
import com.flashmall.common.exception.BusinessException;
import com.flashmall.common.util.RedisLuaScript;
import com.flashmall.product.entity.Product;
import com.flashmall.product.mapper.ProductMapper;
import com.flashmall.seckill.dto.SeckillActivityVO;
import com.flashmall.seckill.dto.SeckillOrderMessage;
import com.flashmall.seckill.entity.SeckillActivity;
import com.flashmall.seckill.mapper.SeckillActivityMapper;
import com.flashmall.seckill.mq.SeckillOrderProducer;
import com.flashmall.seckill.service.SeckillService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 秒杀服务核心实现
 *
 * 防超卖三重保障（从外到里）：
 *
 * 第一道：本地内存标记（ConcurrentHashMap）
 *   - 速度最快，不需要网络请求
 *   - 售罄后立刻标记，后续请求直接拒绝，不打 Redis
 *   - 缺点：多实例部署时每个实例需要各自维护（可接受，仍能减轻 Redis 压力）
 *
 * 第二道：Redis Lua 脚本原子扣减
 *   - 利用 Redis 单线程 + Lua 原子性，保证 "检查库存 + 扣减 + 记录" 三步原子执行
 *   - 返回 -1：重复购买；0：库存不足；1：成功
 *
 * 第三道：数据库乐观锁
 *   - 消费者写入订单时，通过 version 字段做最终兜底
 *   - UPDATE ... WHERE version = ? AND stock > 0
 *   - 如果更新返回0，说明已被抢占，消费者回滚并恢复 Redis 库存
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SeckillServiceImpl extends ServiceImpl<SeckillActivityMapper, SeckillActivity>
        implements SeckillService {

    private final StringRedisTemplate redisTemplate;
    private final SeckillOrderProducer producer;
    private final ProductMapper productMapper;

    /**
     * 本地内存标记：activityId → 是否已售罄
     * 售罄后标记为 true，后续请求直接返回，不用请求 Redis
     *
     * 为什么用 ConcurrentHashMap 而不是 HashMap？
     * 多线程并发读写，ConcurrentHashMap 保证线程安全
     */
    private final Map<Long, Boolean> emptyStockMap = new ConcurrentHashMap<>();

    /**
     * 本地缓存：activityId → productId
     * 活动一旦创建，关联的商品不会变，缓存起来避免成功路径上的 Redis HGET
     */
    private final Map<Long, Long> productIdMap = new ConcurrentHashMap<>();

    @PostConstruct
    @Override
    public void initAllActiveStocks() {
        // 一次查出所有进行中活动，直接复用实体写入 Redis，避免每个活动再走一次 getById
        List<SeckillActivity> activeActivities = lambdaQuery()
            .eq(SeckillActivity::getStatus, 1)
            .list();
        for (SeckillActivity activity : activeActivities) {
            doLoad(activity);
        }
        log.info("已初始化 {} 个进行中的秒杀活动库存到 Redis", activeActivities.size());
    }

    /*
    缓存预热：把秒杀数据先加载到Redis中
     */
    @Override
    public void loadStockToRedis(Long activityId) {
        SeckillActivity activity = getById(activityId);
        if (activity == null) return;
        doLoad(activity);
    }

    private void doLoad(SeckillActivity activity) {
        Long activityId = activity.getId();
        String stockKey = RedisKeyConst.SECKILL_STOCK + activityId;
        // 库存单独用 String，支持原子 DECR
        redisTemplate.opsForValue().set(stockKey, String.valueOf(activity.getStock()));

        // 活动元数据（开始/结束时间、状态、productId）写入 Hash，供 Lua 脚本读取
        // 时间转成 epoch 秒，方便在 Lua 里和 ARGV[2] 数值比较
        String activityKey = RedisKeyConst.SECKILL_ACTIVITY + activityId;
        ZoneId zone = ZoneId.systemDefault();
        Map<String, String> meta = new HashMap<>();
        meta.put("startTime", String.valueOf(activity.getStartTime().atZone(zone).toEpochSecond()));
        meta.put("endTime",   String.valueOf(activity.getEndTime().atZone(zone).toEpochSecond()));
        meta.put("status",    String.valueOf(activity.getStatus()));
        meta.put("productId", String.valueOf(activity.getProductId()));
        redisTemplate.opsForHash().putAll(activityKey, meta);

        productIdMap.put(activityId, activity.getProductId());
        emptyStockMap.remove(activityId);
        log.info("活动 {} 库存与元数据已加载到 Redis: stock={}", activityId, activity.getStock());
    }

    @Override
    public List<SeckillActivityVO> listActiveActivities() {
        List<SeckillActivity> activities = lambdaQuery()
            .eq(SeckillActivity::getStatus, 1)
            .list();

        return activities.stream().map(activity -> {
            Product product = productMapper.selectById(activity.getProductId());
            return SeckillActivityVO.of(activity, product);
        }).toList();
    }

    @Override
    public SeckillActivityVO getActivityDetail(Long activityId) {
        SeckillActivity activity = getById(activityId);
        if (activity == null) {
            throw new BusinessException(ResultCode.SECKILL_NOT_FOUND);
        }
        Product product = productMapper.selectById(activity.getProductId());
        return SeckillActivityVO.of(activity, product);
    }

    @Override
    public String doSeckill(Long activityId, Long userId) {
        // ===== 第一道防线：本地内存标记 =====
        // 售罄标记存在，直接拒绝，连 Redis 都不访问
        if (Boolean.TRUE.equals(emptyStockMap.get(activityId))) {
            throw new BusinessException(ResultCode.SECKILL_STOCK_EMPTY);
        }

        // ===== 第二道防线：Redis Lua 脚本一次性校验 + 扣减 =====
        // 活动状态/时间/库存/重复购买，全部在一次 Redis 往返里原子判断，零 DB 访问
        String activityKey = RedisKeyConst.SECKILL_ACTIVITY + activityId;
        String stockKey    = RedisKeyConst.SECKILL_STOCK + activityId;
        String usersKey    = RedisKeyConst.SECKILL_USERS + activityId;
        long nowTs = System.currentTimeMillis() / 1000;

        Long result = redisTemplate.execute(
            RedisLuaScript.SECKILL_SCRIPT,
            List.of(activityKey, stockKey, usersKey),
            String.valueOf(userId),
            String.valueOf(nowTs)
        );

        if (result == null) {
            throw new BusinessException(ResultCode.SECKILL_SYSTEM_BUSY);
        }
        switch (result.intValue()) {
            case -4 -> throw new BusinessException(ResultCode.SECKILL_NOT_FOUND);
            case -3 -> throw new BusinessException(ResultCode.SECKILL_NOT_STARTED);
            case -2 -> throw new BusinessException(ResultCode.SECKILL_ENDED);
            case -1 -> throw new BusinessException(ResultCode.SECKILL_REPEAT);
            case  0 -> {
                emptyStockMap.put(activityId, true);
                throw new BusinessException(ResultCode.SECKILL_STOCK_EMPTY);
            }
        }

        // ===== Lua 返回 1：扣减成功，发送 MQ 异步下单 =====
        // productId 走本地缓存，loadStockToRedis 时已填充，避免再查 DB/Redis
        Long productId = productIdMap.computeIfAbsent(activityId, id -> {
            Object v = redisTemplate.opsForHash().get(activityKey, "productId");
            return v == null ? null : Long.valueOf(v.toString());
        });

        // 每次秒杀生成全新的幂等键：仅用于 MQ 消息去重 + DB 唯一索引兜底重复消费。
        // "同一用户在同一活动只能成功一次" 这件事由 Redis SECKILL_USERS 集合保证，
        // 不能复用 userId_activityId 作为 idempotentKey，否则取消订单后再次秒杀会
        // 与历史订单的 idempotent_key 冲突，新订单永远写不进 DB。
        String idempotentKey = UUID.randomUUID().toString().replace("-", "");
        SeckillOrderMessage message = new SeckillOrderMessage(userId, activityId,
            productId, idempotentKey);

        producer.sendOrderMessage(message);

        log.info("秒杀成功，userId={}, activityId={}, idempotentKey={}",
            userId, activityId, idempotentKey);

        // 返回幂等键，前端可以用它轮询订单创建结果
        return idempotentKey;
    }
}
