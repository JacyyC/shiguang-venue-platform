package com.example.venue.placement.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.example.venue.common.BusinessException;
import com.example.venue.common.CurrentUser;
import com.example.venue.common.ResultCode;
import com.example.venue.placement.dto.PlacementRequests;
import com.example.venue.placement.dto.PlacementResponses;
import com.example.venue.placement.entity.PlacementCandidateVersion;
import com.example.venue.placement.entity.PlacementDecision;
import com.example.venue.placement.entity.PlacementEvent;
import com.example.venue.placement.entity.PlacementSlot;
import com.example.venue.placement.mapper.PlacementCandidateVersionMapper;
import com.example.venue.placement.mapper.PlacementDecisionMapper;
import com.example.venue.placement.mapper.PlacementEventMapper;
import com.example.venue.placement.mapper.PlacementSlotMapper;
import com.example.venue.placement.source.PlacementSourceReader;
import com.example.venue.placement.source.PlacementSourceReaderRegistry;
import com.example.venue.placement.source.PlacementSourceSnapshot;
import com.example.venue.placement.source.PlacementSourceType;
import com.example.venue.security.SecurityContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * 宣传管理服务。运营端按叶子权限执行版位只读、候选创建/发布/下线与审计查看。
 * 候选创建/发布时按 Reader 预览来源，但候选版本只存来源引用与不可变规则，不存标题副本或跳转 URL。
 * 审计列表有界并命中索引，不暴露令牌原值或完整来源正文。
 */
@Service
public class PlacementAdminService {

    private static final String RULE_VERSION = "PLACEMENT_RULE_V1";
    private static final int MAX_LIST_LIMIT = 100;
    private static final int DEFAULT_LIST_LIMIT = 50;

    private final PlacementSlotMapper slotMapper;
    private final PlacementCandidateVersionMapper candidateMapper;
    private final PlacementDecisionMapper decisionMapper;
    private final PlacementEventMapper eventMapper;
    private final PlacementSourceReaderRegistry readerRegistry;
    private final ObjectMapper objectMapper;

