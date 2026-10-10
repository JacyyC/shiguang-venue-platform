package com.example.venue.placement.dto;

import com.example.venue.placement.entity.PlacementCandidateVersion;
import com.example.venue.placement.entity.PlacementDecision;
import com.example.venue.placement.entity.PlacementEvent;
import com.example.venue.placement.entity.PlacementSlot;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;

/**
 * 宣传位对外窄 DTO。用户端决策只暴露复核所需白名单字段与决策令牌原值；
 * 管理端审计只暴露有界列表与聚合，不含令牌原值或完整来源正文。
 */
public final class PlacementResponses {

    private PlacementResponses() {
    }

    public record HomePrimaryDecision(
        Long decisionId,
        Long candidateVersionId,
        String token,
        boolean reused,
        String title,
        String summary,
        String imageUrl,
        String deepLink,
        LocalDateTime expiresAt
    ) {
        public static HomePrimaryDecision reused(PlacementDecision decision, String rawToken,
                                                 ObjectMapper mapper) {
            SourceSnapshotView source = SourceSnapshotView.parse(decision.sourceSnapshotJson, mapper);
            return new HomePrimaryDecision(
                decision.id, decision.candidateVersionId, rawToken, true,
                source.title, source.summary, source.imageUrl, source.deepLink, decision.expiresAt
            );
        }

        public static HomePrimaryDecision issued(PlacementDecision decision, String rawToken,
                                                 ObjectMapper mapper) {
            SourceSnapshotView source = SourceSnapshotView.parse(decision.sourceSnapshotJson, mapper);
            return new HomePrimaryDecision(
                decision.id, decision.candidateVersionId, rawToken, false,
                source.title, source.summary, source.imageUrl, source.deepLink, decision.expiresAt
            );
        }
    }

    public record RecordEventResult(String resultStatus, String rejectionCode, String eventType) {
        public static RecordEventResult accepted(String eventType) {
            return new RecordEventResult("ACCEPTED", null, eventType);
        }

        public static RecordEventResult replayed(String eventType) {
            return new RecordEventResult("REPLAYED", null, eventType);
        }

        public static RecordEventResult rejected(String eventType, String code) {
            return new RecordEventResult("REJECTED", code, eventType);
        }
    }

    public record SlotResponse(Long id, String slotCode, String slotName, String status) {
        public static SlotResponse from(PlacementSlot slot) {
            return new SlotResponse(slot.id, slot.slotCode, slot.slotName, slot.status);
        }
    }

    public record CandidateVersionResponse(
        Long id, Long slotId, String candidateNo, String sourceType, Long sourceId,
        String sourceVersion, LocalDateTime effectiveFrom, LocalDateTime effectiveUntil,
        String audienceType, String audienceLevelCode, Integer priority,
        Integer frequencyCapPerUser, Integer frequencyCapWindowHours, String ruleVersion,
        String status, LocalDateTime publishedAt
    ) {
        public static CandidateVersionResponse from(PlacementCandidateVersion c) {
            return new CandidateVersionResponse(
                c.id, c.slotId, c.candidateNo, c.sourceType, c.sourceId, c.sourceVersion,
                c.effectiveFrom, c.effectiveUntil, c.audienceType, c.audienceLevelCode,
                c.priority, c.frequencyCapPerUser, c.frequencyCapWindowHours, c.ruleVersion,
                c.status, c.publishedAt
            );
        }
    }

    public record SourcePreviewResponse(
        String sourceType, Long sourceId, String sourceVersion, String title,
        String summary, String imageUrl, String deepLink,
        LocalDateTime sourceEffectiveFrom, LocalDateTime sourceEffectiveUntil
    ) {
    }

    public record DecisionAuditResponse(
        Long id, String decisionNo, Long userId, Long slotId, Long candidateVersionId,
        String status, LocalDateTime expiresAt, LocalDateTime consumedAt,
        LocalDateTime createTime, SourceSnapshotView sourceSnapshot
    ) {
        public static DecisionAuditResponse from(PlacementDecision d, ObjectMapper mapper) {
            return new DecisionAuditResponse(
                d.id, d.decisionNo, d.userId, d.slotId, d.candidateVersionId,
                d.status, d.expiresAt, d.consumedAt, d.createTime,
                SourceSnapshotView.parse(d.sourceSnapshotJson, mapper)
            );
        }
    }

    public record EventAuditResponse(
        Long id, String eventNo, String eventType, Long decisionId, Long userId, Long slotId,
        Long candidateVersionId, String resultStatus, String rejectionCode,
        java.math.BigDecimal visibleRatio, Integer visibleDurationMs, LocalDateTime occurredAt
    ) {
        public static EventAuditResponse from(PlacementEvent e) {
            return new EventAuditResponse(
                e.id, e.eventNo, e.eventType, e.decisionId, e.userId, e.slotId,
                e.candidateVersionId, e.resultStatus, e.rejectionCode,
                e.visibleRatio, e.visibleDurationMs, e.occurredAt
            );
        }
    }

    /**
     * 来源白名单快照视图。仅解析复核所需字段，令牌原值永不进入此视图。
     */
    public record SourceSnapshotView(
        String sourceType, Long sourceId, String sourceVersion, String title,
        String summary, String imageUrl, String deepLink
    ) {
        public static SourceSnapshotView parse(String json, ObjectMapper mapper) {
            if (json == null || json.isBlank()) {
                return new SourceSnapshotView(null, null, null, "", "", null, "");
            }
            try {
                JsonNode node = mapper.readTree(json);
                return new SourceSnapshotView(
                    text(node, "sourceType"),
                    longValue(node, "sourceId"),
                    text(node, "sourceVersion"),
                    text(node, "title"),
                    text(node, "summary"),
                    text(node, "imageUrl"),
                    text(node, "deepLink")
                );
            } catch (Exception exception) {
                return new SourceSnapshotView(null, null, null, "", "", null, "");
            }
        }

        private static String text(JsonNode node, String field) {
            JsonNode value = node.get(field);
            return value == null || value.isNull() ? null : value.asText();
        }

        private static Long longValue(JsonNode node, String field) {
            JsonNode value = node.get(field);
            return value == null || value.isNull() || !value.canConvertToLong() ? null : value.longValue();
        }
    }
}
