package com.example.venue.advertising.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("advertising_creative_version")
public class AdvertisingCreativeVersion {
    @TableId(type = IdType.AUTO)
    public Long id;
    public String creativeNo;
    public Long campaignId;
    public Integer versionNo;
    public String headline;
    public String bodyText;
    public String callToAction;
    public String targetType;
    public Long targetId;
    public String status;
    public Long createdBy;
    public Long submittedBy;
    public LocalDateTime submittedAt;
    public Long reviewedBy;
    public LocalDateTime reviewedAt;
    public Long publishedBy;
    public LocalDateTime publishedAt;
    public Long pausedBy;
    public LocalDateTime pausedAt;
    public Long retiredBy;
    public LocalDateTime retiredAt;
    public LocalDateTime createTime;
    public LocalDateTime updateTime;
}
