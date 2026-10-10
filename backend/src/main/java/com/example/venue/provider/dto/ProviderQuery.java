package com.example.venue.provider.dto;

import com.example.venue.common.PageQuery;
import com.example.venue.common.BusinessException;

import java.time.LocalDateTime;
import java.util.Locale;

public class ProviderQuery extends PageQuery {
    public String providerKey;
    public String providerDomain;
    public String providerEnv;
    public String providerName;
    public String operationType;
    public String status;
    public String snapshotType;
    public String sourceType;
    public Long venueId;
    public String metricKey;
    public Long snapshotId;
    public LocalDateTime startTime;
    public LocalDateTime endTime;

    public String getProviderKey() {
        return providerKey;
    }

    public void setProviderKey(String providerKey) {
        this.providerKey = providerKey;
    }

    public String getProviderDomain() {
        return providerDomain;
    }

    public void setProviderDomain(String providerDomain) {
        this.providerDomain = providerDomain;
    }

    public String getProviderEnv() {
        return providerEnv;
    }

    public void setProviderEnv(String providerEnv) {
        this.providerEnv = providerEnv;
    }

    public String getProviderName() {
        return providerName;
    }

    public void setProviderName(String providerName) {
        this.providerName = providerName;
    }

    public String getOperationType() {
        return operationType;
    }

    public void setOperationType(String operationType) {
        this.operationType = operationType;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getSnapshotType() {
        return snapshotType;
    }

    public void setSnapshotType(String snapshotType) {
        this.snapshotType = snapshotType;
    }

    public String providerKey() {
        return blankToNull(providerKey);
    }

    public String normalizedProviderDomain() {
        return upper(providerDomain);
    }

    public String normalizedProviderEnv() {
        return upper(providerEnv);
    }

    public String normalizedProviderName() {
        return upper(providerName);
    }

    public String normalizedOperationType() {
        return upper(operationType);
    }

    public String normalizedStatus() {
        return upper(status);
    }

    public String normalizedSnapshotType() {
        return upper(snapshotType);
    }

    public String normalizedSourceType() {
        String normalized = blankToNull(sourceType);
        if (normalized == null) {
            return null;
        }
        try {
            return ProviderRunContext.normalizeSourceType(normalized);
        } catch (IllegalArgumentException ex) {
            throw BusinessException.badRequest(ex.getMessage());
        }
    }

    public String getSourceType() {
        return sourceType;
    }

    public void setSourceType(String sourceType) {
        this.sourceType = sourceType;
    }

    public Long getVenueId() {
        return venueId;
    }

    public void setVenueId(Long venueId) {
        this.venueId = venueId;
    }

    public Long venueId() {
        return venueId;
    }

    public String getMetricKey() {
        return metricKey;
    }

    public void setMetricKey(String metricKey) {
        this.metricKey = metricKey;
    }

    public String metricKey() {
        return blankToNull(metricKey);
    }

    public Long getSnapshotId() {
        return snapshotId;
    }

    public void setSnapshotId(Long snapshotId) {
        this.snapshotId = snapshotId;
    }

    public LocalDateTime getStartTime() {
        return startTime;
    }

    public void setStartTime(LocalDateTime startTime) {
        this.startTime = startTime;
    }

    public LocalDateTime getEndTime() {
        return endTime;
    }

    public void setEndTime(LocalDateTime endTime) {
        this.endTime = endTime;
    }

    private String upper(String value) {
        String normalized = blankToNull(value);
        return normalized == null ? null : normalized.toUpperCase(Locale.ROOT);
    }

    private String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
