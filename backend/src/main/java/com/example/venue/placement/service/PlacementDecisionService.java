package com.example.venue.placement.service;

import com.example.venue.common.BusinessException;
import com.example.venue.common.CurrentUser;
import com.example.venue.placement.contract.OwnedPlacementDecisionReader;
import com.example.venue.placement.dto.PlacementResponses;
import com.example.venue.placement.entity.PlacementCandidateVersion;
import com.example.venue.placement.entity.PlacementDecision;
import com.example.venue.placement.entity.PlacementSlot;
import com.example.venue.placement.mapper.PlacementCandidateVersionMapper;
import com.example.venue.placement.mapper.PlacementDecisionMapper;
import com.example.venue.placement.mapper.PlacementEventMapper;
import com.example.venue.placement.mapper.PlacementSlotMapper;
import com.example.venue.placement.profile.PlacementMemberProfileReader;
import com.example.venue.placement.source.PlacementSourceReader;
import com.example.venue.placement.source.PlacementSourceReaderRegistry;
import com.example.venue.placement.source.PlacementSourceSnapshot;
import com.example.venue.placement.source.PlacementSourceType;
import com.example.venue.security.SecurityContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/**
 * 自营宣传位决策引擎。按 plan S08B 确定顺序：读已启用版位与满足时间窗的已发布候选版本 →
 * 只保留 ALL_AUTHENTICATED 或会员 levelCode 精确匹配的受众 → 调 Source Reader 按当前用户
 * 重校验来源 → 在 (user, slot, candidate) 的 MySQL guard 锁内复核已接受 CLIENT_VIEWABLE
 * 数量并复用未过期未消费决策 → 保存决策快照返回窄 DTO。无候选返回空结果。
 *
 * Redis 仅作频控提示，不可用或数据丢失时完整走 MySQL；MySQL 复核失败则决策失败关闭，
 * 由首页静默降级，不得绕过上限。
 */
@Service
public class PlacementDecisionService implements OwnedPlacementDecisionReader {

    public static final String HOME_PRIMARY_CODE = "HOME_PRIMARY";
    private static final int MAX_DECISION_CANDIDATES = 100;
    private static final String RULE_VERSION = "PLACEMENT_RULE_V1";

    private final PlacementSlotMapper slotMapper;
    private final PlacementCandidateVersionMapper candidateMapper;
    private final PlacementDecisionMapper decisionMapper;
    private final PlacementEventMapper eventMapper;
    private final PlacementSourceReaderRegistry readerRegistry;
    private final PlacementMemberProfileReader memberProfileReader;
    private final PlacementTokenService tokenService;
    private final PlacementFrequencyGuard frequencyGuard;
    private final ObjectMapper objectMapper;

