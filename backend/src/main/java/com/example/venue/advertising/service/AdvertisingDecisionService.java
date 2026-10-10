package com.example.venue.advertising.service;

import com.example.venue.advertising.dto.AdvertisingDecisionCandidate;
import com.example.venue.advertising.dto.AdvertisingDecisionResponses;
import com.example.venue.advertising.entity.AdvertisingDecision;
import com.example.venue.advertising.mapper.AdvertisingDecisionGuardMapper;
import com.example.venue.advertising.mapper.AdvertisingDecisionMapper;
import com.example.venue.advertising.mapper.AdvertisingEventMapper;
import com.example.venue.common.BusinessException;
import com.example.venue.common.ResultCode;
import com.example.venue.placement.contract.OwnedPlacementDecisionReader;
import com.example.venue.security.SecurityContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * 商业广告仲裁服务。只有 R6 明确 EMPTY 时才读取商业候选；MySQL guard 和事件计数负责最终频控，
 * Redis 不参与本阶段最终放行。广告决策使用独立 HOME_COMMERCIAL 事实，不写 placement/funnel。
 */
@Service
public class AdvertisingDecisionService {
    public static final String HOME_COMMERCIAL_CODE = "HOME_COMMERCIAL";
    public static final String RULE_VERSION = "ADVERTISING_RULE_V1";
    public static final int WINDOW_HOURS = 24;
    public static final int USER_SLOT_CAP = 3;
    public static final int USER_CREATIVE_CAP = 1;

    private static final int MAX_CANDIDATES = 100;
    private static final DateTimeFormatter DECISION_NO_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final Set<String> ALLOWED_CTA = Set.of("VIEW", "BOOK", "LEARN_MORE");

    private final AdvertisingDecisionMapper decisionMapper;
    private final AdvertisingDecisionGuardMapper guardMapper;
    private final AdvertisingEventMapper eventMapper;
    private final AdvertisingPrivacyPreferenceService privacyService;
    private final AdvertisingTokenService tokenService;
    private final OwnedPlacementDecisionReader ownedPlacementReader;
    private final ObjectMapper objectMapper;

