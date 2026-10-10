package com.example.venue.siteops.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

public class AssetRequest {
    public Long venueId;
    public Long spaceId;
    public String assetType;
    public String name;
    public String brand;
    public String model;
    public String serialNo;
    public String supplierName;
    public LocalDate purchaseDate;
    public BigDecimal purchaseAmount;
    public LocalDate warrantyStartDate;
    public LocalDate warrantyEndDate;
    public Long responsibleUserId;
    public String metadataJson;
}
