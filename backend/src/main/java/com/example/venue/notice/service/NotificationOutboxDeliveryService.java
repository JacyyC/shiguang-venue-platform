package com.example.venue.notice.service;

import com.example.venue.notice.entity.NoticeMessage;
import com.example.venue.notice.entity.NoticeOutbox;
import com.example.venue.notice.mapper.NoticeEmailDeliveryMapper;
import com.example.venue.notice.mapper.NoticeOutboxMapper;
import com.example.venue.notice.provider.NotificationDeliveryCommand;
import com.example.venue.notice.provider.NotificationDeliveryResult;
import com.example.venue.notice.provider.NotificationProvider;
import com.example.venue.notice.provider.NotificationProviderDescriptor;
import com.example.venue.notice.provider.NotificationProviderRouter;
import com.example.venue.provider.dto.ProviderCallRecord;
import com.example.venue.provider.dto.ProviderRunContext;
import com.example.venue.provider.service.ProviderObservabilityService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;

@Service
public class NotificationOutboxDeliveryService {
    private static final Logger log = LoggerFactory.getLogger(NotificationOutboxDeliveryService.class);
    private static final long MAX_BACKOFF_SECONDS = 300;

    private final NoticeOutboxMapper outboxMapper;
    private final NoticeEmailDeliveryMapper emailDeliveryMapper;
    private final NotificationProviderRouter router;
    private final NotificationOutboxCommandFactory commandFactory;
    private final NotificationOutboxProperties properties;
    private final List<RealtimeMessagePublisher> realtimePublishers;
    private final NotificationOutboxAttemptStateService attemptStateService;
    private final NotificationDeliveryFaultHook faultHook;
    private final ObjectMapper objectMapper;
    private final ProviderObservabilityService providerObservabilityService;
    private final Clock clock;

    @Autowired
    public NotificationOutboxDeliveryService(NoticeOutboxMapper outboxMapper,
                                             NoticeEmailDeliveryMapper emailDeliveryMapper,
                                             NotificationProviderRouter router,
                                             NotificationOutboxCommandFactory commandFactory,
                                             NotificationOutboxProperties properties,
                                             List<RealtimeMessagePublisher> realtimePublishers,
                                             NotificationOutboxAttemptStateService attemptStateService,
                                             NotificationDeliveryFaultHook faultHook,
                                             ObjectMapper objectMapper,
                                             ProviderObservabilityService providerObservabilityService) {
        this(outboxMapper, emailDeliveryMapper, router, commandFactory, properties, realtimePublishers,
            attemptStateService, faultHook, objectMapper, providerObservabilityService,
            Clock.systemDefaultZone());
    }

    public NotificationOutboxDeliveryService(NoticeOutboxMapper outboxMapper,
                                             NotificationProviderRouter router,
                                             NotificationOutboxCommandFactory commandFactory,
                                             NotificationOutboxProperties properties,
                                             List<RealtimeMessagePublisher> realtimePublishers,
                                             NotificationOutboxAttemptStateService attemptStateService,
                                             Clock clock) {
        this(outboxMapper, null, router, commandFactory, properties, realtimePublishers,
            attemptStateService, NotificationDeliveryFaultHook.noop(), new ObjectMapper(), null, clock);
    }

    public NotificationOutboxDeliveryService(NoticeOutboxMapper outboxMapper,
                                             NotificationProviderRouter router,
                                             NotificationOutboxCommandFactory commandFactory,
                                             NotificationOutboxProperties properties,
                                             List<RealtimeMessagePublisher> realtimePublishers,
                                             NotificationOutboxAttemptStateService attemptStateService,
                                             NotificationDeliveryFaultHook faultHook,
                                             Clock clock) {
        this(outboxMapper, null, router, commandFactory, properties, realtimePublishers,
            attemptStateService, faultHook, new ObjectMapper(), null, clock);
    }

    public NotificationOutboxDeliveryService(NoticeOutboxMapper outboxMapper,
                                             NotificationProviderRouter router,
                                             NotificationOutboxCommandFactory commandFactory,
                                             NotificationOutboxProperties properties,
                                             List<RealtimeMessagePublisher> realtimePublishers,
                                             NotificationOutboxAttemptStateService attemptStateService,
                                             NotificationDeliveryFaultHook faultHook,
                                             ObjectMapper objectMapper) {
        this(outboxMapper, null, router, commandFactory, properties, realtimePublishers,
            attemptStateService, faultHook, objectMapper, null, Clock.systemDefaultZone());
    }

