package com.example.venue.siteops.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@TableName("asset_item")
public class AssetItem {
    @TableId(type = IdType.AUTO)
    public Long id;
    public String assetNo;
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
    public String status;
    public Long currentUnavailableBlockId;
    public String metadataJson;
    public LocalDateTime createTime;
    public LocalDateTime updateTime;
    public Integer deleted;
}
