package com.example.venue.storedvalue.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;

@Component
public class StoredValueReconciliationScheduler {
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final Logger log = LoggerFactory.getLogger(StoredValueReconciliationScheduler.class);

    private final StoredValueReconciliationService service;

    public StoredValueReconciliationScheduler(StoredValueReconciliationService service) {
        this.service = service;
    }

    @Scheduled(cron = "${app.stored-value.reconciliation-cron:0 0 2 * * *}", zone = "Asia/Shanghai")
    public void reconcileYesterday() {
        LocalDate date = LocalDate.now(SHANGHAI).minusDays(1);
        try {
            StoredValueReconciliationResult result = service.run(date, null);
            log.info("stored-value reconciliation completed date={} status={} differences={}",
                date, result.status(), result.differenceCount());
        } catch (RuntimeException exception) {
            log.warn("stored-value reconciliation skipped date={} reason={}", date,
                exception.getClass().getSimpleName());
        }
    }
}
