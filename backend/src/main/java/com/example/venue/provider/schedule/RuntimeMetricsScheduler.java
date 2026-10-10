package com.example.venue.provider.schedule;

import com.example.venue.provider.config.ProviderObservabilityProperties;
import com.example.venue.provider.dto.ProviderRunContext;
import com.example.venue.provider.service.RuntimeMetricsCollectionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class RuntimeMetricsScheduler {
    private static final Logger log = LoggerFactory.getLogger(RuntimeMetricsScheduler.class);

    private final ProviderObservabilityProperties properties;
    private final RuntimeMetricsCollectionService metricsCollectionService;

    public RuntimeMetricsScheduler(ProviderObservabilityProperties properties,
                                   RuntimeMetricsCollectionService metricsCollectionService) {
        this.properties = properties;
        this.metricsCollectionService = metricsCollectionService;
    }

    @Scheduled(
        scheduler = "providerTaskScheduler",
        initialDelayString = "${app.provider.observability.metrics.initial-delay-ms}",
        fixedDelayString = "${app.provider.observability.metrics.fixed-delay-ms}")
    public void collectRuntimeMetrics() {
        if (!properties.isScheduledEnabled() || !properties.getMetrics().isEnabled()) {
            return;
        }
        try {
            metricsCollectionService.collect(ProviderRunContext.scheduled());
        } catch (RuntimeException ex) {
            log.error("Provider metrics schedule failed", ex);
        }
    }
}
