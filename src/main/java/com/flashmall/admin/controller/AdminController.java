package com.flashmall.admin.controller;

import com.flashmall.common.result.Result;
import com.flashmall.seckill.entity.SeckillActivity;
import com.flashmall.seckill.service.SeckillService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@Tag(name = "管理员模块")
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminController {

    private final SeckillService seckillService;

    @Operation(summary = "创建秒杀活动")
    @PostMapping("/seckill/activity")
    public Result<Void> createActivity(@RequestBody @Valid SeckillActivity activity) {
        activity.setStatus(0);
        seckillService.save(activity);
        return Result.success("活动创建成功", null);
    }

    @Operation(summary = "开始秒杀活动（将库存加载到Redis）")
    @PostMapping("/seckill/activity/{id}/start")
    public Result<Void> startActivity(@PathVariable Long id) {
        SeckillActivity activity = seckillService.getById(id);
        if (activity == null) return Result.fail("活动不存在");

        activity.setStatus(1);
        seckillService.updateById(activity);
        seckillService.loadStockToRedis(id);
        return Result.success("活动已启动，库存已加载到Redis", null);
    }

    @Operation(summary = "结束秒杀活动")
    @PostMapping("/seckill/activity/{id}/end")
    public Result<Void> endActivity(@PathVariable Long id) {
        SeckillActivity activity = seckillService.getById(id);
        if (activity == null) return Result.fail("活动不存在");

        activity.setStatus(2);
        seckillService.updateById(activity);
        return Result.success("活动已结束", null);
    }
}
