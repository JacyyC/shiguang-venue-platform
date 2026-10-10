package com.example.venue.provider.weather;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OpenMeteoWeatherProviderTest {
    private static final Set<String> ALLOWED_ALERT_LEVELS = Set.of("NONE", "LOW", "MEDIUM", "HIGH");

    @Test
    void highPrecipitationAndWindProducesAllowedHighAlertLevel() throws Exception {
        HttpClient httpClient = mock(HttpClient.class);
        @SuppressWarnings("unchecked")
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("""
            {"current":{"time":"2026-07-07T10:00","temperature_2m":28.6,"precipitation":20.0,"wind_speed_10m":50.0,"weather_code":61}}
            """);
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(response);
        OpenMeteoWeatherProvider provider = new OpenMeteoWeatherProvider(httpClient, new ObjectMapper());

        WeatherProviderResult result = provider.fetch(new WeatherProviderRequest(
            1L, 7L, BigDecimal.valueOf(31.2304160), BigDecimal.valueOf(121.4737010)));

        assertThat(result.alertLevel()).isEqualTo("HIGH");
        assertThat(result.alertLevel()).isIn(ALLOWED_ALERT_LEVELS);
        assertThat(result.alertLevel()).isNotEqualTo("WARN");
    }
}
