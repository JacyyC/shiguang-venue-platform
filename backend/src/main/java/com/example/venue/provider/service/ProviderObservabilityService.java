package com.example.venue.provider.service;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.example.venue.common.SensitiveDataMasker;
import com.example.venue.provider.dto.ProviderCallRecord;
import com.example.venue.provider.dto.ProviderRegistryRegistration;
import com.example.venue.provider.entity.ProviderCallLog;
import com.example.venue.provider.entity.ProviderRegistryEntry;
import com.example.venue.provider.mapper.ProviderCallLogMapper;
import com.example.venue.provider.mapper.ProviderRegistryEntryMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.UUID;

@Service
public class ProviderObservabilityService {
    private static final int SUMMARY_LIMIT = 2000;
    private static final int FAILURE_MESSAGE_LIMIT = 255;
    private static final DateTimeFormatter CALL_NO_TIME_FORMAT =
        DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS");

    private final ProviderRegistryEntryMapper registryMapper;
    private final ProviderCallLogMapper callLogMapper;
    private final ProviderRegistryStateService registryStateService;

    public ProviderObservabilityService(ProviderRegistryEntryMapper registryMapper,
                                        ProviderCallLogMapper callLogMapper,
                                        ProviderRegistryStateService registryStateService) {
        this.registryMapper = registryMapper;
        this.callLogMapper = callLogMapper;
        this.registryStateService = registryStateService;
    }

    @Transactional
    public void recordCall(ProviderCallRecord record) {
        LocalDateTime now = LocalDateTime.now();
        callLogMapper.insert(toCallLog(record, now));
        if (record.updateRegistry()) {
            registryStateService.apply(record, now);
        }
    }

    @Transactional
    public void upsertRegistry(ProviderRegistryRegistration registration) {
        ProviderRegistryEntry current = registryMapper.selectByProviderKeyIncludingDeleted(registration.providerKey());
        LocalDateTime now = LocalDateTime.now();
        if (current == null) {
            registryMapper.upsertDescriptor(newDescriptorEntry(registration, now));
            return;
        }

        current = registryMapper.selectByProviderKeyIncludingDeletedForUpdate(registration.providerKey());
        if (current == null) {
            registryMapper.upsertDescriptor(newDescriptorEntry(registration, now));
            return;
        }
        ProviderRegistryEntry entry = descriptorEntry(registration);
        entry.id = current.id;
        entry.updateTime = now;
        if (Integer.valueOf(1).equals(current.deleted)) {
            entry.status = registration.enabled() ? "ACTIVE" : "DISABLED";
            if (registryMapper.restoreDeletedDescriptor(entry, registration.providerKey()) != 1) {
                throw new IllegalStateException("Could not restore provider registry row " + registration.providerKey());
            }
            return;
        }
        if (!registration.enabled()) {
            entry.status = "DISABLED";
        } else if ("DISABLED".equals(current.status)) {
            entry.status = "ACTIVE";
        }
        registryMapper.update(entry, new UpdateWrapper<ProviderRegistryEntry>()
            .eq("id", current.id)
            .eq("provider_key", registration.providerKey()));
    }

    private ProviderRegistryEntry newDescriptorEntry(ProviderRegistryRegistration registration, LocalDateTime now) {
        ProviderRegistryEntry entry = descriptorEntry(registration);
        entry.status = registration.enabled() ? "ACTIVE" : "DISABLED";
        entry.consecutiveFailures = 0;
        entry.createTime = now;
        entry.updateTime = now;
        return entry;
    }

    private ProviderRegistryEntry descriptorEntry(ProviderRegistryRegistration registration) {
        ProviderRegistryEntry entry = new ProviderRegistryEntry();
        entry.providerKey = registration.providerKey();
        entry.providerDomain = registration.providerDomain();
        entry.providerEnv = registration.providerEnv();
        entry.providerName = registration.providerName();
        entry.displayName = registration.displayName();
        entry.configVersion = registration.configVersion();
        entry.enabled = registration.enabled() ? 1 : 0;
        entry.metadataJson = registration.metadataJson();
        entry.deleted = 0;
        return entry;
    }

    private ProviderCallLog toCallLog(ProviderCallRecord record, LocalDateTime now) {
        ProviderCallLog log = new ProviderCallLog();
        log.callNo = callNo(now);
        log.providerKey = record.providerKey();
        log.providerDomain = upper(record.providerDomain());
        log.providerEnv = upper(record.providerEnv());
        log.providerName = upper(record.providerName());
        log.operationType = upper(record.operationType());
        log.status = upper(record.status());
        log.idempotencyKey = record.idempotencyKey();
        log.externalRef = record.externalRef();
        log.requestSummaryJson = safeSummary(record.requestSummaryJson());
        log.responseSummaryJson = safeSummary(record.responseSummaryJson());
        log.failureCode = upper(record.failureCode());
        log.failureMessage = limit(SensitiveDataMasker.mask(record.failureMessage()), FAILURE_MESSAGE_LIMIT);
        log.durationMs = record.durationMs();
        log.configVersion = record.configVersion();
        log.confidence = record.confidence();
        log.snapshotType = upper(record.snapshotType());
        log.snapshotId = record.snapshotId();
        log.sourceType = record.sourceType();
        log.triggeredBy = record.triggeredBy();
        log.occurredAt = now;
        log.createTime = now;
        return log;
    }

    private String callNo(LocalDateTime now) {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase(Locale.ROOT);
        return "PC" + now.format(CALL_NO_TIME_FORMAT) + suffix;
    }

    private String safeSummary(String value) {
        String masked = SensitiveDataMasker.mask(value);
        if (masked == null || masked.length() <= SUMMARY_LIMIT) {
            return masked;
        }
        return "{\"truncated\":true,\"length\":" + masked.length() + "}";
    }

    private String limit(String value, int limit) {
        if (value == null || value.length() <= limit) {
            return value;
        }
        return value.substring(0, limit);
    }

    private String upper(String value) {
        if (value == null || value.isBlank()) {
            return value;
        }
        return value.trim().toUpperCase(Locale.ROOT);
    }
}
