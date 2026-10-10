package com.example.venue.payment.provider;

public record PaymentProviderDescriptor(
    String providerKey,
    String providerEnv,
    String providerName,
    String displayName,
    String configVersion,
    boolean enabled
) {
    public String envNameKey() {
        return providerEnv + ":" + providerName;
    }
}
