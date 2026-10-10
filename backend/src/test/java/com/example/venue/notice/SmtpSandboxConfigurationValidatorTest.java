package com.example.venue.notice;

import com.example.venue.common.BusinessException;
import com.example.venue.notice.provider.NotificationDeliveryCommand;
import com.example.venue.notice.provider.NotificationDeliveryResult;
import com.example.venue.notice.provider.NotificationProvider;
import com.example.venue.notice.provider.NotificationProviderDescriptor;
import com.example.venue.notice.provider.NotificationProviderRouter;
import com.example.venue.notice.provider.SmtpSandboxConfigurationValidator;
import com.example.venue.notice.provider.SmtpSandboxProperties;
import com.example.venue.notice.service.NotificationOutboxProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SmtpSandboxConfigurationValidatorTest {
    private SmtpSandboxProperties properties;
    private NotificationOutboxProperties outboxProperties;

    @BeforeEach
    void setUp() {
        properties = new SmtpSandboxProperties();
        outboxProperties = new NotificationOutboxProperties();
    }

    @Test
    void defaultsAreFixedMailpitOnlyAndDisabled() {
        assertThat(properties.isEnabled()).isFalse();
        assertThat(properties.isSandboxOnly()).isTrue();
        assertThat(properties.getHost()).isEqualTo("127.0.0.1");
        assertThat(properties.getPort()).isEqualTo(1025);
        assertThat(properties.isAuth()).isFalse();
        assertThat(properties.isStarttls()).isFalse();
        assertThat(properties.getUsername()).isEmpty();
        assertThat(properties.getPassword()).isEmpty();
        assertThat(properties.getFromAddress()).isEqualTo("no-reply@venue.local");
        assertThat(properties.getFromName()).isEqualTo("Venue Reservation Sandbox");
        assertThat(properties.getConnectTimeoutMs()).isEqualTo(3000);
        assertThat(properties.getReadTimeoutMs()).isEqualTo(5000);
        assertThat(properties.getWriteTimeoutMs()).isEqualTo(5000);
    }

    @Test
    void enabledSandboxAcceptsOnlyTheExactEnabledDescriptor() {
        properties.setEnabled(true);
        NotificationProviderRouter router = router(descriptor(true, "SMTP_SANDBOX_V1"));

        new SmtpSandboxConfigurationValidator(properties, outboxProperties, router).validate();
    }

    @Test
    void enabledSandboxRejectsNonMailpitHostAndLeaseTooShortForTimeoutBudget() {
        properties.setEnabled(true);
        properties.setHost("smtp.example.com");
        properties.setConnectTimeoutMs(3000);
        properties.setReadTimeoutMs(5000);
        properties.setWriteTimeoutMs(5000);
        outboxProperties.setLeaseSeconds(10);

        assertThatThrownBy(() -> validator(descriptor(true, "SMTP_SANDBOX_V1")).validate())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("app.notice.smtp.host")
            .hasMessageNotContaining("smtp-password-secret");
    }

    @Test
    void enabledSandboxRequiresLeaseToExceedTheCompleteTimeoutBudget() {
        properties.setEnabled(true);
        properties.setConnectTimeoutMs(3000);
        properties.setReadTimeoutMs(5000);
        properties.setWriteTimeoutMs(5000);
        outboxProperties.setLeaseSeconds(13);

        assertThatThrownBy(() -> validator(descriptor(true, "SMTP_SANDBOX_V1")).validate())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("app.notice.outbox.lease-seconds");

        outboxProperties.setLeaseSeconds(14);
        validator(descriptor(true, "SMTP_SANDBOX_V1")).validate();
    }

    @Test
    void enabledSandboxRejectsUnsafeHeadersAndIncompleteAuthenticationWithoutSecrets() {
        properties.setEnabled(true);
        properties.setAuth(true);
        properties.setUsername("mailer");
        properties.setPassword("smtp-password-secret");
        properties.setFromName("Venue\r\nBcc: hidden@example.test");

        assertThatThrownBy(() -> validator(descriptor(true, "SMTP_SANDBOX_V1")).validate())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("app.notice.smtp.from-name")
            .hasMessageNotContaining("smtp-password-secret")
            .hasMessageNotContaining("hidden@example.test");

        properties.setFromName("Venue Reservation Sandbox");
        properties.setPassword("");
        assertThatThrownBy(() -> validator(descriptor(true, "SMTP_SANDBOX_V1")).validate())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("app.notice.smtp.password")
            .hasMessageNotContaining("smtp-password-secret");
    }

    @Test
    void enabledSandboxRejectsDisabledMissingOrVersionMismatchedDescriptor() {
        properties.setEnabled(true);

        assertThatThrownBy(() -> validator(descriptor(false, "SMTP_SANDBOX_V1")).validate())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("notice.smtp.sandbox")
            .hasMessageNotContaining("smtp-password-secret");
        assertThatThrownBy(() -> validator(descriptor(true, "SMTP_SANDBOX_V2")).validate())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("SMTP_SANDBOX_V1")
            .hasMessageNotContaining("smtp-password-secret");
        assertThatThrownBy(() -> new SmtpSandboxConfigurationValidator(
            properties, outboxProperties, new NotificationProviderRouter(List.of())).validate())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("notice.smtp.sandbox");
    }

    @Test
    void disabledDescriptorStaysCatalogVisibleButCannotBeSelected() {
        NotificationProviderRouter router = router(descriptor(false, "SMTP_SANDBOX_V1"));

        new SmtpSandboxConfigurationValidator(properties, outboxProperties, router).validate();

        assertThat(router.catalog()).singleElement().satisfies(item -> {
            assertThat(item.providerKey()).isEqualTo("notice.smtp.sandbox");
            assertThat(item.enabled()).isFalse();
        });
        assertThatThrownBy(() -> router.require("notice.smtp.sandbox"))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("NOTICE_PROVIDER_NOT_FOUND");
    }

    private SmtpSandboxConfigurationValidator validator(NotificationProviderDescriptor descriptor) {
        return new SmtpSandboxConfigurationValidator(properties, outboxProperties, router(descriptor));
    }

    private NotificationProviderRouter router(NotificationProviderDescriptor descriptor) {
        return new NotificationProviderRouter(List.of(new NotificationProvider() {
            @Override
            public NotificationProviderDescriptor descriptor() {
                return descriptor;
            }

            @Override
            public NotificationDeliveryResult send(NotificationDeliveryCommand command) {
                throw new UnsupportedOperationException("not used by configuration validation");
            }
        }));
    }

    private NotificationProviderDescriptor descriptor(boolean enabled, String version) {
        return new NotificationProviderDescriptor(
            "notice.smtp.sandbox", "MESSAGE", "SANDBOX", "SMTP_EMAIL", "EMAIL",
            "SMTP 邮件沙箱", version, enabled);
    }
}
