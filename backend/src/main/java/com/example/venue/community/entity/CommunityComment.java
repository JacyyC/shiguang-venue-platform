package com.example.venue.community.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("community_comment")
public class CommunityComment {
    @TableId(type = IdType.AUTO)
    public Long id;
    public String commentNo;
    public Long postId;
    public Long groupId;
    public Long authorUserId;
    public String contentText;
    public String status;
    public String idempotencyKey;
    public String requestHash;
    public Long versionNo;
    public LocalDateTime publishedAt;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    public Long hiddenBy;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    public String hiddenScope;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    public String hiddenReasonCode;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    public LocalDateTime hiddenAt;
    public Long deletedBy;
    public String deletionReason;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    public LocalDateTime deletedAt;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    public LocalDateTime retainUntil;
    public LocalDateTime purgedAt;
    public LocalDateTime createTime;
    public LocalDateTime updateTime;
}
