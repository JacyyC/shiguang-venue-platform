package com.example.venue.community.activity.service;

import org.springframework.stereotype.Service;

@Service
public class ActivityReservationPropagationService {
    private static final int BATCH_SIZE = 50;
    private final ActivityReservationPropagationBatchService batchService;

    public ActivityReservationPropagationService(ActivityReservationPropagationBatchService batchService) {
        this.batchService = batchService;
    }

    public void propagate(Long reservationId, String reasonCode) {
        long afterId = 0L;
        while (true) {
            ActivityReservationPropagationBatchService.BatchResult batch = batchService.activities(
                reservationId, reasonCode, afterId, BATCH_SIZE);
            if (batch.size() == 0) {
                break;
            }
            afterId = batch.lastId();
        }
        afterId = 0L;
        while (true) {
            ActivityReservationPropagationBatchService.BatchResult batch = batchService.companions(
                reservationId, reasonCode, afterId, BATCH_SIZE);
            if (batch.size() == 0) {
                break;
            }
            afterId = batch.lastId();
        }
    }
}
