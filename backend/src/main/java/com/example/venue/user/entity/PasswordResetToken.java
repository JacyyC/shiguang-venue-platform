package com.example.venue.user.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("password_reset_token")
public class PasswordResetToken {
    @TableId(type = IdType.AUTO)
    public Long id;
    public Long userId;
    public String tokenHash;
    public LocalDateTime expiresAt;
    public LocalDateTime usedAt;
    public LocalDateTime createTime;
}
