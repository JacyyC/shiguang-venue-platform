package com.example.venue.notice.routing;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public record NotificationEvent(
    String eventType,
    String eventKey,
    Long recipientUserId,
    NotificationRecipientKind recipientKind,
    String inAppTitle,
    String inAppContent,
    String businessType,
    Long businessId,
    Map<String, String> templateModel
) {
    public NotificationEvent {
        templateModel = templateModel == null
            ? Map.of()
            : Collections.unmodifiableMap(new LinkedHashMap<>(templateModel));
    }

    public NotificationEvent(String eventType, Long userId, String title, String content,
                             String businessType, Long businessId, Long batchId,
                             Long audienceId, Long noticeId) {
        this(eventType, "LEGACY_IN_APP:" + eventType + ":" + String.valueOf(batchId)
                + ":" + String.valueOf(audienceId) + ":" + String.valueOf(userId),
            userId, NotificationRecipientKind.USER, title, content, businessType, businessId, Map.of());
    }

    public static NotificationEvent businessMessage(
        String eventType, Long userId, String title, String content,
        String businessType, Long businessId
    ) {
        return new NotificationEvent(
            eventType, userId, title, content, businessType, businessId, null, null, null);
    }
}
