package com.example.venue.advertising.dto;

import com.example.venue.advertising.entity.AdvertisingPrivacyPreference;
import com.example.venue.advertising.entity.AdvertisingDecision;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;

public final class AdvertisingDecisionResponses {
    private AdvertisingDecisionResponses() {
    }

    public record PrivacyPreferenceResponse(
        boolean commercialEnabled,
        boolean measurementEnabled,
        String policyVersion,
        LocalDateTime changedAt
    ) {
        public static PrivacyPreferenceResponse from(AdvertisingPrivacyPreference preference,
                                                     String policyVersion) {
            if (preference == null) {
                return new PrivacyPreferenceResponse(false, false, policyVersion, null);
            }
            return new PrivacyPreferenceResponse(
                Integer.valueOf(1).equals(preference.commercialEnabled),
                Integer.valueOf(1).equals(preference.measurementEnabled),
                preference.policyVersion == null ? policyVersion : preference.policyVersion,
                preference.changedAt
            );
        }
    }

    public record HomeCommercialDecision(
        Long decisionId,
        Long creativeVersionId,
        String token,
        boolean reused,
        String adLabel,
        String accessibleAdLabel,
        String headline,
        String bodyText,
        String callToAction,
        String targetType,
        Long targetId,
        String deepLink,
        String policyVersion,
        LocalDateTime expiresAt
    ) {
        public static HomeCommercialDecision fromDecision(AdvertisingDecision decision,
                                                          String token,
                                                          boolean reused,
                                                          ObjectMapper mapper) {
            JsonNode snapshot = parse(decision.creativeSnapshotJson, mapper);
            return new HomeCommercialDecision(
                decision.id,
                decision.creativeVersionId,
                token,
                reused,
                "广告",
                "广告内容",
                text(snapshot, "headline"),
                text(snapshot, "bodyText"),
                text(snapshot, "callToAction"),
                text(snapshot, "targetType"),
                longValue(snapshot, "targetId"),
                text(snapshot, "deepLink"),
                decision.policyVersion,
                decision.expiresAt
            );
        }

        private static JsonNode parse(String json, ObjectMapper mapper) {
            if (json == null || json.isBlank()) {
                return mapper.createObjectNode();
            }
            try {
                return mapper.readTree(json);
            } catch (Exception exception) {
                return mapper.createObjectNode();
            }
        }

        private static String text(JsonNode node, String field) {
            JsonNode value = node.get(field);
            return value == null || value.isNull() ? "" : value.asText();
        }

        private static Long longValue(JsonNode node, String field) {
            JsonNode value = node.get(field);
            return value == null || value.isNull() || !value.canConvertToLong() ? null : value.longValue();
        }
    }

    public record RecordEventResult(
        String resultStatus,
        String rejectionCode,
        String eventType,
        boolean replayed
    ) {
        public static RecordEventResult accepted(String eventType) {
            return new RecordEventResult("ACCEPTED", null, eventType, false);
        }

        public static RecordEventResult replayed(String eventType) {
            return new RecordEventResult("ACCEPTED", null, eventType, true);
        }

        public static RecordEventResult rejected(String eventType, String code) {
            return new RecordEventResult("REJECTED", code, eventType, false);
        }
    }
}
