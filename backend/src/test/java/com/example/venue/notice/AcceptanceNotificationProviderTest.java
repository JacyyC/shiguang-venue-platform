package com.example.venue.notice;

import com.example.venue.notice.provider.AcceptanceNotificationProvider;
import com.example.venue.notice.provider.NotificationDeliveryCommand;
import com.example.venue.notice.provider.NotificationDeliveryResult;
import com.example.venue.notice.provider.NotificationProviderDescriptor;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AcceptanceNotificationProviderTest {
    @Test
    void alwaysReturnsSanitizedRetryableFailureForAcceptanceRuns() {
        AcceptanceNotificationProvider provider = new AcceptanceNotificationProvider();
        NotificationProviderDescriptor descriptor = provider.descriptor();
        NotificationDeliveryCommand command = new NotificationDeliveryCommand(
            41L, "NOTICE_MESSAGE:41", descriptor, 7L, 91L, 99L,
            "RESERVATION", "预约已提交", "请等待", "{\"messageId\":91}");

        NotificationDeliveryResult result = provider.send(command);

        assertThat(descriptor.providerKey()).isEqualTo("notice.acceptance.retryable");
        assertThat(descriptor.providerEnv()).isEqualTo("ACCEPTANCE");
        assertThat(descriptor.providerName()).isEqualTo("RETRYABLE_FAKE");
        assertThat(result.status()).isEqualTo(NotificationDeliveryResult.FAILED_RETRYABLE);
        assertThat(result.retryable()).isTrue();
        assertThat(result.failureCode()).isEqualTo("ACCEPTANCE_RETRYABLE_FAILURE");
        assertThat(result.failureMessage()).doesNotContain("token", "email", "phone");
        assertThat(result.responseSummaryJson())
            .isEqualTo("{\"status\":\"FAILED_RETRYABLE\",\"source\":\"ACCEPTANCE_FAKE\"}");
    }
}
