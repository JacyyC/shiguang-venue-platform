package com.example.venue.advertising.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("advertising_conversion_callback")
public class AdvertisingConversionCallback {
    @TableId(type = IdType.AUTO)
    public Long id;
    public String callbackNo;
    public String eventKey;
    public String eventType;
    public Long reservationId;
    public Long userId;
    public Long venueId;
    public String sourceStatus;
    public String policyVersion;
    public LocalDateTime occurredAt;
    public String status;
    public String resultCode;
    public Integer attemptCount;
    public LocalDateTime nextRetryAt;
    public LocalDateTime processedAt;
    public LocalDateTime createTime;
    public LocalDateTime updateTime;
}
