package com.example.venue.provider.schedule;

import com.example.venue.provider.config.ProviderObservabilityProperties;
import com.example.venue.provider.dto.ProviderRunContext;
import com.example.venue.provider.service.RiskObservationCollectionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class RiskObservationScheduler {
    private static final Logger log = LoggerFactory.getLogger(RiskObservationScheduler.class);

    private final ProviderObservabilityProperties properties;
    private final RiskObservationCollectionService riskCollectionService;

    public RiskObservationScheduler(ProviderObservabilityProperties properties,
                                    RiskObservationCollectionService riskCollectionService) {
        this.properties = properties;
        this.riskCollectionService = riskCollectionService;
    }

    @Scheduled(
        scheduler = "providerTaskScheduler",
        initialDelayString = "${app.provider.observability.risk.initial-delay-ms}",
        fixedDelayString = "${app.provider.observability.risk.fixed-delay-ms}")
    public void collectSystemRisk() {
        if (!properties.isScheduledEnabled() || !properties.getRisk().isEnabled()) {
            return;
        }
        try {
            riskCollectionService.collect(null, ProviderRunContext.scheduled());
        } catch (RuntimeException ex) {
            log.error("Provider risk schedule failed", ex);
        }
    }
}
