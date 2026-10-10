package com.example.venue.advertising.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

public final class AdvertisingDecisionRequests {
    private AdvertisingDecisionRequests() {
    }

    public record UpdatePrivacyRequest(
        @NotNull Boolean commercialEnabled,
        @NotNull Boolean measurementEnabled
    ) {
    }

    public record RecordEventRequest(
        @NotBlank String eventType,
        @NotBlank String token,
        @DecimalMin("0.0") @DecimalMax("1.0") Double visibleRatio,
        @PositiveOrZero Integer visibleDurationMs
    ) {
    }
}
