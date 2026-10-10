package com.example.venue.notice.provider;

import com.example.venue.notice.email.EmailAddressValidator;
import com.example.venue.notice.service.NotificationOutboxProperties;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.util.Set;

@Component
public class SmtpSandboxConfigurationValidator {
    private static final Set<String> ALLOWED_HOSTS = Set.of("127.0.0.1", "localhost", "mailpit");

    private final SmtpSandboxProperties properties;
    private final NotificationOutboxProperties outboxProperties;
    private final NotificationProviderRouter router;
    private final EmailAddressValidator addressValidator = new EmailAddressValidator();

    public SmtpSandboxConfigurationValidator(
        SmtpSandboxProperties properties,
        NotificationOutboxProperties outboxProperties,
        NotificationProviderRouter router
    ) {
        this.properties = properties;
        this.outboxProperties = outboxProperties;
        this.router = router;
    }

    @PostConstruct
    public void validate() {
        if (!properties.isEnabled()) {
            return;
        }
        require(properties.isSandboxOnly(), "app.notice.smtp.sandbox-only");
        require(ALLOWED_HOSTS.contains(trim(properties.getHost())), "app.notice.smtp.host");
        require(properties.getPort() > 0 && properties.getPort() <= 65_535,
            "app.notice.smtp.port");
        require(validFromAddress(properties.getFromAddress()), "app.notice.smtp.from-address");
        require(validHeaderText(properties.getFromName()), "app.notice.smtp.from-name");
        require(properties.getConnectTimeoutMs() > 0, "app.notice.smtp.connect-timeout-ms");
        require(properties.getReadTimeoutMs() > 0, "app.notice.smtp.read-timeout-ms");
        require(properties.getWriteTimeoutMs() > 0, "app.notice.smtp.write-timeout-ms");
        long timeoutBudget = (long) properties.getConnectTimeoutMs()
            + properties.getReadTimeoutMs() + properties.getWriteTimeoutMs();
        require(outboxProperties.getLeaseSeconds() > 0
                && outboxProperties.getLeaseSeconds() * 1000L > timeoutBudget,
            "app.notice.outbox.lease-seconds");
        if (properties.isAuth()) {
            require(hasText(properties.getUsername()), "app.notice.smtp.username");
            require(hasText(properties.getPassword()), "app.notice.smtp.password");
        }
        validateDescriptor();
    }

    private void validateDescriptor() {
        java.util.List<NotificationProviderDescriptor> descriptors = router.catalog().stream()
            .filter(item -> "notice.smtp.sandbox".equals(item.providerKey()))
            .toList();
        require(descriptors.size() == 1, "notice.smtp.sandbox descriptor");
        NotificationProviderDescriptor descriptor = descriptors.get(0);
        require("MESSAGE".equals(descriptor.providerDomain()), "notice.smtp.sandbox providerDomain");
        require("SANDBOX".equals(descriptor.providerEnv()), "notice.smtp.sandbox providerEnv");
        require("SMTP_EMAIL".equals(descriptor.providerName()), "notice.smtp.sandbox providerName");
        require("EMAIL".equals(descriptor.channel()), "notice.smtp.sandbox channel");
        require("SMTP_SANDBOX_V1".equals(descriptor.configVersion()), "SMTP_SANDBOX_V1");
        require(descriptor.enabled(), "notice.smtp.sandbox enabled");
    }

    private boolean validFromAddress(String value) {
        String trimmed = trim(value);
        return trimmed != null && trimmed.equals(addressValidator.normalizeStrict(trimmed));
    }

    private boolean validHeaderText(String value) {
        return hasText(value) && value.indexOf('\r') < 0 && value.indexOf('\n') < 0;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String trim(String value) {
        return value == null ? null : value.trim();
    }

    private void require(boolean condition, String propertyName) {
        if (!condition) {
            throw new IllegalStateException("Invalid SMTP sandbox configuration: " + propertyName);
        }
    }
}
