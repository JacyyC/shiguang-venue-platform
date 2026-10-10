package com.example.venue.community.service;

import com.example.venue.community.entity.CommunityGovernanceEvent;
import com.example.venue.community.enums.CommunityActorScope;
import com.example.venue.community.enums.CommunitySourceType;
import com.example.venue.community.mapper.CommunityGovernanceEventMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

@Service
public class CommunityGovernanceEventService {
    private final CommunityGovernanceEventMapper mapper;
    private final ObjectMapper objectMapper;

    public CommunityGovernanceEventService(CommunityGovernanceEventMapper mapper, ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
    }

    public void record(EventCommand command) {
        if (command == null || command.actorScope == null || command.sourceType == null) {
            throw new IllegalArgumentException("治理事件主体范围和来源不能为空");
        }
        LocalDateTime occurredAt = command.occurredAt == null ? LocalDateTime.now() : command.occurredAt;
        CommunityGovernanceEvent event = new CommunityGovernanceEvent();
        event.eventNo = "CGE-" + UUID.randomUUID().toString().replace("-", "");
        event.eventType = command.eventType;
        event.subjectType = command.subjectType;
        event.subjectId = command.subjectId;
        event.reportId = command.reportId;
        event.actorUserId = command.actorUserId;
        event.actorScope = command.actorScope.name();
        event.sourceType = command.sourceType.name();
        event.fromStatus = command.fromStatus;
        event.toStatus = command.toStatus;
        event.reasonCode = command.reasonCode;
        event.reasonSummary = command.reasonSummary;
        event.resultStatus = command.resultStatus == null ? "SUCCEEDED" : command.resultStatus;
        event.idempotencyKey = eventIdempotencyKey(
            command.sourceType,
            command.eventType,
            command.subjectType,
            command.subjectId,
            command.actorUserId,
            command.idempotencyKey
        );
        event.metadataJson = toJson(command.metadata);
        event.occurredAt = occurredAt;
        event.retainUntil = occurredAt.plusDays(365);
        event.createTime = occurredAt;
        try {
            mapper.insert(event);
        } catch (DuplicateKeyException ignored) {
            // Replayed commands must not duplicate append-only evidence.
        }
    }

    public boolean isRecorded(CommunitySourceType sourceType, String eventType, String subjectType, Long subjectId,
                              Long actorUserId, String idempotencyKey) {
        String eventKey = eventIdempotencyKey(
            sourceType, eventType, subjectType, subjectId, actorUserId, idempotencyKey);
        return mapper.findRecorded(sourceType.name(), eventType, subjectType, subjectId, eventKey) != null;
    }

    public RecordedRequest findRecordedRequest(CommunitySourceType sourceType, String eventType, String subjectType,
                                               Long subjectId, Long actorUserId, String idempotencyKey) {
        String eventKey = eventIdempotencyKey(
            sourceType, eventType, subjectType, subjectId, actorUserId, idempotencyKey);
        CommunityGovernanceEvent event = mapper.findRecorded(
            sourceType.name(), eventType, subjectType, subjectId, eventKey);
        if (event == null) {
            return null;
        }
        try {
            JsonNode metadata = objectMapper.readTree(event.metadataJson == null ? "{}" : event.metadataJson);
            JsonNode requestHash = metadata.get("requestHash");
            return new RecordedRequest(true,
                requestHash == null || !requestHash.isTextual() ? null : requestHash.textValue(),
                longValue(metadata.get("baseVersion")),
                longValue(metadata.get("resultVersion")));
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("治理事件请求绑定无法解析", ex);
        }
    }

    private Long longValue(JsonNode value) {
        return value == null || !value.canConvertToLong() ? null : value.longValue();
    }

    private String toJson(Map<String, Object> metadata) {
        try {
            return objectMapper.writeValueAsString(metadata == null ? Map.of() : metadata);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("治理事件元数据无法序列化", ex);
        }
    }

    private String boundedIdempotencyKey(String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("治理事件幂等键不能为空");
        }
        if (key.length() <= 128) {
            return key;
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(key.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    private String eventIdempotencyKey(CommunitySourceType sourceType, String eventType, String subjectType,
                                       Long subjectId, Long actorUserId, String rawKey) {
        String key = boundedIdempotencyKey(rawKey);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            updateDigest(digest, sourceType.name());
            updateDigest(digest, eventType);
            updateDigest(digest, subjectType);
            updateDigest(digest, subjectId);
            updateDigest(digest, actorUserId);
            updateDigest(digest, key);
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    private void updateDigest(MessageDigest digest, Object value) {
        byte[] bytes = String.valueOf(value).getBytes(StandardCharsets.UTF_8);
        digest.update((byte) (bytes.length >>> 24));
        digest.update((byte) (bytes.length >>> 16));
        digest.update((byte) (bytes.length >>> 8));
        digest.update((byte) bytes.length);
        digest.update(bytes);
    }

    public static class EventCommand {
        public String eventType;
        public String subjectType;
        public Long subjectId;
        public Long reportId;
        public Long actorUserId;
        public CommunityActorScope actorScope;
        public CommunitySourceType sourceType;
        public String fromStatus;
        public String toStatus;
        public String reasonCode;
        public String reasonSummary;
        public String resultStatus;
        public String idempotencyKey;
        public Map<String, Object> metadata;
        public LocalDateTime occurredAt;
    }

    public record RecordedRequest(boolean recorded, String requestHash, Long baseVersion, Long resultVersion) {
        public RecordedRequest(boolean recorded, String requestHash) {
            this(recorded, requestHash, null, null);
        }
    }
}
