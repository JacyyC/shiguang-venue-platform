package com.example.venue.provider.schedule;

import com.example.venue.provider.config.ProviderObservabilityProperties;
import com.example.venue.provider.service.ProviderEvidenceRetentionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class ProviderRetentionScheduler {
    private static final Logger log = LoggerFactory.getLogger(ProviderRetentionScheduler.class);

    private final ProviderObservabilityProperties properties;
    private final ProviderEvidenceRetentionService retentionService;

    public ProviderRetentionScheduler(ProviderObservabilityProperties properties,
                                      ProviderEvidenceRetentionService retentionService) {
        this.properties = properties;
        this.retentionService = retentionService;
    }

    @Scheduled(
        scheduler = "providerTaskScheduler",
        initialDelayString = "${app.provider.observability.retention.initial-delay-ms}",
        fixedDelayString = "${app.provider.observability.retention.fixed-delay-ms}")
    public void runRetention() {
        if (!properties.isScheduledEnabled() || !properties.getRetention().isEnabled()) {
            return;
        }
        try {
            retentionService.runOnce();
        } catch (RuntimeException ex) {
            log.error("Provider retention schedule failed", ex);
        }
    }
}
