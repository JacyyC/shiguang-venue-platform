package com.example.venue.notice.routing;

import com.example.venue.notice.email.EmailRecipientEligibility;

import java.util.List;

public interface NotificationRoutingPolicy {
    NotificationRoutingDecision decide(NotificationEvent event, EmailRecipientEligibility recipient);

    default List<NotificationRoute> routesFor(NotificationEvent event) {
        return decide(event, EmailRecipientEligibility.inAppOnly("LEGACY_IN_APP_ONLY")).routes();
    }
}
