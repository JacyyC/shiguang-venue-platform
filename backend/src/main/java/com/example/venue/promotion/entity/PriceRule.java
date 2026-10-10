package com.example.venue.promotion.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.LocalTime;

@TableName("price_rule")
public class PriceRule {
    @TableId(type = IdType.AUTO)
    public Long id;
    public String ruleCode;
    public String ruleName;
    public String ruleType;
    public Long venueId;
    public Long spaceId;
    public String memberLevelCode;
    public LocalDateTime startTime;
    public LocalDateTime endTime;
    public String dayOfWeekMask;
    public LocalTime startClock;
    public LocalTime endClock;
    public String adjustmentType;
    public BigDecimal adjustmentValue;
    public BigDecimal fixedPrice;
    public Integer priority;
    public String status;
    public String reasonText;
    public String metadataJson;
    public LocalDateTime createTime;
    public LocalDateTime updateTime;
    public Integer deleted;
}
