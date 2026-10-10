package com.example.venue.storedvalue.service;

import java.time.LocalDate;

public record StoredValueReconciliationResult(
    Long runId,
    String runNo,
    LocalDate reconcileDate,
    String status,
    int differenceCount
) {
}
