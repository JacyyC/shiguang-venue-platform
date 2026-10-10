package com.example.venue.reservation.enums;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

public enum ReservationStatus {
    PENDING_REVIEW,
    APPROVED,
    PENDING_PAYMENT,
    PAID,
    COMPLETED,
    REJECTED,
    CANCELED,
    EXPIRED,
    REFUNDING,
    REFUNDED;

    private static final Set<ReservationStatus> OCCUPYING = EnumSet.of(
        PENDING_REVIEW, APPROVED, PENDING_PAYMENT, PAID, REFUNDING
    );
    private static final Set<ReservationStatus> TERMINAL = EnumSet.of(
        COMPLETED, REJECTED, CANCELED, EXPIRED, REFUNDED
    );
    private static final Set<ReservationStatus> CANCELABLE = EnumSet.of(
        PENDING_REVIEW, APPROVED, PENDING_PAYMENT, PAID
    );
    private static final Set<ReservationStatus> PAYABLE = EnumSet.of(PENDING_PAYMENT);
    private static final Set<ReservationStatus> REFUND_CANDIDATE = EnumSet.of(PAID);
    private static final Set<ReservationStatus> CHECKIN_CANDIDATE = EnumSet.of(PAID);
    private static final Set<ReservationStatus> LEGACY_COMPATIBLE = EnumSet.of(APPROVED);

    public boolean occupies() {
        return OCCUPYING.contains(this);
    }

    public static List<String> occupyingStatusNames() {
        return OCCUPYING.stream().map(Enum::name).toList();
    }

    public boolean terminal() {
        return TERMINAL.contains(this);
    }

    public boolean cancelable() {
        return CANCELABLE.contains(this);
    }

    public boolean payable() {
        return PAYABLE.contains(this);
    }

    public boolean refundCandidate() {
        return REFUND_CANDIDATE.contains(this);
    }

    public boolean checkinCandidate() {
        return CHECKIN_CANDIDATE.contains(this);
    }

    public boolean legacyCompatible() {
        return LEGACY_COMPATIBLE.contains(this);
    }
}
