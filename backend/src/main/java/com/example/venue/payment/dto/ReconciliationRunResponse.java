package com.example.venue.payment.dto;

import java.time.LocalDate;

public class ReconciliationRunResponse {
    public LocalDate reconcileDate;
    public String providerEnv;
    public String providerName;
    public long totalCount;
    public long matchedCount;
    public long differenceCount;
    public long missingProviderCount;
    public long missingSystemCount;

    public ReconciliationRunResponse() {
    }

    public ReconciliationRunResponse(LocalDate reconcileDate, String providerEnv, String providerName) {
        this.reconcileDate = reconcileDate;
        this.providerEnv = providerEnv;
        this.providerName = providerName;
    }

    public void record(String status) {
        totalCount++;
        if ("MATCHED".equals(status)) {
            matchedCount++;
        } else if ("DIFFERENCE".equals(status)) {
            differenceCount++;
        } else if ("MISSING_PROVIDER".equals(status)) {
            missingProviderCount++;
        } else if ("MISSING_SYSTEM".equals(status)) {
            missingSystemCount++;
        }
    }
}
