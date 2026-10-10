package com.example.venue.provider.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public class OpsMetricSnapshotResponse {
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
}
