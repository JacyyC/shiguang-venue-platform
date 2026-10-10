package com.example.venue.provider.weather;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record WeatherProviderResult(
    BigDecimal temperatureC,
    BigDecimal precipitationMm,
    BigDecimal windSpeed,
    String weatherCode,
    String alertLevel,
    String summaryText,
    BigDecimal confidence,
    LocalDateTime observedAt,
    String rawSummaryJson
) {
}
