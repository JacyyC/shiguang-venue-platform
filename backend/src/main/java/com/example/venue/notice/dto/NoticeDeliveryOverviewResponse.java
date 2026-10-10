package com.example.venue.notice.dto;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class NoticeDeliveryOverviewResponse {
    public long batchCount;
    public long audienceCount;
    public long sentCount;
    public long acceptedCount;
    public long retryWaitingCount;
    public long failedCount;
    public long deliveredCount;
    public long bouncedCount;
    public long unreadCount;
    public long readCount;
    public BigDecimal readRate = new BigDecimal("0.0000");
    public Map<String, Long> failureReasonCounts = new LinkedHashMap<>();
    public String providerEnv;
    public String providerName;
    public String configVersion;
    public String channel;
    public List<String> dataGaps = new ArrayList<>();
}
