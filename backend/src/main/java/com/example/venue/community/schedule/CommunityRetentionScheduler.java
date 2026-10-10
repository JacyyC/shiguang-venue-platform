package com.example.venue.community.schedule;

import com.example.venue.community.config.CommunityRetentionProperties;
import com.example.venue.community.service.CommunityRetentionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class CommunityRetentionScheduler {
    private static final Logger log = LoggerFactory.getLogger(CommunityRetentionScheduler.class);

    private final CommunityRetentionProperties properties;
    private final CommunityRetentionService retentionService;

    public CommunityRetentionScheduler(CommunityRetentionProperties properties,
                                       CommunityRetentionService retentionService) {
        this.properties = properties;
        this.retentionService = retentionService;
    }

    @Scheduled(
        initialDelayString = "${app.community.retention.initial-delay-ms:300000}",
        fixedDelayString = "${app.community.retention.fixed-delay-ms:86400000}")
    public void runRetention() {
        if (!properties.isEnabled()) {
            return;
        }
        try {
            retentionService.runOnce();
        } catch (RuntimeException ex) {
            log.error("Community retention schedule failed", ex);
        }
    }
}
