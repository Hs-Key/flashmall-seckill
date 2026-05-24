package com.flashmall.seckill.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.flashmall.seckill.dto.SeckillActivityVO;
import com.flashmall.seckill.entity.SeckillActivity;

import java.util.List;

public interface SeckillService extends IService<SeckillActivity> {

    /**
     * 获取进行中的秒杀活动列表（含商品信息）
     */
    List<SeckillActivityVO> listActiveActivities();

    /**
     * 获取活动详情
     */
    SeckillActivityVO getActivityDetail(Long activityId);

    /**
     * 执行秒杀
     * 流程：本地标记 → Redis Lua 扣减 → MQ 异步下单
     * @return 订单幂等键（前端用于查询订单状态）
     */
    String doSeckill(Long activityId, Long userId);

    /**
     * 将活动库存加载到 Redis（活动开始时调用）
     */
    void loadStockToRedis(Long activityId);

    /**
     * 系统启动时，将所有进行中活动的库存加载到 Redis
     */
    void initAllActiveStocks();
}
