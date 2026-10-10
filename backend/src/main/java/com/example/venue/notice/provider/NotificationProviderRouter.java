package com.example.venue.notice.provider;

import com.example.venue.common.BusinessException;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
public class NotificationProviderRouter {
    private final Map<String, NotificationProvider> byKey;
    private final Map<String, NotificationProvider> byEnvName;
    private final List<NotificationProviderDescriptor> catalog;

    public NotificationProviderRouter(List<NotificationProvider> providers) {
        Map<String, NotificationProvider> keyIndex = new LinkedHashMap<>();
        Map<String, NotificationProvider> envNameIndex = new LinkedHashMap<>();
        List<NotificationProviderDescriptor> descriptors = new ArrayList<>();

        for (NotificationProvider provider : providers) {
            NotificationProviderDescriptor descriptor = requireDescriptor(provider);
            String providerKey = requireText(descriptor.providerKey(), "providerKey");
            String envNameKey = envNameKey(descriptor.providerEnv(), descriptor.providerName());
            if (keyIndex.putIfAbsent(providerKey, provider) != null) {
                throw new IllegalStateException("Duplicate notification provider key: " + providerKey);
            }
            if (envNameIndex.putIfAbsent(envNameKey, provider) != null) {
                throw new IllegalStateException("Duplicate notification provider env/name: " + envNameKey);
            }
            descriptors.add(descriptor);
        }

        descriptors.sort((left, right) -> left.providerKey().compareTo(right.providerKey()));
        this.byKey = Collections.unmodifiableMap(keyIndex);
        this.byEnvName = Collections.unmodifiableMap(envNameIndex);
        this.catalog = List.copyOf(descriptors);
    }

    public NotificationProvider require(String providerKey) {
        NotificationProvider provider = providerKey == null ? null : byKey.get(providerKey.trim());
        return requireEnabled(provider);
    }

    public NotificationProvider require(String providerEnv, String providerName) {
        return requireEnabled(byEnvName.get(envNameKey(providerEnv, providerName)));
    }

    public List<NotificationProviderDescriptor> catalog() {
        return catalog;
    }

    private NotificationProvider requireEnabled(NotificationProvider provider) {
        if (provider == null || !provider.descriptor().enabled()) {
            throw BusinessException.notFound(
                "NOTICE_PROVIDER_NOT_FOUND: 通知 Provider 不存在或未启用");
        }
        return provider;
    }

    private NotificationProviderDescriptor requireDescriptor(NotificationProvider provider) {
        if (provider == null || provider.descriptor() == null) {
            throw new IllegalStateException("Notification provider descriptor must not be null");
        }
        NotificationProviderDescriptor descriptor = provider.descriptor();
        requireText(descriptor.providerDomain(), "providerDomain");
        requireText(descriptor.providerEnv(), "providerEnv");
        requireText(descriptor.providerName(), "providerName");
        requireText(descriptor.channel(), "channel");
        requireText(descriptor.configVersion(), "configVersion");
        return descriptor;
    }

    private String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Notification provider " + fieldName + " must not be blank");
        }
        return value.trim();
    }

    private String envNameKey(String providerEnv, String providerName) {
        return normalize(providerEnv) + ":" + normalize(providerName);
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }
}
