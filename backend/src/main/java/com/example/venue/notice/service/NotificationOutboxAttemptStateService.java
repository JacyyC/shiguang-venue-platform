package com.example.venue.notice.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.example.venue.notice.entity.NoticeAudienceSnapshot;
import com.example.venue.notice.entity.NoticeDeliveryBatch;
import com.example.venue.notice.entity.NoticeDeliveryLog;
import com.example.venue.notice.entity.NoticeOutbox;
import com.example.venue.notice.mapper.NoticeAudienceSnapshotMapper;
import com.example.venue.notice.mapper.NoticeDeliveryBatchMapper;
import com.example.venue.notice.mapper.NoticeDeliveryLogMapper;
import com.example.venue.notice.mapper.NoticeEmailDeliveryMapper;
import com.example.venue.notice.mapper.NoticeOutboxMapper;
import com.example.venue.notice.provider.NotificationDeliveryCommand;
import com.example.venue.notice.provider.NotificationDeliveryResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class NotificationOutboxAttemptStateService {
    private final NoticeOutboxMapper outboxMapper;
    private final NoticeDeliveryLogMapper deliveryLogMapper;
    private final NoticeEmailDeliveryMapper emailDeliveryMapper;
    private final NoticeAudienceSnapshotMapper audienceMapper;
    private final NoticeDeliveryBatchMapper batchMapper;
    private final ObjectMapper objectMapper;

    public NotificationOutboxAttemptStateService(NoticeOutboxMapper outboxMapper,
                                                 NoticeDeliveryLogMapper deliveryLogMapper) {
        this(outboxMapper, deliveryLogMapper, null, null, null, new ObjectMapper());
    }

    public NotificationOutboxAttemptStateService(NoticeOutboxMapper outboxMapper,
                                                 NoticeDeliveryLogMapper deliveryLogMapper,
                                                 NoticeEmailDeliveryMapper emailDeliveryMapper) {
        this(outboxMapper, deliveryLogMapper, emailDeliveryMapper, null, null, new ObjectMapper());
    }

    public NotificationOutboxAttemptStateService(NoticeOutboxMapper outboxMapper,
                                                 NoticeDeliveryLogMapper deliveryLogMapper,
                                                 NoticeAudienceSnapshotMapper audienceMapper,
                                                 NoticeDeliveryBatchMapper batchMapper,
                                                 ObjectMapper objectMapper) {
        this(outboxMapper, deliveryLogMapper, null, audienceMapper, batchMapper, objectMapper);
    }

    @Autowired
    public NotificationOutboxAttemptStateService(NoticeOutboxMapper outboxMapper,
                                                 NoticeDeliveryLogMapper deliveryLogMapper,
                                                 NoticeEmailDeliveryMapper emailDeliveryMapper,
                                                 NoticeAudienceSnapshotMapper audienceMapper,
                                                 NoticeDeliveryBatchMapper batchMapper,
                                                 ObjectMapper objectMapper) {
        this.outboxMapper = outboxMapper;
        this.deliveryLogMapper = deliveryLogMapper;
        this.emailDeliveryMapper = emailDeliveryMapper;
        this.audienceMapper = audienceMapper;
        this.batchMapper = batchMapper;
        this.objectMapper = objectMapper;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean markSent(NoticeOutbox outbox, NotificationDeliveryCommand command,
                            NotificationDeliveryResult result, String responseSummaryJson,
                            LocalDateTime now, long durationMs) {
        EmailDeliveryEvidenceContext evidence = EmailDeliveryEvidenceContext.from(outbox);
        return markSent(outbox, command, evidence == null ? null : evidence.withCommand(command),
            result, responseSummaryJson, now, durationMs);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean markSent(NoticeOutbox outbox, NotificationDeliveryCommand command,
                            EmailDeliveryEvidenceContext emailEvidence,
                            NotificationDeliveryResult result, String responseSummaryJson,
                            LocalDateTime now, long durationMs) {
        int updated = outboxMapper.markSent(outbox.id, attemptCount(outbox),
            limit(result.providerRequestId(), 128), responseSummaryJson, now);
        if (updated != 1) {
            recordLostClaim(outbox, command, result, responseSummaryJson, now, durationMs);
            return false;
        }
        updateEmailSnapshot(outbox, true, now);
        NoticeDeliveryLog log = log(outbox, command, emailEvidence, result, "SUCCESS", null, null,
            responseSummaryJson, now, durationMs);
        deliveryLogMapper.insert(log);
        updateCampaign(outbox, "SENT", messageId(command, result), log.id, now);
        return true;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean markRetryWaiting(NoticeOutbox outbox, NotificationDeliveryCommand command,
                                    NotificationDeliveryResult result, String failureCode,
                                    String failureMessage, String responseSummaryJson,
                                    LocalDateTime nextRetryTime, LocalDateTime now, long durationMs) {
        int updated = outboxMapper.markRetryWaiting(outbox.id, attemptCount(outbox), failureCode,
            failureMessage, responseSummaryJson, nextRetryTime, now);
        if (updated != 1) {
            recordLostClaim(outbox, command, result, responseSummaryJson, now, durationMs);
            return false;
        }
        NoticeDeliveryLog log = log(outbox, command, null, result, "FAIL", failureCode, failureMessage,
            responseSummaryJson, now, durationMs);
        deliveryLogMapper.insert(log);
        updateCampaign(outbox, "RETRY_WAITING", null, log.id, now);
        return true;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean markFailed(NoticeOutbox outbox, NotificationDeliveryCommand command,
                              NotificationDeliveryResult result, String failureCode,
                              String failureMessage, String responseSummaryJson,
                              LocalDateTime now, long durationMs) {
        EmailDeliveryEvidenceContext evidence = EmailDeliveryEvidenceContext.from(outbox);
        return markFailed(outbox, command, evidence == null ? null : evidence.withCommand(command),
            result, failureCode, failureMessage, responseSummaryJson, now, durationMs);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean markFailed(NoticeOutbox outbox, NotificationDeliveryCommand command,
                              EmailDeliveryEvidenceContext emailEvidence,
                              NotificationDeliveryResult result, String failureCode,
                              String failureMessage, String responseSummaryJson,
                              LocalDateTime now, long durationMs) {
        int updated = outboxMapper.markFailed(outbox.id, attemptCount(outbox), failureCode,
            failureMessage, responseSummaryJson, now);
        if (updated != 1) {
            recordLostClaim(outbox, command, result, responseSummaryJson, now, durationMs);
            return false;
        }
        updateEmailSnapshot(outbox, false, now);
        NoticeDeliveryLog log = log(outbox, command, emailEvidence, result, "FAIL", failureCode, failureMessage,
            responseSummaryJson, now, durationMs);
        deliveryLogMapper.insert(log);
        updateCampaign(outbox, "FAILED", null, log.id, now);
        return true;
    }

    private void updateCampaign(NoticeOutbox outbox, String deliveryStatus, Long messageId,
                                Long deliveryLogId, LocalDateTime now) {
        if (isEmail(outbox)) {
            return;
        }
        if (outbox.audienceId == null || outbox.batchId == null
            || audienceMapper == null || batchMapper == null) {
            return;
        }
        NoticeDeliveryBatch batch = batchMapper.selectOne(new QueryWrapper<NoticeDeliveryBatch>()
            .eq("id", outbox.batchId)
            .eq("deleted", 0)
            .last("for update"));
        if (batch == null) {
            return;
        }
        NoticeAudienceSnapshot audience = audienceMapper.selectOne(new QueryWrapper<NoticeAudienceSnapshot>()
            .eq("id", outbox.audienceId)
            .eq("deleted", 0)
            .last("for update"));
        if (!belongsToOutbox(audience, outbox)) {
            return;
        }
        audience.deliveryStatus = deliveryStatus;
        if ("SENT".equals(deliveryStatus)) {
            if (!"READ".equals(audience.readStatus)) {
                audience.readStatus = "UNREAD";
            }
            audience.messageId = messageId;
        }
        audience.lastDeliveryLogId = deliveryLogId;
        audience.updateTime = now;
        audienceMapper.updateById(audience);
        recalculateBatch(batch, now);
    }

    private void recalculateBatch(NoticeDeliveryBatch batch, LocalDateTime now) {
        List<NoticeAudienceSnapshot> audiences = safeList(audienceMapper.selectList(
            new QueryWrapper<NoticeAudienceSnapshot>()
                .eq("batch_id", batch.id)
                .eq("deleted", 0)));
        List<NoticeAudienceSnapshot> deliverable = audiences.stream()
            .filter(this::isDeliverable)
            .toList();
        long sent = deliverable.stream().filter(item -> "SENT".equals(item.deliveryStatus)).count();
        long failed = deliverable.stream().filter(item -> "FAILED".equals(item.deliveryStatus)).count();
        boolean inFlight = deliverable.stream()
            .anyMatch(item -> "PENDING".equals(item.deliveryStatus)
                || "SENDING".equals(item.deliveryStatus)
                || "RETRY_WAITING".equals(item.deliveryStatus));
        batch.audienceCount = audiences.size();
        batch.deliverableCount = deliverable.size();
        batch.excludedCount = batch.audienceCount - batch.deliverableCount;
        if (inFlight || sent + failed < deliverable.size()) {
            batch.status = "PROCESSING";
        } else if (deliverable.isEmpty() || sent == deliverable.size()) {
            batch.status = "COMPLETED";
        } else if (failed == deliverable.size()) {
            batch.status = "FAILED";
        } else {
            batch.status = "PARTIAL_FAILED";
        }
        batch.updateTime = now;
        batchMapper.updateById(batch);
    }

    private boolean belongsToOutbox(NoticeAudienceSnapshot audience, NoticeOutbox outbox) {
        return audience != null
            && !Integer.valueOf(1).equals(audience.deleted)
            && outbox.audienceId.equals(audience.id)
            && outbox.batchId.equals(audience.batchId)
            && (outbox.noticeId == null || outbox.noticeId.equals(audience.noticeId))
            && (outbox.userId == null || outbox.userId.equals(audience.userId));
    }

    private boolean isDeliverable(NoticeAudienceSnapshot audience) {
        return audience != null
            && !"EXCLUDED".equals(audience.deliveryStatus)
            && audience.excludeReason == null
            && audience.userId != null;
    }

    private Long messageId(NotificationDeliveryCommand command, NotificationDeliveryResult result) {
        if (command != null && command.messageId() != null) {
            return command.messageId();
        }
        return parseId(result == null ? null : result.providerRequestId());
    }

    private <T> List<T> safeList(List<T> values) {
        return values == null ? List.of() : values;
    }

    private void recordLostClaim(NoticeOutbox outbox, NotificationDeliveryCommand command,
                                 NotificationDeliveryResult result, String responseSummaryJson,
                                 LocalDateTime now, long durationMs) {
        if (isEmail(outbox)) {
            return;
        }
        deliveryLogMapper.insert(log(outbox, command, null, result, "LOST_CLAIM",
            "STALE_GENERATION", "Outbox claim generation changed before terminal update",
            responseSummaryJson, now, durationMs));
    }

    private NoticeDeliveryLog log(NoticeOutbox outbox, NotificationDeliveryCommand command,
                                  EmailDeliveryEvidenceContext emailEvidence,
                                  NotificationDeliveryResult result, String status,
                                  String failureCode, String failureMessage,
                                  String responseSummaryJson, LocalDateTime now, long durationMs) {
        NoticeDeliveryLog log = new NoticeDeliveryLog();
        log.messageId = command == null ? parseId(result == null ? null : result.providerRequestId())
            : command.messageId();
        if (log.messageId == null && result != null) {
            log.messageId = parseId(result.providerRequestId());
        }
        log.channel = outbox.channel;
        EmailDeliveryEvidenceContext resolvedEvidence = resolveEvidence(outbox, command, emailEvidence);
        log.target = isEmail(outbox)
            ? (resolvedEvidence == null || resolvedEvidence.maskedRecipient() == null
                ? "UNAVAILABLE" : resolvedEvidence.maskedRecipient())
            : (outbox.userId == null ? null : String.valueOf(outbox.userId));
        log.status = status;
        log.errorMessage = failureMessage;
        log.sourceType = "OUTBOX_WORKER";
        log.providerEnv = outbox.providerEnv;
        log.providerName = outbox.providerName;
        log.externalRef = isEmail(outbox)
            ? (resolvedEvidence == null ? null : resolvedEvidence.messageIdHash())
            : (result == null ? null : limit(result.providerRequestId(), 128));
        log.idempotencyKey = outbox.idempotencyKey;
        log.requestSummaryJson = sanitizeRequestSummary(outbox, command, resolvedEvidence);
        log.responseSummaryJson = responseSummaryJson;
        log.failureCode = failureCode;
        log.configVersion = outbox.configVersion;
        log.durationMs = durationMs;
        log.metadataJson = sanitizeMetadata(outbox.metadataJson);
        log.createTime = now;
        return log;
    }

    private int attemptCount(NoticeOutbox outbox) {
        return outbox.attemptCount == null ? 0 : outbox.attemptCount;
    }

    private String sanitizeRequestSummary(NoticeOutbox outbox, NotificationDeliveryCommand command,
                                          EmailDeliveryEvidenceContext emailEvidence) {
        if (isEmail(outbox)) {
            ObjectNode sanitized = objectMapper.createObjectNode();
            if (emailEvidence != null) {
                putLong(sanitized, "userId", emailEvidence.userId());
                putText(sanitized, "eventType", emailEvidence.eventType());
                putText(sanitized, "templateKey", emailEvidence.templateKey());
                putText(sanitized, "templateVersion", emailEvidence.templateVersion());
                putText(sanitized, "maskedRecipient", emailEvidence.maskedRecipient());
                putText(sanitized, "messageIdHash", emailEvidence.messageIdHash());
            }
            return sanitized.toString();
        }
        ObjectNode sanitized = objectMapper.createObjectNode();
        JsonNode source = parseObject(outbox.requestSummaryJson);
        copyLong(source, sanitized, "messageId");
        copyLong(source, sanitized, "userId");
        copyLong(source, sanitized, "businessId");
        copyLong(source, sanitized, "noticeId");
        copyLong(source, sanitized, "batchId");
        copyLong(source, sanitized, "audienceId");
        copyLong(source, sanitized, "experimentId");
        copyLong(source, sanitized, "experimentVariantId");
        copyText(source, sanitized, "channel", 32);
        copyText(source, sanitized, "type", 32);
        copyText(source, sanitized, "source", 32);
        return sanitized.toString();
    }

    private EmailDeliveryEvidenceContext resolveEvidence(
        NoticeOutbox outbox,
        NotificationDeliveryCommand command,
        EmailDeliveryEvidenceContext emailEvidence
    ) {
        if (emailEvidence != null) {
            return emailEvidence;
        }
        EmailDeliveryEvidenceContext evidence = EmailDeliveryEvidenceContext.from(outbox);
        return evidence == null ? null : evidence.withCommand(command);
    }

    private void updateEmailSnapshot(NoticeOutbox outbox, boolean accepted, LocalDateTime now) {
        if (!isEmail(outbox) || emailDeliveryMapper == null) {
            return;
        }
        int updated = accepted
            ? emailDeliveryMapper.markAccepted(outbox.id, now)
            : emailDeliveryMapper.markFailed(outbox.id, now);
        if (updated != 1) {
            throw new IllegalStateException(
                "EMAIL snapshot terminal update must affect exactly one row");
        }
    }

    private boolean isEmail(NoticeOutbox outbox) {
        return outbox != null && "EMAIL".equals(outbox.channel);
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

    private String sanitizeMetadata(String json) {
        ObjectNode sanitized = objectMapper.createObjectNode();
        JsonNode source = parseObject(json);
        copyText(source, sanitized, "providerKey", 128);
        copyText(source, sanitized, "source", 32);
        return sanitized.toString();
    }

    private JsonNode parseObject(String json) {
        try {
            JsonNode node = objectMapper.readTree(json == null || json.isBlank() ? "{}" : json);
            return node != null && node.isObject() ? node : objectMapper.createObjectNode();
        } catch (Exception ignored) {
            return objectMapper.createObjectNode();
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

    private Long parseId(String value) {
        try {
            return value == null ? null : Long.valueOf(value);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private String limit(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}
