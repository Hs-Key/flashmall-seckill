package com.flashmall.user;

import com.flashmall.common.exception.BusinessException;
import com.flashmall.user.dto.LoginRequest;
import com.flashmall.user.dto.LoginResponse;
import com.flashmall.user.dto.RegisterRequest;
import com.flashmall.user.entity.User;
import com.flashmall.user.service.UserService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 用户服务集成测试
 * 需要 MySQL + Redis 运行中
 */
@SpringBootTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("用户服务集成测试")
class UserServiceTest {

    @Autowired
    private UserService userService;

    private static final String TEST_USER = "test_" + System.currentTimeMillis();

    @Test
    @Order(1)
    @DisplayName("注册：新用户注册成功")
    void testRegister() {
        RegisterRequest req = new RegisterRequest();
        req.setUsername(TEST_USER);
        req.setPassword("password123");
        req.setNickname("测试用户");
        assertDoesNotThrow(() -> userService.register(req));
    }

    @Test
    @Order(2)
    @DisplayName("登录：正确密码登录成功，返回 token")
    void testLoginSuccess() {
        LoginRequest req = new LoginRequest();
        req.setUsername(TEST_USER);
        req.setPassword("password123");
        LoginResponse response = userService.login(req);
        assertNotNull(response.getAccessToken(), "accessToken 不应为空");
        assertNotNull(response.getRefreshToken(), "refreshToken 不应为空");
        assertEquals(TEST_USER, response.getUsername());
    }

    @Test
    @Order(3)
    @DisplayName("登录：错误密码抛出 BusinessException")
    void testLoginWrongPassword() {
        LoginRequest req = new LoginRequest();
        req.setUsername(TEST_USER);
        req.setPassword("wrong_password");
        assertThrows(BusinessException.class, () -> userService.login(req));
    }

    @Test
    @Order(4)
    @DisplayName("注册：重复用户名抛出 BusinessException")
    void testRegisterDuplicate() {
        RegisterRequest req = new RegisterRequest();
        req.setUsername(TEST_USER);
        req.setPassword("password123");
        assertThrows(BusinessException.class, () -> userService.register(req));
    }
}
