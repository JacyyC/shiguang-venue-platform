package com.example.venue.notice.dto;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

public class NoticeExperimentResponse {
    public Long id;
    public String experimentNo;
    public String name;
    public String status;
    public String targetMetric;
    public String sampleScope;
    public LocalDateTime startTime;
    public LocalDateTime endTime;
    public Long ownerId;
    public List<Variant> variants = new ArrayList<>();

    public static class Variant {
        public Long id;
        public Long experimentId;
        public String variantCode;
        public String title;
        public String content;
        public Integer allocationPercent;
    }
}
