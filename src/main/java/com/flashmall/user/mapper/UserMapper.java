package com.flashmall.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.flashmall.user.entity.User;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface UserMapper extends BaseMapper<User> {
}
