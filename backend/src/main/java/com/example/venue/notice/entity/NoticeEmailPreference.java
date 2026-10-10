package com.example.venue.notice.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("notice_email_preference")
public class NoticeEmailPreference {
    @TableId(type = IdType.AUTO)
    public Long id;
    public Long userId;
    public Integer transactionalEmailEnabled;
    public LocalDateTime createTime;
    public LocalDateTime updateTime;
}
