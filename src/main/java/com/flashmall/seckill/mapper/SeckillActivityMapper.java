package com.flashmall.seckill.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.flashmall.seckill.entity.SeckillActivity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface SeckillActivityMapper extends BaseMapper<SeckillActivity> {

    /**
     * 按值校验扣减库存（防超卖兜底）
     *
     * 生成的 SQL：
     *   UPDATE t_seckill_activity
     *   SET stock = stock - 1, version = version + 1
     *   WHERE id = #{id} AND stock > 0
     *
     * 这里 "WHERE stock > 0" 本身就是行级原子的"按值校验+扣减"，
     * 不需要 version 来检测并发——这张表只有 stock 一个会被并发修改的字段，
     * 没有"快照读后再写"的场景需要 version 来防旁路修改。
     * version 仍然 +1，便于追踪修改次数与日志审计。
     *
     * 返回值：受影响的行数（0 = 库存已为 0）
     */
    @Update("UPDATE t_seckill_activity " +
            "SET stock = stock - 1, version = version + 1 " +
            "WHERE id = #{id} AND stock > 0")
    int decreaseStockIfAvailable(@Param("id") Long id);
}
