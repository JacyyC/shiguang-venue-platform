package com.example.venue.provider.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@TableName("ops_metric_snapshot")
public class OpsMetricSnapshot {
    @TableId(type = IdType.AUTO)
    public Long id;
    public String snapshotNo;
    public String providerKey;
    public String providerEnv;
    public String providerName;
    public String configVersion;
    public String metricKey;
    public BigDecimal metricValue;
    public String metricUnit;
    public String status;
    public LocalDateTime windowStart;
    public LocalDateTime windowEnd;
    public String sourceSummaryJson;
    public BigDecimal confidence;
    public LocalDateTime observedAt;
    public LocalDateTime expiresAt;
    public LocalDateTime createTime;
    public Integer deleted;
}
