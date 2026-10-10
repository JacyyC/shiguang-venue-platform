package com.example.venue.placement.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("placement_decision")
public class PlacementDecision {
    @TableId(type = IdType.AUTO)
    public Long id;
    public String decisionNo;
    public Long userId;
    public Long slotId;
    public Long candidateVersionId;
    public String tokenHash;
    public String ruleVersionSnapshot;
    public String audienceSnapshotJson;
    public String frequencySnapshotJson;
    public String sourceSnapshotJson;
    public Integer prioritySnapshot;
    public LocalDateTime expiresAt;
    public LocalDateTime consumedAt;
    public String status;
    public LocalDateTime createTime;
}
