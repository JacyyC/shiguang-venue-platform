package com.example.venue.siteops.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public class AssetRepairRequest {
    public Long siteTaskId;
    public Long repairOwnerId;
    public String supplierName;
    public String failureCode;
    public String failureDescription;
    public BigDecimal estimatedCostAmount;
    public LocalDateTime downtimeStart;
    public LocalDateTime downtimeEnd;
    public LocalDateTime blockStartTime;
    public LocalDateTime blockEndTime;
}
