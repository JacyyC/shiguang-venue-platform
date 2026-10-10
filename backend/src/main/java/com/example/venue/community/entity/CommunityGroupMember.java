package com.example.venue.community.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("community_group_member")
public class CommunityGroupMember {
    @TableId(type = IdType.AUTO)
    public Long id;
    public Long groupId;
    public Long userId;
    public String memberRole;
    public String status;
    public String lastIdempotencyKey;
    public String lastRequestHash;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    public Long reviewedBy;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    public LocalDateTime reviewedAt;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    public LocalDateTime joinedAt;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    public LocalDateTime leftAt;
    public LocalDateTime createTime;
    public LocalDateTime updateTime;
    public Long activeOwnerGroupId;
}
