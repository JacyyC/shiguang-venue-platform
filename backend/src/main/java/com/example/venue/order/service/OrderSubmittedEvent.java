package com.example.venue.order.service;

import java.time.LocalDateTime;

public record OrderSubmittedEvent(
    Long orderId,
    Long reservationId,
    Long userId,
    Long venueId,
    String eventKey,
    String sourceStatus,
    LocalDateTime occurredAt
) {
}
