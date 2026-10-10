package com.example.venue.provider.dto;

import java.util.Optional;

public record WeatherBatchOutcome(
    String status,
    int successfulLocations,
    int failedLocations,
    String providerKey,
    String providerEnv,
    String providerName,
    String configVersion,
    long durationMs
) {
    private static final String WEATHER_DOMAIN = "WEATHER";
    private static final String BATCH_OPERATION = "FETCH_WEATHER_BATCH";
    private static final String WEATHER_SNAPSHOT_TYPE = "WEATHER";

    public static WeatherBatchOutcome skipped() {
        return new WeatherBatchOutcome("SKIPPED", 0, 0, null, null, null, null, 0L);
    }

    public static WeatherBatchOutcome noActiveLocation() {
        return skipped();
    }

    public static WeatherBatchOutcome completed(String providerKey,
                                                String providerEnv,
                                                String providerName,
                                                String configVersion,
                                                int successfulLocations,
                                                int failedLocations,
                                                long durationMs) {
        if (successfulLocations < 0 || failedLocations < 0 || successfulLocations + failedLocations == 0) {
            throw new IllegalArgumentException("Completed weather batch requires at least one location result");
        }
        String status = successfulLocations == 0 ? "FAILED"
            : failedLocations == 0 ? "SUCCESS"
            : "DEGRADED";
        return new WeatherBatchOutcome(
            status, successfulLocations, failedLocations, providerKey, providerEnv, providerName, configVersion,
            durationMs);
    }

    public Optional<ProviderCallRecord> aggregateRecord(ProviderRunContext context) {
        if (successfulLocations + failedLocations == 0) {
            return Optional.empty();
        }
        String failureCode = "DEGRADED".equals(status) ? "WEATHER_BATCH_PARTIAL_FAILURE"
            : "FAILED".equals(status) ? "WEATHER_BATCH_FAILED"
            : null;
        String failureMessage = "DEGRADED".equals(status) ? "部分场馆天气采集失败"
            : "FAILED".equals(status) ? "全部场馆天气采集失败"
            : null;
        String requestSummary = "{\"activeLocationCount\":" + (successfulLocations + failedLocations) + "}";
        String responseSummary = "{\"successfulLocations\":" + successfulLocations
            + ",\"failedLocations\":" + failedLocations + "}";
        return Optional.of(ProviderCallRecord.of(
            context, providerKey, WEATHER_DOMAIN, providerEnv, providerName, null, BATCH_OPERATION, status,
            configVersion, requestSummary, responseSummary, failureCode, failureMessage, durationMs, null,
            WEATHER_SNAPSHOT_TYPE, null, null, true));
    }
}
