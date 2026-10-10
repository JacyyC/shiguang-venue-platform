package com.example.venue.notice.dto;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

public class NoticeExperimentResultResponse {
    public Long experimentId;
    public String experimentNo;
    public String name;
    public String status;
    public String targetMetric;
    public String sampleScope;
    public String providerKey;
    public String providerEnv;
    public String providerName;
    public String channel;
    public String displayName;
    public String configVersion;
    public long audienceCount;
    public long sentCount;
    public long failedCount;
    public long readCount;
    public BigDecimal readRate = new BigDecimal("0.0000");
    public List<VariantResult> variants = new ArrayList<>();

    public static class VariantResult {
        public Long variantId;
        public String variantCode;
        public String title;
        public Integer allocationPercent;
        public long audienceCount;
        public long sentCount;
        public long failedCount;
        public long readCount;
        public BigDecimal readRate = new BigDecimal("0.0000");
    }
}
