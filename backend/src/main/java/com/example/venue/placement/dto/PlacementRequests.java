package com.example.venue.placement.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.time.LocalDateTime;

/**
 * 宣传管理请求。候选请求不得提交标题副本、原始正文、HTML 或跳转 URL；
 * 来源白名单由 Reader 在引用/发布/决策时实时复核。
 */
public final class PlacementRequests {

    private PlacementRequests() {
    }

    public record CreateCandidateRequest(
        @NotNull Long slotId,
        @NotNull String sourceType,
        @NotNull Long sourceId,
        @NotNull LocalDateTime effectiveFrom,
        @NotNull LocalDateTime effectiveUntil,
        String audienceType,
        String audienceLevelCode,
        Integer priority,
        @Positive Integer frequencyCapPerUser,
        @Positive Integer frequencyCapWindowHours
    ) {
    }

    public record RecordEventRequest(
        @NotNull String eventType,
        @NotNull String token,
        Double visibleRatio,
        Integer visibleDurationMs
    ) {
    }
}
