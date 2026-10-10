package com.example.venue.community.activity.schedule;

import com.example.venue.community.activity.service.ActivityReservationReconciliationService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class ActivityReservationReconciliationScheduler {
    private final ActivityReservationReconciliationService service;

    public ActivityReservationReconciliationScheduler(ActivityReservationReconciliationService service) {
        this.service = service;
    }

    @Scheduled(initialDelayString = "${app.community.activity.reconciliation.initial-delay-ms:60000}",
        fixedDelayString = "${app.community.activity.reconciliation.fixed-delay-ms:60000}")
    public void reconcile() {
        service.reconcile();
    }
}
