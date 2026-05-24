package com.flashmall.common.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flashmall.common.enums.ResultCode;
import com.flashmall.common.filter.JwtAuthFilter;
import com.flashmall.common.result.Result;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import java.nio.charset.StandardCharsets;

/**
 * Spring Security 配置
 *
 * 核心要点：
 * 1. 无状态（Stateless）：不使用 Session，所有认证基于 JWT
 * 2. 白名单：登录、注册、静态资源、Swagger 不需要认证
 * 3. 其余接口需要登录才能访问
 * 4. /admin/** 需要 ADMIN 角色
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity       // 开启方法级别权限（@PreAuthorize）
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthFilter jwtAuthFilter;
    private final ObjectMapper objectMapper;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            // 关闭 CSRF（前后端分离 + JWT 不需要 CSRF 防护）
            .csrf(AbstractHttpConfigurer::disable)

            // 无状态，不创建 Session
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

            // 接口权限配置
            .authorizeHttpRequests(auth -> auth
                // 白名单（无需登录）
                .requestMatchers(
                    "/api/user/login",
                    "/api/user/register",
                    "/api/product/**",       // 商品列表/详情可以未登录浏览
                    "/api/seckill/list",     // 活动列表可公开
                    "/static/**",
                    "/",
                    "/index",
                    "/login",               // 登录页面
                    "/orders",              // 订单页面（前端用 JWT 鉴权）
                    "/swagger-ui/**",
                    "/v3/api-docs/**",
                    "/error"
                ).permitAll()
                // 管理员接口
                .requestMatchers("/api/admin/**").hasRole("ADMIN")
                // 其余接口需要登录
                .anyRequest().authenticated()
            )

            // 未认证时返回 JSON（而不是跳转登录页）
            .exceptionHandling(e -> e
                .authenticationEntryPoint((req, res, ex) -> {
                    res.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                    res.setContentType(MediaType.APPLICATION_JSON_VALUE);
                    res.setCharacterEncoding(StandardCharsets.UTF_8.name());
                    res.getWriter().write(objectMapper.writeValueAsString(
                        Result.fail(ResultCode.UNAUTHORIZED)
                    ));
                })
                .accessDeniedHandler((req, res, ex) -> {
                    res.setStatus(HttpServletResponse.SC_FORBIDDEN);
                    res.setContentType(MediaType.APPLICATION_JSON_VALUE);
                    res.setCharacterEncoding(StandardCharsets.UTF_8.name());
                    res.getWriter().write(objectMapper.writeValueAsString(
                        Result.fail(ResultCode.FORBIDDEN)
                    ));
                })
            )

            // 在 UsernamePasswordAuthenticationFilter 前插入 JWT 过滤器
            .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    /**
     * 密码加密器（BCrypt）
     * BCrypt 每次加密结果不同，无法反推原文，是目前存储密码的最佳实践
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
