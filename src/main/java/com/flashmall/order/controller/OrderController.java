package com.flashmall.order.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.flashmall.common.annotation.Idempotent;
import com.flashmall.common.constant.RedisKeyConst;
import com.flashmall.common.result.Result;
import com.flashmall.common.util.UserContext;
import com.flashmall.order.entity.Order;
import com.flashmall.order.service.OrderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Tag(name = "订单模块")
@RestController
@RequestMapping("/api/order")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;
    private final StringRedisTemplate redisTemplate;

    /**
     * 获取幂等 Token
     * 下单前先调用此接口获取一次性 token，
     * 然后将 token 放在下单请求的 Idempotent-Token 请求头中
     */
    @Operation(summary = "获取幂等Token（下单前调用）")
    @GetMapping("/token")
    public Result<String> getIdempotentToken() {
        String token = UUID.randomUUID().toString().replace("-", "");
        String key = RedisKeyConst.IDEMPOTENT_TOKEN + token;
        // token 有效期 5 分钟，超时未使用自动失效
        redisTemplate.opsForValue().set(key, "1", 5, TimeUnit.MINUTES);
        return Result.success(token);
    }

    /**
     * 查询用户订单列表
     */
    @Operation(summary = "查询我的订单列表")
    @GetMapping("/list")
    public Result<IPage<Order>> listOrders(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        return Result.success(orderService.listUserOrders(UserContext.getUserId(), page, size));
    }

    /**
     * 查询订单详情
     */
    @Operation(summary = "查询订单详情")
    @GetMapping("/{orderId}")
    public Result<Order> getOrder(@PathVariable String orderId) {
        return Result.success(orderService.getOrderDetail(orderId, UserContext.getUserId()));
    }

    /**
     * 通过幂等键查询订单（前端轮询秒杀结果）
     */
    @Operation(summary = "通过幂等键查询订单（轮询秒杀结果）")
    @GetMapping("/by-key/{key}")
    public Result<Order> getOrderByKey(@PathVariable String key) {
        Order order = orderService.getOrderByIdempotentKey(key);
        if (order == null) {
            return Result.fail("订单处理中，请稍后再试");
        }
        return Result.success(order);
    }

    /**
     * 模拟支付
     */
    @Operation(summary = "模拟支付订单")
    @PostMapping("/{orderId}/pay")
    public Result<Order> payOrder(@PathVariable String orderId) {
        Order order = orderService.payOrder(orderId, UserContext.getUserId());
        return Result.success("支付成功", order);
    }

    /**
     * 取消订单
     */
    @Operation(summary = "取消订单")
    @PostMapping("/{orderId}/cancel")
    public Result<Order> cancelOrder(@PathVariable String orderId) {
        Order order = orderService.cancelOrder(orderId, UserContext.getUserId());
        return Result.success("取消成功", order);
    }
}
