package com.example.venue.notice;

import com.example.venue.notice.email.EmailSnapshotService;
import com.example.venue.notice.entity.NoticeEmailDelivery;
import com.example.venue.notice.mapper.NoticeEmailDeliveryMapper;
import com.example.venue.notice.provider.NotificationDeliveryCommand;
import com.example.venue.notice.provider.NotificationDeliveryResult;
import com.example.venue.notice.provider.SmtpFailureClassifier;
import com.example.venue.notice.provider.SmtpSandboxNotificationProvider;
import com.example.venue.notice.provider.SmtpSandboxProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.mail.Address;
import jakarta.mail.Message;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SmtpSandboxNotificationProviderTest {
    private NoticeEmailDeliveryMapper deliveryMapper;
    private RecordingMailSender mailSender;
    private SmtpSandboxProperties properties;
    private SmtpSandboxNotificationProvider provider;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        deliveryMapper = mock(NoticeEmailDeliveryMapper.class);
        mailSender = new RecordingMailSender();
        properties = new SmtpSandboxProperties();
        objectMapper = new ObjectMapper();
        provider = new SmtpSandboxNotificationProvider(
            properties,
            new EmailSnapshotService(deliveryMapper),
            new SmtpFailureClassifier(),
            objectMapper,
            mailSender);
    }

    @Test
    void descriptorIdentityIsFixedAndEnabledOnlyByTheSingleFlag() {
        assertThat(provider.descriptor()).satisfies(descriptor -> {
            assertThat(descriptor.providerKey()).isEqualTo("notice.smtp.sandbox");
            assertThat(descriptor.providerDomain()).isEqualTo("MESSAGE");
            assertThat(descriptor.providerEnv()).isEqualTo("SANDBOX");
            assertThat(descriptor.providerName()).isEqualTo("SMTP_EMAIL");
            assertThat(descriptor.channel()).isEqualTo("EMAIL");
            assertThat(descriptor.configVersion()).isEqualTo("SMTP_SANDBOX_V1");
            assertThat(descriptor.enabled()).isFalse();
        });

        properties.setEnabled(true);
        assertThat(provider.descriptor().enabled()).isTrue();
    }

    @Test
    void acceptedResultRequiresCompletedSendAndUsesOnlyDurableSnapshotContent() throws Exception {
        NoticeEmailDelivery snapshot = snapshot();
        when(deliveryMapper.findByOutboxId(91L)).thenReturn(snapshot);
        NotificationDeliveryCommand command = emailCommand();

        NotificationDeliveryResult result = provider.send(command);

        assertThat(result.status()).isEqualTo(NotificationDeliveryResult.ACCEPTED);
        assertThat(result.providerRequestId()).isEqualTo(snapshot.messageIdHeader);
        assertThat(mailSender.message).isNotNull();
        Address[] from = mailSender.message.getFrom();
        assertThat(from).singleElement().asString().contains("no-reply@venue.local");
        assertThat(mailSender.message.getRecipients(Message.RecipientType.TO))
            .singleElement().asString().isEqualTo("alice@example.test");
        assertThat(mailSender.message.getSubject()).isEqualTo(snapshot.subjectSnapshot);
        assertThat(mailSender.message.getHeader("Message-ID", null)).isEqualTo(snapshot.messageIdHeader);
        assertThat(mailSender.message.getHeader("X-Notification-Event", null))
            .isEqualTo("RESERVATION_APPROVED");
        assertThat(mailSender.message.getHeader("X-Template-Key", null))
            .isEqualTo("reservation.approved");
        assertThat(mailSender.message.getHeader("X-Template-Version", null)).isEqualTo("v1");
        assertThat(mailSender.message.getContentType()).startsWith("text/plain").contains("UTF-8");
        assertThat(mailSender.message.getContent().toString()).isEqualTo(snapshot.bodyTextSnapshot);

        JsonNode summary = objectMapper.readTree(result.responseSummaryJson());
        assertThat(summary.get("status").asText()).isEqualTo(NotificationDeliveryResult.ACCEPTED);
        assertThat(summary.get("maskedRecipient").asText()).isEqualTo("a***e@e***e.test");
        assertThat(summary.get("templateKey").asText()).isEqualTo("reservation.approved");
        assertThat(summary.get("templateVersion").asText()).isEqualTo("v1");
        assertThat(summary.get("messageIdHash").asText()).matches("[0-9a-f]{64}");
        assertThat(result.responseSummaryJson())
            .doesNotContain("alice@example.test", snapshot.bodyTextSnapshot, snapshot.subjectSnapshot,
                snapshot.messageIdHeader);
    }

    @Test
    void sendFailureNeverReportsAcceptedOrLeaksTransportText() {
        when(deliveryMapper.findByOutboxId(91L)).thenReturn(snapshot());
        mailSender.failure = new MailSendException(
            "alice@example.test smtp-password-secret hidden subject body smtp response");

        NotificationDeliveryResult result = provider.send(emailCommand());

        assertThat(result.status()).isEqualTo(NotificationDeliveryResult.FAILED_RETRYABLE);
        assertThat(result.failureCode()).isEqualTo("SMTP_UNCLASSIFIED_FAILURE");
        assertThat(result.failureMessage()).doesNotContain(
            "alice@example.test", "smtp-password-secret", "hidden subject", "body", "smtp response");
        assertThat(result.responseSummaryJson()).doesNotContain(
            "alice@example.test", "smtp-password-secret", "hidden subject", "body", "smtp response");
    }

    @Test
    void mismatchedSnapshotIsAClosedPermanentFailureBeforeSmtp() {
        NoticeEmailDelivery snapshot = snapshot();
        snapshot.outboxId = 999L;
        when(deliveryMapper.findByOutboxId(91L)).thenReturn(snapshot);

        NotificationDeliveryResult result = provider.send(emailCommand());

        assertThat(result.status()).isEqualTo(NotificationDeliveryResult.FAILED_PERMANENT);
        assertThat(result.failureCode()).isEqualTo("EMAIL_SNAPSHOT_INVALID");
        assertThat(mailSender.message).isNull();
    }

    @Test
    void syntacticallyValidNonSandboxRecipientFailsClosedBeforeSmtp() {
        NoticeEmailDelivery snapshot = snapshot();
        snapshot.recipientAddress = "alice@example.com";
        when(deliveryMapper.findByOutboxId(91L)).thenReturn(snapshot);

        NotificationDeliveryResult result = provider.send(emailCommand());

        assertThat(result.status()).isEqualTo(NotificationDeliveryResult.FAILED_PERMANENT);
        assertThat(result.failureCode()).isEqualTo("EMAIL_ADDRESS_INVALID");
        assertThat(result.failureMessage()).doesNotContain("alice@example.com");
        assertThat(result.responseSummaryJson()).doesNotContain("alice@example.com");
        assertThat(mailSender.message).isNull();
    }

    private NotificationDeliveryCommand emailCommand() {
        return new NotificationDeliveryCommand(
            91L, "EMAIL:" + "a".repeat(64), provider.descriptor(), 7L,
            null, 42L, "RESERVATION", null, null, null,
            101L, "a***e@e***e.test", "reservation.approved", "v1",
            canonicalMessageId(), "RESERVATION_APPROVED");
    }

    private NoticeEmailDelivery snapshot() {
        NoticeEmailDelivery delivery = new NoticeEmailDelivery();
        delivery.id = 101L;
        delivery.outboxId = 91L;
        delivery.recipientUserId = 7L;
        delivery.recipientAddress = "alice@example.test";
        delivery.recipientMasked = "a***e@e***e.test";
        delivery.templateKey = "reservation.approved";
        delivery.templateVersion = "v1";
        delivery.subjectSnapshot = "预约 42 已审核通过";
        delivery.bodyTextSnapshot = "您的预约 42 已审核通过。";
        delivery.messageIdHeader = canonicalMessageId();
        delivery.acceptanceStatus = "PENDING";
        delivery.downstreamStatus = "NOT_OBSERVED";
        return delivery;
    }

    private String canonicalMessageId() {
        return "<" + "c".repeat(64) + "@venue.local>";
    }

    private static final class RecordingMailSender extends JavaMailSenderImpl {
        private MimeMessage message;
        private RuntimeException failure;

        @Override
        public void send(MimeMessage mimeMessage) {
            if (failure != null) {
                throw failure;
            }
            try {
                String messageId = mimeMessage.getMessageID();
                mimeMessage.saveChanges();
                if (messageId != null) {
                    mimeMessage.setHeader("Message-ID", messageId);
                }
            } catch (Exception exception) {
                throw new IllegalStateException(exception);
            }
            message = mimeMessage;
        }
    }
}
