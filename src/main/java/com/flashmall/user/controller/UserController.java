package com.flashmall.user.controller;

import com.flashmall.common.result.Result;
import com.flashmall.common.util.UserContext;
import com.flashmall.user.dto.LoginRequest;
import com.flashmall.user.dto.LoginResponse;
import com.flashmall.user.dto.RegisterRequest;
import com.flashmall.user.entity.User;
import com.flashmall.user.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@Tag(name = "用户模块")
@RestController
@RequestMapping("/api/user")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    @Operation(summary = "用户注册")
    @PostMapping("/register")
    public Result<Void> register(@RequestBody @Valid RegisterRequest request) {
        userService.register(request);
        return Result.success("注册成功", null);
    }

    @Operation(summary = "用户登录")
    @PostMapping("/login")
    public Result<LoginResponse> login(@RequestBody @Valid LoginRequest request) {
        LoginResponse response = userService.login(request);
        return Result.success(response);
    }

    @Operation(summary = "退出登录")
    @PostMapping("/logout")
    public Result<Void> logout() {
        userService.logout(UserContext.getUserId());
        return Result.success("退出成功", null);
    }

    @Operation(summary = "获取当前用户信息")
    @GetMapping("/info")
    public Result<User> getUserInfo() {
        User user = userService.getCurrentUser(UserContext.getUserId());
        return Result.success(user);
    }
}