    public NotificationOutboxDeliveryService(NoticeOutboxMapper outboxMapper,
                                             NoticeEmailDeliveryMapper emailDeliveryMapper,
                                             NotificationProviderRouter router,
                                             NotificationOutboxCommandFactory commandFactory,
                                             NotificationOutboxProperties properties,
                                             List<RealtimeMessagePublisher> realtimePublishers,
                                             NotificationOutboxAttemptStateService attemptStateService,
                                             NotificationDeliveryFaultHook faultHook,
                                             ObjectMapper objectMapper,
                                             ProviderObservabilityService providerObservabilityService,
                                             Clock clock) {
        this.outboxMapper = outboxMapper;
        this.emailDeliveryMapper = emailDeliveryMapper;
        this.router = router;
        this.commandFactory = commandFactory;
        this.properties = properties;
        this.realtimePublishers = realtimePublishers == null ? List.of() : List.copyOf(realtimePublishers);
        this.attemptStateService = attemptStateService;
        this.faultHook = faultHook;
        this.objectMapper = objectMapper;
        this.providerObservabilityService = providerObservabilityService;
        this.clock = clock;
    }

    public void deliver(Long outboxId, LocalDateTime staleBefore) {
        long startedAt = System.nanoTime();
        LocalDateTime now = LocalDateTime.now(clock);
        NoticeOutbox outbox = outboxMapper.selectById(outboxId);
        if (outbox == null) {
            return;
        }
        int previousAttemptCount = outbox.attemptCount == null ? 0 : outbox.attemptCount;
        if (outboxMapper.claimForDelivery(outboxId, previousAttemptCount, now, staleBefore) != 1) {
            return;
        }
        outbox.attemptCount = previousAttemptCount + 1;
        outbox.status = "SENDING";
        outbox.updateTime = now;

        NotificationProvider provider;
        NotificationDeliveryCommand command = null;
        EmailDeliveryEvidenceContext emailEvidence = EmailDeliveryEvidenceContext.from(outbox);
        if (emailEvidence != null && emailDeliveryMapper != null) {
            try {
                emailEvidence = emailEvidence.withSafeSnapshot(
                    emailDeliveryMapper.findEvidenceByOutboxId(outbox.id));
            } catch (RuntimeException ignored) {
                log.error("NOTICE_EMAIL_EVIDENCE_PROJECTION_GAP");
            }
        }
        try {
            String providerKey = commandFactory.providerKey(outbox);
            provider = router.require(providerKey);
            NotificationProviderDescriptor descriptor = provider.descriptor();
            command = commandFactory.create(outbox, descriptor);
            if (emailEvidence != null) {
                emailEvidence = emailEvidence.withCommand(command);
            }
        } catch (DataAccessException ex) {
            // A26-P2-17：命令构建阶段的瞬时 DB 异常（连接池耗尽、主从切换等）走重试退避，不直接终态化。
            // commandFactory.create 内部含两次真实 DB 读，DataAccessException 是瞬时故障而非永久失败；
            // 同样抖动若发生在 provider.send 内则正常退避重试，命令构建阶段应与之对齐。
            NotificationDeliveryResult failure = new NotificationDeliveryResult(
                NotificationDeliveryResult.FAILED_RETRYABLE, null, true,
                "NOTICE_OUTBOX_COMMAND_BUILD_TRANSIENT", ex.getMessage(),
                failedSummary(NotificationDeliveryResult.FAILED_RETRYABLE));
            handleRetryable(outbox, command, emailEvidence, failure,
                failure.failureCode(), failure.failureMessage(),
                failure.responseSummaryJson(), now, elapsedMillis(startedAt));
            return;
        } catch (RuntimeException ex) {
            markPermanent(outbox, command, emailEvidence, null,
                failureCode(ex, "NOTICE_OUTBOX_ROUTING_FAILED"),
                ex.getMessage(), now, elapsedMillis(startedAt));
            return;
        }

        NotificationDeliveryResult result;
        try {
            result = provider.send(command);
        } catch (RuntimeException ex) {
            NotificationDeliveryResult failure = new NotificationDeliveryResult(
                NotificationDeliveryResult.FAILED_RETRYABLE, null, true,
                "NOTICE_PROVIDER_DELIVERY_EXCEPTION", ex.getMessage(),
                failedSummary(NotificationDeliveryResult.FAILED_RETRYABLE));
            handleRetryable(outbox, command, emailEvidence, failure,
                failure.failureCode(), failure.failureMessage(),
                failure.responseSummaryJson(), now, elapsedMillis(startedAt));
            return;
        }

        if (result == null) {
            markPermanent(outbox, command, emailEvidence, null, "NOTICE_PROVIDER_RESULT_INVALID",
                "通知 Provider 返回空结果", now, elapsedMillis(startedAt));
            return;
        }
        String responseSummaryJson;
        try {
            responseSummaryJson = sanitizeSummary(result, command);
        } catch (RuntimeException ex) {
            markPermanent(outbox, command, emailEvidence, result, "NOTICE_PROVIDER_RESULT_INVALID",
                "通知 Provider 返回无效摘要", now, elapsedMillis(startedAt));
            return;
        }
        faultHook.afterProviderResult(outboxId, result);
        if (NotificationDeliveryResult.ACCEPTED.equals(result.status())) {
            long durationMs = elapsedMillis(startedAt);
            if (!attemptStateService.markSent(outbox, command, emailEvidence, result, responseSummaryJson,
                now, durationMs)) {
                return;
            }
            recordSmtpTerminalOutcome(outbox, emailEvidence, "SUCCESS", null, null, durationMs);
            if ("IN_APP".equals(command.provider().channel())) {
                publishRealtime(command, result);
            }
            return;
        }
        if (NotificationDeliveryResult.FAILED_RETRYABLE.equals(result.status()) || result.retryable()) {
            handleRetryable(outbox, command, emailEvidence, result,
                defaultCode(result.failureCode(), "NOTICE_PROVIDER_RETRYABLE_FAILURE"),
                result.failureMessage(), responseSummaryJson, now, elapsedMillis(startedAt));
            return;
        }
        if (NotificationDeliveryResult.FAILED_PERMANENT.equals(result.status())) {
            markPermanent(outbox, command, emailEvidence, result,
                defaultCode(result.failureCode(), "NOTICE_PROVIDER_PERMANENT_FAILURE"),
                result.failureMessage(), responseSummaryJson, now, elapsedMillis(startedAt));
            return;
        }
        markPermanent(outbox, command, emailEvidence, result, "NOTICE_PROVIDER_RESULT_INVALID",
            "通知 Provider 返回未知状态", now, elapsedMillis(startedAt));
    }

