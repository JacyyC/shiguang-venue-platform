package com.example.venue.checkin.dto;

import com.example.venue.checkin.entity.ReservationRescheduleRequest;

import java.time.LocalDateTime;

public record ReservationRescheduleResponse(
    Long id,
    Long reservationId,
    Long userId,
    Long venueId,
    Long spaceId,
    LocalDateTime fromStartTime,
    LocalDateTime fromEndTime,
    LocalDateTime toStartTime,
    LocalDateTime toEndTime,
    String status,
    String reasonCode,
    String reasonText,
    LocalDateTime reviewedAt,
    String reviewReason,
    LocalDateTime createTime,
    LocalDateTime updateTime
) {
    public static ReservationRescheduleResponse from(ReservationRescheduleRequest request) {
        return new ReservationRescheduleResponse(
            request.id,
            request.reservationId,
            request.userId,
            request.venueId,
            request.spaceId,
            request.fromStartTime,
            request.fromEndTime,
            request.toStartTime,
            request.toEndTime,
            request.status,
            request.reasonCode,
            request.reasonText,
            request.reviewedAt,
            request.reviewReason,
            request.createTime,
            request.updateTime
        );
    }
}
