package com.example.venue.reservation.service;

import java.time.LocalDateTime;

public record ReservationLifecycleChangedEvent(
    Long reservationId,
    Long userId,
    Long venueId,
    String eventKey,
    String changeType,
    String fromStatus,
    String toStatus,
    String reasonCode,
    String source,
    LocalDateTime occurredAt
) {
    public boolean submitted() {
        return "SUBMITTED".equals(changeType);
    }
}
