package com.example.venue.siteops.dto;

import java.time.LocalDateTime;

public class AssetStatusRequest {
    public String toStatus;
    public String reasonCode;
    public String reasonText;
    public LocalDateTime blockStartTime;
    public LocalDateTime blockEndTime;
    public boolean releaseUnavailableBlock;
}
