package com.example.venue.notice.dto;

public record NotificationProviderOptionResponse(
    String providerKey,
    String providerDomain,
    String providerEnv,
    String providerName,
    String channel,
    String displayName,
    String configVersion,
    boolean enabled,
    boolean defaultProvider
) {
}
