package com.example.venue.advertising.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("advertising_decision_guard")
public class AdvertisingDecisionGuard {
    @TableId(type = IdType.AUTO)
    public Long id;
    public Long userId;
    public String slotCode;
    public String scopeKey;
    public LocalDateTime createTime;
}
