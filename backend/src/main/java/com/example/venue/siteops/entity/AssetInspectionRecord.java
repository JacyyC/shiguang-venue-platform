package com.example.venue.siteops.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("asset_inspection_record")
public class AssetInspectionRecord {
    @TableId(type = IdType.AUTO)
    public Long id;
    public String inspectionNo;
    public Long assetId;
    public Long venueId;
    public Long spaceId;
    public Long siteTaskId;
    public Long inspectorUserId;
    public String inspectionResult;
    public String riskLevel;
    public String checklistJson;
    public String issueSummary;
    public LocalDateTime nextInspectionDueAt;
    public LocalDateTime inspectedAt;
    public String metadataJson;
    public LocalDateTime createTime;
    public LocalDateTime updateTime;
    public Integer deleted;
}
