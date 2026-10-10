package com.example.venue.notice.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("notice_message")
public class NoticeMessage {
    @TableId(type = IdType.AUTO)
    public Long id;
    public Long userId;
    public String title;
    public String content;
    public String type;
    public Long businessId;
    public Long batchId;
    public Long audienceId;
    public String providerEnv;
    public String providerName;
    public String deliveryStatus;
    public Integer readFlag;
    public LocalDateTime createTime;
    public LocalDateTime readTime;
}
