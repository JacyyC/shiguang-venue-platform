package com.example.venue.provider.schedule;

import com.example.venue.provider.config.ProviderObservabilityProperties;
import com.example.venue.provider.dto.ProviderRunContext;
import com.example.venue.provider.service.WeatherObservationCollectionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class WeatherObservationScheduler {
    private static final Logger log = LoggerFactory.getLogger(WeatherObservationScheduler.class);

    private final ProviderObservabilityProperties properties;
    private final WeatherObservationCollectionService weatherCollectionService;

    public WeatherObservationScheduler(ProviderObservabilityProperties properties,
                                       WeatherObservationCollectionService weatherCollectionService) {
        this.properties = properties;
        this.weatherCollectionService = weatherCollectionService;
    }

    @Scheduled(
        scheduler = "providerTaskScheduler",
        initialDelayString = "${app.provider.observability.weather.initial-delay-ms}",
        fixedDelayString = "${app.provider.observability.weather.fixed-delay-ms}")
    public void collectActiveLocations() {
        if (!properties.isScheduledEnabled() || !properties.getWeather().isEnabled()) {
            return;
        }
        try {
            weatherCollectionService.collectActiveLocations(ProviderRunContext.scheduled());
        } catch (RuntimeException ex) {
            log.error("Provider weather schedule failed", ex);
        }
    }
}
