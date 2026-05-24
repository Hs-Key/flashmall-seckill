package com.flashmall.seckill;

import com.flashmall.common.util.RedisLuaScript;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 秒杀 Lua 脚本测试
 *
 * 这是最关键的测试——验证 Lua 脚本的防超卖、防重复购买逻辑正确。
 * 需要 Redis 服务运行中（docker compose up -d）
 */
@SpringBootTest
@DisplayName("秒杀 Lua 脚本单元测试")
class SeckillLuaScriptTest {

    @Autowired
    private StringRedisTemplate redisTemplate;

    private static final String STOCK_KEY = "test:seckill:stock:1";
    private static final String USERS_KEY = "test:seckill:users:1";

    @BeforeEach
    void setUp() {
        // 每次测试前重置 Redis 状态
        redisTemplate.delete(STOCK_KEY);
        redisTemplate.delete(USERS_KEY);
        redisTemplate.opsForValue().set(STOCK_KEY, "5");  // 设置库存为5
    }

    @Test
    @DisplayName("正常扣减：库存足够，用户未购买 → 返回1")
    void testNormalDecrease() {
        Long result = redisTemplate.execute(
            RedisLuaScript.SECKILL_SCRIPT,
            List.of(STOCK_KEY, USERS_KEY),
            "user1"
        );
        assertEquals(1L, result, "扣减成功应返回1");

        // 验证库存已减1
        String stock = redisTemplate.opsForValue().get(STOCK_KEY);
        assertEquals("4", stock, "库存应从5减为4");

        // 验证用户已被记录
        Boolean isMember = redisTemplate.opsForSet().isMember(USERS_KEY, "user1");
        assertEquals(Boolean.TRUE, isMember, "user1 应被加入已购集合");
    }

    @Test
    @DisplayName("重复购买：同一用户再次购买 → 返回-1")
    void testRepeatPurchase() {
        // 第一次购买
        redisTemplate.execute(RedisLuaScript.SECKILL_SCRIPT, List.of(STOCK_KEY, USERS_KEY), "user1");

        // 第二次购买（同一用户）
        Long result = redisTemplate.execute(
            RedisLuaScript.SECKILL_SCRIPT,
            List.of(STOCK_KEY, USERS_KEY),
            "user1"
        );
        assertEquals(-1L, result, "重复购买应返回-1");

        // 库存只应减少1次
        String stock = redisTemplate.opsForValue().get(STOCK_KEY);
        assertEquals("4", stock, "重复购买不应再扣减库存");
    }

    @Test
    @DisplayName("库存不足：库存为0时购买 → 返回0")
    void testStockEmpty() {
        // 模拟库存已为0
        redisTemplate.opsForValue().set(STOCK_KEY, "0");

        Long result = redisTemplate.execute(
            RedisLuaScript.SECKILL_SCRIPT,
            List.of(STOCK_KEY, USERS_KEY),
            "user99"
        );
        assertEquals(0L, result, "库存不足应返回0");
    }

    @Test
    @DisplayName("防超卖：5个并发请求，库存5，只应成功5个")
    void testAntiOversell() {
        int stock = 5;
        int requestCount = 10;
        redisTemplate.opsForValue().set(STOCK_KEY, String.valueOf(stock));

        long successCount = 0;
        for (int i = 1; i <= requestCount; i++) {
            Long result = redisTemplate.execute(
                RedisLuaScript.SECKILL_SCRIPT,
                List.of(STOCK_KEY, USERS_KEY),
                "user" + i
            );
            if (result != null && result == 1L) {
                successCount++;
            }
        }

        assertEquals(stock, successCount, "成功数应等于初始库存");

        String remainingStock = redisTemplate.opsForValue().get(STOCK_KEY);
        assertEquals("0", remainingStock, "库存应减为0");
    }
}
