package com.example.venue.payment.provider;

public record PaymentProviderOptionResponse(
    String providerKey,
    String providerEnv,
    String providerName,
    String displayName,
    String configVersion,
    boolean enabled,
    boolean defaultProvider
) {
}
