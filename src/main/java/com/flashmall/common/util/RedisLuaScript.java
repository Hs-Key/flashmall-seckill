package com.flashmall.common.util;

import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * Redis Lua 脚本工具
 *
 * 为什么用 Lua 脚本？
 * Redis 是单线程执行命令的，但多条命令之间不是原子的。
 * Lua 脚本在 Redis 中作为一个原子操作执行，不会被其他命令打断，
 * 可以安全地做"判断 + 修改"这类需要原子性的操作。
 */
public class RedisLuaScript {

    /**
     * 秒杀脚本：活动校验 + 库存扣减一次性原子完成
     *
     * 参数：
     *   KEYS[1] = seckill:activity:{activityId}  活动元数据 Hash
     *   KEYS[2] = seckill:stock:{activityId}     库存 key
     *   KEYS[3] = seckill:users:{activityId}     已购用户集合 key
     *   ARGV[1] = userId                          当前用户ID
     *   ARGV[2] = 当前时间戳（秒）
     *
     * 返回值：
     *   -4 = 活动不存在（Redis 中无元数据）
     *   -3 = 活动尚未开始
     *   -2 = 活动已结束
     *   -1 = 该用户已购买
     *    0 = 库存不足
     *    1 = 扣减成功
     *
     * 把校验放进 Lua 是为了：
     *   1. 消除 doSeckill 热路径上的 DB 访问，Redis 一次往返完成所有判断
     *   2. 校验和扣减完全原子，避免"活动刚结束的瞬间又扣减成功"这类边界 bug
     */
    public static final RedisScript<Long> SECKILL_SCRIPT = new DefaultRedisScript<>(
            """
            local activity_key = KEYS[1]
            local stock_key    = KEYS[2]
            local users_key    = KEYS[3]
            local user_id      = ARGV[1]
            local now_ts       = tonumber(ARGV[2])

            if redis.call('exists', activity_key) == 0 then
                return -4
            end

            local start_ts = tonumber(redis.call('hget', activity_key, 'startTime'))
            local end_ts   = tonumber(redis.call('hget', activity_key, 'endTime'))
            local status   = tonumber(redis.call('hget', activity_key, 'status'))

            if now_ts < start_ts then
                return -3
            end
            if now_ts > end_ts or status == 2 then
                return -2
            end

            if redis.call('sismember', users_key, user_id) == 1 then
                return -1
            end

            local stock = tonumber(redis.call('get', stock_key))
            if stock == nil or stock <= 0 then
                return 0
            end

            redis.call('decr', stock_key)
            redis.call('sadd', users_key, user_id)
            return 1
            """,
            Long.class
    );

    /**
     * 接口访问限流脚本（滑动计数器）
     *
     * 参数：
     *   KEYS[1] = limit 计数 key
     *   ARGV[1] = 时间窗口内允许的最大请求次数
     *   ARGV[2] = 时间窗口大小（秒）
     *
     * 返回值：
     *   0 = 未超限（允许通过）
     *   1 = 已超限（拒绝）
     *
     * 实现：在 window 秒内，每次请求 +1，超过 maxCount 则拒绝
     * 使用 INCR + EXPIRE 实现计数，首次设置时才 EXPIRE
     */
    public static final RedisScript<Long> ACCESS_LIMIT_SCRIPT = new DefaultRedisScript<>(
            """
            local key      = KEYS[1]
            local maxCount = tonumber(ARGV[1])
            local window   = tonumber(ARGV[2])

            local current = tonumber(redis.call('incr', key))
            if current == 1 then
                -- 首次访问，设置过期时间
                redis.call('expire', key, window)
            end

            if current > maxCount then
                return 1
            end
            return 0
            """,
            Long.class
    );

    /**
     * 幂等 Token 校验脚本
     * 查询 token 是否存在，存在则立即删除（一次性使用）
     *
     * 参数：
     *   KEYS[1] = idempotent:token:{token}
     *
     * 返回值：
     *   1 = token 有效（已删除）
     *   0 = token 不存在（已用过或不存在）
     *
     * 为什么要原子地"查询+删除"？
     * 防止并发场景下两个请求同时验证到 token 存在，然后都通过校验，
     * 导致幂等机制失效（同一个 token 被使用两次）
     */
    public static final RedisScript<Long> IDEMPOTENT_TOKEN_SCRIPT = new DefaultRedisScript<>(
            """
            local key = KEYS[1]
            if redis.call('exists', key) == 1 then
                redis.call('del', key)
                return 1
            end
            return 0
            """,
            Long.class
    );
}
