package com.example.venue.notice;

import com.example.venue.notice.entity.NoticeMessage;
import com.example.venue.notice.entity.NoticeOutbox;
import com.example.venue.notice.entity.NoticeDeliveryLog;
import com.example.venue.notice.mapper.NoticeOutboxMapper;
import com.example.venue.notice.mapper.NoticeDeliveryLogMapper;
import com.example.venue.notice.mapper.NoticeEmailDeliveryMapper;
import com.example.venue.notice.provider.NotificationDeliveryCommand;
import com.example.venue.notice.provider.NotificationDeliveryResult;
import com.example.venue.notice.provider.NotificationProvider;
import com.example.venue.notice.provider.NotificationProviderDescriptor;
import com.example.venue.notice.provider.NotificationProviderRouter;
import com.example.venue.notice.service.NotificationOutboxCommandFactory;
import com.example.venue.notice.service.NotificationDeliveryFaultHook;
import com.example.venue.notice.service.EmailDeliveryEvidenceContext;
import com.example.venue.notice.service.NotificationOutboxAttemptStateService;
import com.example.venue.notice.service.NotificationOutboxDeliveryService;
import com.example.venue.notice.service.NotificationOutboxProperties;
import com.example.venue.notice.service.RealtimeMessagePublisher;
import com.example.venue.notice.provider.SmtpSandboxNotificationProvider;
import com.example.venue.notice.provider.SmtpSandboxProperties;
import com.example.venue.notice.provider.SmtpSandboxProviderRegistrySync;
import com.example.venue.provider.dto.ProviderCallRecord;
import com.example.venue.provider.dto.ProviderRegistryRegistration;
import com.example.venue.provider.service.ProviderObservabilityService;
import com.example.venue.common.BusinessException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.apache.ibatis.annotations.Select;
import org.mockito.Mock;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NotificationOutboxDeliveryServiceTest {
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 7, 10, 10, 0);

    @Mock
    private NoticeOutboxMapper outboxMapper;
    @Mock
    private NoticeDeliveryLogMapper deliveryLogMapper;
    @Mock
    private NoticeEmailDeliveryMapper emailDeliveryMapper;
    @Mock
    private NotificationProviderRouter router;
    @Mock
    private NotificationProvider inAppProvider;
    @Mock
    private NotificationOutboxCommandFactory commandFactory;
    @Mock
    private RealtimeMessagePublisher realtimePublisher;
    @Mock
    private NotificationDeliveryFaultHook faultHook;
    @Mock
    private ProviderObservabilityService providerObservabilityService;

    private NotificationOutboxDeliveryService service;
    private NotificationProviderDescriptor descriptor;

    @BeforeEach
    void setUp() {
        descriptor = new NotificationProviderDescriptor(
            "notice.in_app.dev", "MESSAGE", "DEV", "IN_APP_MESSAGE",
            "IN_APP", "站内消息", "IN_APP_MESSAGE_V1", true);
        NotificationOutboxProperties properties = new NotificationOutboxProperties();
        Clock clock = Clock.fixed(Instant.parse("2026-07-10T02:00:00Z"), ZONE);
        NotificationOutboxAttemptStateService stateService =
            new NotificationOutboxAttemptStateService(
                outboxMapper, deliveryLogMapper, emailDeliveryMapper);
        service = new NotificationOutboxDeliveryService(
            outboxMapper, emailDeliveryMapper, router, commandFactory, properties, List.of(realtimePublisher),
            stateService, faultHook, new ObjectMapper(), providerObservabilityService, clock);
    }

    @Test
    void onlyClaimWinnerCallsProviderAndMarksAccepted() {
        LocalDateTime staleBefore = NOW.minusSeconds(60);
        NoticeOutbox outbox = claimedOutbox(41L);
        NotificationDeliveryCommand command = command(outbox);
        when(outboxMapper.selectById(41L)).thenReturn(outbox);
        when(outboxMapper.claimForDelivery(41L, 0, NOW, staleBefore)).thenReturn(1);
        when(commandFactory.providerKey(outbox)).thenReturn("notice.in_app.dev");
        when(router.require("notice.in_app.dev")).thenReturn(inAppProvider);
        when(inAppProvider.descriptor()).thenReturn(descriptor);
        when(commandFactory.create(outbox, descriptor)).thenReturn(command);
        when(inAppProvider.send(command)).thenReturn(accepted("in-app-41"));
        when(outboxMapper.markSent(eq(41L), eq(1), eq("in-app-41"), anyString(), eq(NOW))).thenReturn(1);

        service.deliver(41L, staleBefore);

        var ordered = inOrder(faultHook, outboxMapper);
        ordered.verify(faultHook).afterProviderResult(eq(41L), any(NotificationDeliveryResult.class));
        ordered.verify(outboxMapper).markSent(eq(41L), eq(1), eq("in-app-41"), anyString(), eq(NOW));
        verify(inAppProvider).send(eq(command));
        verify(realtimePublisher).publish(any(NoticeMessage.class));
    }

    @Test
    void acceptedResultWithoutTerminalClaimDoesNotPublishRealtime() {
        LocalDateTime staleBefore = NOW.minusSeconds(60);
        NoticeOutbox outbox = claimedOutbox(41L);
        stubClaimed(outbox, staleBefore);
        when(inAppProvider.send(any())).thenReturn(accepted("in-app-41"));
        when(outboxMapper.markSent(eq(41L), eq(1), eq("in-app-41"), anyString(), eq(NOW))).thenReturn(0);

        service.deliver(41L, staleBefore);

        verify(realtimePublisher, never()).publish(any());
    }

    @Test
    void acceptedNonInAppProviderDoesNotPublishRealtime() {
        LocalDateTime staleBefore = NOW.minusSeconds(60);
        NoticeOutbox outbox = claimedOutbox(41L);
        NotificationProviderDescriptor emailDescriptor = new NotificationProviderDescriptor(
            "notice.email.test", "MESSAGE", "TEST", "EMAIL_TEST",
            "EMAIL", "测试邮件", "EMAIL_TEST_V1", true);
        NotificationDeliveryCommand command = new NotificationDeliveryCommand(
            outbox.id, outbox.idempotencyKey, emailDescriptor, 7L, null, 99L,
            "RESERVATION", "预约已提交", "请等待", "{}");
        when(outboxMapper.selectById(41L)).thenReturn(outbox);
        when(outboxMapper.claimForDelivery(41L, 0, NOW, staleBefore)).thenReturn(1);
        when(commandFactory.providerKey(outbox)).thenReturn("notice.email.test");
        when(router.require("notice.email.test")).thenReturn(inAppProvider);
        when(inAppProvider.descriptor()).thenReturn(emailDescriptor);
        when(commandFactory.create(outbox, emailDescriptor)).thenReturn(command);
        when(inAppProvider.send(command)).thenReturn(accepted("email-41"));
        when(outboxMapper.markSent(eq(41L), eq(1), eq("email-41"), anyString(), eq(NOW))).thenReturn(1);

        service.deliver(41L, staleBefore);

        verify(realtimePublisher, never()).publish(any());
    }

    @Test
    void acceptedResultWithInvalidJsonIsMarkedPermanent() {
        LocalDateTime staleBefore = NOW.minusSeconds(60);
        NoticeOutbox outbox = claimedOutbox(41L);
        stubClaimed(outbox, staleBefore);
        when(inAppProvider.send(any())).thenReturn(new NotificationDeliveryResult(
            NotificationDeliveryResult.ACCEPTED, "in-app-41", false,
            null, null, "{invalid"));

        service.deliver(41L, staleBefore);

        verify(outboxMapper).markFailed(eq(41L), eq(1), eq("NOTICE_PROVIDER_RESULT_INVALID"),
            anyString(), anyString(), eq(NOW));
        verify(outboxMapper, never()).markSent(any(), anyInt(), any(), any(), any());
        verify(realtimePublisher, never()).publish(any());
    }

    @Test
    void providerSummaryKeepsAllowedEvidenceAndDropsSensitiveFields() {
        LocalDateTime staleBefore = NOW.minusSeconds(60);
        NoticeOutbox outbox = claimedOutbox(41L);
        stubClaimed(outbox, staleBefore);
        when(inAppProvider.send(any())).thenReturn(new NotificationDeliveryResult(
            NotificationDeliveryResult.ACCEPTED, "in-app-41", false,
            null, null, "{\"status\":\"ACCEPTED\",\"messageId\":91,\"token\":\"secret\"}"));
        when(outboxMapper.markSent(eq(41L), eq(1), eq("in-app-41"), anyString(), eq(NOW))).thenReturn(1);

        service.deliver(41L, staleBefore);

        ArgumentCaptor<String> summary = ArgumentCaptor.forClass(String.class);
        verify(outboxMapper).markSent(eq(41L), eq(1), eq("in-app-41"), summary.capture(), eq(NOW));
        assertThat(summary.getValue()).contains("messageId").doesNotContain("token", "secret");
    }

    @Test
    void retryableFailureSchedulesExactBackoff() {
        LocalDateTime staleBefore = NOW.minusSeconds(60);
        NoticeOutbox outbox = claimedOutbox(41L);
        outbox.attemptCount = 0;
        stubClaimed(outbox, staleBefore);
        when(inAppProvider.send(any())).thenReturn(new NotificationDeliveryResult(
            NotificationDeliveryResult.FAILED_RETRYABLE, null, true,
            "TEMPORARY_UNAVAILABLE", "temporary", "{\"status\":\"FAILED\"}"));

        service.deliver(41L, staleBefore);

        verify(outboxMapper).markRetryWaiting(
            eq(41L), eq(1), eq("TEMPORARY_UNAVAILABLE"), eq("temporary"), anyString(),
            eq(NOW.plusSeconds(30)), eq(NOW));
        verify(realtimePublisher, never()).publish(any());
    }

    @Test
    void secondRetryableFailureSchedulesExactSixtySecondBackoff() {
        LocalDateTime staleBefore = NOW.minusSeconds(60);
        NoticeOutbox outbox = claimedOutbox(41L);
        outbox.attemptCount = 1;
        stubClaimed(outbox, staleBefore);
        when(inAppProvider.send(any())).thenReturn(new NotificationDeliveryResult(
            NotificationDeliveryResult.FAILED_RETRYABLE, null, true,
            "TEMPORARY_UNAVAILABLE", "temporary", "{\"status\":\"FAILED_RETRYABLE\"}"));

        service.deliver(41L, staleBefore);

        verify(outboxMapper).markRetryWaiting(
            eq(41L), eq(2), eq("TEMPORARY_UNAVAILABLE"), eq("temporary"), anyString(),
            eq(NOW.plusSeconds(60)), eq(NOW));
    }

    @Test
    void providerFailureMessageIsRedactedBeforePersistence() {
        LocalDateTime staleBefore = NOW.minusSeconds(60);
        NoticeOutbox outbox = claimedOutbox(41L);
        stubClaimed(outbox, staleBefore);
        when(inAppProvider.send(any())).thenReturn(new NotificationDeliveryResult(
            NotificationDeliveryResult.FAILED_RETRYABLE, null, true,
            "TEMPORARY_UNAVAILABLE", "token=secret user@example.com 13800138000",
            "{\"status\":\"FAILED_RETRYABLE\"}"));

        service.deliver(41L, staleBefore);

        ArgumentCaptor<String> failureMessage = ArgumentCaptor.forClass(String.class);
        verify(outboxMapper).markRetryWaiting(eq(41L), eq(1), eq("TEMPORARY_UNAVAILABLE"),
            failureMessage.capture(), anyString(), eq(NOW.plusSeconds(30)), eq(NOW));
        assertThat(failureMessage.getValue())
            .doesNotContain("secret", "user@example.com", "13800138000");
    }

    @Test
    void permanentFailureStopsWithoutNextRetry() {
        LocalDateTime staleBefore = NOW.minusSeconds(60);
        NoticeOutbox outbox = claimedOutbox(41L);
        stubClaimed(outbox, staleBefore);
        when(inAppProvider.send(any())).thenReturn(new NotificationDeliveryResult(
            NotificationDeliveryResult.FAILED_PERMANENT, null, false,
            "INVALID_PAYLOAD", "invalid", "{\"status\":\"FAILED\"}"));

        service.deliver(41L, staleBefore);

        verify(outboxMapper).markFailed(
            eq(41L), eq(1), eq("INVALID_PAYLOAD"), eq("invalid"), anyString(), eq(NOW));
        verify(outboxMapper, never()).markRetryWaiting(any(), anyInt(), any(), any(), any(), any(), any());
    }

    @Test
    void retryableFailureAtMaxAttemptsBecomesPermanent() {
        LocalDateTime staleBefore = NOW.minusSeconds(60);
        NoticeOutbox outbox = claimedOutbox(41L);
        outbox.attemptCount = 2;
        stubClaimed(outbox, staleBefore);
        when(inAppProvider.send(any())).thenReturn(new NotificationDeliveryResult(
            NotificationDeliveryResult.FAILED_RETRYABLE, null, true,
            "TEMPORARY_UNAVAILABLE", "temporary", "{\"status\":\"FAILED\"}"));

        service.deliver(41L, staleBefore);

        verify(outboxMapper).markFailed(
            eq(41L), eq(3), eq("MAX_ATTEMPTS_EXCEEDED"), eq("temporary"), anyString(), eq(NOW));
        verify(outboxMapper, never()).markRetryWaiting(any(), anyInt(), any(), any(), any(), any(), any());
    }

    @Test
    void lostClaimDoesNotCallProvider() {
        LocalDateTime staleBefore = NOW.minusSeconds(60);
        NoticeOutbox outbox = claimedOutbox(41L);
        when(outboxMapper.selectById(41L)).thenReturn(outbox);
        when(outboxMapper.claimForDelivery(41L, 0, NOW, staleBefore)).thenReturn(0);

        service.deliver(41L, staleBefore);

        verifyNoInteractions(router, commandFactory, inAppProvider, realtimePublisher);
    }

    @Test
    void invalidPayloadIsMarkedAsPermanentFailure() {
        LocalDateTime staleBefore = NOW.minusSeconds(60);
        NoticeOutbox outbox = claimedOutbox(41L);
        when(outboxMapper.selectById(41L)).thenReturn(outbox);
        when(outboxMapper.claimForDelivery(41L, 0, NOW, staleBefore)).thenReturn(1);
        when(commandFactory.providerKey(outbox)).thenThrow(
            BusinessException.badRequest("NOTICE_OUTBOX_PAYLOAD_INVALID: 通知 Outbox 载荷无效"));

        service.deliver(41L, staleBefore);

        verify(outboxMapper).markFailed(eq(41L), eq(1), eq("NOTICE_OUTBOX_PAYLOAD_INVALID"),
            anyString(), anyString(), eq(NOW));
        verifyNoInteractions(router, inAppProvider, realtimePublisher);
    }

    @Test
    void routingFailureIsMarkedAsPermanentFailure() {
        LocalDateTime staleBefore = NOW.minusSeconds(60);
        NoticeOutbox outbox = claimedOutbox(41L);
        when(outboxMapper.selectById(41L)).thenReturn(outbox);
        when(outboxMapper.claimForDelivery(41L, 0, NOW, staleBefore)).thenReturn(1);
        when(commandFactory.providerKey(outbox)).thenReturn("notice.unknown");
        when(router.require("notice.unknown")).thenThrow(
            BusinessException.notFound("NOTICE_PROVIDER_NOT_FOUND: 通知 Provider 不存在或未启用"));

        service.deliver(41L, staleBefore);

        verify(outboxMapper).markFailed(eq(41L), eq(1), eq("NOTICE_PROVIDER_NOT_FOUND"),
            anyString(), anyString(), eq(NOW));
        verifyNoInteractions(inAppProvider, realtimePublisher);
    }

    @Test
    // A26-P2-17：命令构建阶段的瞬时 DB 异常（连接池耗尽、主从切换）应走重试退避，不直接终态化。
    // commandFactory.create 内部含真实 DB 读，DataAccessException 是瞬时故障；同样抖动若发生在
    // provider.send 内则正常退避重试，命令构建阶段应与之对齐。
    void commandBuildDataAccessExceptionSchedulesRetryableBackoff() {
        LocalDateTime staleBefore = NOW.minusSeconds(60);
        NoticeOutbox outbox = claimedOutbox(41L);
        outbox.attemptCount = 0;
        when(outboxMapper.selectById(41L)).thenReturn(outbox);
        when(outboxMapper.claimForDelivery(41L, 0, NOW, staleBefore)).thenReturn(1);
        when(commandFactory.providerKey(outbox)).thenThrow(
            new DataAccessResourceFailureException("Connection refused: pool exhausted"));

        service.deliver(41L, staleBefore);

        verify(outboxMapper).markRetryWaiting(
            eq(41L), eq(1), eq("NOTICE_OUTBOX_COMMAND_BUILD_TRANSIENT"), anyString(), anyString(),
            eq(NOW.plusSeconds(30)), eq(NOW));
        verify(outboxMapper, never()).markFailed(any(), anyInt(), any(), any(), any(), any());
        verifyNoInteractions(inAppProvider, realtimePublisher);
    }

    @Test
    // A26-P2-17：commandFactory.create 内部 DB 读瞬时异常同样走重试退避（覆盖 create 路径，与 providerKey 路径一致）
    void commandFactoryCreateDataAccessExceptionSchedulesRetryableBackoff() {
        LocalDateTime staleBefore = NOW.minusSeconds(60);
        NoticeOutbox outbox = claimedOutbox(41L);
        outbox.attemptCount = 1;
        when(outboxMapper.selectById(41L)).thenReturn(outbox);
        when(outboxMapper.claimForDelivery(41L, 1, NOW, staleBefore)).thenReturn(1);
        when(commandFactory.providerKey(outbox)).thenReturn("notice.in_app.dev");
        when(router.require("notice.in_app.dev")).thenReturn(inAppProvider);
        when(inAppProvider.descriptor()).thenReturn(descriptor);
        when(commandFactory.create(outbox, descriptor)).thenThrow(
            new DataAccessResourceFailureException("HikariCP: connection is not available"));

        service.deliver(41L, staleBefore);

        verify(outboxMapper).markRetryWaiting(
            eq(41L), eq(2), eq("NOTICE_OUTBOX_COMMAND_BUILD_TRANSIENT"), anyString(), anyString(),
            eq(NOW.plusSeconds(60)), eq(NOW));
        verify(outboxMapper, never()).markFailed(any(), anyInt(), any(), any(), any(), any());
        verify(inAppProvider, never()).send(any());
        verifyNoInteractions(realtimePublisher);
    }

    @Test
    // A26-P2-17：非 DataAccessException 的 RuntimeException（如 BusinessException 路由失败）仍走终态化，
    // 与原语义保持一致。确保新增 DataAccessException 分支不吞掉永久失败路径。
    void commandBuildRuntimeExceptionStillMarkedPermanent() {
        LocalDateTime staleBefore = NOW.minusSeconds(60);
        NoticeOutbox outbox = claimedOutbox(41L);
        when(outboxMapper.selectById(41L)).thenReturn(outbox);
        when(outboxMapper.claimForDelivery(41L, 0, NOW, staleBefore)).thenReturn(1);
        when(commandFactory.providerKey(outbox)).thenThrow(
            new IllegalStateException("NOTICE_OUTBOX_PAYLOAD_INVALID: 通知 Outbox 载荷无效"));

        service.deliver(41L, staleBefore);

        verify(outboxMapper).markFailed(eq(41L), eq(1), eq("NOTICE_OUTBOX_PAYLOAD_INVALID"),
            anyString(), anyString(), eq(NOW));
        verify(outboxMapper, never()).markRetryWaiting(any(), anyInt(), any(), any(), any(), any(), any());
        verifyNoInteractions(inAppProvider, realtimePublisher);
    }

    @Test
    void acceptedEmailRebuildsTrustedSummaryAndNeverPublishesSse() throws Exception {
        LocalDateTime staleBefore = NOW.minusSeconds(60);
        NoticeOutbox outbox = emailOutbox(41L, 0);
        NotificationDeliveryCommand command = emailCommand(outbox);
        NotificationDeliveryResult result = emailAccepted();
        stubClaimedEmail(outbox, command, staleBefore);
        when(inAppProvider.send(command)).thenReturn(result);
        when(outboxMapper.markSent(eq(41L), eq(1), eq(canonicalMessageId()),
            anyString(), eq(NOW))).thenReturn(1);
        when(emailDeliveryMapper.markAccepted(41L, NOW)).thenReturn(1);

        service.deliver(41L, staleBefore);

        verify(emailDeliveryMapper).markAccepted(41L, NOW);
        verify(realtimePublisher, never()).publish(any());
        ArgumentCaptor<ProviderCallRecord> evidence = ArgumentCaptor.forClass(ProviderCallRecord.class);
        verify(providerObservabilityService).recordCall(evidence.capture());
        assertThat(evidence.getValue().status()).isEqualTo("SUCCESS");
        assertThat(evidence.getValue().idempotencyKey()).isEqualTo(outbox.idempotencyKey).hasSize(70);
        assertThat(evidence.getValue().snapshotId()).isEqualTo(101L);
        assertThat(evidence.getValue().requestSummaryJson())
            .contains("userId", "eventType", "templateKey", "templateVersion", "maskedRecipient",
                "messageIdHash")
            .doesNotContain("alice@example.test", canonicalMessageId());
        assertThat(evidence.getValue().responseSummaryJson())
            .doesNotContain("alice@example.test", canonicalMessageId());
        ArgumentCaptor<String> outboxSummary = ArgumentCaptor.forClass(String.class);
        verify(outboxMapper).markSent(eq(41L), eq(1), eq(canonicalMessageId()),
            outboxSummary.capture(), eq(NOW));
        JsonNode trustedSummary = new ObjectMapper().readTree(outboxSummary.getValue());
        assertThat(trustedSummary.fieldNames()).toIterable().containsExactlyInAnyOrder(
            "status", "maskedRecipient", "templateKey", "templateVersion", "messageIdHash");
        assertThat(trustedSummary.path("status").asText()).isEqualTo("ACCEPTED");
        assertThat(trustedSummary.path("maskedRecipient").asText()).isEqualTo(command.maskedRecipient());
        assertThat(trustedSummary.path("templateKey").asText()).isEqualTo(command.templateKey());
        assertThat(trustedSummary.path("templateVersion").asText()).isEqualTo(command.templateVersion());
        assertThat(trustedSummary.path("messageIdHash").asText()).isEqualTo(sha256(canonicalMessageId()));
        assertThat(outboxSummary.getValue())
            .doesNotContain("alice@example.test", "forged.template", "forged-version",
                "messageId\"", "providerName", "c".repeat(64));
    }

    @Test
    void malformedAcceptedEmailSummaryPreservesAcceptedDeliveryFact() {
        LocalDateTime staleBefore = NOW.minusSeconds(60);
        NoticeOutbox outbox = emailOutbox(41L, 0);
        NotificationDeliveryCommand command = emailCommand(outbox);
        stubClaimedEmail(outbox, command, staleBefore);
        when(inAppProvider.send(command)).thenReturn(new NotificationDeliveryResult(
            NotificationDeliveryResult.ACCEPTED, canonicalMessageId(), false,
            null, null, "{invalid"));
        when(outboxMapper.markSent(eq(41L), eq(1), eq(canonicalMessageId()),
            anyString(), eq(NOW))).thenReturn(1);
        when(emailDeliveryMapper.markAccepted(41L, NOW)).thenReturn(1);

        service.deliver(41L, staleBefore);

        verify(outboxMapper).markSent(eq(41L), eq(1), eq(canonicalMessageId()),
            anyString(), eq(NOW));
        verify(emailDeliveryMapper).markAccepted(41L, NOW);
        verify(outboxMapper, never()).markFailed(any(), anyInt(), any(), any(), any(), any());
    }

    @Test
    void permanentEmailSummaryUsesResultFailureCodeAndTrustedCommandIdentity() throws Exception {
        LocalDateTime staleBefore = NOW.minusSeconds(60);
        NoticeOutbox outbox = emailOutbox(41L, 0);
        NotificationDeliveryCommand command = emailCommand(outbox);
        stubClaimedEmail(outbox, command, staleBefore);
        NotificationDeliveryResult result = new NotificationDeliveryResult(
            NotificationDeliveryResult.FAILED_PERMANENT, null, false,
            "SMTP_AUTH_FAILED", "permanent",
            "{\"status\":\"ACCEPTED\",\"failureCode\":\"FORGED\","
                + "\"maskedRecipient\":\"alice@example.test\","
                + "\"messageIdHash\":\"" + "c".repeat(64) + "\"}");
        when(inAppProvider.send(command)).thenReturn(result);
        when(outboxMapper.markFailed(eq(41L), eq(1), eq("SMTP_AUTH_FAILED"), eq("permanent"),
            anyString(), eq(NOW))).thenReturn(1);
        when(emailDeliveryMapper.markFailed(41L, NOW)).thenReturn(1);

        service.deliver(41L, staleBefore);

        ArgumentCaptor<String> summary = ArgumentCaptor.forClass(String.class);
        verify(outboxMapper).markFailed(eq(41L), eq(1), eq("SMTP_AUTH_FAILED"), eq("permanent"),
            summary.capture(), eq(NOW));
        JsonNode trustedSummary = new ObjectMapper().readTree(summary.getValue());
        assertThat(trustedSummary.path("status").asText()).isEqualTo("FAILED_PERMANENT");
        assertThat(trustedSummary.path("failureCode").asText()).isEqualTo("SMTP_AUTH_FAILED");
        assertThat(trustedSummary.path("maskedRecipient").asText()).isEqualTo(command.maskedRecipient());
        assertThat(trustedSummary.path("messageIdHash").asText()).isEqualTo(sha256(canonicalMessageId()));
        assertThat(summary.getValue()).doesNotContain("alice@example.test", "FORGED", "c".repeat(64));
    }

    @Test
    void lostTerminalCasCreatesNoProviderEvidenceAndDoesNotCauseSecondSmtpCall() {
        LocalDateTime staleBefore = NOW.minusSeconds(60);
        NoticeOutbox outbox = emailOutbox(41L, 0);
        NotificationDeliveryCommand command = emailCommand(outbox);
        stubClaimedEmail(outbox, command, staleBefore);
        when(inAppProvider.send(command)).thenReturn(emailAccepted());
        when(outboxMapper.markSent(eq(41L), eq(1), eq(canonicalMessageId()), anyString(), eq(NOW)))
            .thenReturn(0);

        service.deliver(41L, staleBefore);

        verify(inAppProvider, times(1)).send(command);
        verifyNoInteractions(providerObservabilityService);
        verify(emailDeliveryMapper, never()).markAccepted(any(), any());
        verify(deliveryLogMapper, never()).insert(any(com.example.venue.notice.entity.NoticeDeliveryLog.class));
    }

    @Test
    void providerEvidenceFailureCannotRollbackAcceptanceOrResendSmtp() {
        LocalDateTime staleBefore = NOW.minusSeconds(60);
        NoticeOutbox outbox = emailOutbox(41L, 0);
        NotificationDeliveryCommand command = emailCommand(outbox);
        stubClaimedEmail(outbox, command, staleBefore);
        when(inAppProvider.send(command)).thenReturn(emailAccepted());
        when(outboxMapper.markSent(eq(41L), eq(1), eq(canonicalMessageId()), anyString(), eq(NOW)))
            .thenReturn(1);
        when(emailDeliveryMapper.markAccepted(41L, NOW)).thenReturn(1);
        doThrow(new IllegalStateException("database secret details"))
            .when(providerObservabilityService).recordCall(any());

        service.deliver(41L, staleBefore);

        verify(outboxMapper, times(1)).markSent(eq(41L), eq(1), eq(canonicalMessageId()),
            anyString(), eq(NOW));
        verify(inAppProvider, times(1)).send(command);
        verify(outboxMapper, never()).markFailed(any(), anyInt(), any(), any(), any(), any());
    }

    @Test
    void terminalSmtpTimeoutRecordsTimeoutOnlyAfterFailedCasSucceeds() {
        LocalDateTime staleBefore = NOW.minusSeconds(60);
        NoticeOutbox outbox = emailOutbox(41L, 2);
        NotificationDeliveryCommand command = emailCommand(outbox);
        stubClaimedEmail(outbox, command, staleBefore);
        NotificationDeliveryResult timeout = new NotificationDeliveryResult(
            NotificationDeliveryResult.FAILED_RETRYABLE, null, true,
            "SMTP_READ_TIMEOUT", "SMTP read timed out",
            "{\"status\":\"FAILED_RETRYABLE\",\"failureCode\":\"SMTP_READ_TIMEOUT\"}");
        when(inAppProvider.send(command)).thenReturn(timeout);
        when(outboxMapper.markFailed(eq(41L), eq(3), eq("MAX_ATTEMPTS_EXCEEDED"),
            eq("SMTP read timed out"), anyString(), eq(NOW))).thenReturn(1);
        when(emailDeliveryMapper.markFailed(41L, NOW)).thenReturn(1);

        service.deliver(41L, staleBefore);

        ArgumentCaptor<ProviderCallRecord> evidence = ArgumentCaptor.forClass(ProviderCallRecord.class);
        var ordered = inOrder(outboxMapper, emailDeliveryMapper, providerObservabilityService);
        ordered.verify(outboxMapper).markFailed(eq(41L), eq(3), eq("MAX_ATTEMPTS_EXCEEDED"),
            eq("SMTP read timed out"), anyString(), eq(NOW));
        ordered.verify(emailDeliveryMapper).markFailed(41L, NOW);
        ordered.verify(providerObservabilityService).recordCall(evidence.capture());
        assertThat(evidence.getValue().status()).isEqualTo("TIMEOUT");
        assertThat(evidence.getValue().failureCode()).isEqualTo("MAX_ATTEMPTS_EXCEEDED");
    }

    @Test
    void smtp5xxAndAuthenticationFailuresAreImmediatelyTerminal() {
        LocalDateTime staleBefore = NOW.minusSeconds(60);
        NoticeOutbox first = emailOutbox(41L, 0);
        NoticeOutbox second = emailOutbox(42L, 0);
        NotificationDeliveryCommand firstCommand = emailCommand(first);
        NotificationDeliveryCommand secondCommand = emailCommand(second);
        when(outboxMapper.selectById(41L)).thenReturn(first);
        when(outboxMapper.selectById(42L)).thenReturn(second);
        when(outboxMapper.claimForDelivery(eq(41L), eq(0), eq(NOW), eq(staleBefore))).thenReturn(1);
        when(outboxMapper.claimForDelivery(eq(42L), eq(0), eq(NOW), eq(staleBefore))).thenReturn(1);
        when(commandFactory.providerKey(any())).thenReturn("notice.smtp.sandbox");
        when(router.require("notice.smtp.sandbox")).thenReturn(inAppProvider);
        when(inAppProvider.descriptor()).thenReturn(emailDescriptor());
        when(commandFactory.create(first, emailDescriptor())).thenReturn(firstCommand);
        when(commandFactory.create(second, emailDescriptor())).thenReturn(secondCommand);
        when(inAppProvider.send(firstCommand)).thenReturn(emailPermanent("SMTP_5XX_550"));
        when(inAppProvider.send(secondCommand)).thenReturn(emailPermanent("SMTP_AUTH_FAILED"));

        service.deliver(41L, staleBefore);
        service.deliver(42L, staleBefore);

        verify(outboxMapper).markFailed(eq(41L), eq(1), eq("SMTP_5XX_550"), anyString(), anyString(), eq(NOW));
        verify(outboxMapper).markFailed(eq(42L), eq(1), eq("SMTP_AUTH_FAILED"), anyString(), anyString(), eq(NOW));
        verify(outboxMapper, never()).markRetryWaiting(any(), anyInt(), any(), any(), any(), any(), any());
    }

    @Test
    void missingEmailSnapshotWritesUnavailableCommandNullEvidenceWithoutSmtp() {
        LocalDateTime staleBefore = NOW.minusSeconds(60);
        NoticeOutbox invalid = emailOutbox(41L, 0);
        invalid.requestSummaryJson = "{\"recipient\":\"alice@example.test\","
            + "\"subject\":\"private subject\",\"body\":\"secret body\"}";
        invalid.metadataJson = "{\"providerKey\":\"notice.smtp.sandbox\","
            + "\"secret\":\"metadata-secret\"}";
        when(outboxMapper.selectById(41L)).thenReturn(invalid);
        when(outboxMapper.claimForDelivery(41L, 0, NOW, staleBefore)).thenReturn(1);
        when(emailDeliveryMapper.findEvidenceByOutboxId(41L)).thenReturn(null);
        when(commandFactory.providerKey(invalid)).thenReturn("notice.smtp.sandbox");
        when(router.require("notice.smtp.sandbox")).thenReturn(inAppProvider);
        when(inAppProvider.descriptor()).thenReturn(emailDescriptor());
        when(commandFactory.create(invalid, emailDescriptor())).thenThrow(
            BusinessException.badRequest(
                "EMAIL_SNAPSHOT_INVALID: alice@example.test secret=raw-snapshot-secret"));
        when(outboxMapper.markFailed(eq(41L), eq(1), eq("EMAIL_SNAPSHOT_INVALID"),
            anyString(), anyString(), eq(NOW))).thenReturn(1);
        when(emailDeliveryMapper.markFailed(41L, NOW)).thenReturn(1);

        service.deliver(41L, staleBefore);

        verify(outboxMapper).markFailed(eq(41L), eq(1), eq("EMAIL_SNAPSHOT_INVALID"),
            anyString(), anyString(), eq(NOW));
        verify(inAppProvider, never()).send(any());
        verify(emailDeliveryMapper).findEvidenceByOutboxId(41L);
        verify(emailDeliveryMapper).markFailed(41L, NOW);
        ArgumentCaptor<NoticeDeliveryLog> log = ArgumentCaptor.forClass(NoticeDeliveryLog.class);
        verify(deliveryLogMapper).insert(log.capture());
        assertThat(log.getValue().target).isEqualTo("UNAVAILABLE");
        assertThat(log.getValue().failureCode).isEqualTo("EMAIL_SNAPSHOT_INVALID");
        assertThat(log.getValue().requestSummaryJson)
            .contains("userId", "eventType", "templateKey", "templateVersion")
            .doesNotContain("alice@example.test", "private subject", "secret body",
                "metadata-secret", "raw-snapshot-secret");
        assertThat(log.getValue().errorMessage)
            .doesNotContain("alice@example.test", "raw-snapshot-secret");

        ArgumentCaptor<ProviderCallRecord> evidence = ArgumentCaptor.forClass(ProviderCallRecord.class);
        verify(providerObservabilityService).recordCall(evidence.capture());
        assertThat(evidence.getValue().status()).isEqualTo("FAILED");
        assertThat(evidence.getValue().failureCode()).isEqualTo("EMAIL_SNAPSHOT_INVALID");
        assertThat(evidence.getValue().idempotencyKey()).isEqualTo(invalid.idempotencyKey).hasSize(70);
        assertThat(evidence.getValue().snapshotId()).isNull();
        assertThat(evidence.getValue().requestSummaryJson())
            .contains("userId", "eventType", "templateKey", "templateVersion")
            .doesNotContain("alice@example.test", "private subject", "secret body",
                "metadata-secret", "raw-snapshot-secret");
        assertThat(evidence.getValue().responseSummaryJson())
            .contains("FAILED", "EMAIL_SNAPSHOT_INVALID")
            .doesNotContain("alice@example.test", "raw-snapshot-secret");
    }

    @Test
    void disabledSmtpProviderUsesSafeSnapshotProjectionForCommandNullEvidence() throws Exception {
        LocalDateTime staleBefore = NOW.minusSeconds(60);
        NoticeOutbox outbox = emailOutbox(41L, 0);
        EmailDeliveryEvidenceContext.SafeSnapshotProjection projection = safeProjection(outbox);
        when(outboxMapper.selectById(41L)).thenReturn(outbox);
        when(outboxMapper.claimForDelivery(41L, 0, NOW, staleBefore)).thenReturn(1);
        when(emailDeliveryMapper.findEvidenceByOutboxId(41L)).thenReturn(projection);
        when(commandFactory.providerKey(outbox)).thenReturn("notice.smtp.sandbox");
        when(router.require("notice.smtp.sandbox")).thenThrow(
            BusinessException.notFound("NOTICE_PROVIDER_NOT_FOUND: provider disabled"));
        when(outboxMapper.markFailed(eq(41L), eq(1), eq("NOTICE_PROVIDER_NOT_FOUND"),
            anyString(), anyString(), eq(NOW))).thenReturn(1);
        when(emailDeliveryMapper.markFailed(41L, NOW)).thenReturn(1);

        service.deliver(41L, staleBefore);

        verify(outboxMapper).markFailed(eq(41L), eq(1), eq("NOTICE_PROVIDER_NOT_FOUND"),
            anyString(), anyString(), eq(NOW));
        verify(inAppProvider, never()).send(any());
        verifyNoInteractions(realtimePublisher);
        ArgumentCaptor<NoticeDeliveryLog> log = ArgumentCaptor.forClass(NoticeDeliveryLog.class);
        verify(deliveryLogMapper).insert(log.capture());
        assertThat(log.getValue().target).isEqualTo(projection.maskedRecipient);
        assertThat(log.getValue().externalRef).isEqualTo(sha256(canonicalMessageId()));
        assertThat(log.getValue().requestSummaryJson)
            .contains("userId", "eventType", "templateKey", "templateVersion",
                "maskedRecipient", "messageIdHash")
            .doesNotContain(canonicalMessageId());
        ArgumentCaptor<ProviderCallRecord> evidence = ArgumentCaptor.forClass(ProviderCallRecord.class);
        verify(providerObservabilityService).recordCall(evidence.capture());
        assertThat(evidence.getValue().status()).isEqualTo("FAILED");
        assertThat(evidence.getValue().failureCode()).isEqualTo("NOTICE_PROVIDER_NOT_FOUND");
        assertThat(evidence.getValue().idempotencyKey()).isEqualTo(outbox.idempotencyKey).hasSize(70);
        assertThat(evidence.getValue().snapshotId()).isEqualTo(projection.snapshotId);
        assertThat(evidence.getValue().externalRef()).isEqualTo(sha256(canonicalMessageId()));
        assertThat(evidence.getValue().requestSummaryJson())
            .contains(projection.maskedRecipient, "messageIdHash")
            .doesNotContain(canonicalMessageId());
    }

    @Test
    void staleSendingEmailReclaimsSameSnapshotMessageId() {
        LocalDateTime staleBefore = NOW.minusSeconds(60);
        NoticeOutbox outbox = emailOutbox(41L, 1);
        outbox.status = "SENDING";
        NotificationDeliveryCommand command = emailCommand(outbox);
        stubClaimedEmail(outbox, command, staleBefore);
        when(inAppProvider.send(command)).thenReturn(emailAccepted());

        service.deliver(41L, staleBefore);

        verify(outboxMapper).claimForDelivery(41L, 1, NOW, staleBefore);
        verify(inAppProvider).send(command);
        assertThat(command.messageIdHeader()).isEqualTo(canonicalMessageId());
    }

    @Test
    void startupSyncUpsertsSmtpDescriptorWithoutReadingRegistryForRouting() throws Exception {
        SmtpSandboxNotificationProvider smtpProvider = mock(SmtpSandboxNotificationProvider.class);
        SmtpSandboxProperties smtpProperties = new SmtpSandboxProperties();
        smtpProperties.setEnabled(true);
        smtpProperties.setAuth(true);
        smtpProperties.setUsername("smtp-user");
        smtpProperties.setPassword("smtp-secret");
        when(smtpProvider.descriptor()).thenReturn(emailDescriptor());
        SmtpSandboxProviderRegistrySync sync = new SmtpSandboxProviderRegistrySync(
            providerObservabilityService, smtpProvider, new ObjectMapper());

        sync.run(null);

        ArgumentCaptor<ProviderRegistryRegistration> registration =
            ArgumentCaptor.forClass(ProviderRegistryRegistration.class);
        verify(providerObservabilityService).upsertRegistry(registration.capture());
        assertThat(registration.getValue().providerKey()).isEqualTo("notice.smtp.sandbox");
        assertThat(registration.getValue().enabled()).isTrue();
        JsonNode metadata = new ObjectMapper().readTree(registration.getValue().metadataJson());
        assertThat(metadata.fieldNames()).toIterable().containsExactlyInAnyOrder(
            "sandbox", "acceptanceOnly", "internetDelivery");
        assertThat(metadata.path("sandbox").asBoolean()).isTrue();
        assertThat(metadata.path("acceptanceOnly").asBoolean()).isTrue();
        assertThat(metadata.path("internetDelivery").asBoolean()).isFalse();
        assertThat(registration.getValue().metadataJson())
            .doesNotContain("auth", "starttls", "channel", smtpProperties.getHost(),
                smtpProperties.getUsername(), smtpProperties.getPassword());
    }

    @Test
    void emailEvidenceProjectionSelectsOnlySafeSnapshotColumns() {
        Method method = java.util.Arrays.stream(NoticeEmailDeliveryMapper.class.getDeclaredMethods())
            .filter(candidate -> "findEvidenceByOutboxId".equals(candidate.getName()))
            .findFirst()
            .orElse(null);

        assertThat(method).as("safe EMAIL evidence projection mapper method").isNotNull();
        Select select = method.getAnnotation(Select.class);
        assertThat(select).isNotNull();
        String sql = String.join(" ", select.value()).replaceAll("\\s+", " ").toLowerCase();
        assertThat(sql)
            .contains("select id as snapshot_id, outbox_id, recipient_masked as masked_recipient, "
                + "template_key, template_version, message_id_header")
            .contains("from notice_email_delivery where outbox_id = #{outboxid} limit 1")
            .doesNotContain("select *", "recipient_address", "subject_snapshot", "body_text_snapshot");
    }

    @Test
    void emailEvidenceProjectionSqlAliasesAlignWithSafeSnapshotProjectionFields() {
        Method method = java.util.Arrays.stream(NoticeEmailDeliveryMapper.class.getDeclaredMethods())
            .filter(candidate -> "findEvidenceByOutboxId".equals(candidate.getName()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("safe EMAIL evidence projection mapper method missing"));

        Select select = method.getAnnotation(Select.class);
        assertThat(select).as("@Select on findEvidenceByOutboxId").isNotNull();
        String sql = String.join(" ", select.value()).replaceAll("\\s+", " ");

        java.util.Set<String> projectionFields = java.util.Arrays.stream(
                EmailDeliveryEvidenceContext.SafeSnapshotProjection.class.getDeclaredFields())
            .map(java.lang.reflect.Field::getName)
            .collect(java.util.stream.Collectors.toSet());

        int selectStart = sql.toLowerCase().indexOf("select ") + "select ".length();
        int fromStart = sql.toLowerCase().indexOf(" from ");
        String selectClause = sql.substring(selectStart, fromStart);

        java.util.Set<String> resolvedFields = new java.util.LinkedHashSet<>();
        for (String colDef : selectClause.split(",")) {
            String trimmed = colDef.trim();
            String alias;
            int asIndex = trimmed.toLowerCase().indexOf(" as ");
            if (asIndex >= 0) {
                alias = trimmed.substring(asIndex + " as ".length()).trim();
            } else {
                alias = trimmed;
            }
            resolvedFields.add(underscoreToCamelCase(alias));
        }

        assertThat(resolvedFields)
            .as("EMAIL evidence projection SQL aliases must align with SafeSnapshotProjection fields "
                + "after applying map-underscore-to-camel-case")
            .containsExactlyInAnyOrderElementsOf(projectionFields);
    }

    private static String underscoreToCamelCase(String value) {
        StringBuilder result = new StringBuilder();
        boolean nextUpper = false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '_') {
                nextUpper = true;
            } else if (nextUpper) {
                result.append(Character.toUpperCase(c));
                nextUpper = false;
            } else {
                result.append(Character.toLowerCase(c));
            }
        }
        return result.toString();
    }

    private void stubClaimed(NoticeOutbox outbox, LocalDateTime staleBefore) {
        NotificationDeliveryCommand command = command(outbox);
        when(outboxMapper.selectById(outbox.id)).thenReturn(outbox);
        when(outboxMapper.claimForDelivery(outbox.id, outbox.attemptCount, NOW, staleBefore)).thenReturn(1);
        when(commandFactory.providerKey(outbox)).thenReturn("notice.in_app.dev");
        when(router.require("notice.in_app.dev")).thenReturn(inAppProvider);
        when(inAppProvider.descriptor()).thenReturn(descriptor);
        when(commandFactory.create(outbox, descriptor)).thenReturn(command);
    }

    private NoticeOutbox claimedOutbox(Long id) {
        NoticeOutbox outbox = new NoticeOutbox();
        outbox.id = id;
        outbox.status = "SENDING";
        outbox.attemptCount = 0;
        outbox.providerEnv = "DEV";
        outbox.providerName = "IN_APP_MESSAGE";
        outbox.configVersion = "IN_APP_MESSAGE_V1";
        outbox.idempotencyKey = "NOTICE_MESSAGE:" + id;
        outbox.metadataJson = "{\"providerKey\":\"notice.in_app.dev\"}";
        return outbox;
    }

    private NotificationDeliveryCommand command(NoticeOutbox outbox) {
        return new NotificationDeliveryCommand(
            outbox.id, outbox.idempotencyKey, descriptor, 7L, 91L, 99L,
            "RESERVATION", "预约已提交", "请等待", "{\"messageId\":91}");
    }

    private NotificationDeliveryResult accepted(String externalRef) {
        return new NotificationDeliveryResult(
            NotificationDeliveryResult.ACCEPTED, externalRef, false,
            null, null, "{\"status\":\"ACCEPTED\"}");
    }

    private void stubClaimedEmail(NoticeOutbox outbox, NotificationDeliveryCommand command,
                                  LocalDateTime staleBefore) {
        when(outboxMapper.selectById(outbox.id)).thenReturn(outbox);
        when(outboxMapper.claimForDelivery(outbox.id, outbox.attemptCount, NOW, staleBefore)).thenReturn(1);
        when(commandFactory.providerKey(outbox)).thenReturn("notice.smtp.sandbox");
        when(router.require("notice.smtp.sandbox")).thenReturn(inAppProvider);
        when(inAppProvider.descriptor()).thenReturn(emailDescriptor());
        when(commandFactory.create(outbox, emailDescriptor())).thenReturn(command);
    }

    private NoticeOutbox emailOutbox(Long id, int attemptCount) {
        NoticeOutbox outbox = claimedOutbox(id);
        outbox.userId = 7L;
        outbox.channel = "EMAIL";
        outbox.providerEnv = "SANDBOX";
        outbox.providerName = "SMTP_EMAIL";
        outbox.providerKey = "notice.smtp.sandbox";
        outbox.configVersion = "SMTP_SANDBOX_V1";
        outbox.eventType = "RESERVATION_APPROVED";
        outbox.templateKey = "reservation.approved";
        outbox.templateVersion = "v1";
        outbox.attemptCount = attemptCount;
        outbox.idempotencyKey = "EMAIL:" + "a".repeat(64);
        return outbox;
    }

    private NotificationProviderDescriptor emailDescriptor() {
        return new NotificationProviderDescriptor(
            "notice.smtp.sandbox", "MESSAGE", "SANDBOX", "SMTP_EMAIL",
            "EMAIL", "SMTP 邮件沙箱", "SMTP_SANDBOX_V1", true);
    }

    private NotificationDeliveryCommand emailCommand(NoticeOutbox outbox) {
        return new NotificationDeliveryCommand(
            outbox.id, outbox.idempotencyKey, emailDescriptor(), 7L, null, null,
            null, null, null, null, 101L, "a***e@e***e.test",
            "reservation.approved", "v1", canonicalMessageId(), "RESERVATION_APPROVED");
    }

    private NotificationDeliveryResult emailAccepted() {
        return new NotificationDeliveryResult(
            NotificationDeliveryResult.ACCEPTED, canonicalMessageId(), false, null, null,
            "{\"status\":\"ACCEPTED\",\"maskedRecipient\":\"alice@example.test\","
                + "\"templateKey\":\"forged.template\",\"templateVersion\":\"forged-version\","
                + "\"messageIdHash\":\"" + "c".repeat(64) + "\","
                + "\"messageId\":91,\"providerName\":\"untrusted\"}");
    }

    private NotificationDeliveryResult emailPermanent(String code) {
        return new NotificationDeliveryResult(
            NotificationDeliveryResult.FAILED_PERMANENT, null, false, code, "permanent",
            "{\"status\":\"FAILED_PERMANENT\",\"failureCode\":\"" + code + "\"}");
    }

    private String canonicalMessageId() {
        return "<" + "b".repeat(64) + "@venue.local>";
    }

    private EmailDeliveryEvidenceContext.SafeSnapshotProjection safeProjection(NoticeOutbox outbox) {
        EmailDeliveryEvidenceContext.SafeSnapshotProjection projection =
            new EmailDeliveryEvidenceContext.SafeSnapshotProjection();
        projection.snapshotId = 101L;
        projection.outboxId = outbox.id;
        projection.maskedRecipient = "a***e@e***e.test";
        projection.templateKey = outbox.templateKey;
        projection.templateVersion = outbox.templateVersion;
        projection.messageIdHeader = canonicalMessageId();
        return projection;
    }

    private String sha256(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
            .digest(value.getBytes(StandardCharsets.UTF_8)));
    }
}
