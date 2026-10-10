package com.example.venue.notice.provider;

public record NotificationDeliveryCommand(
    Long outboxId,
    String idempotencyKey,
    NotificationProviderDescriptor provider,
    Long userId,
    Long messageId,
    Long businessId,
    String businessType,
    String title,
    String content,
    String requestSummaryJson,
    Long emailSnapshotId,
    String maskedRecipient,
    String templateKey,
    String templateVersion,
    String messageIdHeader,
    String eventType
) {
    public NotificationDeliveryCommand(
        Long outboxId,
        String idempotencyKey,
        NotificationProviderDescriptor provider,
        Long userId,
        Long messageId,
        Long businessId,
        String businessType,
        String title,
        String content,
        String requestSummaryJson
    ) {
        this(outboxId, idempotencyKey, provider, userId, messageId, businessId, businessType,
            title, content, requestSummaryJson, null, null, null, null, null, null);
    }

    @Override
    public String toString() {
        return "NotificationDeliveryCommand[outboxId=" + outboxId + ", providerKey="
            + (provider == null ? null : provider.providerKey()) + ", userId=" + userId
            + ", messageId=" + messageId + ", emailSnapshotId=" + emailSnapshotId
            + ", templateKey=" + templateKey + ", templateVersion=" + templateVersion
            + ", eventType=" + eventType + "]";
    }
}
