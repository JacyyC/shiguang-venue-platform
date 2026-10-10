package com.example.venue.notice.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("notice_delivery_batch")
public class NoticeDeliveryBatch {
    @TableId(type = IdType.AUTO)
    public Long id;
    public String batchNo;
    public Long noticeId;
    public String targetScope;
    public String targetRuleJson;
    public Integer audienceCount;
    public Integer deliverableCount;
    public Integer excludedCount;
    public String status;
    public String providerEnv;
    public String providerName;
    public String configVersion;
    public String sourceType;
    public Long publisherId;
    public Long experimentId;
    public String metadataJson;
    public LocalDateTime createTime;
    public LocalDateTime updateTime;
    public Integer deleted;
}
