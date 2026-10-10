package com.example.venue.provider.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("provider_registry_entry")
public class ProviderRegistryEntry {
    @TableId(type = IdType.AUTO)
    public Long id;
    public String providerKey;
    public String providerDomain;
    public String providerEnv;
    public String providerName;
    public String displayName;
    public String configVersion;
    public Integer enabled;
    public String status;
    public Integer freshnessSeconds;
    public Integer failureThreshold;
    public Integer timeoutMs;
    public LocalDateTime lastSuccessAt;
    public LocalDateTime lastFailureAt;
    public Integer consecutiveFailures;
    public String lastFailureCode;
    public String lastFailureMessage;
    public String metadataJson;
    public LocalDateTime createTime;
    public LocalDateTime updateTime;
    public Integer deleted;
}
