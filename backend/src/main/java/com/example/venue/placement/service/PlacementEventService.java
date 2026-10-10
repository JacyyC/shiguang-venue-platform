package com.example.venue.placement.service;

import com.example.venue.common.BusinessException;
import com.example.venue.common.CurrentUser;
import com.example.venue.placement.dto.PlacementRequests;
import com.example.venue.placement.dto.PlacementResponses;
import com.example.venue.placement.entity.PlacementDecision;
import com.example.venue.placement.entity.PlacementEvent;
import com.example.venue.placement.mapper.PlacementDecisionMapper;
import com.example.venue.placement.mapper.PlacementEventMapper;
import com.example.venue.security.SecurityContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.dao.DuplicateKeyException;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * 宣传事件服务。只接受 CLIENT_VIEWABLE 与 CLICK，验证当前认证用户、令牌哈希/时效、决策、
 * 版位、候选版本与事件类型；请求不接受候选 ID、版位或用户 ID 覆盖令牌绑定。
 *
 * 幂等：Idempotency-Key 作用域 (decision,eventType)，同键同载荷返回原结果、同键异载荷冲突、
 * 同决策同类事件最多一条。CLIENT_VIEWABLE 需 visibleRatio&gt;=0.5 且 visibleDurationMs&gt;=1000，
 * 首条满足阈值且令牌有效的事件标记决策 CONSUMED。CLICK 可先于曝光，记为数据缺口，不反向补写。
 */
@Service
public class PlacementEventService {

    public static final String CLIENT_VIEWABLE = "CLIENT_VIEWABLE";
    public static final String CLICK = "CLICK";
    private static final Set<String> ALLOWED_TYPES = Set.of(CLIENT_VIEWABLE, CLICK);
    private static final BigDecimal VISIBILITY_RATIO_THRESHOLD = new BigDecimal("0.5000");
    private static final int VISIBILITY_DURATION_THRESHOLD_MS = 1000;
    private static final int MAX_IDEMPOTENCY_KEY_LENGTH = 160;

    private final PlacementDecisionMapper decisionMapper;
    private final PlacementEventMapper eventMapper;
    private final PlacementTokenService tokenService;
    private final PlacementFrequencyGuard frequencyGuard;

    public PlacementEventService(PlacementDecisionMapper decisionMapper,
                                 PlacementEventMapper eventMapper,
                                 PlacementTokenService tokenService,
                                 PlacementFrequencyGuard frequencyGuard) {
        this.decisionMapper = decisionMapper;
        this.eventMapper = eventMapper;
        this.tokenService = tokenService;
        this.frequencyGuard = frequencyGuard;
    }

    @Transactional
    public PlacementResponses.RecordEventResult recordEvent(Long decisionId,
                                                            PlacementRequests.RecordEventRequest request,
                                                            String idempotencyKey) {
        CurrentUser user = requireAuthenticatedUser();
        requireValid(decisionId, request, idempotencyKey);
        String eventType = request.eventType();

        PlacementDecision decision = verifyTokenAndBinding(decisionId, request.token(), user.id());
        String eventKey = composeEventKey(decisionId, eventType, idempotencyKey);
        String requestHash = requestHash(eventType, request);

        PlacementEvent existing = eventMapper.findByKey(eventKey);
        if (existing != null) {
            if (!Objects.equals(existing.requestHash, requestHash)) {
                throw BusinessException.conflict("PLACEMENT_EVENT_IDEMPOTENCY_CONFLICT: 同键异载荷");
            }
            return new PlacementResponses.RecordEventResult(existing.resultStatus, existing.rejectionCode, eventType);
        }

        PlacementEvent sameType = eventMapper.findByDecisionAndType(decisionId, eventType);
        if (sameType != null) {
            return PlacementResponses.RecordEventResult.rejected(eventType, "DUPLICATE_EVENT_TYPE");
        }

        if (CLIENT_VIEWABLE.equals(eventType)
            && !meetsVisibilityThreshold(request)) {
            return PlacementResponses.RecordEventResult.rejected(eventType, "VISIBILITY_BELOW_THRESHOLD");
        }

        PlacementEvent event = buildAcceptedEvent(decision, eventType, eventKey, requestHash, request);
        try {
            int inserted = eventMapper.insertEvent(event);
            if (inserted != 1) {
                throw new IllegalStateException("PLACEMENT_EVENT_INSERT_NOT_PERSISTED");
            }
        } catch (DuplicateKeyException duplicate) {
            // 并发请求可能撞上 event_key 或 (decision,eventType) 唯一键；只有读回已知
            // 幂等事实时才转换为业务结果，未知唯一键和其它数据库错误继续向上抛出。
            existing = eventMapper.findByKey(eventKey);
            if (existing != null) {
                if (!Objects.equals(existing.requestHash, requestHash)) {
                    throw BusinessException.conflict("PLACEMENT_EVENT_IDEMPOTENCY_CONFLICT: 同键异载荷");
                }
                return new PlacementResponses.RecordEventResult(
                    existing.resultStatus, existing.rejectionCode, eventType);
            }
            sameType = eventMapper.findByDecisionAndType(decisionId, eventType);
            if (sameType != null) {
                return PlacementResponses.RecordEventResult.rejected(eventType, "DUPLICATE_EVENT_TYPE");
            }
            throw duplicate;
        }

        if (CLIENT_VIEWABLE.equals(eventType)) {
            int updated = decisionMapper.markConsumed(decision.id, event.occurredAt);
            if (updated == 0) {
                // 决策已被并发消费；唯一键已保证同类只有一条 ACCEPTED，此处不应发生。
                // 若发生，回滚事件以保持决策与事件一致。
                throw new IllegalStateException("PLACEMENT_DECISION_ALREADY_CONSUMED");
            }
            frequencyGuard.recordAccepted(decision.userId, decision.slotId,
                decision.candidateVersionId, 24);
        }
        return PlacementResponses.RecordEventResult.accepted(eventType);
    }

