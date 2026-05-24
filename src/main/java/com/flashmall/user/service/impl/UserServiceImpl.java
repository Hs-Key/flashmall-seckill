package com.flashmall.user.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.flashmall.common.constant.RedisKeyConst;
import com.flashmall.common.enums.ResultCode;
import com.flashmall.common.exception.BusinessException;
import com.flashmall.common.util.JwtUtil;
import com.flashmall.user.dto.LoginRequest;
import com.flashmall.user.dto.LoginResponse;
import com.flashmall.user.dto.RegisterRequest;
import com.flashmall.user.entity.User;
import com.flashmall.user.mapper.UserMapper;
import com.flashmall.user.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements UserService {

    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;
    private final StringRedisTemplate redisTemplate;

    @Override
    public void register(RegisterRequest request) {
        // 检查用户名是否已存在
        boolean exists = lambdaQuery()
            .eq(User::getUsername, request.getUsername())
            .exists();
        if (exists) {
            throw new BusinessException(ResultCode.USER_ALREADY_EXISTS);
        }

        User user = new User();
        user.setUsername(request.getUsername());
        user.setPassword(passwordEncoder.encode(request.getPassword()));  // BCrypt 加密
        user.setNickname(request.getNickname() != null ? request.getNickname() : request.getUsername());
        user.setPhone(request.getPhone());
        user.setRole("USER");
        user.setStatus(1);

        save(user);
        log.info("新用户注册: {}", request.getUsername());
    }

    @Override
    public LoginResponse login(LoginRequest request) {
        // 查询用户
        User user = lambdaQuery()
            .eq(User::getUsername, request.getUsername())
            .one();

        // note: 遇到业务异常直接 抛出异常 throw BusinessException
        if (user == null) {
            throw new BusinessException(ResultCode.PASSWORD_ERROR);
        }

        if (user.getStatus() == 0) {
            throw new BusinessException(ResultCode.USER_DISABLED);
        }

        // 验证密码（BCrypt 自动处理 salt，matches 方法比较）
        if (!passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            throw new BusinessException(ResultCode.PASSWORD_ERROR);
        }

        // 生成 accessToken + refreshToken
        String accessToken = jwtUtil.generateAccessToken(user.getId(), user.getUsername(), user.getRole());
        String refreshToken = jwtUtil.generateRefreshToken(user.getId());

        // 将 refreshToken 存入 Redis（支持主动失效，退出登录时删除）
        String refreshKey = RedisKeyConst.USER_REFRESH_TOKEN + user.getId();
        redisTemplate.opsForValue().set(refreshKey, refreshToken, jwtUtil.getRefreshTtl(), TimeUnit.SECONDS);

        log.info("用户登录: {}", user.getUsername());
        return new LoginResponse(accessToken, refreshToken,
            user.getId(), user.getUsername(), user.getNickname(), user.getRole());
    }

    @Override
    public void logout(Long userId) {
        // 删除 Redis 中的 refreshToken，使其即时失效
        String refreshKey = RedisKeyConst.USER_REFRESH_TOKEN + userId;
        redisTemplate.delete(refreshKey);
        log.info("用户退出登录: userId={}", userId);
    }

    @Override
    public User getCurrentUser(Long userId) {
        User user = getById(userId);
        if (user == null) {
            throw new BusinessException(ResultCode.USER_NOT_FOUND);
        }
        return user;
    }
}
