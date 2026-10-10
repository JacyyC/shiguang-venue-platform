package com.example.venue.notice.dto;

import java.util.List;

public class SaveNoticeExperimentRequest {
    public String name;
    public String targetMetric;
    public String sampleScope;
    public List<Variant> variants;

    public static class Variant {
        public String variantCode;
        public String title;
        public String content;
        public Integer allocationPercent;
    }
}
