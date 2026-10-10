package com.example.venue.advertising.service;

import com.example.venue.advertising.dto.AdvertisingDecisionRequests;
import com.example.venue.advertising.dto.AdvertisingDecisionResponses;
import com.example.venue.advertising.entity.AdvertisingDecision;
import com.example.venue.advertising.entity.AdvertisingEvent;
import com.example.venue.advertising.mapper.AdvertisingDecisionGuardMapper;
import com.example.venue.advertising.mapper.AdvertisingDecisionMapper;
import com.example.venue.advertising.mapper.AdvertisingEventMapper;
import com.example.venue.common.BusinessException;
import com.example.venue.common.ResultCode;
import com.example.venue.security.SecurityContext;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * 商业广告事件服务。事件只接受服务端令牌绑定的当前用户/版位/创意；CLICK 可先于曝光，
 * CLIENT_VIEWABLE 需达到 50%/1000ms，幂等和频控在 MySQL 事务内最终裁决。
 */
@Service
public class AdvertisingEventService {
    public static final String CLIENT_VIEWABLE = "CLIENT_VIEWABLE";
    public static final String CLICK = "CLICK";

    private static final Set<String> ALLOWED_TYPES = Set.of(CLIENT_VIEWABLE, CLICK);
    private static final BigDecimal VISIBILITY_RATIO_THRESHOLD = new BigDecimal("0.5000");
    private static final int VISIBILITY_DURATION_THRESHOLD_MS = 1000;
    private static final int MAX_IDEMPOTENCY_KEY_LENGTH = 160;
    private static final int MAX_TOKEN_LENGTH = 512;
    private static final DateTimeFormatter EVENT_NO_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final AdvertisingDecisionMapper decisionMapper;
    private final AdvertisingEventMapper eventMapper;
    private final AdvertisingDecisionGuardMapper guardMapper;
    private final AdvertisingPrivacyPreferenceService privacyService;
    private final AdvertisingTokenService tokenService;
    private final AdvertisingFrequencyHint frequencyHint;

    public AdvertisingEventService(AdvertisingDecisionMapper decisionMapper,
                                   AdvertisingEventMapper eventMapper,
                                   AdvertisingDecisionGuardMapper guardMapper,
                                   AdvertisingPrivacyPreferenceService privacyService,
                                   AdvertisingTokenService tokenService,
                                   AdvertisingFrequencyHint frequencyHint) {
        this.decisionMapper = Objects.requireNonNull(decisionMapper, "decisionMapper");
        this.eventMapper = Objects.requireNonNull(eventMapper, "eventMapper");
        this.guardMapper = Objects.requireNonNull(guardMapper, "guardMapper");
        this.privacyService = Objects.requireNonNull(privacyService, "privacyService");
        this.tokenService = Objects.requireNonNull(tokenService, "tokenService");
        this.frequencyHint = Objects.requireNonNull(frequencyHint, "frequencyHint");
    }

