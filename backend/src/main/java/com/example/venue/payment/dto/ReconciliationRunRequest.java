package com.example.venue.payment.dto;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Locale;

public class ReconciliationRunRequest {
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");

    public LocalDate reconcileDate;
    public String providerEnv;
    public String providerName;
    private final Clock clock;

    public ReconciliationRunRequest() {
        this(Clock.system(SHANGHAI));
    }

    ReconciliationRunRequest(Clock clock) {
        this.clock = clock;
    }

    public LocalDate reconcileDate() {
        return reconcileDate == null ? LocalDate.now(clock.withZone(SHANGHAI)) : reconcileDate;
    }

    public String normalizedProviderEnv() {
        return normalize(providerEnv);
    }

    public String normalizedProviderName() {
        return normalize(providerName);
    }

    private String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim().toUpperCase(Locale.ROOT);
    }
}
