package com.example.venue.community.activity.service;

import com.example.venue.common.BusinessException;
import com.example.venue.common.CurrentUser;
import com.example.venue.community.activity.dto.ActivityResponses;
import com.example.venue.community.activity.entity.CommunityActivity;
import com.example.venue.community.activity.entity.CommunityCompanionRequest;
import com.example.venue.community.activity.entity.CommunityMatchDecision;
import com.example.venue.community.activity.mapper.CommunityActivityMapper;
import com.example.venue.community.activity.mapper.CommunityCompanionRequestMapper;
import com.example.venue.community.activity.mapper.CommunityMatchDecisionMapper;
import com.example.venue.community.service.CommunityAccessService;
import com.example.venue.community.service.CommunityTextPolicy;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Service
public class CompanionMatchingService {
    public static final String RULE_VERSION = "COMPANION_MATCH_V1";
    private static final int EVALUATION_LIMIT = 500;
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
    };
    private static final Set<String> PUBLIC_MATCH_REASONS = Set.of(
        "ACTIVITY_TYPE_MATCH", "TIME_WINDOW_OVERLAP", "LOCATION_EXACT_SPACE",
        "LOCATION_SAME_AREA", "CAPACITY_AVAILABLE", "SAME_LINKED_ACTIVITY");

    private final CommunityCompanionRequestMapper requestMapper;
    private final CommunityActivityMapper activityMapper;
    private final CommunityMatchDecisionMapper decisionMapper;
    private final CommunityAccessService accessService;
    private final CommunityTextPolicy textPolicy;
    private final ActivityCommandService commandService;
    private final ActivityStateEventService eventService;
    private final ObjectMapper objectMapper;

    public CompanionMatchingService(CommunityCompanionRequestMapper requestMapper,
                                    CommunityActivityMapper activityMapper,
                                    CommunityMatchDecisionMapper decisionMapper,
                                    CommunityAccessService accessService,
                                    CommunityTextPolicy textPolicy,
                                    ActivityCommandService commandService,
                                    ActivityStateEventService eventService,
                                    ObjectMapper objectMapper) {
        this.requestMapper = requestMapper;
        this.activityMapper = activityMapper;
        this.decisionMapper = decisionMapper;
        this.accessService = accessService;
        this.textPolicy = textPolicy;
        this.commandService = commandService;
        this.eventService = eventService;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public ActivityResponses.MatchPage refresh(Long sourceRequestId, int requestedLimit, String rawKey) {
        CurrentUser actor = accessService.requireCurrentUser();
        CommunityCompanionRequest source = requestMapper.selectByIdForUpdate(sourceRequestId);
        if (source == null || !actor.id().equals(source.ownerUserId)) {
            throw accessService.hiddenResource();
        }
        accessService.requireActivityContext(source.groupId, source.referencePostId, actor.id());
        requireLinkedActivityVisible(source, actor.id());
        if (!"PUBLISHED".equals(source.status) || !source.windowEnd.isAfter(LocalDateTime.now())) {
            throw BusinessException.conflict("找搭子需求当前状态不能刷新匹配");
        }
        int limit = requestedLimit <= 0 ? 20 : Math.min(requestedLimit, 50);
        ActivityCommandService.Claim claim = commandService.claim(actor.id(), "COMPANION_MATCH_REFRESH", rawKey,
            textPolicy.fingerprint(source.id, source.versionNo, RULE_VERSION, limit));
        if (!claim.replayed()) {
            List<CommunityCompanionRequest> candidates = requestMapper.discover(
                actor.id(), source.id, null, source.activityType, source.windowStart, source.windowEnd,
                source.venueId, source.spaceId, source.areaCode, null,
                LocalDateTime.now(), EVALUATION_LIMIT);
            for (CommunityCompanionRequest candidate : candidates) {
                persistDecision(source, candidate, evaluate(source, candidate));
            }
            eventService.record("MATCH_REFRESHED", "COMPANION_REQUEST", source.id, actor.id(), "USER",
                source.status, source.status, null, claim.command().idempotencyKey,
                Map.of("ruleVersion", RULE_VERSION, "candidateCount", candidates.size(),
                    "sourceVersion", source.versionNo));
            commandService.complete(claim, "COMPANION_REQUEST", source.id, source.status);
        }
        return page(source, limit);
    }

    @Transactional(readOnly = true)
    public ActivityResponses.MatchPage matches(Long sourceRequestId, int requestedLimit) {
        CurrentUser actor = accessService.requireCurrentUser();
        CommunityCompanionRequest source = requestMapper.selectById(sourceRequestId);
        if (source == null || !actor.id().equals(source.ownerUserId)) {
            throw accessService.hiddenResource();
        }
        accessService.requireActivityContext(source.groupId, source.referencePostId, actor.id());
        requireLinkedActivityVisible(source, actor.id());
        return page(source, requestedLimit <= 0 ? 20 : Math.min(requestedLimit, 50));
    }

    public MatchEvaluation evaluate(CommunityCompanionRequest source,
                                    CommunityCompanionRequest candidate) {
        List<String> reasons = new ArrayList<>();
        Map<String, Object> evidence = new LinkedHashMap<>();
        int score = 0;
        if (Objects.equals(source.ownerUserId, candidate.ownerUserId)) {
            return MatchEvaluation.excluded("SAME_OWNER");
        }
        if (!Objects.equals(source.activityType, candidate.activityType)) {
            return MatchEvaluation.excluded("ACTIVITY_TYPE_MISMATCH");
        }
        reasons.add("ACTIVITY_TYPE_MATCH");
        score += 30;

        LocalDateTime overlapStart = source.windowStart.isAfter(candidate.windowStart)
            ? source.windowStart : candidate.windowStart;
        LocalDateTime overlapEnd = source.windowEnd.isBefore(candidate.windowEnd)
            ? source.windowEnd : candidate.windowEnd;
        long overlapMinutes = Duration.between(overlapStart, overlapEnd).toMinutes();
        if (overlapMinutes <= 0) {
            return MatchEvaluation.excluded("TIME_WINDOW_NO_OVERLAP");
        }
        reasons.add("TIME_WINDOW_OVERLAP");
        score += 30 + (int) Math.min(10, overlapMinutes / 60);
        evidence.put("overlapMinutes", overlapMinutes);

        String locationMatch;
        if ("VENUE_SPACE".equals(source.locationType) && "VENUE_SPACE".equals(candidate.locationType)
            && Objects.equals(source.venueId, candidate.venueId)
            && Objects.equals(source.spaceId, candidate.spaceId)) {
            locationMatch = "EXACT_SPACE";
            score += 30;
        } else if ("CAMPUS_AREA".equals(source.locationType) && "CAMPUS_AREA".equals(candidate.locationType)
            && Objects.equals(source.areaCode, candidate.areaCode)) {
            locationMatch = "SAME_AREA";
            score += 20;
        } else {
            return MatchEvaluation.excluded("LOCATION_MISMATCH");
        }
        reasons.add("LOCATION_" + locationMatch);
        evidence.put("locationMatch", locationMatch);

        int availableSlots = candidate.capacityTotal - candidate.participantCount;
        if (availableSlots <= 0 || !"PUBLISHED".equals(candidate.status)) {
            return MatchEvaluation.excluded("CAPACITY_UNAVAILABLE");
        }
        reasons.add("CAPACITY_AVAILABLE");
        evidence.put("availableSlots", availableSlots);

        boolean linkedActivityMatch = source.activityId != null
            && source.activityId.equals(candidate.activityId);
        if (linkedActivityMatch) {
            reasons.add("SAME_LINKED_ACTIVITY");
            score += 10;
        }
        evidence.put("sameLinkedActivity", linkedActivityMatch);
        return new MatchEvaluation("MATCHED", Math.min(100, score), List.copyOf(reasons), Map.copyOf(evidence));
    }

    private void persistDecision(CommunityCompanionRequest source,
                                 CommunityCompanionRequest candidate,
                                 MatchEvaluation evaluation) {
        CommunityMatchDecision existing = decisionMapper.findReplay(
            source.id, candidate.id, source.versionNo, candidate.versionNo, RULE_VERSION);
        if (existing != null) {
            return;
        }
        CommunityMatchDecision decision = new CommunityMatchDecision();
        decision.decisionNo = "CMD-" + UUID.randomUUID().toString().replace("-", "");
        decision.sourceRequestId = source.id;
        decision.candidateRequestId = candidate.id;
        decision.sourceVersion = source.versionNo;
        decision.candidateVersion = candidate.versionNo;
        decision.ruleVersion = RULE_VERSION;
        decision.outcome = evaluation.outcome();
        decision.score = evaluation.score();
        decision.reasonCodesJson = json(evaluation.reasonCodes());
        decision.evidenceJson = json(evaluation.evidence());
        decision.decidedAt = LocalDateTime.now();
        decision.createTime = decision.decidedAt;
        try {
            decisionMapper.insert(decision);
        } catch (DuplicateKeyException ignored) {
            // Same normalized versions and rule produce the same deterministic decision.
        }
    }

    private ActivityResponses.MatchPage page(CommunityCompanionRequest source, int limit) {
        List<CommunityMatchDecision> decisions = decisionMapper.listMatches(
            source.id, source.versionNo, RULE_VERSION, EVALUATION_LIMIT);
        List<ActivityResponses.Match> matches = new ArrayList<>();
        for (CommunityMatchDecision decision : decisions) {
            if (matches.size() >= limit) {
                break;
            }
            CommunityCompanionRequest candidate = requestMapper.selectById(decision.candidateRequestId);
            if (candidate == null || candidate.versionNo == null
                || !candidate.versionNo.equals(decision.candidateVersion)
                || !"PUBLISHED".equals(candidate.status)
                || accessService.isBlocked(source.ownerUserId, candidate.ownerUserId)) {
                continue;
            }
            try {
                accessService.requireActivityContext(
                    candidate.groupId, candidate.referencePostId, source.ownerUserId);
                requireLinkedActivityVisible(candidate, source.ownerUserId);
            } catch (BusinessException hidden) {
                continue;
            }
            matches.add(ActivityResponses.match(decision, candidate, publicReasonCodes(decision.reasonCodesJson)));
        }
        matches.sort(Comparator.comparingInt((ActivityResponses.Match item) -> item.score).reversed()
            .thenComparingLong(item -> item.candidate.id));
        return new ActivityResponses.MatchPage(List.copyOf(matches), RULE_VERSION);
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("匹配最小证据无法序列化", exception);
        }
    }

    private List<String> publicReasonCodes(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, STRING_LIST).stream()
                .filter(PUBLIC_MATCH_REASONS::contains)
                .toList();
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("匹配原因证据无法读取", exception);
        }
    }

    private void requireLinkedActivityVisible(CommunityCompanionRequest request, Long viewerUserId) {
        if (request.activityId == null) {
            return;
        }
        CommunityActivity activity = activityMapper.selectById(request.activityId);
        if (activity == null || !Set.of("PUBLISHED", "FULL").contains(activity.status)
            || !request.groupId.equals(activity.groupId) || !activity.endTime.isAfter(LocalDateTime.now())) {
            throw accessService.hiddenResource();
        }
        accessService.requireActivityContext(activity.groupId, activity.referencePostId, viewerUserId);
        accessService.requireNoBlock(viewerUserId, activity.organizerUserId);
    }

    public record MatchEvaluation(String outcome,
                                  int score,
                                  List<String> reasonCodes,
                                  Map<String, Object> evidence) {
        private static MatchEvaluation excluded(String reasonCode) {
            return new MatchEvaluation("EXCLUDED", 0, List.of(reasonCode),
                Map.of("exclusionCode", reasonCode));
        }
    }
}