    public PlacementAdminService(PlacementSlotMapper slotMapper,
                                 PlacementCandidateVersionMapper candidateMapper,
                                 PlacementDecisionMapper decisionMapper,
                                 PlacementEventMapper eventMapper,
                                 PlacementSourceReaderRegistry readerRegistry,
                                 ObjectMapper objectMapper) {
        this.slotMapper = slotMapper;
        this.candidateMapper = candidateMapper;
        this.decisionMapper = decisionMapper;
        this.eventMapper = eventMapper;
        this.readerRegistry = readerRegistry;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public List<PlacementResponses.SlotResponse> listSlots() {
        return slotMapper.selectList(new QueryWrapper<PlacementSlot>()
                .orderByAsc("id")).stream()
            .map(PlacementResponses.SlotResponse::from)
            .toList();
    }

    @Transactional(readOnly = true)
    public List<PlacementResponses.CandidateVersionResponse> listCandidates(Long slotId, Integer limit) {
        int bounded = boundedLimit(limit);
        return candidateMapper.selectList(new QueryWrapper<PlacementCandidateVersion>()
                .eq(slotId != null, "slot_id", slotId)
                .orderByDesc("id")
                .last("LIMIT " + bounded)).stream()
            .map(PlacementResponses.CandidateVersionResponse::from)
            .toList();
    }

    @Transactional
    public PlacementResponses.CandidateVersionResponse createCandidate(PlacementRequests.CreateCandidateRequest request) {
        if (request == null) {
            throw BusinessException.badRequest("候选请求不能为空");
        }
        PlacementSlot slot = requireSlot(request.slotId());
        PlacementSourceType sourceType = parseSourceType(request.sourceType());
        PlacementSourceReader reader = readerRegistry.find(sourceType)
            .orElseThrow(() -> BusinessException.badRequest("来源类型不支持"));
        PlacementSourceSnapshot preview = readForPreviewSafely(reader, request.sourceId());
        if (preview == null) {
            throw BusinessException.badRequest("来源不可用或已下线");
        }
        validateWindow(request.effectiveFrom(), request.effectiveUntil());
        String audienceType = normalizeAudienceType(request.audienceType());
        String audienceLevelCode = normalizeAudienceLevelCode(audienceType, request.audienceLevelCode());
        int priority = request.priority() == null ? 0 : request.priority();
        int capPerUser = request.frequencyCapPerUser() == null ? 1 : request.frequencyCapPerUser();
        int windowHours = request.frequencyCapWindowHours() == null ? 24 : request.frequencyCapWindowHours();

        CurrentUser actor = requireCurrentUser();
        LocalDateTime now = LocalDateTime.now();
        PlacementCandidateVersion candidate = new PlacementCandidateVersion();
        candidate.slotId = slot.id;
        candidate.candidateNo = nextCandidateNo(now);
        candidate.sourceType = sourceType.name();
        candidate.sourceId = request.sourceId();
        candidate.sourceVersion = preview.sourceVersion();
        candidate.effectiveFrom = request.effectiveFrom();
        candidate.effectiveUntil = request.effectiveUntil();
        candidate.audienceType = audienceType;
        candidate.audienceLevelCode = audienceLevelCode;
        candidate.priority = priority;
        candidate.frequencyCapPerUser = capPerUser;
        candidate.frequencyCapWindowHours = windowHours;
        candidate.ruleVersion = RULE_VERSION;
        candidate.status = "DRAFT";
        candidate.createdBy = actor.id();
        candidate.createTime = now;
        candidate.updateTime = now;
        candidateMapper.insert(candidate);
        return PlacementResponses.CandidateVersionResponse.from(candidate);
    }

    @Transactional(readOnly = true)
    public PlacementResponses.SourcePreviewResponse previewSource(String sourceType, Long sourceId) {
        PlacementSourceType type = parseSourceType(sourceType);
        PlacementSourceReader reader = readerRegistry.find(type)
            .orElseThrow(() -> BusinessException.badRequest("来源类型不支持"));
        PlacementSourceSnapshot snapshot = readForPreviewSafely(reader, sourceId);
        if (snapshot == null) {
            throw BusinessException.notFound("来源不可用或已下线");
        }
        return new PlacementResponses.SourcePreviewResponse(
            snapshot.sourceType().name(), snapshot.sourceId(), snapshot.sourceVersion(),
            snapshot.title(), snapshot.summary(), snapshot.imageUrl(), snapshot.deepLink(),
            snapshot.sourceEffectiveFrom(), snapshot.sourceEffectiveUntil()
        );
    }

    @Transactional
    public PlacementResponses.CandidateVersionResponse publishCandidate(Long candidateId) {
        PlacementCandidateVersion candidate = requireCandidateForUpdate(candidateId);
        if (!"DRAFT".equals(candidate.status) && !"PAUSED".equals(candidate.status)) {
            throw BusinessException.conflict("候选当前状态不能发布");
        }
        PlacementSourceReader reader = readerRegistry.find(parseSourceType(candidate.sourceType))
            .orElseThrow(() -> BusinessException.conflict("来源类型已不可用"));
        PlacementSourceSnapshot preview = readForPreviewSafely(reader, candidate.sourceId);
        if (preview == null) {
            throw BusinessException.conflict("来源不可用或已下线");
        }
        CurrentUser actor = requireCurrentUser();
        LocalDateTime now = LocalDateTime.now();
        candidate.status = "PUBLISHED";
        candidate.publishedBy = actor.id();
        candidate.publishedAt = now;
        candidate.updateTime = now;
        candidateMapper.updateById(candidate);
        return PlacementResponses.CandidateVersionResponse.from(candidate);
    }

    @Transactional
    public PlacementResponses.CandidateVersionResponse pauseCandidate(Long candidateId) {
        return transitionCandidate(candidateId, "PAUSED", "PUBLISHED");
    }

    @Transactional
    public PlacementResponses.CandidateVersionResponse retireCandidate(Long candidateId) {
        return transitionCandidate(candidateId, "RETIRED", "PUBLISHED", "PAUSED");
    }

    private PlacementResponses.CandidateVersionResponse transitionCandidate(Long candidateId,
                                                                            String target,
                                                                            String... allowedFrom) {
        PlacementCandidateVersion candidate = requireCandidateForUpdate(candidateId);
        boolean allowed = false;
        for (String status : allowedFrom) {
            if (status.equals(candidate.status)) {
                allowed = true;
                break;
            }
        }
        if (!allowed) {
            throw BusinessException.conflict("候选当前状态不能切换为 " + target);
        }
        candidate.status = target;
        candidate.updateTime = LocalDateTime.now();
        candidateMapper.updateById(candidate);
        return PlacementResponses.CandidateVersionResponse.from(candidate);
    }

    @Transactional(readOnly = true)
    public List<PlacementResponses.DecisionAuditResponse> listDecisions(Long slotId, Integer limit) {
        int bounded = boundedLimit(limit);
        return decisionMapper.selectList(new QueryWrapper<PlacementDecision>()
                .eq(slotId != null, "slot_id", slotId)
                .orderByDesc("id")
                .last("LIMIT " + bounded)).stream()
            .map(d -> PlacementResponses.DecisionAuditResponse.from(d, objectMapper))
            .toList();
    }

    @Transactional(readOnly = true)
    public List<PlacementResponses.EventAuditResponse> listEvents(Long slotId, Integer limit) {
        int bounded = boundedLimit(limit);
        LocalDateTime to = LocalDateTime.now();
        LocalDateTime from = to.minusDays(7);
        return eventMapper.findRecentBySlot(slotId, from, to, bounded).stream()
            .map(PlacementResponses.EventAuditResponse::from)
            .toList();
    }

    @Transactional(readOnly = true)
    public List<PlacementResponses.EventAuditResponse> listDecisionEvents(Long decisionId, Integer limit) {
        int bounded = boundedLimit(limit);
        return eventMapper.findByDecision(decisionId, bounded).stream()
            .map(PlacementResponses.EventAuditResponse::from)
            .toList();
    }

    private PlacementSlot requireSlot(Long slotId) {
        if (slotId == null || slotId <= 0) {
            throw BusinessException.badRequest("版位 ID 不合法");
        }
        PlacementSlot slot = slotMapper.selectById(slotId);
        if (slot == null) {
            throw BusinessException.notFound("版位不存在");
        }
        return slot;
    }

    private PlacementCandidateVersion requireCandidateForUpdate(Long candidateId) {
        if (candidateId == null || candidateId <= 0) {
            throw BusinessException.badRequest("候选 ID 不合法");
        }
        PlacementCandidateVersion candidate = candidateMapper.selectById(candidateId);
        if (candidate == null) {
            throw BusinessException.notFound("候选版本不存在");
        }
        return candidate;
    }

    private PlacementSourceSnapshot readForPreviewSafely(PlacementSourceReader reader, Long sourceId) {
        try {
            return reader.readForPreview(sourceId);
        } catch (RuntimeException exception) {
            // Source failures are optional placement input; keep reader internals out of the operator API.
            throw new BusinessException(ResultCode.SYSTEM_ERROR, "来源暂时不可用，请稍后重试");
        }
    }

    private CurrentUser requireCurrentUser() {
        CurrentUser user = SecurityContext.currentUser();
        if (user == null || user.id() == null) {
            throw BusinessException.forbidden("请先登录");
        }
        return user;
    }

    private void validateWindow(LocalDateTime from, LocalDateTime until) {
        if (from == null || until == null || !until.isAfter(from)) {
            throw BusinessException.badRequest("生效时间窗必须满足 effective_until > effective_from");
        }
    }

    private String normalizeAudienceType(String raw) {
        String value = raw == null || raw.isBlank() ? "ALL_AUTHENTICATED" : raw.trim().toUpperCase(Locale.ROOT);
        if (!"ALL_AUTHENTICATED".equals(value) && !"MEMBER_LEVEL".equals(value)) {
            throw BusinessException.badRequest("受众类型不合法");
        }
        return value;
    }

    private String normalizeAudienceLevelCode(String audienceType, String raw) {
        if ("ALL_AUTHENTICATED".equals(audienceType)) {
            return null;
        }
        if (raw == null || raw.isBlank()) {
            throw BusinessException.badRequest("MEMBER_LEVEL 受众必须指定会员等级");
        }
        return raw.trim().toUpperCase(Locale.ROOT);
    }

    private PlacementSourceType parseSourceType(String raw) {
        if (raw == null || raw.isBlank()) {
            throw BusinessException.badRequest("来源类型不能为空");
        }
        try {
            return PlacementSourceType.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw BusinessException.badRequest("来源类型不支持");
        }
    }

    private int boundedLimit(Integer limit) {
        if (limit == null || limit <= 0) {
            return DEFAULT_LIST_LIMIT;
        }
        return Math.min(limit, MAX_LIST_LIMIT);
    }

    private String nextCandidateNo(LocalDateTime now) {
        return "PLC-" + now.format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
            + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase(Locale.ROOT);
    }
}
