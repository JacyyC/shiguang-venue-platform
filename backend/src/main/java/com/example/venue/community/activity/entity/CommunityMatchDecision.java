package com.example.venue.community.activity.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("community_match_decision")
public class CommunityMatchDecision {
    @TableId(type = IdType.AUTO)
    public Long id;
    public String decisionNo;
    public Long sourceRequestId;
    public Long candidateRequestId;
    public Long sourceVersion;
    public Long candidateVersion;
    public String ruleVersion;
    public String outcome;
    public Integer score;
    public String reasonCodesJson;
    public String evidenceJson;
    public LocalDateTime decidedAt;
    public LocalDateTime createTime;
}
