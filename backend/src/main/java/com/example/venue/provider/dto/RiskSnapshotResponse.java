package com.example.venue.provider.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public class RiskSnapshotResponse {
    public Long id;
    public String snapshotNo;
    public String scopeType;
    public Long scopeId;
    public String providerKey;
    public String providerEnv;
    public String providerName;
    public String configVersion;
    public BigDecimal riskScore;
    public String riskLevel;
    public LocalDateTime windowStart;
    public LocalDateTime windowEnd;
    public Integer failedLoginCount;
    public Integer paymentFailureCount;
    public Integer noticeFailureCount;
    public Integer openComplaintCount;
    public Integer severeComplaintCount;
    public String ruleSummaryJson;
    public BigDecimal confidence;
    public LocalDateTime createTime;
}
