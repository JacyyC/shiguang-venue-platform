package com.example.venue.payment.provider;

import com.example.venue.common.BusinessException;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
public class PaymentProviderRouter {
    private final PaymentProviderProperties properties;
    private final Map<String, PaymentProvider> byKey;
    private final Map<String, PaymentProvider> byEnvName;
    private final List<PaymentProviderDescriptor> catalog;

    public PaymentProviderRouter(List<PaymentProvider> providers, PaymentProviderProperties properties) {
        this.properties = properties;
        Map<String, PaymentProvider> keyIndex = new LinkedHashMap<>();
        Map<String, PaymentProvider> envNameIndex = new LinkedHashMap<>();
        List<PaymentProviderDescriptor> descriptors = new ArrayList<>();

        for (PaymentProvider provider : providers) {
            PaymentProviderDescriptor descriptor = requireDescriptor(provider);
            String providerKey = requireText(descriptor.providerKey(), "providerKey");
            String envNameKey = envNameKey(descriptor.providerEnv(), descriptor.providerName());
            if (keyIndex.putIfAbsent(providerKey, provider) != null) {
                throw new IllegalStateException("Duplicate payment provider key: " + providerKey);
            }
            if (envNameIndex.putIfAbsent(envNameKey, provider) != null) {
                throw new IllegalStateException("Duplicate payment provider env/name: " + envNameKey);
            }
            descriptors.add(descriptor);
        }

        descriptors.sort((left, right) -> left.providerKey().compareTo(right.providerKey()));
        this.byKey = Collections.unmodifiableMap(keyIndex);
        this.byEnvName = Collections.unmodifiableMap(envNameIndex);
        this.catalog = List.copyOf(descriptors);
    }

    public PaymentProvider requireDefault() {
        String providerKey = properties.getDefaultProviderKey();
        PaymentProvider provider = providerKey == null ? null : byKey.get(providerKey.trim());
        return requireConfigured(provider);
    }

    public PaymentProvider requireByKey(String providerKey) {
        PaymentProvider provider = providerKey == null ? null : byKey.get(providerKey.trim());
        return requireConfigured(provider);
    }

    private PaymentProvider requireConfigured(PaymentProvider provider) {
        if (!isEnabled(provider)) {
            throw BusinessException.conflict(
                "PAYMENT_PROVIDER_NOT_CONFIGURED: 默认支付 Provider 不可用");
        }
        return provider;
    }

    public PaymentProvider require(String providerEnv, String providerName) {
        PaymentProvider provider = byEnvName.get(envNameKey(providerEnv, providerName));
        if (!isEnabled(provider)) {
            throw BusinessException.notFound(
                "PAYMENT_PROVIDER_NOT_FOUND: 支付 Provider 不存在或未启用");
        }
        return provider;
    }

    public PaymentProvider requireByName(String providerName) {
        String normalizedName = normalize(providerName);
        List<PaymentProvider> matches = byKey.values().stream()
            .filter(this::isEnabled)
            .filter(provider -> normalize(provider.descriptor().providerName()).equals(normalizedName))
            .toList();
        if (matches.isEmpty()) {
            throw BusinessException.notFound(
                "PAYMENT_PROVIDER_NOT_FOUND: 支付 Provider 不存在或未启用");
        }
        if (matches.size() > 1) {
            throw BusinessException.conflict(
                "PAYMENT_PROVIDER_AMBIGUOUS: 支付 Provider 名称不唯一");
        }
        return matches.get(0);
    }

    public List<PaymentProviderDescriptor> catalog() {
        return catalog;
    }

    private PaymentProviderDescriptor requireDescriptor(PaymentProvider provider) {
        if (provider == null || provider.descriptor() == null) {
            throw new IllegalStateException("Payment provider descriptor must not be null");
        }
        PaymentProviderDescriptor descriptor = provider.descriptor();
        requireText(descriptor.providerEnv(), "providerEnv");
        requireText(descriptor.providerName(), "providerName");
        return descriptor;
    }

    private String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Payment provider " + fieldName + " must not be blank");
        }
        return value.trim();
    }

    private boolean isEnabled(PaymentProvider provider) {
        return provider != null && provider.descriptor().enabled();
    }

    private String envNameKey(String providerEnv, String providerName) {
        return normalize(providerEnv) + ":" + normalize(providerName);
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }
}
