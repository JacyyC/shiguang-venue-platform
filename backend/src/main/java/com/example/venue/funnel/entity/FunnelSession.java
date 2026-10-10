package com.example.venue.funnel.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("funnel_session")
public class FunnelSession {
    @TableId(type = IdType.AUTO)
    public Long id;
    public String sessionKey;
    public Long userId;
    public String visitorIdHash;
    public String sourceChannel;
    public String campaignId;
    public String entryPath;
    public String landingPage;
    public String deviceType;
    public String browserFamily;
    public String userAgentHash;
    public String providerEnv;
    public LocalDateTime firstSeenAt;
    public LocalDateTime lastSeenAt;
    public String metadataJson;
    public LocalDateTime createTime;
    public LocalDateTime updateTime;
    public Integer deleted;
}
