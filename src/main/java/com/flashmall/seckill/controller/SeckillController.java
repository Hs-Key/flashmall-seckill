package com.flashmall.seckill.controller;

import com.flashmall.common.annotation.AccessLimit;
import com.flashmall.common.annotation.Idempotent;
import com.flashmall.common.result.Result;
import com.flashmall.common.util.UserContext;
import com.flashmall.seckill.dto.SeckillActivityVO;
import com.flashmall.seckill.service.SeckillService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "秒杀模块")
@RestController
@RequestMapping("/api/seckill")
@RequiredArgsConstructor
public class SeckillController {

    private final SeckillService seckillService;

    @Operation(summary = "获取进行中的秒杀活动列表")
    @GetMapping("/list")
    public Result<List<SeckillActivityVO>> listActivities() {
        return Result.success(seckillService.listActiveActivities());
    }

    @Operation(summary = "获取秒杀活动详情")
    @GetMapping("/{activityId}")
    public Result<SeckillActivityVO> getActivity(@PathVariable Long activityId) {
        return Result.success(seckillService.getActivityDetail(activityId));
    }

    /**
     * 执行秒杀
     *
     * @AccessLimit：5秒内同一用户最多请求3次（防刷）
     *
     * 返回值：幂等键（用于前端轮询订单状态）
     * 前端收到后可每隔1秒查询 GET /api/order/by-key/{key} 确认订单是否创建成功
     */
    @Operation(summary = "执行秒杀")
    @PostMapping("/do/{activityId}")
    @AccessLimit(seconds = 5, maxCount = 3, msg = "操作过于频繁，请稍后再试")
    @Idempotent
    public Result<String> doSeckill(@PathVariable Long activityId) {
        String idempotentKey = seckillService.doSeckill(activityId, UserContext.getUserId());
        return Result.success("秒杀请求已提交，正在处理中...", idempotentKey);
    }
}
