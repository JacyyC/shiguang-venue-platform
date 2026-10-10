package com.example.venue.notice.service;

import com.example.venue.notice.provider.NotificationDeliveryResult;

public interface NotificationDeliveryFaultHook {
    void afterProviderResult(Long outboxId, NotificationDeliveryResult result);

    static NotificationDeliveryFaultHook noop() {
        return (outboxId, result) -> {
        };
    }
}
