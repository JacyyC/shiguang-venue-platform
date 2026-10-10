package com.example.venue.storedvalue.provider;

import com.example.venue.common.BusinessException;
import org.springframework.core.env.Environment;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
public class StoredValueProviderRouter {
    private final StoredValueProviderProperties properties;
    private final Map<String, StoredValueRechargeProvider> providers;
    private final Environment environment;
    private final JdbcTemplate jdbc;

    public StoredValueProviderRouter(List<StoredValueRechargeProvider> providers,
                                      StoredValueProviderProperties properties) {
        this(providers, properties, null, null);
    }

    public StoredValueProviderRouter(List<StoredValueRechargeProvider> providers,
                                     StoredValueProviderProperties properties,
                                     Environment environment) {
        this(providers, properties, environment, null);
    }

    @Autowired
    public StoredValueProviderRouter(List<StoredValueRechargeProvider> providers,
                                     StoredValueProviderProperties properties,
                                     Environment environment,
                                     JdbcTemplate jdbc) {
        this.properties = properties;
        this.environment = environment;
        this.jdbc = jdbc;
        Map<String, StoredValueRechargeProvider> index = new LinkedHashMap<>();
        for (StoredValueRechargeProvider provider : providers) {
            StoredValueProviderDescriptor descriptor = provider == null ? null : provider.descriptor();
            if (descriptor == null || descriptor.providerKey() == null || descriptor.providerKey().isBlank()) {
                throw new IllegalStateException("stored value provider descriptor must not be blank");
            }
            if (index.putIfAbsent(descriptor.providerKey(), provider) != null) {
                throw new IllegalStateException("duplicate stored value provider key: " + descriptor.providerKey());
            }
        }
        this.providers = Map.copyOf(index);
    }

    public StoredValueRechargeProvider requireDefault() {
        return require(properties.getDefaultProviderKey());
    }

    public StoredValueRechargeProvider requireDefault(Long productId) {
        return require(properties.getDefaultProviderKey(), productId);
    }

    public StoredValueRechargeProvider require(String providerKey) {
        StoredValueRechargeProvider provider = resolve(providerKey);
        if (!properties.isRechargeEnabled()) {
            throw BusinessException.conflict("STORED_VALUE_RECHARGE_PAUSED: 储值充值已暂停");
        }
        return provider;
    }

    public StoredValueRechargeProvider require(String providerKey, Long productId) {
        StoredValueRechargeProvider provider = require(providerKey);
        requireDatabaseConfiguration(provider, productId);
        return provider;
    }

    public StoredValueRechargeProvider requireForRefund(String providerKey) {
        if (!properties.isRefundEnabled()) {
            throw BusinessException.conflict("STORED_VALUE_REFUND_PAUSED: 储值退款已暂停");
        }
        return resolve(providerKey);
    }

    public StoredValueRechargeProvider requireForRefund(String providerKey, Long productId) {
        if (!properties.isRefundEnabled()) {
            throw BusinessException.conflict("STORED_VALUE_REFUND_PAUSED: 储值退款已暂停");
        }
        StoredValueRechargeProvider provider = resolve(providerKey);
        requireDatabaseConfiguration(provider, productId);
        return provider;
    }

    private void requireDatabaseConfiguration(StoredValueRechargeProvider provider, Long productId) {
        if (jdbc == null || productId == null) {
            throw BusinessException.conflict("STORED_VALUE_PROVIDER_NOT_CONFIGURED: 储值 Provider 配置不可用");
        }
        StoredValueProviderDescriptor descriptor = provider.descriptor();
        ProviderConfig config = jdbc.query("""
            SELECT provider_key, provider_env, provider_name, config_version, enabled, manual_confirmed, status
            FROM stored_value_provider_config
            WHERE product_id = ? AND provider_key = ? AND status = 'ACTIVE' AND enabled = 1
            ORDER BY id DESC LIMIT 1
            """, rs -> rs.next() ? new ProviderConfig(rs.getString("provider_key"),
                rs.getString("provider_env"), rs.getString("provider_name"), rs.getString("config_version"),
                rs.getBoolean("enabled"), rs.getBoolean("manual_confirmed"), rs.getString("status")) : null,
            productId, descriptor.providerKey());
        if (config == null || !descriptor.providerKey().equals(config.providerKey)
            || !descriptor.providerEnv().equals(config.providerEnv)
            || !descriptor.providerName().equals(config.providerName)
            || !descriptor.configVersion().equals(config.configVersion)
            || !config.enabled || !"ACTIVE".equals(config.status)
            || ("SANDBOX".equals(normalize(config.providerEnv)) && !config.manualConfirmed)) {
            throw BusinessException.conflict("STORED_VALUE_PROVIDER_NOT_CONFIGURED: 储值 Provider 配置不可用或已变化");
        }
    }

    private StoredValueRechargeProvider resolve(String providerKey) {
        if (isProductionProfile()) {
            throw BusinessException.forbidden("STORED_VALUE_PROD_PROFILE_DISABLED: 生产环境永久关闭储值资金入口");
        }
        String key = providerKey == null || providerKey.isBlank()
            ? properties.getDefaultProviderKey() : providerKey.trim();
        StoredValueRechargeProvider provider = providers.get(key);
        if (provider == null || !provider.descriptor().enabled()) {
            throw BusinessException.conflict("STORED_VALUE_PROVIDER_NOT_CONFIGURED: 储值 Provider 不可用");
        }
        String env = normalize(provider.descriptor().providerEnv());
        if ("PROD".equals(env) || "PRODUCTION".equals(env)) {
            throw BusinessException.forbidden("STORED_VALUE_PROD_DISABLED: 生产储值 Provider 永久关闭");
        }
        if ("SANDBOX".equals(env)
            && (!properties.isAlipaySandboxEnabled() || !properties.isAlipaySandboxManualConfirmation())) {
            throw BusinessException.conflict("STORED_VALUE_SANDBOX_NOT_CONFIRMED: 沙箱充值需要显式配置和人工确认");
        }
        return provider;
    }

    private boolean isProductionProfile() {
        return environment != null && (environment.matchesProfiles("prod")
            || environment.matchesProfiles("production"));
    }

    public List<StoredValueProviderDescriptor> catalog() {
        return providers.values().stream().map(StoredValueRechargeProvider::descriptor)
            .sorted((a, b) -> a.providerKey().compareTo(b.providerKey())).toList();
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private record ProviderConfig(String providerKey, String providerEnv, String providerName,
                                  String configVersion, boolean enabled, boolean manualConfirmed,
                                  String status) {
    }
}
