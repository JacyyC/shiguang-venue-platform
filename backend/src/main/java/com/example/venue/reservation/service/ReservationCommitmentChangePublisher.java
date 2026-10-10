package com.example.venue.reservation.service;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

@Component
public class ReservationCommitmentChangePublisher {
    private final ApplicationEventPublisher eventPublisher;

    public ReservationCommitmentChangePublisher(ApplicationEventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    public void publish(Long reservationId, String changeType, String reasonCode) {
        if (reservationId != null) {
            eventPublisher.publishEvent(
                new ReservationCommitmentChangedEvent(reservationId, changeType, reasonCode));
        }
    }
}
