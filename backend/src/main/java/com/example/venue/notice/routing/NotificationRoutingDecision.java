package com.example.venue.notice.routing;

import java.util.List;

public record NotificationRoutingDecision(
    List<NotificationRoute> routes,
    String emailExclusionCode
) {
    public NotificationRoutingDecision {
        routes = List.copyOf(routes);
    }
}
