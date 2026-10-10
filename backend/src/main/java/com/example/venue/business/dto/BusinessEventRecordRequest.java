package com.example.venue.business.dto;

import com.example.venue.business.enums.BusinessDomain;
import com.example.venue.business.enums.BusinessEventType;
import com.example.venue.business.enums.BusinessSourceType;
import com.example.venue.business.enums.ProviderEnv;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

public class BusinessEventRecordRequest {
    public BusinessEventType eventType;
    public BusinessDomain businessDomain;
    public String entityType;
    public Long entityId;
    public String relatedEntityType;
    public Long relatedEntityId;
    public Long actorUserId;
    public String actorUsername;
    public BusinessSourceType sourceType;
    public ProviderEnv providerEnv;
    public String providerName;
    public String externalRef;
    public String idempotencyKey;
    public BigDecimal confidence;
    public String reasonCode;
    public String reasonText;
    public String fromStatus;
    public String toStatus;
    public String resultStatus;
    public LocalDateTime occurredAt;
    public Map<String, Object> metadata = new LinkedHashMap<>();

    public static BusinessEventRecordRequest of(BusinessEventType eventType, BusinessDomain businessDomain,
                                                String entityType, Long entityId,
                                                BusinessSourceType sourceType, String resultStatus) {
        BusinessEventRecordRequest request = new BusinessEventRecordRequest();
        request.eventType = eventType;
        request.businessDomain = businessDomain;
        request.entityType = entityType;
        request.entityId = entityId;
        request.sourceType = sourceType;
        request.resultStatus = resultStatus;
        return request;
    }

    public BusinessEventRecordRequest relatedEntity(String type, Long id) {
        this.relatedEntityType = type;
        this.relatedEntityId = id;
        return this;
    }

    public BusinessEventRecordRequest actor(Long userId, String username) {
        this.actorUserId = userId;
        this.actorUsername = username;
        return this;
    }

    public BusinessEventRecordRequest provider(ProviderEnv env, String name) {
        this.providerEnv = env;
        this.providerName = name;
        return this;
    }

    public BusinessEventRecordRequest externalRef(String externalRef) {
        this.externalRef = externalRef;
        return this;
    }

    public BusinessEventRecordRequest idempotencyKey(String idempotencyKey) {
        this.idempotencyKey = idempotencyKey;
        return this;
    }

    public BusinessEventRecordRequest confidence(BigDecimal confidence) {
        this.confidence = confidence;
        return this;
    }

    public BusinessEventRecordRequest reasonCode(String reasonCode) {
        this.reasonCode = reasonCode;
        return this;
    }

    public BusinessEventRecordRequest reasonText(String reasonText) {
        this.reasonText = reasonText;
        return this;
    }

    public BusinessEventRecordRequest fromStatus(String fromStatus) {
        this.fromStatus = fromStatus;
        return this;
    }

    public BusinessEventRecordRequest toStatus(String toStatus) {
        this.toStatus = toStatus;
        return this;
    }

    public BusinessEventRecordRequest occurredAt(LocalDateTime occurredAt) {
        this.occurredAt = occurredAt;
        return this;
    }

    public BusinessEventRecordRequest metadata(String key, Object value) {
        this.metadata.put(key, value);
        return this;
    }
}
