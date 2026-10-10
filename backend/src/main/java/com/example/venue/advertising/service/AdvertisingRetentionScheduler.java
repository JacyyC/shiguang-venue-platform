package com.example.venue.advertising.service;

import com.example.venue.advertising.config.AdvertisingRetentionProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class AdvertisingRetentionScheduler {
    private static final Logger log = LoggerFactory.getLogger(AdvertisingRetentionScheduler.class);

    private final AdvertisingRetentionProperties properties;
    private final AdvertisingRetentionService retentionService;

    public AdvertisingRetentionScheduler(AdvertisingRetentionProperties properties,
                                         AdvertisingRetentionService retentionService) {
        this.properties = properties;
        this.retentionService = retentionService;
    }

    @Scheduled(
        initialDelayString = "${app.advertising.retention.initial-delay-ms:300000}",
        fixedDelayString = "${app.advertising.retention.fixed-delay-ms:86400000}")
    public void runRetention() {
        if (!properties.isEnabled()) {
            return;
        }
        try {
            retentionService.runOnce();
        } catch (RuntimeException exception) {
            log.error("广告事实保留任务失败", exception);
        }
    }
}