    public PlacementDecisionService(PlacementSlotMapper slotMapper,
                                    PlacementCandidateVersionMapper candidateMapper,
                                    PlacementDecisionMapper decisionMapper,
                                    PlacementEventMapper eventMapper,
                                    PlacementSourceReaderRegistry readerRegistry,
                                    PlacementMemberProfileReader memberProfileReader,
                                    PlacementTokenService tokenService,
                                    PlacementFrequencyGuard frequencyGuard,
                                    ObjectMapper objectMapper) {
        this.slotMapper = slotMapper;
        this.candidateMapper = candidateMapper;
        this.decisionMapper = decisionMapper;
        this.eventMapper = eventMapper;
        this.readerRegistry = readerRegistry;
        this.memberProfileReader = memberProfileReader;
        this.tokenService = tokenService;
        this.frequencyGuard = frequencyGuard;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public PlacementResponses.HomePrimaryDecision decideHomePrimary() {
        return resolveHomePrimary().decision();
    }

    /**
     * 为商业广告提供只读仲裁结果。明确没有可用自营候选才返回 EMPTY；
     * 来源/数据库异常返回 UNAVAILABLE，广告域不得在该状态回填。
     */
    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public OwnedPlacementDecisionReader.ArbitrationResult readHomePrimary() {
        try {
            DecisionAttempt attempt = resolveHomePrimary();
            if (attempt.decision() != null) {
                return OwnedPlacementDecisionReader.ArbitrationResult.available();
            }
            return attempt.sourceFailure()
                ? OwnedPlacementDecisionReader.ArbitrationResult.unavailable()
                : OwnedPlacementDecisionReader.ArbitrationResult.empty();
        } catch (RuntimeException exception) {
            return OwnedPlacementDecisionReader.ArbitrationResult.unavailable();
        }
    }

    private DecisionAttempt resolveHomePrimary() {
        CurrentUser user = requireAuthenticatedUser();
        PlacementSlot slot = slotMapper.findByCode(HOME_PRIMARY_CODE);
        if (slot == null || !"ENABLED".equals(slot.status)) {
            return new DecisionAttempt(null, false);
        }
        LocalDateTime now = LocalDateTime.now();
        List<PlacementCandidateVersion> candidates = candidateMapper.findPublishedForDecision(
            slot.id, now, MAX_DECISION_CANDIDATES);
        if (candidates.isEmpty()) {
            return new DecisionAttempt(null, false);
        }
        String levelCode = memberProfileReader.currentLevelCode(user.id());
        boolean sourceFailure = false;
        for (PlacementCandidateVersion candidate : candidates) {
            if (!audienceMatches(candidate, levelCode)) {
                continue;
            }
            SourceReadAttempt sourceAttempt = readSourceForUser(candidate, user.id());
            if (sourceAttempt.sourceFailure()) {
                sourceFailure = true;
            }
            if (sourceAttempt.snapshot() == null) {
                continue;
            }
            PlacementResponses.HomePrimaryDecision result =
                tryIssueOrReuse(user.id(), slot, candidate, sourceAttempt.snapshot(), now);
            if (result != null) {
                return new DecisionAttempt(result, sourceFailure);
            }
        }
        // 所有候选均达频控或不可用，返回空，首页静默降级。
        return new DecisionAttempt(null, sourceFailure);
    }

    private CurrentUser requireAuthenticatedUser() {
        CurrentUser user = SecurityContext.currentUser();
        if (user == null || user.id() == null) {
            throw BusinessException.forbidden("请先登录");
        }
        return user;
    }

    private boolean audienceMatches(PlacementCandidateVersion candidate, String levelCode) {
        if ("ALL_AUTHENTICATED".equals(candidate.audienceType)) {
            return true;
        }
        if ("MEMBER_LEVEL".equals(candidate.audienceType) && candidate.audienceLevelCode != null) {
            return candidate.audienceLevelCode.equals(levelCode);
        }
        return false;
    }

    private SourceReadAttempt readSourceForUser(PlacementCandidateVersion candidate, Long userId) {
        PlacementSourceType sourceType;
        try {
            sourceType = PlacementSourceType.valueOf(candidate.sourceType);
        } catch (RuntimeException exception) {
            return new SourceReadAttempt(null, true);
        }
        PlacementSourceReader reader = readerRegistry.find(sourceType).orElse(null);
        if (reader == null) {
            return new SourceReadAttempt(null, true);
        }
        try {
            return new SourceReadAttempt(reader.readForUser(candidate.sourceId, userId), false);
        } catch (RuntimeException exception) {
            // 来源域异常统一视为来源不可用，不泄漏内部原因。
            return new SourceReadAttempt(null, true);
        }
    }

    /**
     * MySQL guard 锁内复核频控并复用/签发决策。返回 null 表示该候选达频控，应继续下一候选。
     */
    private PlacementResponses.HomePrimaryDecision tryIssueOrReuse(Long userId,
                                                                   PlacementSlot slot,
                                                                   PlacementCandidateVersion candidate,
                                                                   PlacementSourceSnapshot source,
                                                                   LocalDateTime now) {
        // 锁住 (user, slot, candidate) 范围，串行化同用户同候选的决策签发。
        decisionMapper.lockUserCandidateRange(userId, slot.id, candidate.id);

        PlacementDecision reusable = decisionMapper.findReusableIssuedForUpdate(
            userId, slot.id, candidate.id, now);
        if (reusable != null) {
            // The raw token is intentionally never stored. Rotate its hash while the reusable
            // decision row is locked, then return the new raw token only in this response.
            PlacementTokenService.GeneratedToken token = tokenService.generate();
            int rotated = decisionMapper.rotateIssuedTokenHash(reusable.id, token.hash(), now);
            if (rotated != 1) {
                throw new IllegalStateException("PLACEMENT_DECISION_TOKEN_ROTATION_FAILED");
            }
            return PlacementResponses.HomePrimaryDecision.reused(reusable, token.raw(), objectMapper);
        }

        LocalDateTime windowStart = now.minusHours(candidate.frequencyCapWindowHours);
        int accepted = eventMapper.countAcceptedViewable(userId, slot.id, candidate.id, windowStart);
        if (accepted >= candidate.frequencyCapPerUser) {
            return null;
        }
        // Redis 提示仅作加速；若提示已达上限而 MySQL 未达，以 MySQL 为准放行并继续签发。
        Long hint = frequencyGuard.tryReadHint(userId, slot.id, candidate.id, candidate.frequencyCapWindowHours);
        if (hint != null && hint >= candidate.frequencyCapPerUser && accepted >= candidate.frequencyCapPerUser) {
            return null;
        }

        PlacementTokenService.GeneratedToken token = tokenService.generate();
        LocalDateTime expiresAt = tokenService.earliestExpiry(now, candidate.effectiveUntil, source);
        PlacementDecision decision = buildDecision(userId, slot, candidate, source, token, expiresAt, now);
        decisionMapper.insert(decision);
        return PlacementResponses.HomePrimaryDecision.issued(decision, token.raw(), objectMapper);
    }

    private PlacementDecision buildDecision(Long userId,
                                           PlacementSlot slot,
                                           PlacementCandidateVersion candidate,
                                           PlacementSourceSnapshot source,
                                           PlacementTokenService.GeneratedToken token,
                                           LocalDateTime expiresAt,
                                           LocalDateTime now) {
        PlacementDecision decision = new PlacementDecision();
        decision.decisionNo = nextDecisionNo(now);
        decision.userId = userId;
        decision.slotId = slot.id;
        decision.candidateVersionId = candidate.id;
        decision.tokenHash = token.hash();
        decision.ruleVersionSnapshot = candidate.ruleVersion != null ? candidate.ruleVersion : RULE_VERSION;
        decision.audienceSnapshotJson = audienceJson(candidate);
        decision.frequencySnapshotJson = frequencyJson(candidate);
        decision.sourceSnapshotJson = sourceJson(source);
        decision.prioritySnapshot = candidate.priority;
        decision.expiresAt = expiresAt;
        decision.status = "ISSUED";
        decision.createTime = now;
        return decision;
    }

    private String audienceJson(PlacementCandidateVersion candidate) {
        return toJson(java.util.Map.of(
            "audienceType", nullSafe(candidate.audienceType),
            "audienceLevelCode", nullSafe(candidate.audienceLevelCode)
        ));
    }

    private String frequencyJson(PlacementCandidateVersion candidate) {
        return toJson(java.util.Map.of(
            "capPerUser", candidate.frequencyCapPerUser,
            "windowHours", candidate.frequencyCapWindowHours
        ));
    }

    private String sourceJson(PlacementSourceSnapshot source) {
        return toJson(java.util.Map.of(
            "sourceType", nullSafe(source.sourceType() == null ? null : source.sourceType().name()),
            "sourceId", Objects.requireNonNullElse(source.sourceId(), 0),
            "sourceVersion", nullSafe(source.sourceVersion()),
            "title", nullSafe(source.title()),
            "summary", nullSafe(source.summary()),
            "imageUrl", nullSafe(source.imageUrl()),
            "deepLink", nullSafe(source.deepLink())
        ));
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("placement snapshot serialization failed", exception);
        }
    }

    private String nullSafe(String value) {
        return value == null ? "" : value;
    }

    private String nextDecisionNo(LocalDateTime now) {
        return "PLD-" + now.format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
            + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase(Locale.ROOT);
    }

    private record DecisionAttempt(PlacementResponses.HomePrimaryDecision decision,
                                   boolean sourceFailure) {
    }

    private record SourceReadAttempt(PlacementSourceSnapshot snapshot,
                                    boolean sourceFailure) {
    }
}
