package com.example.venue.inventory.dto;

public class BookabilityDecision {
    public boolean bookable;
    public String status;
    public String reasonCode;
    public String reasonText;
    public Long conflictReservationId;
    public Long conflictBlockId;

    public static BookabilityDecision ok() {
        BookabilityDecision decision = new BookabilityDecision();
        decision.bookable = true;
        decision.status = "AVAILABLE";
        return decision;
    }

    public static BookabilityDecision rejected(String status, String reasonCode, String reasonText) {
        BookabilityDecision decision = new BookabilityDecision();
        decision.bookable = false;
        decision.status = status;
        decision.reasonCode = reasonCode;
        decision.reasonText = reasonText;
        return decision;
    }

    public BookabilityDecision conflictReservation(Long reservationId) {
        this.conflictReservationId = reservationId;
        return this;
    }

    public BookabilityDecision conflictBlock(Long blockId) {
        this.conflictBlockId = blockId;
        return this;
    }
}
