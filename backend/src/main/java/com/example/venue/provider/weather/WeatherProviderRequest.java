package com.example.venue.provider.weather;

import java.math.BigDecimal;

public record WeatherProviderRequest(Long venueId, Long locationId, BigDecimal latitude, BigDecimal longitude) {
}
