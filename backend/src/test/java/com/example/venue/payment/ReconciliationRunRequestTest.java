package com.example.venue.payment.dto;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class ReconciliationRunRequestTest {
    @Test
    void nullDateUsesShanghaiLocalDateAcrossUtcDayBoundary() {
        ReconciliationRunRequest request = new ReconciliationRunRequest(
            Clock.fixed(Instant.parse("2026-07-14T16:30:00Z"), ZoneOffset.UTC));

        assertThat(request.reconcileDate()).isEqualTo(LocalDate.of(2026, 7, 15));
    }
}
