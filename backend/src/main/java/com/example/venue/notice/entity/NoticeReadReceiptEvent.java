package com.example.venue.notice.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("notice_read_receipt_event")
public class NoticeReadReceiptEvent {
    @TableId(type = IdType.AUTO)
    public Long id;
    public String receiptNo;
    public Long batchId;
    public Long audienceId;
    public Long noticeId;
    public Long messageId;
    public Long userId;
    public String entry;
    public LocalDateTime readTime;
    public String idempotencyKey;
    public String metadataJson;
    public LocalDateTime createTime;
}
