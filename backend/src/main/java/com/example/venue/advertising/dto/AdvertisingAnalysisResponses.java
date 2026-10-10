package com.example.venue.advertising.dto;

import java.time.LocalDateTime;

public final class AdvertisingAnalysisResponses {
    private AdvertisingAnalysisResponses() {
    }

    public record Overview(
        LocalDateTime from,
        LocalDateTime until,
        long decisions,
        long viewableEvents,
        long clickEvents,
        long attributedReservations,
        long gapReservations,
        long revokedReservations,
        long noValidClickCallbacks,
        long privacyDisabledCallbacks,
        long pendingRetryCallbacks,
        long unmatchedClicks
    ) {
    }
}
