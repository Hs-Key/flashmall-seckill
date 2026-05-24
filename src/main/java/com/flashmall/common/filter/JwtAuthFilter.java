package com.flashmall.common.filter;

import com.flashmall.common.constant.RedisKeyConst;
import com.flashmall.common.util.JwtUtil;
import com.flashmall.common.util.UserContext;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * JWT 认证过滤器
 * 每次请求执行一次（OncePerRequestFilter）
 *
 * 职责：
 * 1. 从请求头 Authorization 中提取 Bearer Token
 * 2. 验证 accessToken 有效性
 * 3. 将用户信息写入 Spring Security 上下文 + ThreadLocal
 * 4. 无感续期：token 剩余有效期 < 30分钟时，在响应头返回新 token
 * 5. 请求结束后清除 ThreadLocal，防止线程池复用导致的数据污染
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtUtil jwtUtil;
    private final StringRedisTemplate redisTemplate;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        try {
            String token = extractToken(request);
            if (StringUtils.hasText(token)) {
                processToken(token, request, response);
            }
            filterChain.doFilter(request, response);
        } finally {
            // 必须清除 ThreadLocal，防止 Tomcat 线程池中线程被复用时数据污染
            UserContext.clear();
        }
    }

    private void processToken(String token, HttpServletRequest request, HttpServletResponse response) {
        try {
            Claims claims = jwtUtil.parseToken(token);
            Long userId = Long.valueOf(claims.getSubject());
            String username = claims.get("username", String.class);
            String role = claims.get("role", String.class);

            // 写入 Spring Security 上下文（用于 @PreAuthorize 等权限注解）
            var authorities = List.of(new SimpleGrantedAuthority("ROLE_" + role));
            var auth = new UsernamePasswordAuthenticationToken(userId, null, authorities);
            SecurityContextHolder.getContext().setAuthentication(auth);

            // 写入 ThreadLocal（方便业务代码快速获取当前用户）
            UserContext.set(userId, username, role);

            // 无感续期：token 快过期时自动刷新，放在响应头里
            if (jwtUtil.isAboutToExpire(token)) {
                String newToken = jwtUtil.generateAccessToken(userId, username, role);
                response.setHeader("Authorization", "Bearer " + newToken);
                response.setHeader("Access-Control-Expose-Headers", "Authorization");
                log.debug("用户 {} 的 token 已自动续期", username);
            }
        } catch (ExpiredJwtException e) {
            log.debug("token 已过期，需重新登录");
        } catch (JwtException e) {
            log.warn("无效的 token: {}", e.getMessage());
        }
    }

    /**
     * 从请求头提取 Bearer Token
     */
    private String extractToken(HttpServletRequest request) {
        String bearer = request.getHeader("Authorization");
        if (StringUtils.hasText(bearer) && bearer.startsWith("Bearer ")) {
            return bearer.substring(7);
        }
        return null;
    }
}
