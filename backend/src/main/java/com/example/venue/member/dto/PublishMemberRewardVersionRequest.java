package com.example.venue.member.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDateTime;

public class PublishMemberRewardVersionRequest {
    @NotNull @Min(1) @Max(1_000_000_000) public Integer pointsCost;
    @NotBlank public String minimumLevelCode;
    @NotNull public Long couponTemplateId;
    @Min(1) @Max(3_650) public Integer validityDays;
    public LocalDateTime effectiveStartTime;
    public LocalDateTime effectiveEndTime;
}
