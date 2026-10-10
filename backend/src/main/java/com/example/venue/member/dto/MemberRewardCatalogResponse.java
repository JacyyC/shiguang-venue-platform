package com.example.venue.member.dto;

import java.time.LocalDateTime;

public class MemberRewardCatalogResponse {
    public Long itemId;
    public String itemCode;
    public String itemName;
    public String descriptionText;
    public String status;
    public Long versionId;
    public Integer versionNo;
    public Integer pointsCost;
    public String minimumLevelCode;
    public String minimumLevelName;
    public Integer minimumGrowthThreshold;
    public Long couponTemplateId;
    public Integer validityDays;
    public LocalDateTime effectiveStartTime;
    public LocalDateTime effectiveEndTime;
    public Integer availablePoints;
    public Integer pendingOffsetPoints;
    public boolean eligible;
    public String unavailableReason;
}
