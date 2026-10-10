package com.example.venue.provider.service;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.example.venue.common.SensitiveDataMasker;
import com.example.venue.provider.dto.ProviderCallRecord;
import com.example.venue.provider.entity.ProviderRegistryEntry;
import com.example.venue.provider.mapper.ProviderRegistryEntryMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
public class ProviderRegistryStateService {
    private static final int DEFAULT_FAILURE_THRESHOLD = 3;
    private static final int FAILURE_MESSAGE_LIMIT = 255;

    private final ProviderRegistryEntryMapper registryMapper;

    public ProviderRegistryStateService(ProviderRegistryEntryMapper registryMapper) {
        this.registryMapper = registryMapper;
    }

    @Transactional
    public void apply(ProviderCallRecord record, LocalDateTime occurredAt) {
        if (!record.updateRegistry() || "SKIPPED".equals(record.status())) {
            return;
        }

        ProviderRegistryEntry current = registryMapper.selectByProviderKeyForUpdate(record.providerKey());
        if (current == null) {
            throw new IllegalStateException("No active provider registry row for key " + record.providerKey());
        }

        ProviderRegistryEntry updated = copyOf(current);
        updated.updateTime = occurredAt;
        if (Integer.valueOf(0).equals(current.enabled)) {
            updated.status = "DISABLED";
            update(current, updated);
            return;
        }

        switch (record.status()) {
            case "SUCCESS" -> applySuccess(updated, occurredAt);
            case "DEGRADED" -> applyDegraded(updated, record, occurredAt);
            case "FAILED", "TIMEOUT" -> applyHardFailure(updated, current, record, occurredAt);
            default -> throw new IllegalArgumentException("Unsupported registry state status " + record.status());
        }
        update(current, updated);
    }

    private void applySuccess(ProviderRegistryEntry updated, LocalDateTime occurredAt) {
        updated.status = "ACTIVE";
        updated.lastSuccessAt = occurredAt;
        updated.consecutiveFailures = 0;
    }

    private void applyDegraded(ProviderRegistryEntry updated,
                               ProviderCallRecord record,
                               LocalDateTime occurredAt) {
        updated.status = "DEGRADED";
        updated.lastSuccessAt = occurredAt;
        updated.lastFailureAt = occurredAt;
        updated.lastFailureCode = upper(record.failureCode());
        updated.lastFailureMessage = failureMessage(record.failureMessage());
        updated.consecutiveFailures = 0;
    }

    private void applyHardFailure(ProviderRegistryEntry updated,
                                  ProviderRegistryEntry current,
                                  ProviderCallRecord record,
                                  LocalDateTime occurredAt) {
        int nextFailures = nextConsecutiveFailures(current);
        updated.status = nextFailures >= failureThreshold(current) ? "FAILED" : "DEGRADED";
        updated.lastFailureAt = occurredAt;
        updated.lastFailureCode = upper(record.failureCode());
        updated.lastFailureMessage = failureMessage(record.failureMessage());
        updated.consecutiveFailures = nextFailures;
    }

    private void update(ProviderRegistryEntry current, ProviderRegistryEntry updated) {
        registryMapper.update(updated, new UpdateWrapper<ProviderRegistryEntry>()
            .eq("id", current.id)
            .eq("deleted", 0));
    }

    private int nextConsecutiveFailures(ProviderRegistryEntry current) {
        return current.consecutiveFailures == null ? 1 : current.consecutiveFailures + 1;
    }

    private int failureThreshold(ProviderRegistryEntry current) {
        if (current.failureThreshold == null || current.failureThreshold <= 0) {
            return DEFAULT_FAILURE_THRESHOLD;
        }
        return current.failureThreshold;
    }

    private String failureMessage(String value) {
        String masked = SensitiveDataMasker.mask(value);
        if (masked == null || masked.length() <= FAILURE_MESSAGE_LIMIT) {
            return masked;
        }
        return masked.substring(0, FAILURE_MESSAGE_LIMIT);
    }

    private String upper(String value) {
        return value == null || value.isBlank() ? value : value.trim().toUpperCase(java.util.Locale.ROOT);
    }

    private ProviderRegistryEntry copyOf(ProviderRegistryEntry source) {
        ProviderRegistryEntry target = new ProviderRegistryEntry();
        target.id = source.id;
        target.providerKey = source.providerKey;
        target.providerDomain = source.providerDomain;
        target.providerEnv = source.providerEnv;
        target.providerName = source.providerName;
        target.displayName = source.displayName;
        target.configVersion = source.configVersion;
        target.enabled = source.enabled;
        target.status = source.status;
        target.freshnessSeconds = source.freshnessSeconds;
        target.failureThreshold = source.failureThreshold;
        target.timeoutMs = source.timeoutMs;
        target.lastSuccessAt = source.lastSuccessAt;
        target.lastFailureAt = source.lastFailureAt;
        target.consecutiveFailures = source.consecutiveFailures;
        target.lastFailureCode = source.lastFailureCode;
        target.lastFailureMessage = source.lastFailureMessage;
        target.metadataJson = source.metadataJson;
        target.createTime = source.createTime;
        target.updateTime = source.updateTime;
        target.deleted = source.deleted;
        return target;
    }
}
