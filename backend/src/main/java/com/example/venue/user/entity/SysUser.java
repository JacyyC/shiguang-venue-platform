package com.example.venue.user.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("sys_user")
public class SysUser {
    @TableId(type = IdType.AUTO)
    public Long id;
    public String username;
    public String password;
    public Long credentialVersion;
    public Long userVersion;
    public String nickname;
    public String phone;
    public String email;
    public String avatar;
    public String status;
    public Long roleId;
    public LocalDateTime lastLoginTime;
    public LocalDateTime createTime;
    public LocalDateTime updateTime;
    public Integer deleted;
}
