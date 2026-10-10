package com.example.venue.provider.dto;

import java.util.Locale;
import java.util.Objects;
import java.util.Set;

public record ProviderRunContext(String sourceType, Long triggeredBy) {
    private static final Set<String> ALLOWED_SOURCES = Set.of("MANUAL", "SCHEDULED", "SYSTEM");

    public ProviderRunContext {
        sourceType = normalizeSourceType(sourceType);
        if ("MANUAL".equals(sourceType) && triggeredBy == null) {
            throw new IllegalArgumentException("MANUAL sourceType requires triggeredBy");
        }
        if (!"MANUAL".equals(sourceType) && triggeredBy != null) {
            throw new IllegalArgumentException(sourceType + " sourceType requires null triggeredBy");
        }
    }

    public static ProviderRunContext manual(Long userId) {
        return new ProviderRunContext("MANUAL", Objects.requireNonNull(userId, "manual userId"));
    }

    public static ProviderRunContext scheduled() {
        return new ProviderRunContext("SCHEDULED", null);
    }

    public static ProviderRunContext system() {
        return new ProviderRunContext("SYSTEM", null);
    }

    static String normalizeSourceType(String sourceType) {
        if (sourceType == null || sourceType.isBlank()) {
            throw new IllegalArgumentException("sourceType must be one of " + ALLOWED_SOURCES);
        }
        String normalized = sourceType.trim().toUpperCase(Locale.ROOT);
        if (!ALLOWED_SOURCES.contains(normalized)) {
            throw new IllegalArgumentException("sourceType must be one of " + ALLOWED_SOURCES);
        }
        return normalized;
    }
}
