package com.example.venue.notice.routing;

public record EmailRouteDefinition(
    String eventType,
    NotificationRecipientKind recipientKind,
    String templateKey,
    String templateVersion
) {
}