    private void handleRetryable(NoticeOutbox outbox, NotificationDeliveryCommand command,
                                 EmailDeliveryEvidenceContext emailEvidence,
                                 NotificationDeliveryResult result, String failureCode,
                                 String failureMessage, String responseSummaryJson,
                                 LocalDateTime now, long durationMs) {
        int attemptCount = outbox.attemptCount == null ? 1 : Math.max(1, outbox.attemptCount);
        if (attemptCount >= properties.getMaxAttempts()) {
            String sanitizedMessage = sanitizeFailureMessage(failureMessage);
            if (attemptStateService.markFailed(outbox, command, emailEvidence, result,
                "MAX_ATTEMPTS_EXCEEDED",
                sanitizedMessage, responseSummaryJson, now, durationMs)) {
                recordSmtpTerminalOutcome(outbox, emailEvidence, terminalFailureStatus(result),
                    "MAX_ATTEMPTS_EXCEEDED", sanitizedMessage, durationMs);
            }
            return;
        }
        LocalDateTime nextRetryTime = now.plusSeconds(backoffSeconds(attemptCount));
        attemptStateService.markRetryWaiting(outbox, command, result, failureCode,
            sanitizeFailureMessage(failureMessage), responseSummaryJson, nextRetryTime, now, durationMs);
    }

    private long backoffSeconds(int attemptCount) {
        long delay = Math.min(Math.max(0, properties.getRetryBaseSeconds()), MAX_BACKOFF_SECONDS);
        for (int attempt = 1; attempt < attemptCount && delay < MAX_BACKOFF_SECONDS; attempt++) {
            delay = Math.min(delay * 2, MAX_BACKOFF_SECONDS);
        }
        return delay;
    }

    private void markPermanent(NoticeOutbox outbox, NotificationDeliveryCommand command,
                               EmailDeliveryEvidenceContext emailEvidence,
                               NotificationDeliveryResult result, String failureCode,
                               String failureMessage, LocalDateTime now, long durationMs) {
        markPermanent(outbox, command, emailEvidence, result, failureCode, failureMessage,
            failedSummary(NotificationDeliveryResult.FAILED_PERMANENT), now, durationMs);
    }

