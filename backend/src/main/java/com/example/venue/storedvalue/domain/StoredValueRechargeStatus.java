package com.example.venue.storedvalue.domain;

public enum StoredValueRechargeStatus {
    INITIATED, WAITING_PROVIDER, PROVIDER_SUCCEEDED_PENDING_POSTING, POSTING,
    SUCCEEDED, FAILED, CANCELED, TIMED_OUT, REFUNDING, REFUNDED, DISPUTED, MANUAL_REVIEW;

    public boolean matches(String persistedValue) {
        return name().equals(persistedValue);
    }

    public boolean isReservationReleaseTarget() {
        return this == FAILED || this == CANCELED || this == TIMED_OUT || this == MANUAL_REVIEW;
    }

    public static boolean any(String persistedValue, StoredValueRechargeStatus... candidates) {
        if (candidates == null) {
            return false;
        }
        for (StoredValueRechargeStatus candidate : candidates) {
            if (candidate != null && candidate.matches(persistedValue)) {
                return true;
            }
        }
        return false;
    }

    public static StoredValueRechargeStatus parse(String persistedValue) {
        if (persistedValue == null || persistedValue.isBlank()) {
            throw new IllegalArgumentException("充值状态不能为空");
        }
        try {
            return valueOf(persistedValue);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("未知充值状态: " + persistedValue, exception);
        }
    }
}
