package com.example.venue.siteops.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@TableName("asset_repair_record")
public class AssetRepairRecord {
    @TableId(type = IdType.AUTO)
    public Long id;
    public String repairNo;
    public Long assetId;
    public Long venueId;
    public Long spaceId;
    public Long siteTaskId;
    public String status;
    public Long requestedBy;
    public Long repairOwnerId;
    public String supplierName;
    public String failureCode;
    public String failureDescription;
    public BigDecimal estimatedCostAmount;
    public BigDecimal actualCostAmount;
    public LocalDateTime downtimeStart;
    public LocalDateTime downtimeEnd;
    public Long unavailableBlockId;
    public String resolution;
    public String sourceType;
    public String metadataJson;
    public LocalDateTime createTime;
    public LocalDateTime updateTime;
    public Integer deleted;
}
