package com.example.venue.provider.dto;

import java.util.Locale;

public record ProviderRegistryRegistration(
    String providerKey,
    String providerDomain,
    String providerEnv,
    String providerName,
    String displayName,
    String configVersion,
    boolean enabled,
    String metadataJson
) {
    public ProviderRegistryRegistration {
        providerKey = requiredText(providerKey, "providerKey");
        providerDomain = upperRequired(providerDomain, "providerDomain");
        providerEnv = upperRequired(providerEnv, "providerEnv");
        providerName = upperRequired(providerName, "providerName");
        displayName = requiredText(displayName, "displayName");
        configVersion = requiredText(configVersion, "configVersion");
    }

    private static String upperRequired(String value, String fieldName) {
        return requiredText(value, fieldName).toUpperCase(Locale.ROOT);
    }

    private static String requiredText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.trim();
    }
}
