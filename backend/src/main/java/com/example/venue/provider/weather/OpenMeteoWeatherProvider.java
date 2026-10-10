package com.example.venue.provider.weather;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;

@Component
public class OpenMeteoWeatherProvider implements WeatherProvider {
    private static final Duration TIMEOUT = Duration.ofSeconds(2);
    private static final String BASE_URL = "https://api.open-meteo.com/v1/forecast";

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public OpenMeteoWeatherProvider() {
        this(HttpClient.newBuilder().connectTimeout(TIMEOUT).build(), new ObjectMapper());
    }

    OpenMeteoWeatherProvider(HttpClient httpClient, ObjectMapper objectMapper) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public String providerKey() {
        return "weather.open_meteo.dev";
    }

    @Override
    public String providerEnv() {
        return "DEV";
    }

    @Override
    public String providerName() {
        return "OPEN_METEO";
    }

    @Override
    public String configVersion() {
        return "OPEN_METEO_V1";
    }

    @Override
    public WeatherProviderResult fetch(WeatherProviderRequest request) {
        try {
            HttpRequest httpRequest = HttpRequest.newBuilder(uri(request))
                .timeout(TIMEOUT)
                .GET()
                .build();
            HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("Open-Meteo returned HTTP " + response.statusCode());
            }
            return parse(response.body());
        } catch (IOException ex) {
            throw new IllegalStateException("Open-Meteo request failed: " + ex.getMessage(), ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Open-Meteo request interrupted", ex);
        }
    }

    private URI uri(WeatherProviderRequest request) {
        String url = BASE_URL
            + "?latitude=" + request.latitude().toPlainString()
            + "&longitude=" + request.longitude().toPlainString()
            + "&current=temperature_2m,precipitation,wind_speed_10m,weather_code"
            + "&timezone=auto";
        return URI.create(url);
    }

    private WeatherProviderResult parse(String body) throws IOException {
        JsonNode root = objectMapper.readTree(body);
        JsonNode current = root.path("current");
        BigDecimal precipitation = decimal(current, "precipitation");
        BigDecimal windSpeed = decimal(current, "wind_speed_10m");
        String weatherCode = current.path("weather_code").isMissingNode()
            ? null
            : current.path("weather_code").asText();
        return new WeatherProviderResult(
            decimal(current, "temperature_2m"),
            precipitation,
            windSpeed,
            weatherCode,
            alertLevel(precipitation, windSpeed),
            summary(weatherCode),
            BigDecimal.valueOf(0.90),
            observedAt(current.path("time").asText(null)),
            body
        );
    }

    private BigDecimal decimal(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isNumber() ? BigDecimal.valueOf(value.asDouble()) : null;
    }

    private LocalDateTime observedAt(String value) {
        if (value == null || value.isBlank()) {
            return LocalDateTime.now();
        }
        return LocalDateTime.parse(value);
    }

    private String summary(String weatherCode) {
        return weatherCode == null ? "Open-Meteo weather snapshot" : "Open-Meteo weather_code=" + weatherCode;
    }

    private String alertLevel(BigDecimal precipitation, BigDecimal windSpeed) {
        if (atLeast(precipitation, "20") || atLeast(windSpeed, "50")) {
            return "HIGH";
        }
        return "NONE";
    }

    private boolean atLeast(BigDecimal value, String threshold) {
        return value != null && value.compareTo(new BigDecimal(threshold)) >= 0;
    }
}
