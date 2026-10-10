package com.example.venue.notice.routing;

public record NotificationRoute(
    String channel,
    String providerKey,
    String providerEnv,
    String providerName,
    String configVersion
) {
}
