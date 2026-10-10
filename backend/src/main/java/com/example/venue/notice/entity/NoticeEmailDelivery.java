package com.example.venue.notice.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("notice_email_delivery")
public class NoticeEmailDelivery {
    @TableId(type = IdType.AUTO)
    public Long id;
    public Long outboxId;
    public Long recipientUserId;
    public String recipientAddress;
    public String recipientMasked;
    public String templateKey;
    public String templateVersion;
    public String subjectSnapshot;
    public String bodyTextSnapshot;
    public String messageIdHeader;
    public String acceptanceStatus;
    public String downstreamStatus;
    public LocalDateTime acceptedAt;
    public LocalDateTime createTime;
    public LocalDateTime updateTime;
}