    private void markPermanent(NoticeOutbox outbox, NotificationDeliveryCommand command,
                               EmailDeliveryEvidenceContext emailEvidence,
                               NotificationDeliveryResult result, String failureCode,
                               String failureMessage, String responseSummaryJson,
                               LocalDateTime now, long durationMs) {
        String safeCode = defaultCode(failureCode, "NOTICE_DELIVERY_FAILED");
        String safeMessage = sanitizeFailureMessage(failureMessage);
        if (attemptStateService.markFailed(outbox, command, emailEvidence, result, safeCode,
            safeMessage, responseSummaryJson, now, durationMs)) {
            recordSmtpTerminalOutcome(outbox, emailEvidence, terminalFailureStatus(result), safeCode,
                safeMessage, durationMs);
        }
    }

    private long elapsedMillis(long startedAt) {
        return Math.max(0, (System.nanoTime() - startedAt) / 1_000_000);
    }

    private void publishRealtime(NotificationDeliveryCommand command, NotificationDeliveryResult result) {
        NoticeMessage message = new NoticeMessage();
        message.id = command.messageId() == null ? parseId(result.providerRequestId()) : command.messageId();
        message.userId = command.userId();
        message.title = command.title();
        message.content = command.content();
        message.type = command.businessType();
        message.businessId = command.businessId();
        message.providerEnv = command.provider().providerEnv();
        message.providerName = command.provider().providerName();
        message.deliveryStatus = "SENT";
        for (RealtimeMessagePublisher publisher : realtimePublishers) {
            try {
                publisher.publish(message);
            } catch (RuntimeException ex) {
                log.warn("Realtime publisher {} failed for message {}: {}",
                    publisher.channel(), message.id, ex.getMessage());
            }
        }
    }

