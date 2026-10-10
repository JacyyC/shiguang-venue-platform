package com.example.venue.notice.service;

import com.example.venue.common.BusinessException;
import com.example.venue.notice.entity.NoticeMessage;
import com.example.venue.notice.entity.NoticeOutbox;
import com.example.venue.notice.email.EmailSnapshotService;
import com.example.venue.notice.mapper.NoticeMessageMapper;
import com.example.venue.notice.provider.NotificationDeliveryCommand;
import com.example.venue.notice.provider.NotificationProviderDescriptor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class NotificationOutboxCommandFactory {
    private final NoticeMessageMapper messageMapper;
    private final EmailSnapshotService emailSnapshotService;
    private final ObjectMapper objectMapper;

    public NotificationOutboxCommandFactory(NoticeMessageMapper messageMapper, ObjectMapper objectMapper) {
        this(messageMapper, null, objectMapper);
    }

    @Autowired
    public NotificationOutboxCommandFactory(
        NoticeMessageMapper messageMapper,
        EmailSnapshotService emailSnapshotService,
        ObjectMapper objectMapper
    ) {
        this.messageMapper = messageMapper;
        this.emailSnapshotService = emailSnapshotService;
        this.objectMapper = objectMapper;
    }

    public String providerKey(NoticeOutbox outbox) {
        if (outbox == null) {
            throw payloadInvalid();
        }
        String providerKey = firstNonBlank(outbox.providerKey, text(parse(outbox.metadataJson), "providerKey"));
        if (providerKey == null) {
            providerKey = text(parse(outbox.requestSummaryJson), "providerKey");
        }
        if (providerKey == null && "DEV".equalsIgnoreCase(outbox.providerEnv)
            && "IN_APP_MESSAGE".equalsIgnoreCase(outbox.providerName)) {
            providerKey = "notice.in_app.dev";
        }
        if (providerKey == null) {
            throw payloadInvalid();
        }
        return providerKey;
    }

    public NotificationDeliveryCommand create(NoticeOutbox outbox, NotificationProviderDescriptor descriptor) {
        if (outbox == null || outbox.id == null || descriptor == null) {
            throw payloadInvalid();
        }
        if ("EMAIL".equals(descriptor.channel())) {
            return createEmail(outbox, descriptor);
        }
        JsonNode request = parse(outbox.requestSummaryJson);
        if (!request.isObject()) {
            throw payloadInvalid();
        }
        Long messageId = longValue(request, "messageId");
        NoticeMessage message = messageId == null ? null : messageMapper.selectById(messageId);
        Long requestUserId = longValue(request, "userId");
        if (messageId != null && (message == null
            || !sameWhenPresent(outbox.userId, message.userId)
            || !sameWhenPresent(requestUserId, message.userId))) {
            throw payloadInvalid();
        }
        Long userId = message == null ? firstNonNull(outbox.userId, requestUserId) : message.userId;
        Long noticeId = firstNonNull(longValue(request, "noticeId"), outbox.noticeId);
        Long businessId = firstNonNull(message == null ? null : message.businessId,
            firstNonNull(longValue(request, "businessId"), noticeId));
        String businessType = firstNonBlank(message == null ? null : message.type, text(request, "type"));
        if (businessType == null && noticeId != null) {
            businessType = "NOTICE";
        }
        String title = firstNonBlank(message == null ? null : message.title, text(request, "title"));
        String content = firstNonBlank(message == null ? null : message.content, text(request, "content"));
        if (userId == null || title == null || content == null
            || outbox.idempotencyKey == null || outbox.idempotencyKey.isBlank()) {
            throw payloadInvalid();
        }
        String commandSummary = normalizedSummary(request, outbox);
        return new NotificationDeliveryCommand(
            outbox.id, outbox.idempotencyKey, descriptor, userId, messageId, businessId,
            businessType, title, content, commandSummary);
    }

    private NotificationDeliveryCommand createEmail(
        NoticeOutbox outbox, NotificationProviderDescriptor descriptor
    ) {
        if (emailSnapshotService == null || outbox.userId == null
            || !"EMAIL".equals(outbox.channel)
            || !same(outbox.providerKey, descriptor.providerKey())
            || !same(outbox.providerEnv, descriptor.providerEnv())
            || !same(outbox.providerName, descriptor.providerName())
            || !same(outbox.configVersion, descriptor.configVersion())
            || isBlank(outbox.eventType) || isBlank(outbox.eventKey)
            || isBlank(outbox.recipientKind) || isBlank(outbox.templateKey)
            || isBlank(outbox.templateVersion) || outbox.idempotencyKey == null
            || !outbox.idempotencyKey.matches("EMAIL:[0-9a-f]{64}")) {
            throw emailSnapshotInvalid();
        }
        EmailSnapshotService.CommandSnapshot snapshot;
        try {
            snapshot = emailSnapshotService.loadForCommand(outbox.id);
        } catch (BusinessException exception) {
            throw emailSnapshotInvalid();
        }
        if (!same(outbox.id, snapshot.outboxId())
            || !same(outbox.userId, snapshot.recipientUserId())
            || !same(outbox.templateKey, snapshot.templateKey())
            || !same(outbox.templateVersion, snapshot.templateVersion())
            || isBlank(snapshot.maskedRecipient()) || isBlank(snapshot.messageIdHeader())) {
            throw emailSnapshotInvalid();
        }
        return new NotificationDeliveryCommand(
            outbox.id, outbox.idempotencyKey, descriptor, outbox.userId, null, null,
            null, null, null, null, snapshot.snapshotId(), snapshot.maskedRecipient(),
            snapshot.templateKey(), snapshot.templateVersion(), snapshot.messageIdHeader(),
            outbox.eventType);
    }

    private JsonNode parse(String json) {
        try {
            return objectMapper.readTree(json == null || json.isBlank() ? "{}" : json);
        } catch (Exception ex) {
            throw payloadInvalid();
        }
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        String text = value.asText();
        return text == null || text.isBlank() ? null : text;
    }

    private Long longValue(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || !value.canConvertToLong() ? null : value.asLong();
    }

    private BusinessException payloadInvalid() {
        return BusinessException.badRequest("NOTICE_OUTBOX_PAYLOAD_INVALID: 通知 Outbox 载荷无效");
    }

    private BusinessException emailSnapshotInvalid() {
        return BusinessException.badRequest(
            "EMAIL_SNAPSHOT_INVALID: email delivery snapshot is invalid");
    }

    private Long firstNonNull(Long first, Long second) {
        return first == null ? second : first;
    }

    private boolean sameWhenPresent(Long value, Long expected) {
        return value == null || value.equals(expected);
    }

    private boolean same(Object value, Object expected) {
        return value != null && value.equals(expected);
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String normalizedSummary(JsonNode request, NoticeOutbox outbox) {
        com.fasterxml.jackson.databind.node.ObjectNode normalized = request.deepCopy();
        putIfPresent(normalized, "batchId", outbox.batchId);
        putIfPresent(normalized, "audienceId", outbox.audienceId);
        putIfPresent(normalized, "noticeId", outbox.noticeId);
        return normalized.toString();
    }

    private void putIfPresent(com.fasterxml.jackson.databind.node.ObjectNode target, String field, Long value) {
        if (value != null && !target.hasNonNull(field)) {
            target.put(field, value);
        }
    }

    private String firstNonBlank(String first, String second) {
        return first == null || first.isBlank() ? second : first;
    }
}
