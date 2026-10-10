package com.example.venue.community.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("community_user_block")
public class CommunityUserBlock {
    @TableId(type = IdType.AUTO)
    public Long id;
    public Long blockerUserId;
    public Long blockedUserId;
    public String status;
    public String lastIdempotencyKey;
    public LocalDateTime createdAt;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    public LocalDateTime removedAt;
    public LocalDateTime updateTime;
}