    @Transactional
    public AdvertisingDecisionResponses.RecordEventResult recordEvent(
        Long decisionId,
        AdvertisingDecisionRequests.RecordEventRequest request,
        String idempotencyKey
    ) {
        Long userId = requireCurrentUserId();
        validateRequest(decisionId, request, idempotencyKey);

        String eventType = request.eventType();
        String tokenHash = tokenService.hash(request.token());
        AdvertisingDecision decision = decisionMapper.findByTokenHash(tokenHash);
        verifyDecision(decision, decisionId, userId);

        String eventKey = decisionId + ":" + eventType + ":" + idempotencyKey;
        String requestHash = requestHash(eventType, request);
        AdvertisingEvent existing = eventMapper.findByEventKey(eventKey);
        if (existing != null) {
            if (!Objects.equals(existing.requestHash, requestHash)) {
                throw BusinessException.conflict("ADVERTISING_EVENT_IDEMPOTENCY_CONFLICT: 同键异载荷");
            }
            return AdvertisingDecisionResponses.RecordEventResult.replayed(eventType);
        }

        if (!privacyService.isCommercialMeasurementEnabled(userId)) {
            return AdvertisingDecisionResponses.RecordEventResult.rejected(
                eventType, "ADVERTISING_MEASUREMENT_DISABLED");
        }
        if (!privacyService.lockForAdvertisingFact(userId)) {
            return AdvertisingDecisionResponses.RecordEventResult.rejected(
                eventType, "ADVERTISING_MEASUREMENT_DISABLED");
        }
        if (CLIENT_VIEWABLE.equals(eventType) && !meetsVisibilityThreshold(request)) {
            return AdvertisingDecisionResponses.RecordEventResult.rejected(
                eventType, "VISIBILITY_BELOW_THRESHOLD");
        }

        if (CLIENT_VIEWABLE.equals(eventType)) {
            lockScope(userId, "SLOT");
            lockScope(userId, "CREATIVE:" + decision.creativeVersionId);
        }
        // 决策服务的锁顺序是 guard -> decision；可见事件保持同一顺序避免并发死锁。
        decision = decisionMapper.findByTokenHashForUpdate(tokenHash);
        verifyDecision(decision, decisionId, userId);
        existing = eventMapper.findByEventKey(eventKey);
        if (existing != null) {
            if (!Objects.equals(existing.requestHash, requestHash)) {
                throw BusinessException.conflict("ADVERTISING_EVENT_IDEMPOTENCY_CONFLICT: 同键异载荷");
            }
            return AdvertisingDecisionResponses.RecordEventResult.replayed(eventType);
        }
        AdvertisingEvent sameType = eventMapper.findByDecisionAndType(decisionId, eventType);
        if (sameType != null) {
            return AdvertisingDecisionResponses.RecordEventResult.rejected(eventType, "DUPLICATE_EVENT_TYPE");
        }

        if (CLIENT_VIEWABLE.equals(eventType)) {
            if (!"ISSUED".equals(decision.status)) {
                return AdvertisingDecisionResponses.RecordEventResult.rejected(
                    eventType, "DECISION_ALREADY_CONSUMED");
            }
            sameType = eventMapper.findByDecisionAndType(decisionId, eventType);
            if (sameType != null) {
                return AdvertisingDecisionResponses.RecordEventResult.rejected(eventType, "DUPLICATE_EVENT_TYPE");
            }
            LocalDateTime windowStart = LocalDateTime.now().minusHours(AdvertisingDecisionService.WINDOW_HOURS);
            int slotAccepted = eventMapper.countAcceptedViewableByUserSlot(
                userId, AdvertisingDecisionService.HOME_COMMERCIAL_CODE, windowStart);
            if (slotAccepted >= AdvertisingDecisionService.USER_SLOT_CAP) {
                return AdvertisingDecisionResponses.RecordEventResult.rejected(eventType, "USER_SLOT_FREQUENCY_CAP");
            }
            int creativeAccepted = eventMapper.countAcceptedViewableByUserCreative(
                userId, decision.creativeVersionId, windowStart);
            if (creativeAccepted >= AdvertisingDecisionService.USER_CREATIVE_CAP) {
                return AdvertisingDecisionResponses.RecordEventResult.rejected(
                    eventType, "USER_CREATIVE_FREQUENCY_CAP");
            }
            // Redis 只读提示，MySQL 计数已经完成最终判断。
            frequencyHint.tryReadUserSlot(userId, AdvertisingDecisionService.WINDOW_HOURS);
            frequencyHint.tryReadUserCreative(userId, decision.creativeVersionId,
                AdvertisingDecisionService.WINDOW_HOURS);
        }

        AdvertisingEvent event = buildEvent(decision, eventType, eventKey, requestHash, request, userId);
        try {
            if (eventMapper.insert(event) != 1) {
                throw new IllegalStateException("ADVERTISING_EVENT_INSERT_NOT_PERSISTED");
            }
        } catch (DuplicateKeyException duplicate) {
            AdvertisingEvent keyed = eventMapper.findByEventKey(eventKey);
            if (keyed != null) {
                if (!Objects.equals(keyed.requestHash, requestHash)) {
                    throw BusinessException.conflict("ADVERTISING_EVENT_IDEMPOTENCY_CONFLICT: 同键异载荷");
                }
                return AdvertisingDecisionResponses.RecordEventResult.replayed(eventType);
            }
            AdvertisingEvent typed = eventMapper.findByDecisionAndType(decisionId, eventType);
            if (typed != null) {
                return AdvertisingDecisionResponses.RecordEventResult.rejected(eventType, "DUPLICATE_EVENT_TYPE");
            }
            throw duplicate;
        }

        if (CLIENT_VIEWABLE.equals(eventType)) {
            if (decisionMapper.markConsumed(decision.id, event.occurredAt) != 1) {
                throw new IllegalStateException("ADVERTISING_DECISION_ALREADY_CONSUMED");
            }
            frequencyHint.recordAccepted(userId, decision.creativeVersionId,
                AdvertisingDecisionService.WINDOW_HOURS);
        }
        return AdvertisingDecisionResponses.RecordEventResult.accepted(eventType);
    }

