package com.flashmall.user.service;

import com.flashmall.user.dto.LoginRequest;
import com.flashmall.user.dto.LoginResponse;
import com.flashmall.user.dto.RegisterRequest;
import com.flashmall.user.entity.User;

public interface UserService {

    /**
     * 用户注册
     */
    void register(RegisterRequest request);

    /**
     * 用户登录
     * @return 包含 accessToken、refreshToken 的登录响应
     */
    LoginResponse login(LoginRequest request);

    /**
     * 退出登录（删除 Redis 中的 refreshToken，使 token 即时失效）
     */
    void logout(Long userId);

    /**
     * 获取当前用户信息
     */
    User getCurrentUser(Long userId);
}
