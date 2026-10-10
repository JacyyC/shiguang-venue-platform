package com.example.venue.promotion.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@TableName("promotion_campaign")
public class PromotionCampaign {
    @TableId(type = IdType.AUTO)
    public Long id;
    public String campaignCode;
    public String campaignName;
    public String campaignType;
    public Long venueId;
    public Long spaceId;
    public String memberLevelCode;
    public String discountType;
    public BigDecimal discountValue;
    public Integer priority;
    public Integer stackable;
    public LocalDateTime startTime;
    public LocalDateTime endTime;
    public BigDecimal budgetAmount;
    public BigDecimal budgetUsedAmount;
    public String status;
    public Long createdBy;
    public Long updatedBy;
    public String metadataJson;
    public LocalDateTime createTime;
    public LocalDateTime updateTime;
    public Integer deleted;
}
