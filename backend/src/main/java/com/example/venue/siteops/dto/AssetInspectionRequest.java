package com.example.venue.siteops.dto;

import java.time.LocalDateTime;

public class AssetInspectionRequest {
    public Long siteTaskId;
    public Long inspectorUserId;
    public String inspectionResult;
    public String riskLevel;
    public String checklistJson;
    public String issueSummary;
    public LocalDateTime nextInspectionDueAt;
    public LocalDateTime inspectedAt;
    public String metadataJson;
}