    public AdvertisingDecisionService(AdvertisingDecisionMapper decisionMapper,
                                      AdvertisingDecisionGuardMapper guardMapper,
                                      AdvertisingEventMapper eventMapper,
                                      AdvertisingPrivacyPreferenceService privacyService,
                                      AdvertisingTokenService tokenService,
                                      OwnedPlacementDecisionReader ownedPlacementReader,
                                      ObjectMapper objectMapper) {
        this.decisionMapper = Objects.requireNonNull(decisionMapper, "decisionMapper");
        this.guardMapper = Objects.requireNonNull(guardMapper, "guardMapper");
        this.eventMapper = Objects.requireNonNull(eventMapper, "eventMapper");
        this.privacyService = Objects.requireNonNull(privacyService, "privacyService");
        this.tokenService = Objects.requireNonNull(tokenService, "tokenService");
        this.ownedPlacementReader = Objects.requireNonNull(ownedPlacementReader, "ownedPlacementReader");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    @Transactional
    public AdvertisingDecisionResponses.HomeCommercialDecision decideHomeCommercial() {
        Long userId = requireCurrentUserId();
        if (!privacyService.isCommercialMeasurementEnabled(userId)) {
            return null;
        }
        if (!privacyService.lockForAdvertisingFact(userId)) {
            return null;
        }

        OwnedPlacementDecisionReader.ArbitrationResult r6Result;
        try {
            r6Result = ownedPlacementReader.readHomePrimary();
        } catch (RuntimeException exception) {
            return null;
        }
        if (r6Result == null || r6Result.status() != OwnedPlacementDecisionReader.Status.EMPTY) {
            return null;
        }

        LocalDateTime now = LocalDateTime.now();
        List<AdvertisingDecisionCandidate> candidates = decisionMapper.findPublishedCandidates(now, MAX_CANDIDATES);
        if (candidates == null || candidates.isEmpty()) {
            return null;
        }
        for (AdvertisingDecisionCandidate candidate : candidates) {
            if (!isSafeCandidate(candidate, now)) {
                continue;
            }
            AdvertisingDecisionResponses.HomeCommercialDecision result = issueOrReuse(userId, candidate, now);
            if (result != null) {
                return result;
            }
        }
        return null;
    }

    private AdvertisingDecisionResponses.HomeCommercialDecision issueOrReuse(
        Long userId, AdvertisingDecisionCandidate candidate, LocalDateTime now
    ) {
        lockScope(userId, "SLOT");
        lockScope(userId, creativeScope(candidate.creativeVersionId));

        // Serialize the final advertising lifecycle check with pause/retire operations.
        // A candidate read before the guard is only a hint and must not authorize a write.
        AdvertisingDecisionCandidate currentCandidate = decisionMapper.findPublishedCandidateForUpdate(
            candidate.creativeVersionId, now);
        if (!isSafeCandidate(currentCandidate, now) || !isExplicitR6Empty()) {
            return null;
        }

        AdvertisingDecision reusable = decisionMapper.findReusableIssuedForUpdate(
            userId, HOME_COMMERCIAL_CODE, currentCandidate.creativeVersionId, now);
        if (reusable != null) {
            AdvertisingTokenService.GeneratedToken token = tokenService.generate();
            if (decisionMapper.rotateIssuedTokenHash(reusable.id, token.hash(), now) != 1) {
                throw new IllegalStateException("ADVERTISING_DECISION_TOKEN_ROTATION_FAILED");
            }
            return AdvertisingDecisionResponses.HomeCommercialDecision.fromDecision(
                reusable, token.raw(), true, objectMapper);
        }

        LocalDateTime windowStart = now.minusHours(WINDOW_HOURS);
        int userSlotAccepted = eventMapper.countAcceptedViewableByUserSlot(
            userId, HOME_COMMERCIAL_CODE, windowStart);
        if (userSlotAccepted >= USER_SLOT_CAP) {
            return null;
        }
        int creativeAccepted = eventMapper.countAcceptedViewableByUserCreative(
            userId, currentCandidate.creativeVersionId, windowStart);
        if (creativeAccepted >= USER_CREATIVE_CAP) {
            return null;
        }

        AdvertisingTokenService.GeneratedToken token = tokenService.generate();
        AdvertisingDecision decision = buildDecision(userId, currentCandidate, token, now);
        if (decisionMapper.insert(decision) != 1) {
            throw new IllegalStateException("ADVERTISING_DECISION_INSERT_NOT_PERSISTED");
        }
        return AdvertisingDecisionResponses.HomeCommercialDecision.fromDecision(
            decision, token.raw(), false, objectMapper);
    }

    private void lockScope(Long userId, String scopeKey) {
        if (guardMapper.findScope(userId, HOME_COMMERCIAL_CODE, scopeKey) == null) {
            try {
                guardMapper.insertScope(userId, HOME_COMMERCIAL_CODE, scopeKey);
            } catch (DuplicateKeyException ignored) {
                // Another transaction created the guard; the following FOR UPDATE is authoritative.
            }
        }
        if (guardMapper.lockScope(userId, HOME_COMMERCIAL_CODE, scopeKey) == null) {
            throw new IllegalStateException("ADVERTISING_GUARD_NOT_LOCKED");
        }
    }

    private AdvertisingDecision buildDecision(Long userId,
                                              AdvertisingDecisionCandidate candidate,
                                              AdvertisingTokenService.GeneratedToken token,
                                              LocalDateTime now) {
        AdvertisingDecision decision = new AdvertisingDecision();
        decision.decisionNo = nextDecisionNo(now);
        decision.userId = userId;
        decision.slotCode = HOME_COMMERCIAL_CODE;
        decision.advertiserId = candidate.advertiserId;
        decision.campaignId = candidate.campaignId;
        decision.creativeVersionId = candidate.creativeVersionId;
        decision.tokenHash = token.hash();
        decision.policyVersion = AdvertisingPrivacyPreferenceService.POLICY_VERSION;
        decision.ruleVersionSnapshot = RULE_VERSION;
        decision.privacySnapshotJson = json(new LinkedHashMap<>(java.util.Map.of(
            "commercialEnabled", true,
            "measurementEnabled", true,
            "policyVersion", AdvertisingPrivacyPreferenceService.POLICY_VERSION
        )));
        decision.frequencySnapshotJson = json(new LinkedHashMap<>(java.util.Map.of(
            "windowHours", WINDOW_HOURS,
            "slotCapPerUser", USER_SLOT_CAP,
            "creativeCapPerUser", USER_CREATIVE_CAP
        )));
        LinkedHashMap<String, Object> creative = new LinkedHashMap<>();
        creative.put("headline", candidate.headline.trim());
        creative.put("bodyText", candidate.bodyText.trim());
        creative.put("callToAction", candidate.callToAction);
        creative.put("targetType", candidate.targetType);
        creative.put("targetId", candidate.targetId);
        creative.put("deepLink", deepLink(candidate.targetType, candidate.targetId));
        creative.put("adLabel", "广告");
        creative.put("accessibleAdLabel", "广告内容");
        decision.creativeSnapshotJson = json(creative);
        decision.arbitrationSnapshotJson = json(new LinkedHashMap<>(java.util.Map.of(
            "r6HomePrimary", "EMPTY",
            "slotCode", HOME_COMMERCIAL_CODE,
            "ruleVersion", RULE_VERSION
        )));
        decision.expiresAt = tokenService.earliestExpiry(now, candidate.campaignEffectiveUntil);
        decision.status = "ISSUED";
        decision.createTime = now;
        return decision;
    }

    private boolean isSafeCandidate(AdvertisingDecisionCandidate candidate, LocalDateTime now) {
        if (candidate == null || candidate.advertiserId == null || candidate.campaignId == null
            || candidate.creativeVersionId == null || candidate.campaignEffectiveUntil == null
            || candidate.campaignEffectiveFrom == null || now.isBefore(candidate.campaignEffectiveFrom)
            || !now.isBefore(candidate.campaignEffectiveUntil)
            || candidate.headline == null || candidate.headline.isBlank()
            || candidate.bodyText == null || candidate.bodyText.isBlank()
            || !ALLOWED_CTA.contains(candidate.callToAction)) {
            return false;
        }
        if ("VENUE_LIST".equals(candidate.targetType)) {
            return candidate.targetId == null;
        }
        return ("VENUE_DETAIL".equals(candidate.targetType) || "ACTIVITY_DETAIL".equals(candidate.targetType))
            && candidate.targetId != null && candidate.targetId > 0;
    }

    private String deepLink(String targetType, Long targetId) {
        return switch (targetType) {
            case "VENUE_LIST" -> "/venues";
            case "VENUE_DETAIL" -> "/venues/" + targetId;
            case "ACTIVITY_DETAIL" -> "/activities/" + targetId;
            default -> throw new IllegalArgumentException("unsupported advertising target");
        };
    }

    private String creativeScope(Long creativeVersionId) {
        return "CREATIVE:" + creativeVersionId;
    }

    private boolean isExplicitR6Empty() {
        try {
            OwnedPlacementDecisionReader.ArbitrationResult result = ownedPlacementReader.readHomePrimary();
            return result != null && result.status() == OwnedPlacementDecisionReader.Status.EMPTY;
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private String nextDecisionNo(LocalDateTime now) {
        return "ADD-" + now.format(DECISION_NO_TIME)
            + UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase(Locale.ROOT);
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("advertising snapshot serialization failed", exception);
        }
    }

    private Long requireCurrentUserId() {
        Long userId = SecurityContext.currentUserId();
        if (userId == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED, "Authentication is required");
        }
        return userId;
    }
}
