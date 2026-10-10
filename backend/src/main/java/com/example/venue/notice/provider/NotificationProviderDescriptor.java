package com.example.venue.notice.provider;

public record NotificationProviderDescriptor(
    String providerKey,
    String providerDomain,
    String providerEnv,
    String providerName,
    String channel,
    String displayName,
    String configVersion,
    boolean enabled
) {
}
