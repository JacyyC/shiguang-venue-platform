package com.example.venue.member.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

@TableName("member_reward_catalog_version")
public class MemberRewardCatalogVersion {
    @TableId(type = IdType.AUTO) public Long id;
    public Long catalogItemId;
    public Integer versionNo;
    public Integer pointsCost;
    public String minimumLevelCode;
    public String minimumLevelName;
    public Integer minimumGrowthThreshold;
    public Long couponTemplateId;
    public Integer validityDays;
    public LocalDateTime effectiveStartTime;
    public LocalDateTime effectiveEndTime;
    public String configSummaryJson;
    public String status;
    public Long publishedBy;
    public LocalDateTime publishedAt;
    public LocalDateTime createTime;
}
