package com.flashmall.common.aop;

import com.flashmall.common.annotation.AccessLimit;
import com.flashmall.common.constant.RedisKeyConst;
import com.flashmall.common.exception.BusinessException;
import com.flashmall.common.enums.ResultCode;
import com.flashmall.common.util.RedisLuaScript;
import com.flashmall.common.util.UserContext;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.List;

/**
 * 接口限流切面
 *
 * 处理 @AccessLimit 注解：
 * 以"接口URI + 用户ID（或IP）"为维度，在 Redis 中做滑动窗口计数。
 * 超限则抛出 BusinessException，由 GlobalExceptionHandler 统一返回错误响应。
 *
 * AOP 核心概念：
 * - @Aspect: 标记这是一个切面类
 * - @Around: 环绕通知，能在方法执行前后插入逻辑
 * - ProceedingJoinPoint: 连接点，调用 proceed() 才真正执行目标方法
 */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class AccessLimitAspect {

    private final StringRedisTemplate redisTemplate;

    @Around("@annotation(accessLimit)")
    public Object doLimit(ProceedingJoinPoint joinPoint, AccessLimit accessLimit) throws Throwable {
        // 获取当前请求
        HttpServletRequest request = getCurrentRequest();
        String identifier = buildIdentifier(request, accessLimit);

        // 执行 Lua 脚本：计数 + 判断是否超限（原子操作）
        String key = RedisKeyConst.ACCESS_LIMIT + identifier;
        Long result = redisTemplate.execute(
            RedisLuaScript.ACCESS_LIMIT_SCRIPT,
            List.of(key),
            String.valueOf(accessLimit.maxCount()),
            String.valueOf(accessLimit.seconds())
        );

        if (result != null && result == 1L) {
            log.warn("接口限流触发: key={}, maxCount={}", key, accessLimit.maxCount());
            throw new BusinessException(ResultCode.ACCESS_LIMIT.getCode(), accessLimit.msg());
        }

        return joinPoint.proceed();
    }

    /**
     * 构建限流 key 的标识符
     * 已登录用户：URI + userId（精准到用户）
     * 未登录用户：URI + IP（粗粒度限制）
     */
    private String buildIdentifier(HttpServletRequest request, AccessLimit accessLimit) {
        String uri = request.getRequestURI();
        Long userId = UserContext.getUserId();

        if (accessLimit.needLogin() && userId != null) {
            return uri + ":" + userId;
        }
        return uri + ":" + getClientIp(request);
    }

    private HttpServletRequest getCurrentRequest() {
        ServletRequestAttributes attrs =
            (ServletRequestAttributes) RequestContextHolder.currentRequestAttributes();
        return attrs.getRequest();
    }

    /**
     * 获取客户端真实 IP（处理了 Nginx 反向代理的情况）
     */
    private String getClientIp(HttpServletRequest request) {
        String ip = request.getHeader("X-Forwarded-For");
        if (ip == null || ip.isBlank() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("X-Real-IP");
        }
        if (ip == null || ip.isBlank() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getRemoteAddr();
        }
        // X-Forwarded-For 可能包含多个 IP（逗号分隔），取第一个
        if (ip != null && ip.contains(",")) {
            ip = ip.split(",")[0].trim();
        }
        return ip;
    }
}
