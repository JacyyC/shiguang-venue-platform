package com.example.venue.notice;

import com.example.venue.notice.entity.NoticeDeliveryLog;
import com.example.venue.notice.entity.NoticeOutbox;
import com.example.venue.notice.mapper.NoticeDeliveryLogMapper;
import com.example.venue.notice.mapper.NoticeEmailDeliveryMapper;
import com.example.venue.notice.mapper.NoticeOutboxMapper;
import com.example.venue.notice.provider.NotificationDeliveryCommand;
import com.example.venue.notice.provider.NotificationDeliveryResult;
import com.example.venue.notice.provider.NotificationProviderDescriptor;
import com.example.venue.notice.service.NotificationOutboxAttemptStateService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NotificationOutboxAttemptStateServiceTest {
    private NoticeOutboxMapper outboxMapper;
    private NoticeDeliveryLogMapper deliveryLogMapper;
    private NoticeEmailDeliveryMapper emailDeliveryMapper;
    private NotificationOutboxAttemptStateService service;

    @BeforeEach
    void setUp() {
        outboxMapper = mock(NoticeOutboxMapper.class);
        deliveryLogMapper = mock(NoticeDeliveryLogMapper.class);
        emailDeliveryMapper = mock(NoticeEmailDeliveryMapper.class);
        service = new NotificationOutboxAttemptStateService(
            outboxMapper, deliveryLogMapper, emailDeliveryMapper);
    }

    @Test
    void sentTransitionAndAttemptLogAreWrittenTogetherForClaimGeneration() {
        NoticeOutbox outbox = outbox();
        outbox.requestSummaryJson = "{\"messageId\":91,\"userId\":7,"
            + "\"content\":\"user@example.com token=secret\"}";
        outbox.metadataJson = "{\"providerKey\":\"notice.in_app.dev\",\"secret\":\"hidden\"}";
        LocalDateTime now = LocalDateTime.of(2026, 7, 10, 10, 0);
        when(outboxMapper.markSent(41L, 2, "message-91", "{\"status\":\"ACCEPTED\"}", now))
            .thenReturn(1);

        boolean updated = service.markSent(outbox, command(), accepted(),
            "{\"status\":\"ACCEPTED\"}", now, 12L);

        assertThat(updated).isTrue();
        ArgumentCaptor<NoticeDeliveryLog> log = ArgumentCaptor.forClass(NoticeDeliveryLog.class);
        verify(deliveryLogMapper).insert(log.capture());
        assertThat(log.getValue().status).isEqualTo("SUCCESS");
        assertThat(log.getValue().messageId).isEqualTo(91L);
        assertThat(log.getValue().externalRef).isEqualTo("message-91");
        assertThat(log.getValue().idempotencyKey).isEqualTo("NOTICE_MESSAGE:91");
        assertThat(log.getValue().durationMs).isEqualTo(12L);
        assertThat(log.getValue().requestSummaryJson)
            .contains("messageId", "userId").doesNotContain("content", "example.com", "secret");
        assertThat(log.getValue().metadataJson)
            .contains("providerKey").doesNotContain("secret", "hidden");
    }

    @Test
    void lostGenerationWritesAttemptEvidenceWithoutChangingOutbox() {
        NoticeOutbox outbox = outbox();
        LocalDateTime now = LocalDateTime.of(2026, 7, 10, 10, 0);
        when(outboxMapper.markSent(41L, 2, "message-91", "{\"status\":\"ACCEPTED\"}", now))
            .thenReturn(0);

        boolean updated = service.markSent(outbox, command(), accepted(),
            "{\"status\":\"ACCEPTED\"}", now, 12L);

        assertThat(updated).isFalse();
        ArgumentCaptor<NoticeDeliveryLog> log = ArgumentCaptor.forClass(NoticeDeliveryLog.class);
        verify(deliveryLogMapper).insert(log.capture());
        assertThat(log.getValue().status).isEqualTo("LOST_CLAIM");
        assertThat(log.getValue().failureCode).isEqualTo("STALE_GENERATION");
        verify(emailDeliveryMapper, never()).markAccepted(any(), any());
        verify(emailDeliveryMapper, never()).markFailed(any(), any());
    }

    @Test
    void acceptedEmailUpdatesSnapshotAfterTerminalCasAndWritesOnlyMaskedTarget() {
        NoticeOutbox outbox = emailOutbox();
        LocalDateTime now = LocalDateTime.of(2026, 7, 10, 10, 0);
        NotificationDeliveryCommand command = emailCommand();
        when(outboxMapper.markSent(41L, 2, canonicalMessageId(),
            "{\"status\":\"ACCEPTED\"}", now)).thenReturn(1);
        when(emailDeliveryMapper.markAccepted(41L, now)).thenReturn(1);

        boolean updated = service.markSent(outbox, command, emailAccepted(),
            "{\"status\":\"ACCEPTED\"}", now, 12L);

        assertThat(updated).isTrue();
        var ordered = inOrder(outboxMapper, emailDeliveryMapper, deliveryLogMapper);
        ordered.verify(outboxMapper).markSent(41L, 2, canonicalMessageId(),
            "{\"status\":\"ACCEPTED\"}", now);
        ordered.verify(emailDeliveryMapper).markAccepted(41L, now);
        ArgumentCaptor<NoticeDeliveryLog> log = ArgumentCaptor.forClass(NoticeDeliveryLog.class);
        ordered.verify(deliveryLogMapper).insert(log.capture());
        assertThat(log.getValue().target).isEqualTo("a***e@e***e.test");
        assertThat(log.getValue().target).doesNotContain("alice@example.test");
        assertThat(log.getValue().requestSummaryJson)
            .contains("userId", "eventType", "templateKey", "templateVersion", "maskedRecipient")
            .doesNotContain("alice@example.test", canonicalMessageId());
    }

    @Test
    void terminalEmailFailureUpdatesSnapshotOnlyAfterSuccessfulOutboxCas() {
        NoticeOutbox outbox = emailOutbox();
        LocalDateTime now = LocalDateTime.of(2026, 7, 10, 10, 0);
        when(outboxMapper.markFailed(41L, 2, "SMTP_AUTH_FAILED", "SMTP authentication failed",
            "{\"status\":\"FAILED_PERMANENT\"}", now)).thenReturn(1);
        when(emailDeliveryMapper.markFailed(41L, now)).thenReturn(1);

        boolean updated = service.markFailed(outbox, emailCommand(), emailPermanentFailure(),
            "SMTP_AUTH_FAILED", "SMTP authentication failed",
            "{\"status\":\"FAILED_PERMANENT\"}", now, 9L);

        assertThat(updated).isTrue();
        var ordered = inOrder(outboxMapper, emailDeliveryMapper);
        ordered.verify(outboxMapper).markFailed(41L, 2, "SMTP_AUTH_FAILED",
            "SMTP authentication failed", "{\"status\":\"FAILED_PERMANENT\"}", now);
        ordered.verify(emailDeliveryMapper).markFailed(41L, now);
        verify(emailDeliveryMapper, never()).markAccepted(any(), any());
    }

    @Test
    void acceptedEmailSnapshotZeroRowsAbortsTerminalUnitOfWorkBeforeLog() {
        NoticeOutbox outbox = emailOutbox();
        LocalDateTime now = LocalDateTime.of(2026, 7, 10, 10, 0);
        when(outboxMapper.markSent(41L, 2, canonicalMessageId(),
            "{\"status\":\"ACCEPTED\"}", now)).thenReturn(1);
        when(emailDeliveryMapper.markAccepted(41L, now)).thenReturn(0);

        assertThatThrownBy(() -> service.markSent(outbox, emailCommand(), emailAccepted(),
            "{\"status\":\"ACCEPTED\"}", now, 12L))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("EMAIL snapshot terminal update");

        verify(deliveryLogMapper, never()).insert(any(NoticeDeliveryLog.class));
    }

    @Test
    void failedEmailSnapshotZeroRowsAbortsTerminalUnitOfWorkBeforeLog() {
        NoticeOutbox outbox = emailOutbox();
        LocalDateTime now = LocalDateTime.of(2026, 7, 10, 10, 0);
        when(outboxMapper.markFailed(41L, 2, "SMTP_AUTH_FAILED", "SMTP authentication failed",
            "{\"status\":\"FAILED_PERMANENT\"}", now)).thenReturn(1);
        when(emailDeliveryMapper.markFailed(41L, now)).thenReturn(0);

        assertThatThrownBy(() -> service.markFailed(outbox, emailCommand(), emailPermanentFailure(),
            "SMTP_AUTH_FAILED", "SMTP authentication failed",
            "{\"status\":\"FAILED_PERMANENT\"}", now, 9L))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("EMAIL snapshot terminal update");

        verify(deliveryLogMapper, never()).insert(any(NoticeDeliveryLog.class));
    }

    @Test
    void acceptedMapperAllowsPendingOrFailedManualRetryWithoutOverwritingAccepted() throws Exception {
        Update update = NoticeEmailDeliveryMapper.class
            .getMethod("markAccepted", Long.class, LocalDateTime.class)
            .getAnnotation(Update.class);

        String sql = String.join(" ", update.value()).replaceAll("\\s+", " ");

        assertThat(sql)
            .contains("acceptance_status in ('PENDING', 'FAILED')")
            .doesNotContain("acceptance_status <> 'ACCEPTED'");
    }

    @Test
    void failedMapperOnlyTouchesPendingOrFailedSnapshots() throws Exception {
        Update update = NoticeEmailDeliveryMapper.class
            .getMethod("markFailed", Long.class, LocalDateTime.class)
            .getAnnotation(Update.class);

        String sql = String.join(" ", update.value()).replaceAll("\\s+", " ");

        assertThat(sql)
            .contains("acceptance_status in ('PENDING', 'FAILED')")
            .doesNotContain("acceptance_status <> 'ACCEPTED'");
    }

    @Test
    void retryWaitingEmailLeavesSnapshotPending() {
        NoticeOutbox outbox = emailOutbox();
        LocalDateTime now = LocalDateTime.of(2026, 7, 10, 10, 0);
        LocalDateTime nextRetry = now.plusSeconds(30);
        when(outboxMapper.markRetryWaiting(41L, 2, "SMTP_4XX_421", "temporary",
            "{\"status\":\"FAILED_RETRYABLE\"}", nextRetry, now)).thenReturn(1);

        assertThat(service.markRetryWaiting(outbox, emailCommand(), emailRetryableFailure(),
            "SMTP_4XX_421", "temporary", "{\"status\":\"FAILED_RETRYABLE\"}",
            nextRetry, now, 8L)).isTrue();

        verify(emailDeliveryMapper, never()).markAccepted(any(), any());
        verify(emailDeliveryMapper, never()).markFailed(any(), any());
    }

    private NoticeOutbox outbox() {
        NoticeOutbox outbox = new NoticeOutbox();
        outbox.id = 41L;
        outbox.userId = 7L;
        outbox.channel = "IN_APP";
        outbox.providerEnv = "DEV";
        outbox.providerName = "IN_APP_MESSAGE";
        outbox.configVersion = "IN_APP_MESSAGE_V1";
        outbox.attemptCount = 2;
        outbox.idempotencyKey = "NOTICE_MESSAGE:91";
        outbox.requestSummaryJson = "{\"messageId\":91}";
        outbox.metadataJson = "{\"providerKey\":\"notice.in_app.dev\"}";
        return outbox;
    }

    private NotificationDeliveryCommand command() {
        NotificationProviderDescriptor descriptor = new NotificationProviderDescriptor(
            "notice.in_app.dev", "MESSAGE", "DEV", "IN_APP_MESSAGE",
            "IN_APP", "站内消息", "IN_APP_MESSAGE_V1", true);
        return new NotificationDeliveryCommand(
            41L, "NOTICE_MESSAGE:91", descriptor, 7L, 91L, 99L,
            "RESERVATION", "预约已提交", "请等待", "{\"messageId\":91}");
    }

    private NotificationDeliveryResult accepted() {
        return new NotificationDeliveryResult(
            NotificationDeliveryResult.ACCEPTED, "message-91", false,
            null, null, "{\"status\":\"ACCEPTED\"}");
    }

    private NoticeOutbox emailOutbox() {
        NoticeOutbox outbox = outbox();
        outbox.channel = "EMAIL";
        outbox.providerEnv = "SANDBOX";
        outbox.providerName = "SMTP_EMAIL";
        outbox.configVersion = "SMTP_SANDBOX_V1";
        outbox.idempotencyKey = "EMAIL:" + "a".repeat(64);
        outbox.eventType = "RESERVATION_APPROVED";
        outbox.templateKey = "reservation.approved";
        outbox.templateVersion = "v1";
        return outbox;
    }

    private NotificationDeliveryCommand emailCommand() {
        NotificationProviderDescriptor descriptor = new NotificationProviderDescriptor(
            "notice.smtp.sandbox", "MESSAGE", "SANDBOX", "SMTP_EMAIL",
            "EMAIL", "SMTP 邮件沙箱", "SMTP_SANDBOX_V1", true);
        return new NotificationDeliveryCommand(
            41L, "EMAIL:" + "a".repeat(64), descriptor, 7L, null, null,
            null, null, null, null, 101L, "a***e@e***e.test",
            "reservation.approved", "v1", canonicalMessageId(), "RESERVATION_APPROVED");
    }

    private NotificationDeliveryResult emailAccepted() {
        return new NotificationDeliveryResult(
            NotificationDeliveryResult.ACCEPTED, canonicalMessageId(), false,
            null, null, "{\"status\":\"ACCEPTED\"}");
    }

    private NotificationDeliveryResult emailPermanentFailure() {
        return new NotificationDeliveryResult(
            NotificationDeliveryResult.FAILED_PERMANENT, null, false,
            "SMTP_AUTH_FAILED", "SMTP authentication failed",
            "{\"status\":\"FAILED_PERMANENT\"}");
    }

    private NotificationDeliveryResult emailRetryableFailure() {
        return new NotificationDeliveryResult(
            NotificationDeliveryResult.FAILED_RETRYABLE, null, true,
            "SMTP_4XX_421", "temporary", "{\"status\":\"FAILED_RETRYABLE\"}");
    }

    private String canonicalMessageId() {
        return "<" + "b".repeat(64) + "@venue.local>";
    }
}
