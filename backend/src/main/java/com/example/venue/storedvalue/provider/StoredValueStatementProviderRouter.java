package com.example.venue.storedvalue.provider;

import com.example.venue.common.BusinessException;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class StoredValueStatementProviderRouter {
    private final StoredValueProviderProperties properties;
    private final Map<String, StoredValueStatementProvider> providers;

    public StoredValueStatementProviderRouter(List<StoredValueStatementProvider> providers,
                                               StoredValueProviderProperties properties) {
        this.properties = properties;
        Map<String, StoredValueStatementProvider> index = new LinkedHashMap<>();
        for (StoredValueStatementProvider provider : providers) {
            if (provider == null || provider.descriptor() == null
                || provider.descriptor().providerKey() == null
                || provider.descriptor().providerKey().isBlank()) {
                throw new IllegalStateException("stored value statement provider descriptor must not be blank");
            }
            if (index.putIfAbsent(provider.descriptor().providerKey(), provider) != null) {
                throw new IllegalStateException("duplicate stored value statement provider key: "
                    + provider.descriptor().providerKey());
            }
        }
        this.providers = Map.copyOf(index);
    }

    public StoredValueStatementProvider requireDefault() {
        return require(properties.getDefaultProviderKey());
    }

    public StoredValueStatementProvider require(String providerKey) {
        String key = providerKey == null || providerKey.isBlank()
            ? properties.getDefaultProviderKey() : providerKey.trim();
        StoredValueStatementProvider provider = providers.get(key);
        if (provider == null || !provider.descriptor().enabled()) {
            throw BusinessException.conflict("STORED_VALUE_STATEMENT_PROVIDER_NOT_CONFIGURED: 储值账单 Provider 不可用");
        }
        String environment = provider.descriptor().providerEnv();
        if ("PROD".equalsIgnoreCase(environment) || "PRODUCTION".equalsIgnoreCase(environment)) {
            throw BusinessException.forbidden("STORED_VALUE_PROD_DISABLED: 生产储值账单永久关闭");
        }
        if ("SANDBOX".equalsIgnoreCase(environment)
            && (!properties.isAlipaySandboxEnabled() || !properties.isAlipaySandboxManualConfirmation())) {
            throw BusinessException.conflict("STORED_VALUE_SANDBOX_NOT_CONFIRMED: 沙箱账单需要显式配置和人工确认");
        }
        return provider;
    }
}