    private CurrentUser requireAuthenticatedUser() {
        CurrentUser user = SecurityContext.currentUser();
        if (user == null || user.id() == null) {
            throw BusinessException.forbidden("请先登录");
        }
        return user;
    }

    private void requireValid(Long decisionId, PlacementRequests.RecordEventRequest request, String idempotencyKey) {
        if (decisionId == null || decisionId <= 0) {
            throw BusinessException.badRequest("决策 ID 不合法");
        }
        if (request == null || request.eventType() == null || !ALLOWED_TYPES.contains(request.eventType())) {
            throw BusinessException.badRequest("仅接受 CLIENT_VIEWABLE 与 CLICK 事件");
        }
        if (request.token() == null || request.token().isBlank()) {
            throw BusinessException.badRequest("缺少决策令牌");
        }
        if (idempotencyKey == null || idempotencyKey.isBlank()
            || idempotencyKey.length() > MAX_IDEMPOTENCY_KEY_LENGTH) {
            throw BusinessException.badRequest("Idempotency-Key 不合法");
        }
    }

    /**
     * 令牌绑定验证：用令牌哈希查决策，校验决策 ID、当前用户、时效。请求不含 candidate/slot/userId，
     * 无法覆盖令牌绑定。过期或跨用户令牌统一返回不可枚举失败，不泄漏决策归属。
     */
    private PlacementDecision verifyTokenAndBinding(Long decisionId, String rawToken, Long userId) {
        String tokenHash = tokenService.hash(rawToken);
        PlacementDecision decision = decisionMapper.findByTokenHash(tokenHash);
        if (decision == null || !decision.id.equals(decisionId) || !decision.userId.equals(userId)) {
            throw BusinessException.badRequest("PLACEMENT_TOKEN_INVALID: 令牌无效");
        }
        LocalDateTime now = LocalDateTime.now();
        if (decision.expiresAt == null || !now.isBefore(decision.expiresAt)) {
            throw BusinessException.conflict("PLACEMENT_TOKEN_EXPIRED: 令牌已过期");
        }
        if ("EXPIRED".equals(decision.status)) {
            throw BusinessException.conflict("PLACEMENT_TOKEN_EXPIRED: 令牌已过期");
        }
        return decision;
    }

    private boolean meetsVisibilityThreshold(PlacementRequests.RecordEventRequest request) {
        if (request.visibleRatio() == null || request.visibleDurationMs() == null) {
            return false;
        }
        BigDecimal ratio = BigDecimal.valueOf(request.visibleRatio());
        return ratio.compareTo(VISIBILITY_RATIO_THRESHOLD) >= 0
            && request.visibleDurationMs() >= VISIBILITY_DURATION_THRESHOLD_MS;
    }

    private PlacementEvent buildAcceptedEvent(PlacementDecision decision,
                                              String eventType,
                                              String eventKey,
                                              String requestHash,
                                              PlacementRequests.RecordEventRequest request) {
        LocalDateTime now = LocalDateTime.now();
        PlacementEvent event = new PlacementEvent();
        event.eventNo = nextEventNo(now);
        event.eventKey = eventKey;
        event.eventType = eventType;
        event.decisionId = decision.id;
        event.userId = decision.userId;
        event.slotId = decision.slotId;
        event.candidateVersionId = decision.candidateVersionId;
        event.requestHash = requestHash;
        if (CLIENT_VIEWABLE.equals(eventType)) {
            event.visibleRatio = request.visibleRatio() == null ? null
                : BigDecimal.valueOf(request.visibleRatio());
            event.visibleDurationMs = request.visibleDurationMs();
        }
        event.resultStatus = "ACCEPTED";
        event.occurredAt = now;
        event.createTime = now;
        return event;
    }

    private String composeEventKey(Long decisionId, String eventType, String idempotencyKey) {
        String legacyKey = decisionId + ":" + eventType + ":" + idempotencyKey;
        if (legacyKey.length() <= MAX_IDEMPOTENCY_KEY_LENGTH) {
            // Keep the existing representation so already-persisted event keys replay unchanged.
            return legacyKey;
        }
        return sha256Hex("placement-event-key|" + legacyKey);
    }

    private String requestHash(String eventType, PlacementRequests.RecordEventRequest request) {
        String payload = eventType + "|" + nullSafe(request.visibleRatio()) + "|" + nullSafe(request.visibleDurationMs());
        return sha256Hex(payload);
    }

    private String sha256Hex(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private String nullSafe(Object value) {
        return value == null ? "" : value.toString();
    }

    private String nextEventNo(LocalDateTime now) {
        return "PLE-" + now.format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
            + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase(Locale.ROOT);
    }
}
