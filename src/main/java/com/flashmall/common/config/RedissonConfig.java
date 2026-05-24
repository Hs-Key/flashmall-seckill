package com.flashmall.common.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Redisson 配置（分布式锁）
 *
 * 为什么用 Redisson 而不是 SETNX？
 * - Redisson 封装了完整的分布式锁语义，包括：
 *   1. 锁的自动续期（看门狗机制）：防止业务未执行完锁就过期，被其他线程抢占
 *   2. 可重入锁：同一线程可以多次获取同一把锁
 *   3. 锁的安全释放：只有持有锁的线程才能释放（通过 Lua 脚本保证原子性）
 *   4. 公平锁/读写锁等高级特性
 *
 * SETNX 只是最简单的实现，容易出现死锁（服务宕机锁不释放）或
 * 误释放（A 释放了 B 持有的锁）等问题。
 */
@Configuration
public class RedissonConfig {

    @Value("${spring.data.redis.host}")
    private String host;

    @Value("${spring.data.redis.port}")
    private int port;

    @Value("${spring.data.redis.password:}")
    private String password;

    @Bean
    public RedissonClient redissonClient() {
        Config config = new Config();
        String address = "redis://" + host + ":" + port;
        config.useSingleServer()
              .setAddress(address)
              .setConnectionMinimumIdleSize(5)
              .setConnectionPoolSize(10)
              .setConnectTimeout(3000)
              .setTimeout(3000);

        if (password != null && !password.isBlank()) {
            config.useSingleServer().setPassword(password);
        }

        return Redisson.create(config);
    }
}