    private Long parseId(String value) {
        try {
            return value == null ? null : Long.valueOf(value);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private String failureCode(RuntimeException ex, String fallback) {
        String message = ex.getMessage();
        if (message != null) {
            int separator = message.indexOf(':');
            String candidate = (separator < 0 ? message : message.substring(0, separator)).trim();
            if (candidate.matches("[A-Z][A-Z0-9_]*")) {
                return candidate;
            }
        }
        return fallback;
    }

    private String defaultCode(String value, String fallback) {
        String code = value == null || value.isBlank() ? fallback : value;
        return code.length() <= 64 ? code : code.substring(0, 64);
    }

    private String sanitizeSummary(NotificationDeliveryResult result,
                                   NotificationDeliveryCommand command) {
        ObjectNode sanitized = objectMapper.createObjectNode();
        sanitized.put("status", safeStatus(result.status()));
        if (isEmailCommand(command)) {
            putText(sanitized, "failureCode", safeFailureCode(result.failureCode()));
            putText(sanitized, "maskedRecipient", command.maskedRecipient());
            putText(sanitized, "templateKey", command.templateKey());
            putText(sanitized, "templateVersion", command.templateVersion());
            putText(sanitized, "messageIdHash", messageIdHash(command.messageIdHeader()));
            return sanitized.toString();
        }
        if (result.responseSummaryJson() == null || result.responseSummaryJson().isBlank()) {
            return sanitized.toString();
        }
        try {
            JsonNode source = objectMapper.readTree(result.responseSummaryJson());
            if (source == null || !source.isObject()) {
                throw new IllegalArgumentException("Provider summary must be a JSON object");
            }
            copyText(source, sanitized, "failureCode", 64);
            copyLong(source, sanitized, "messageId");
            copyText(source, sanitized, "providerName", 64);
            copyText(source, sanitized, "maskedRecipient", 255);
            copyText(source, sanitized, "templateKey", 128);
            copyText(source, sanitized, "templateVersion", 64);
            copyText(source, sanitized, "messageIdHash", 64);
            return sanitized.toString();
        } catch (Exception ex) {
            throw new IllegalArgumentException("Provider summary is invalid", ex);
        }
    }

    private void copyLong(JsonNode source, ObjectNode target, String field) {
        JsonNode value = source.get(field);
        if (value != null && value.canConvertToLong()) {
            target.put(field, value.asLong());
        }
    }

    private void copyText(JsonNode source, ObjectNode target, String field, int maxLength) {
        JsonNode value = source.get(field);
        if (value == null || !value.isTextual()) {
            return;
        }
        String text = value.asText();
        target.put(field, text.length() <= maxLength ? text : text.substring(0, maxLength));
    }

    private String failedSummary(String status) {
        return "{\"status\":\"" + status + "\"}";
    }

    private String safeStatus(String status) {
        if (NotificationDeliveryResult.ACCEPTED.equals(status)
            || NotificationDeliveryResult.FAILED_RETRYABLE.equals(status)
            || NotificationDeliveryResult.FAILED_PERMANENT.equals(status)) {
            return status;
        }
        return "INVALID";
    }

    private String safeFailureCode(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String code = value.trim();
        return code.matches("[A-Z][A-Z0-9_]{0,63}") ? code : "NOTICE_PROVIDER_FAILURE";
    }

    private boolean isEmailCommand(NotificationDeliveryCommand command) {
        return command != null && command.provider() != null
            && "EMAIL".equals(command.provider().channel());
    }

    private String sanitizeFailureMessage(String value) {
        if (value == null) {
            return null;
        }
        String sanitized = value
            .replaceAll("(?i)(token|secret|password|authorization)\\s*[:=]\\s*[^\\s,;]+", "$1=[REDACTED]")
            .replaceAll("(?i)[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}", "[REDACTED_EMAIL]")
            .replaceAll("(?<!\\d)1[3-9]\\d{9}(?!\\d)", "[REDACTED_PHONE]");
        return limit(sanitized);
    }

    private void recordSmtpTerminalOutcome(NoticeOutbox outbox,
                                           EmailDeliveryEvidenceContext emailEvidence,
                                           String status,
                                           String failureCode,
                                           String failureMessage,
                                           long durationMs) {
        if (providerObservabilityService == null || outbox == null || emailEvidence == null
            || !"EMAIL".equals(outbox.channel)
            || !"notice.smtp.sandbox".equals(outbox.providerKey)) {
            return;
        }
        try {
            ProviderCallRecord record = ProviderCallRecord.of(
                ProviderRunContext.system(),
                "notice.smtp.sandbox",
                "MESSAGE",
                "SANDBOX",
                "SMTP_EMAIL",
                outbox.idempotencyKey,
                "SEND_EMAIL",
                status,
                "SMTP_SANDBOX_V1",
                smtpRequestSummary(emailEvidence),
                smtpResponseSummary(status, failureCode, emailEvidence.messageIdHash()),
                failureCode,
                failureMessage,
                durationMs,
                emailEvidence.messageIdHash(),
                "NOTICE_EMAIL_DELIVERY",
                emailEvidence.snapshotId(),
                null,
                true);
            providerObservabilityService.recordCall(record);
        } catch (RuntimeException exception) {
            log.error("NOTICE_SMTP_PROVIDER_EVIDENCE_GAP");
        }
    }

    private String smtpRequestSummary(EmailDeliveryEvidenceContext evidence) {
        ObjectNode summary = objectMapper.createObjectNode();
        putLong(summary, "userId", evidence.userId());
        putText(summary, "eventType", evidence.eventType());
        putText(summary, "templateKey", evidence.templateKey());
        putText(summary, "templateVersion", evidence.templateVersion());
        putText(summary, "maskedRecipient", evidence.maskedRecipient());
        putText(summary, "messageIdHash", evidence.messageIdHash());
        return summary.toString();
    }

    private String smtpResponseSummary(String status, String failureCode, String messageIdHash) {
        ObjectNode summary = objectMapper.createObjectNode();
        putText(summary, "status", status);
        putText(summary, "failureCode", failureCode);
        putText(summary, "messageIdHash", messageIdHash);
        return summary.toString();
    }

    private String terminalFailureStatus(NotificationDeliveryResult result) {
        return result != null && containsTimeout(result.failureCode()) ? "TIMEOUT" : "FAILED";
    }

    private boolean containsTimeout(String failureCode) {
        return failureCode != null && failureCode.toUpperCase(java.util.Locale.ROOT).contains("TIMEOUT");
    }

    private void putLong(ObjectNode target, String field, Long value) {
        if (value != null) {
            target.put(field, value);
        }
    }

    private void putText(ObjectNode target, String field, String value) {
        if (value != null && !value.isBlank()) {
            target.put(field, value);
        }
    }

    private String messageIdHash(String messageIdHeader) {
        if (messageIdHeader == null) {
            return null;
        }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(messageIdHeader.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private String limit(String value) {
        if (value == null || value.length() <= 255) {
            return value;
        }
        return value.substring(0, 255);
    }
}
