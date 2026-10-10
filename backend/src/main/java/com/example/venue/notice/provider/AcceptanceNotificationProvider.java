package com.example.venue.notice.provider;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("acceptance")
@ConditionalOnProperty(name = "app.notice.acceptance.fake-provider-enabled", havingValue = "true")
public class AcceptanceNotificationProvider implements NotificationProvider {
    private static final NotificationProviderDescriptor DESCRIPTOR = new NotificationProviderDescriptor(
        "notice.acceptance.retryable", "MESSAGE", "ACCEPTANCE", "RETRYABLE_FAKE",
        "IN_APP", "验收重试 Provider", "ACCEPTANCE_RETRYABLE_V1", true);

    @Override
    public NotificationProviderDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public NotificationDeliveryResult send(NotificationDeliveryCommand command) {
        return new NotificationDeliveryResult(
            NotificationDeliveryResult.FAILED_RETRYABLE,
            null,
            true,
            "ACCEPTANCE_RETRYABLE_FAILURE",
            "Acceptance fake provider requested retry",
            "{\"status\":\"FAILED_RETRYABLE\",\"source\":\"ACCEPTANCE_FAKE\"}");
    }
}
