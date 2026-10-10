package com.example.venue.business.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.example.venue.business.dto.BusinessEventRecordRequest;
import com.example.venue.business.entity.BusinessEvent;
import com.example.venue.business.enums.BusinessEventType;
import com.example.venue.business.mapper.BusinessEventMapper;
import com.example.venue.common.BusinessException;
import com.example.venue.common.CurrentUser;
import com.example.venue.security.SecurityContext;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class BusinessEventService {
    private static final DateTimeFormatter EVENT_NO_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final EnumSet<BusinessEventType> ALLOWED_EVENT_TYPES = EnumSet.of(
        BusinessEventType.RESERVATION_CREATED,
        BusinessEventType.UNAVAILABLE_BLOCK_CREATED,
        BusinessEventType.UNAVAILABLE_BLOCK_CANCELED,
        BusinessEventType.VENUE_CALENDAR_CHANGED,
        BusinessEventType.INVENTORY_CONFLICT_REJECTED,
        BusinessEventType.RESOURCE_ALLOCATION_CONFLICT_REJECTED,
        BusinessEventType.MEMBER_LEVEL_CHANGED,
        BusinessEventType.MEMBER_POINTS_CHANGED,
        BusinessEventType.ACTIVITY_CREATED,
        BusinessEventType.ACTIVITY_STATUS_CHANGED,
        BusinessEventType.PRICE_RULE_CHANGED,
        BusinessEventType.COUPON_CLAIMED,
        BusinessEventType.COUPON_LOCKED,
        BusinessEventType.COUPON_RELEASED,
        BusinessEventType.COUPON_USED,
        BusinessEventType.COUPON_REFUNDED,
        BusinessEventType.RESERVATION_CANCELED,
        BusinessEventType.RESERVATION_APPROVED,
        BusinessEventType.ORDER_CREATED,
        BusinessEventType.PAYMENT_SUCCEEDED,
        BusinessEventType.PAYMENT_FAILED,
        BusinessEventType.REFUND_REQUESTED,
        BusinessEventType.REFUND_APPROVED,
        BusinessEventType.REFUND_REJECTED,
        BusinessEventType.REFUND_SUCCEEDED,
        BusinessEventType.CHECKIN_VERIFIED,
        BusinessEventType.CHECKIN_EXCEPTION_RECORDED,
        BusinessEventType.NO_SHOW_CANDIDATE_MARKED,
        BusinessEventType.NO_SHOW_CONFIRMED,
        BusinessEventType.NO_SHOW_REVOKED,
        BusinessEventType.RESCHEDULE_REQUESTED,
        BusinessEventType.RESCHEDULE_APPROVED,
        BusinessEventType.RESCHEDULE_REJECTED,
        BusinessEventType.NOTICE_PUBLISHED,
        BusinessEventType.NOTICE_DELIVERED,
        BusinessEventType.FUNNEL_MILESTONE_RECORDED,
        BusinessEventType.SITE_SHIFT_CREATED,
        BusinessEventType.SITE_SHIFT_CLOCKED_IN,
        BusinessEventType.SITE_SHIFT_COMPLETED,
        BusinessEventType.SITE_TASK_CREATED,
        BusinessEventType.SITE_TASK_COMPLETED,
        BusinessEventType.SITE_HANDOVER_SUBMITTED,
        BusinessEventType.SITE_INCIDENT_RECORDED,
        BusinessEventType.ASSET_STATUS_CHANGED,
        BusinessEventType.ASSET_INSPECTION_RECORDED,
        BusinessEventType.ASSET_REPAIR_RECORDED,
        BusinessEventType.ASSET_UNAVAILABLE_LINKED,
        BusinessEventType.REVIEW_SUBMITTED,
        BusinessEventType.REVIEW_MODERATED,
        BusinessEventType.COMPLAINT_SUBMITTED,
        BusinessEventType.COMPLAINT_TRIAGED,
        BusinessEventType.CUSTOMER_SERVICE_CASE_CREATED,
        BusinessEventType.CUSTOMER_SERVICE_CASE_ASSIGNED,
        BusinessEventType.CUSTOMER_SERVICE_CASE_STATUS_CHANGED,
        BusinessEventType.CUSTOMER_SERVICE_CASE_RESOLVED,
        BusinessEventType.CUSTOMER_SERVICE_CASE_CLOSED
    );

    private final BusinessEventMapper eventMapper;
    private final ObjectMapper objectMapper;

    public BusinessEventService(BusinessEventMapper eventMapper,
                                 ObjectMapper objectMapper) {
        this.eventMapper = eventMapper;
        this.objectMapper = objectMapper;
    }

    public BusinessEvent record(BusinessEventRecordRequest request) {
        validate(request);
        BusinessEvent existing = findExistingByIdempotency(request);
        if (existing != null) {
            return existing;
        }

        LocalDateTime now = LocalDateTime.now();
        CurrentUser currentUser = SecurityContext.currentUser();
        BusinessEvent event = new BusinessEvent();
        event.eventNo = nextEventNo(now);
        event.eventType = request.eventType.name();
        event.businessDomain = request.businessDomain.name();
        event.entityType = request.entityType;
        event.entityId = request.entityId;
        event.relatedEntityType = request.relatedEntityType;
        event.relatedEntityId = request.relatedEntityId;
        event.actorUserId = request.actorUserId != null ? request.actorUserId : currentUser == null ? null : currentUser.id();
        event.actorUsername = request.actorUsername != null ? request.actorUsername : currentUser == null ? null : currentUser.username();
        event.sourceType = request.sourceType.name();
        event.providerEnv = request.providerEnv == null ? null : request.providerEnv.name();
        event.providerName = blankToNull(request.providerName);
        event.externalRef = blankToNull(request.externalRef);
        event.idempotencyKey = blankToNull(request.idempotencyKey);
        event.confidence = request.confidence == null
            ? new BigDecimal("1.00")
            : request.confidence.setScale(2, RoundingMode.HALF_UP);
        event.reasonCode = blankToNull(request.reasonCode);
        event.reasonText = blankToNull(request.reasonText);
        event.fromStatus = blankToNull(request.fromStatus);
        event.toStatus = blankToNull(request.toStatus);
        event.resultStatus = request.resultStatus;
        event.occurredAt = request.occurredAt == null ? now : request.occurredAt;
        event.metadataJson = toJson(request.metadata);
        event.createTime = now;
        try {
            eventMapper.insert(event);
        } catch (DuplicateKeyException ex) {
            BusinessEvent concurrentExisting = findExistingByIdempotency(request);
            if (concurrentExisting != null) {
                return concurrentExisting;
            }
            throw ex;
        }
        return event;
    }

    public List<BusinessEvent> listForEntity(String entityType, Long entityId, int limit) {
        int cappedLimit = Math.max(1, Math.min(limit, 200));
        return eventMapper.selectList(new QueryWrapper<BusinessEvent>()
            .eq("entity_type", entityType)
            .eq("entity_id", entityId)
            .orderByDesc("occurred_at")
            .orderByDesc("id")
            .last("LIMIT " + cappedLimit));
    }

    private void validate(BusinessEventRecordRequest request) {
        if (request == null
            || request.eventType == null
            || request.businessDomain == null
            || request.entityType == null
            || request.entityType.isBlank()
            || request.entityId == null
            || request.sourceType == null
            || request.resultStatus == null
            || request.resultStatus.isBlank()) {
            throw BusinessException.badRequest("业务事件参数不完整");
        }
        if (!ALLOWED_EVENT_TYPES.contains(request.eventType)) {
            throw BusinessException.badRequest("当前阶段不支持该业务事件类型");
        }
    }

    private BusinessEvent findExistingByIdempotency(BusinessEventRecordRequest request) {
        String idempotencyKey = blankToNull(request.idempotencyKey);
        if (idempotencyKey == null) {
            return null;
        }

        QueryWrapper<BusinessEvent> wrapper = new QueryWrapper<BusinessEvent>()
            .eq("source_type", request.sourceType.name())
            .eq("idempotency_key", idempotencyKey);
        if (request.providerEnv == null) {
            wrapper.isNull("provider_env");
        } else {
            wrapper.eq("provider_env", request.providerEnv.name());
        }
        String providerName = blankToNull(request.providerName);
        if (providerName == null) {
            wrapper.isNull("provider_name");
        } else {
            wrapper.eq("provider_name", providerName);
        }
        return eventMapper.selectOne(wrapper.last("LIMIT 1"));
    }

    private String nextEventNo(LocalDateTime now) {
        return "BE"
            + now.format(EVENT_NO_TIME)
            + UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase();
    }

    private String toJson(Map<String, Object> metadata) {
        try {
            return objectMapper.writeValueAsString(metadata == null ? Map.of() : metadata);
        } catch (JsonProcessingException ex) {
            throw BusinessException.badRequest("业务事件元数据无法序列化");
        }
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
