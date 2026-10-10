package com.example.venue.notice.service;

import com.example.venue.notice.provider.NotificationDeliveryResult;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("!acceptance")
public class NoOpNotificationDeliveryFaultHook implements NotificationDeliveryFaultHook {
    @Override
    public void afterProviderResult(Long outboxId, NotificationDeliveryResult result) {
    }
}
