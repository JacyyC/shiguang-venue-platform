package com.example.venue.advertising.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class AdvertisingConversionScheduler {
    private static final Logger log = LoggerFactory.getLogger(AdvertisingConversionScheduler.class);
    private final AdvertisingConversionService conversionService;

    public AdvertisingConversionScheduler(AdvertisingConversionService conversionService) {
        this.conversionService = conversionService;
    }

    @Scheduled(fixedDelay = 60_000)
    public void retryPendingCallbacks() {
        try {
            conversionService.retryPending();
        } catch (RuntimeException exception) {
            log.warn("广告回连待重试任务扫描失败", exception);
        }
    }
}
