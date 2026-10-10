package com.example.venue.notice.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("notice_audience_snapshot")
public class NoticeAudienceSnapshot {
    @TableId(type = IdType.AUTO)
    public Long id;
    public Long batchId;
    public Long noticeId;
    public Long userId;
    public Long venueId;
    public Long memberLevelId;
    public String segmentCode;
    public Long experimentVariantId;
    public String deliveryStatus;
    public String readStatus;
    public String excludeReason;
    public Long messageId;
    public Long lastDeliveryLogId;
    public LocalDateTime readTime;
    public String metadataJson;
    public LocalDateTime createTime;
    public LocalDateTime updateTime;
    public Integer deleted;
}
