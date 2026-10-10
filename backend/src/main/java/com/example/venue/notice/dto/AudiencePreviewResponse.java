package com.example.venue.notice.dto;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class AudiencePreviewResponse {
    public String targetScope;
    public int audienceCount;
    public int deliverableCount;
    public int excludedCount;
    public Map<String, Long> excludeReasonCounts;
    public List<AudienceUser> samples = new ArrayList<>();
}
