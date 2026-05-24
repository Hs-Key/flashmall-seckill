package com.flashmall.common.util;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Map;

/**
 * JWT 工具类
 *
 * 双 Token 机制：
 *   - accessToken：短期有效（2小时），用于接口认证
 *   - refreshToken：长期有效（7天），存入 Redis，用于无感续期
 *
 * 无感续期策略：
 *   JwtAuthFilter 中，当 accessToken 剩余有效期 < 30分钟时，
 *   自动在响应头中返回新的 accessToken（Authorization 头）
 */
@Slf4j
@Component
public class JwtUtil {

    @Value("${jwt.secret}")
    private String secret;

    @Value("${jwt.access-ttl}")
    private long accessTtl;   // 秒

    @Value("${jwt.refresh-ttl}")
    private long refreshTtl;  // 秒

    /** accessToken 即将过期的阈值：剩余 < 30分钟时自动续期 */
    private static final long RENEWAL_THRESHOLD = 30 * 60 * 1000L;

    private SecretKey getSigningKey() {
        byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);
        return Keys.hmacShaKeyFor(keyBytes);
    }

    /**
     * 生成 accessToken
     */
    public String generateAccessToken(Long userId, String username, String role) {
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim("username", username)
                .claim("role", role)
                .claim("type", "access")
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + accessTtl * 1000))
                .signWith(getSigningKey())
                .compact();
    }

    /**
     * 生成 refreshToken（不带用户信息，只带 userId）
     */
    public String generateRefreshToken(Long userId) {
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim("type", "refresh")
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + refreshTtl * 1000))
                .signWith(getSigningKey())
                .compact();
    }

    /**
     * 解析 token，返回 Claims
     * @throws JwtException token 无效
     * @throws ExpiredJwtException token 已过期
     */
    public Claims parseToken(String token) {
        return Jwts.parser()
                .verifyWith(getSigningKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    /**
     * 从 token 中提取用户ID
     */
    public Long getUserId(String token) {
        return Long.valueOf(parseToken(token).getSubject());
    }

    /**
     * 判断 token 是否即将过期（剩余有效期 < 30分钟）
     */
    public boolean isAboutToExpire(String token) {
        try {
            Date expiration = parseToken(token).getExpiration();
            return expiration.getTime() - System.currentTimeMillis() < RENEWAL_THRESHOLD;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 获取 refreshToken 有效期（秒）
     */
    public long getRefreshTtl() {
        return refreshTtl;
    }
}
