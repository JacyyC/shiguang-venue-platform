package com.example.venue.community.activity.service;

import com.example.venue.community.activity.entity.CommunityActivityStateEvent;
import com.example.venue.community.activity.mapper.CommunityActivityStateEventMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

@Service
public class ActivityStateEventService {
    private final CommunityActivityStateEventMapper mapper;
    private final ObjectMapper objectMapper;

    public ActivityStateEventService(CommunityActivityStateEventMapper mapper, ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
    }

    public void record(String eventType,
                       String subjectType,
                       Long subjectId,
                       Long actorUserId,
                       String sourceType,
                       String fromStatus,
                       String toStatus,
                       String reasonCode,
                       String idempotencyKey,
                       Map<String, Object> metadata) {
        CommunityActivityStateEvent event = new CommunityActivityStateEvent();
        event.eventNo = "CAE-" + UUID.randomUUID().toString().replace("-", "");
        event.eventType = eventType;
        event.subjectType = subjectType;
        event.subjectId = subjectId;
        event.actorUserId = actorUserId;
        event.sourceType = sourceType;
        event.fromStatus = fromStatus;
        event.toStatus = toStatus;
        event.reasonCode = bounded(reasonCode, 64);
        event.idempotencyKey = bounded(idempotencyKey, 128);
        event.metadataJson = json(metadata);
        event.occurredAt = LocalDateTime.now();
        event.createTime = event.occurredAt;
        try {
            mapper.insert(event);
        } catch (DuplicateKeyException ignored) {
            // Stable state keys make propagation and command replay append exactly once.
        }
    }

    private String json(Map<String, Object> metadata) {
        try {
            return objectMapper.writeValueAsString(metadata == null ? Map.of() : metadata);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("活动状态事件元数据无法序列化", exception);
        }
    }

    private String bounded(String value, int limit) {
        if (value == null) {
            return null;
        }
        return value.length() <= limit ? value : value.substring(0, limit);
    }
}
