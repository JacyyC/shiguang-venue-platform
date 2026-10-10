package com.example.venue.storedvalue.provider;

public record StoredValueProviderDescriptor(
    String providerKey,
    String providerEnv,
    String providerName,
    String configVersion,
    boolean enabled
) {
    public String snapshotKey() {
        return providerKey + ":" + providerEnv + ":" + providerName + ":" + configVersion;
    }
}
