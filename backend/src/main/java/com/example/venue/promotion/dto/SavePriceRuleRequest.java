package com.example.venue.promotion.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.LocalTime;

public class SavePriceRuleRequest {
    @NotBlank
    public String ruleCode;
    @NotBlank
    public String ruleName;
    @NotBlank
    public String ruleType;
    public Long venueId;
    public Long spaceId;
    public String memberLevelCode;
    @NotNull
    public LocalDateTime startTime;
    @NotNull
    public LocalDateTime endTime;
    public String dayOfWeekMask;
    public LocalTime startClock;
    public LocalTime endClock;
    @NotBlank
    public String adjustmentType;
    @NotNull
    public BigDecimal adjustmentValue;
    public Integer priority;
    public String status;
    public String reasonText;
}
