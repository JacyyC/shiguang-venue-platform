package com.example.venue.community.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("community_group")
public class CommunityGroup {
    @TableId(type = IdType.AUTO)
    public Long id;
    public String groupNo;
    public String name;
    public String description;
    public Long ownerUserId;
    public String visibility;
    public String joinPolicy;
    public String categoryCode;
    public String status;
    public String idempotencyKey;
    public String requestHash;
    public Long versionNo;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    public LocalDateTime hiddenAt;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    public LocalDateTime deletedAt;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    public LocalDateTime retainUntil;
    public LocalDateTime purgedAt;
    public LocalDateTime createTime;
    public LocalDateTime updateTime;
}