    private void lockScope(Long userId, String scopeKey) {
        String slotCode = AdvertisingDecisionService.HOME_COMMERCIAL_CODE;
        if (guardMapper.findScope(userId, slotCode, scopeKey) == null) {
            try {
                guardMapper.insertScope(userId, slotCode, scopeKey);
            } catch (DuplicateKeyException ignored) {
                // Another transaction created the guard; the following FOR UPDATE is authoritative.
            }
        }
        if (guardMapper.lockScope(userId, slotCode, scopeKey) == null) {
            throw new IllegalStateException("ADVERTISING_GUARD_NOT_LOCKED");
        }
    }

    private AdvertisingEvent buildEvent(AdvertisingDecision decision,
                                        String eventType,
                                        String eventKey,
                                        String requestHash,
                                        AdvertisingDecisionRequests.RecordEventRequest request,
                                        Long userId) {
        LocalDateTime now = LocalDateTime.now();
        AdvertisingEvent event = new AdvertisingEvent();
        event.eventNo = "ADE-" + now.format(EVENT_NO_TIME)
            + UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase(Locale.ROOT);
        event.eventKey = eventKey;
        event.eventType = eventType;
        event.decisionId = decision.id;
        event.userId = userId;
        event.slotCode = decision.slotCode;
        event.advertiserId = decision.advertiserId;
        event.campaignId = decision.campaignId;
        event.creativeVersionId = decision.creativeVersionId;
        event.requestHash = requestHash;
        if (CLIENT_VIEWABLE.equals(eventType)) {
            event.visibleRatio = BigDecimal.valueOf(request.visibleRatio());
            event.visibleDurationMs = request.visibleDurationMs();
        }
        event.resultStatus = "ACCEPTED";
        event.policyVersion = decision.policyVersion;
        event.occurredAt = now;
        event.createTime = now;
        return event;
    }

    private void verifyDecision(AdvertisingDecision decision, Long decisionId, Long userId) {
        if (decision == null || !Objects.equals(decision.id, decisionId)
            || !Objects.equals(decision.userId, userId)
            || !AdvertisingDecisionService.HOME_COMMERCIAL_CODE.equals(decision.slotCode)) {
            throw BusinessException.badRequest("ADVERTISING_TOKEN_INVALID: 令牌无效");
        }
        if (decision.expiresAt == null || !LocalDateTime.now().isBefore(decision.expiresAt)
            || "EXPIRED".equals(decision.status)) {
            throw BusinessException.conflict("ADVERTISING_TOKEN_EXPIRED: 令牌已过期");
        }
    }

    private void validateRequest(Long decisionId,
                                 AdvertisingDecisionRequests.RecordEventRequest request,
                                 String idempotencyKey) {
        if (decisionId == null || decisionId <= 0 || request == null
            || request.eventType() == null || !ALLOWED_TYPES.contains(request.eventType())
            || request.token() == null || request.token().isBlank()
            || request.token().length() > MAX_TOKEN_LENGTH
            || idempotencyKey == null || idempotencyKey.isBlank()
            || idempotencyKey.length() > MAX_IDEMPOTENCY_KEY_LENGTH) {
            throw BusinessException.badRequest("广告事件请求不合法");
        }
        if (request.visibleRatio() != null
            && (request.visibleRatio().isNaN() || request.visibleRatio().isInfinite())) {
            throw BusinessException.badRequest("visibleRatio 不合法");
        }
    }

    private boolean meetsVisibilityThreshold(AdvertisingDecisionRequests.RecordEventRequest request) {
        return request.visibleRatio() != null && request.visibleDurationMs() != null
            && BigDecimal.valueOf(request.visibleRatio()).compareTo(VISIBILITY_RATIO_THRESHOLD) >= 0
            && request.visibleRatio() <= 1.0
            && request.visibleDurationMs() >= VISIBILITY_DURATION_THRESHOLD_MS;
    }

    private String requestHash(String eventType, AdvertisingDecisionRequests.RecordEventRequest request) {
        String payload = eventType + "|" + nullSafe(request.visibleRatio()) + "|" + nullSafe(request.visibleDurationMs());
        return tokenService.hash(payload);
    }

    private String nullSafe(Object value) {
        return value == null ? "" : value.toString();
    }

    private Long requireCurrentUserId() {
        Long userId = SecurityContext.currentUserId();
        if (userId == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED, "Authentication is required");
        }
        return userId;
    }
}
