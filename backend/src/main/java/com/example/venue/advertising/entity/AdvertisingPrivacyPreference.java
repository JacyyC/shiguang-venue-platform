package com.example.venue.advertising.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("advertising_privacy_preference")
public class AdvertisingPrivacyPreference {
    @TableId(type = IdType.AUTO)
    public Long id;
    public Long userId;
    public Integer commercialEnabled;
    public Integer measurementEnabled;
    public String policyVersion;
    public LocalDateTime changedAt;
    public LocalDateTime createTime;
    public LocalDateTime updateTime;
}
