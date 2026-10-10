package com.example.venue.reservation.service;

public record ReservationCommitmentChangedEvent(
    Long reservationId,
    String changeType,
    String reasonCode
) {
}
