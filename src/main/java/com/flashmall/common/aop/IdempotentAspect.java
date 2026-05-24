package com.flashmall.common.aop;

import com.flashmall.common.annotation.Idempotent;
import com.flashmall.common.constant.RedisKeyConst;
import com.flashmall.common.exception.BusinessException;
import com.flashmall.common.enums.ResultCode;
import com.flashmall.common.util.RedisLuaScript;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.List;

/**
 * 幂等性切面
 *
 * 处理 @Idempotent 注解：
 * 1. 从请求头中获取幂等 token
 * 2. 通过 Lua 脚本原子地"校验 + 删除" Redis 中的 token
 * 3. token 有效则放行；无效则抛出异常
 *
 * 为什么"校验 + 删除"要原子执行？
 * 高并发下两个完全相同的请求同时到达：
 * - 线程A：查询 token 存在
 * - 线程B：查询 token 存在（线程A还没删）
 * - 线程A：删除 token，通过
 * - 线程B：通过（幂等失效！）
 * 用 Lua 脚本保证"查到就删，只有一个线程能查到"，解决竞态条件。
 */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class IdempotentAspect {

    private final StringRedisTemplate redisTemplate;

    @Around("@annotation(idempotent)")
    public Object checkIdempotent(ProceedingJoinPoint joinPoint, Idempotent idempotent) throws Throwable {
        HttpServletRequest request = getCurrentRequest();
        String token = request.getHeader(idempotent.headerName());

        if (!StringUtils.hasText(token)) {
            throw new BusinessException(ResultCode.IDEMPOTENT_TOKEN_MISSING);
        }

        String key = RedisKeyConst.IDEMPOTENT_TOKEN + token;

        // Lua 脚本：检查 token 是否存在，存在则原子删除，返回 1；否则返回 0
        Long result = redisTemplate.execute(
            RedisLuaScript.IDEMPOTENT_TOKEN_SCRIPT,
            List.of(key)
        );

        if (result == null || result == 0L) {
            log.warn("幂等校验失败，token 不存在或已使用: {}", token);
            throw new BusinessException(ResultCode.IDEMPOTENT_TOKEN_INVALID.getCode(),
                idempotent.msg());
        }

        return joinPoint.proceed();
    }

    private HttpServletRequest getCurrentRequest() {
        ServletRequestAttributes attrs =
            (ServletRequestAttributes) RequestContextHolder.currentRequestAttributes();
        return attrs.getRequest();
    }
}
