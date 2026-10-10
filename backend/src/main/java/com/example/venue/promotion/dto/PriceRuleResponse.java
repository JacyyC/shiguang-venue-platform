package com.example.venue.promotion.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.LocalTime;

public class PriceRuleResponse {
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
    public Integer priority;
    public String status;
    public String reasonText;
}
