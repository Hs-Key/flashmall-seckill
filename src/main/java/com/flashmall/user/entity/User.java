package com.flashmall.user.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("t_user")
public class User {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String username;

    @JsonIgnore  // 密码不序列化到 JSON（防止接口泄露密码）
    private String password;

    private String nickname;

    private String phone;

    private String role;

    private Integer status;

    private LocalDateTime createdAt;
}
