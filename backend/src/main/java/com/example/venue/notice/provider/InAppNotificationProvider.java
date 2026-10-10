package com.example.venue.notice.provider;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.example.venue.notice.entity.NoticeMessage;
import com.example.venue.notice.entity.NoticeOutbox;
import com.example.venue.notice.mapper.NoticeMessageMapper;
import com.example.venue.notice.mapper.NoticeOutboxMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Component
public class InAppNotificationProvider implements NotificationProvider {
    private static final NotificationProviderDescriptor DESCRIPTOR = new NotificationProviderDescriptor(
        "notice.in_app.dev", "MESSAGE", "DEV", "IN_APP_MESSAGE", "IN_APP",
        "站内消息", "IN_APP_MESSAGE_V1", true);

    private final NoticeMessageMapper messageMapper;
    private final NoticeOutboxMapper outboxMapper;
    private final ObjectMapper objectMapper;

    public InAppNotificationProvider(NoticeMessageMapper messageMapper,
                                     NoticeOutboxMapper outboxMapper,
                                     ObjectMapper objectMapper) {
        this.messageMapper = messageMapper;
        this.outboxMapper = outboxMapper;
        this.objectMapper = objectMapper;
    }

    @Override
    public NotificationProviderDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    @Transactional
    public NotificationDeliveryResult send(NotificationDeliveryCommand command) {
        if (command == null || command.userId() == null || command.title() == null || command.content() == null) {
            return failure("NOTICE_OUTBOX_PAYLOAD_INVALID", "通知 Outbox 载荷无效", false);
        }
        if (command.messageId() == null
            && (command.businessId() == null || command.businessType() == null || command.businessType().isBlank())) {
            return failure("NOTICE_OUTBOX_PAYLOAD_INVALID", "通知 Outbox 缺少稳定业务键", false);
        }
        try {
            NoticeOutbox lockedOutbox = outboxMapper.lockForInAppDelivery(command.outboxId());
            if (lockedOutbox == null || !"SENDING".equals(lockedOutbox.status)) {
                return failure("NOTICE_OUTBOX_NOT_CLAIMED", "通知 Outbox 不处于投递状态", false);
            }
            NoticeMessage message = findExisting(command);
            if (message == null) {
                JsonNode request = parse(command.requestSummaryJson());
                message = new NoticeMessage();
                message.userId = command.userId();
                message.title = command.title();
                message.content = command.content();
                message.type = command.businessType();
                message.businessId = command.businessId();
                message.batchId = longValue(request, "batchId");
                message.audienceId = longValue(request, "audienceId");
                message.providerEnv = DESCRIPTOR.providerEnv();
                message.providerName = DESCRIPTOR.providerName();
                message.deliveryStatus = "SENT";
                message.readFlag = 0;
                message.createTime = LocalDateTime.now();
                messageMapper.insert(message);
            } else if (!"SENT".equals(message.deliveryStatus)) {
                message.deliveryStatus = "SENT";
                messageMapper.updateById(message);
            }
            String requestId = String.valueOf(message.id);
            ObjectNode response = objectMapper.createObjectNode();
            response.put("status", NotificationDeliveryResult.ACCEPTED);
            response.put("messageId", message.id);
            response.put("providerName", DESCRIPTOR.providerName());
            return new NotificationDeliveryResult(
                NotificationDeliveryResult.ACCEPTED, requestId, false, null, null, response.toString());
        } catch (RuntimeException ex) {
            return failure("IN_APP_TEMPORARY_FAILURE", ex.getMessage(), true);
        }
    }

    private NoticeMessage findExisting(NotificationDeliveryCommand command) {
        if (command.messageId() != null) {
            NoticeMessage message = messageMapper.selectById(command.messageId());
            if (message != null && command.userId().equals(message.userId)) {
                return message;
            }
        }
        JsonNode request = parse(command.requestSummaryJson());
        Long audienceId = longValue(request, "audienceId");
        Long batchId = longValue(request, "batchId");
        if (audienceId != null) {
            return messageMapper.selectOne(new QueryWrapper<NoticeMessage>()
                .eq("audience_id", audienceId)
                .eq(batchId != null, "batch_id", batchId)
                .eq("user_id", command.userId())
                .eq("provider_env", DESCRIPTOR.providerEnv())
                .eq("provider_name", DESCRIPTOR.providerName())
                .last("limit 1"));
        }
        return messageMapper.selectOne(new QueryWrapper<NoticeMessage>()
            .eq("user_id", command.userId())
            .eq(command.businessId() != null, "business_id", command.businessId())
            .eq(command.businessType() != null, "type", command.businessType())
            .eq("provider_env", DESCRIPTOR.providerEnv())
            .eq("provider_name", DESCRIPTOR.providerName())
            .last("limit 1"));
    }

    private JsonNode parse(String json) {
        try {
            return objectMapper.readTree(json == null || json.isBlank() ? "{}" : json);
        } catch (Exception ex) {
            throw new IllegalArgumentException("通知 Outbox 摘要无效", ex);
        }
    }

    private Long longValue(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || !value.canConvertToLong() ? null : value.asLong();
    }

    private NotificationDeliveryResult failure(String code, String message, boolean retryable) {
        ObjectNode response = objectMapper.createObjectNode();
        response.put("status", retryable
            ? NotificationDeliveryResult.FAILED_RETRYABLE
            : NotificationDeliveryResult.FAILED_PERMANENT);
        response.put("failureCode", code);
        return new NotificationDeliveryResult(
            retryable ? NotificationDeliveryResult.FAILED_RETRYABLE : NotificationDeliveryResult.FAILED_PERMANENT,
            null, retryable, code, message, response.toString());
    }
}
