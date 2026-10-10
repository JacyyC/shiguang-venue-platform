package com.example.venue.reservation.dto;

/** Read-only reservation-to-venue projection used for page enrichment. */
public class ReservationVenueName {
    public Long reservationId;
    public Long venueId;
    public String venueName;
}
