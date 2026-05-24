package com.flashmall.common.util;

/**
 * 用户上下文工具
 * 基于 ThreadLocal 在同一个请求线程内传递当前登录用户信息
 *
 * 使用方式：
 *   JwtAuthFilter 解析 JWT 后写入 → 业务代码中随时取用 → Filter 完成后清除（防内存泄漏）
 */
public class UserContext {

    private static final ThreadLocal<Long> USER_ID_HOLDER = new ThreadLocal<>();
    private static final ThreadLocal<String> USERNAME_HOLDER = new ThreadLocal<>();
    private static final ThreadLocal<String> ROLE_HOLDER = new ThreadLocal<>();

    public static void set(Long userId, String username, String role) {
        USER_ID_HOLDER.set(userId);
        USERNAME_HOLDER.set(username);
        ROLE_HOLDER.set(role);
    }

    public static Long getUserId() {
        return USER_ID_HOLDER.get();
    }

    public static String getUsername() {
        return USERNAME_HOLDER.get();
    }

    public static String getRole() {
        return ROLE_HOLDER.get();
    }

    /**
     * 清除当前线程的用户信息
     * 必须在请求处理完成后调用，避免 Tomcat 线程池中的线程泄漏
     */
    public static void clear() {
        USER_ID_HOLDER.remove();
        USERNAME_HOLDER.remove();
        ROLE_HOLDER.remove();
    }
}
