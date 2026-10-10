package com.example.venue.member.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.venue.common.BusinessException;
import com.example.venue.common.PageQuery;
import com.example.venue.common.PageResult;
import com.example.venue.member.dto.MemberAccountResponse;
import com.example.venue.member.dto.MemberRewardCatalogResponse;
import com.example.venue.member.dto.MemberRewardEventResponse;
import com.example.venue.member.dto.MemberRewardRedemptionResponse;
import com.example.venue.member.dto.PublishMemberRewardVersionRequest;
import com.example.venue.member.dto.SaveMemberRewardCatalogItemRequest;
import com.example.venue.member.entity.MemberLevel;
import com.example.venue.member.entity.MemberRewardCatalogItem;
import com.example.venue.member.entity.MemberRewardCatalogVersion;
import com.example.venue.member.entity.MemberRewardEvent;
import com.example.venue.member.entity.MemberRewardRedemption;
import com.example.venue.member.mapper.MemberLevelMapper;
import com.example.venue.member.mapper.MemberRewardCatalogItemMapper;
import com.example.venue.member.mapper.MemberRewardCatalogVersionMapper;
import com.example.venue.member.mapper.MemberRewardEventMapper;
import com.example.venue.member.mapper.MemberRewardRedemptionMapper;
import com.example.venue.promotion.service.CouponRewardIssuanceService;
import com.example.venue.security.SecurityContext;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

@Service
public class MemberRewardService {
    private static final int MAX_POINTS_COST = 1_000_000_000;
    private static final int MAX_VALIDITY_DAYS = 3_650;
    private static final java.util.Set<String> REDEMPTION_HISTORY_STATUSES =
        java.util.Set.of("PENDING", "SUCCEEDED", "REJECTED", "FAILED", "COMPENSATED");
    private final MemberRewardCatalogItemMapper itemMapper;
    private final MemberRewardCatalogVersionMapper versionMapper;
    private final MemberRewardRedemptionMapper redemptionMapper;
    private final MemberRewardEventMapper eventMapper;
    private final MemberRewardEventService eventService;
    private final MemberLevelMapper levelMapper;
    private final MemberService memberService;
    private final MemberRewardCommandService commandService;
    private final MemberRewardExecutionService executionService;
    private final MemberRewardFailureService failureService;
    private final MemberRewardRetryService retryService;
    private final CouponRewardIssuanceService couponService;
    private final MemberRewardExecutionFaultInjector faultInjector;
    private final ObjectMapper objectMapper;

    public MemberRewardService(MemberRewardCatalogItemMapper itemMapper,
                               MemberRewardCatalogVersionMapper versionMapper,
                               MemberRewardRedemptionMapper redemptionMapper,
                               MemberRewardEventMapper eventMapper, MemberRewardEventService eventService,
                               MemberLevelMapper levelMapper,
                               MemberService memberService, MemberRewardCommandService commandService,
                               MemberRewardExecutionService executionService,
                               MemberRewardFailureService failureService,
                               MemberRewardRetryService retryService,
                               CouponRewardIssuanceService couponService,
                               MemberRewardExecutionFaultInjector faultInjector, ObjectMapper objectMapper) {
        this.itemMapper = itemMapper;
        this.versionMapper = versionMapper;
        this.redemptionMapper = redemptionMapper;
        this.eventMapper = eventMapper;
        this.eventService = eventService;
        this.levelMapper = levelMapper;
        this.memberService = memberService;
        this.commandService = commandService;
        this.executionService = executionService;
        this.failureService = failureService;
        this.retryService = retryService;
        this.couponService = couponService;
        this.faultInjector = faultInjector;
        this.objectMapper = objectMapper;
    }

    public List<MemberRewardCatalogResponse> catalog() {
        Long userId = currentUserId();
        MemberAccountResponse account = memberService.account(userId);
        return itemMapper.selectList(new QueryWrapper<MemberRewardCatalogItem>()
                .eq("status", "ACTIVE").eq("deleted", 0).orderByAsc("id"))
            .stream().map(item -> response(item,
                versionMapper.findCurrentPublished(item.id, LocalDateTime.now()), account)).toList();
    }

    public MemberRewardCatalogResponse preview(Long itemId) {
        Long userId = currentUserId();
        MemberRewardCatalogItem item = requireItem(itemId);
        MemberRewardCatalogVersion version = versionMapper.findCurrentPublished(itemId, LocalDateTime.now());
        return response(item, version, memberService.account(userId));
    }

    public MemberRewardRedemptionResponse redeem(Long itemId, Long catalogVersionId, String idempotencyKey) {
        Long userId = currentUserId();
        if (itemId == null || itemId <= 0) {
            throw BusinessException.badRequest("兑换目录不能为空");
        }
        if (catalogVersionId == null || catalogVersionId <= 0) {
            throw BusinessException.badRequest("兑换版本不能为空");
        }
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 128) {
            throw BusinessException.badRequest("兑换幂等键不能为空且不能超过 128 字符");
        }
        String normalizedKey = idempotencyKey.trim();
        String requestHash = requestHash(userId, itemId, catalogVersionId);
        MemberRewardRedemption existing = commandService.findExisting(userId, normalizedKey, requestHash);
        if (existing != null) {
            if (!"PENDING".equals(existing.status)) {
                return MemberRewardRedemptionResponse.from(existing);
            }
            return executePending(existing.id);
        }
        MemberRewardCatalogItem item = requireItem(itemId);
        MemberRewardCatalogVersion version = versionMapper.selectById(catalogVersionId);
        if (version == null || !item.id.equals(version.catalogItemId)
            || !"PUBLISHED".equals(version.status)) {
            throw BusinessException.conflict("预览兑换版本已不可用，请刷新后重试");
        }
        MemberRewardRedemption command = command(item, version, userId, normalizedKey, requestHash);
        MemberRewardRedemption registered = commandService.register(command);
        if (!"PENDING".equals(registered.status)) {
            return MemberRewardRedemptionResponse.from(registered);
        }
        return executePending(registered.id);
    }

    private MemberRewardRedemptionResponse executePending(Long redemptionId) {
        MemberRewardRedemption result;
        try {
            result = executionService.execute(redemptionId);
        } catch (RuntimeException unexpected) {
            MemberRewardRedemption failed = failureService.markFailed(redemptionId, "EXECUTION_ERROR");
            if (failed == null) throw unexpected;
            return MemberRewardRedemptionResponse.from(failed);
        }
        if ("SUCCEEDED".equals(result.status)) {
            faultInjector.afterSuccessfulCommit(result.id);
        }
        return MemberRewardRedemptionResponse.from(result);
    }

    public PageResult<MemberRewardRedemptionResponse> history(PageQuery query, String status) {
        PageQuery safe = query == null ? new PageQuery() : query;
        String normalizedStatus = normalizeHistoryStatus(status);
        QueryWrapper<MemberRewardRedemption> wrapper = new QueryWrapper<MemberRewardRedemption>()
            .eq("user_id", currentUserId());
        if (normalizedStatus != null) {
            wrapper.eq("status", normalizedStatus);
        }
        Page<MemberRewardRedemption> page = redemptionMapper.selectPage(
            new Page<>(safe.getPageNum(), safe.getPageSize()),
            wrapper.orderByDesc("create_time").orderByDesc("id"));
        return PageResult.of(page.getRecords().stream().map(MemberRewardRedemptionResponse::from).toList(),
            page.getTotal(), safe.getPageNum(), safe.getPageSize());
    }

    private String normalizeHistoryStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        String normalized = status.trim().toUpperCase(Locale.ROOT);
        if (!REDEMPTION_HISTORY_STATUSES.contains(normalized)) {
            throw BusinessException.badRequest("兑换记录状态仅支持 PENDING/SUCCEEDED/REJECTED/FAILED/COMPENSATED");
        }
        return normalized;
    }

    public List<MemberRewardCatalogResponse> listCatalogItems() {
        return itemMapper.selectList(new QueryWrapper<MemberRewardCatalogItem>()
                .eq("deleted", 0).orderByDesc("update_time").orderByDesc("id"))
            .stream().map(item -> response(item,
                versionMapper.findCurrentPublished(item.id, LocalDateTime.now()), null)).toList();
    }

    @Transactional
    public MemberRewardCatalogResponse setCatalogItemStatus(Long itemId, String status) {
        if (itemId == null || itemId <= 0) {
            throw BusinessException.badRequest("兑换目录不能为空");
        }
        if (!"ACTIVE".equals(status) && !"INACTIVE".equals(status)) {
            throw BusinessException.badRequest("目录状态仅支持启用或停用");
        }
        MemberRewardCatalogItem item = itemMapper.selectByIdForUpdate(itemId);
        if (item == null || Integer.valueOf(1).equals(item.deleted)) {
            throw BusinessException.notFound("兑换目录不存在");
        }
        if (item.status.equals(status)) {
            return response(item, versionMapper.findCurrentPublished(item.id, LocalDateTime.now()), null);
        }
        String fromStatus = item.status;
        if ("ACTIVE".equals(status) && !"INACTIVE".equals(item.status) && !"DRAFT".equals(item.status)) {
            throw BusinessException.conflict("仅草稿或停用状态的目录可启用");
        }
        if ("INACTIVE".equals(status) && !"ACTIVE".equals(item.status)) {
            throw BusinessException.conflict("仅启用状态的目录可停用");
        }
        item.status = status;
        item.updatedBy = currentUserId();
        item.updateTime = LocalDateTime.now();
        itemMapper.updateById(item);
        eventService.catalogStatusChanged(item, fromStatus, status);
        return response(item, versionMapper.findCurrentPublished(item.id, LocalDateTime.now()), null);
    }

    @Transactional
    public MemberRewardCatalogResponse createCatalogItem(SaveMemberRewardCatalogItemRequest request) {
        if (request == null || request.itemCode == null || request.itemCode.isBlank()
            || request.itemCode.trim().length() > 64
            || request.itemName == null || request.itemName.isBlank() || request.itemName.trim().length() > 128
            || (request.descriptionText != null && request.descriptionText.length() > 500)) {
            throw BusinessException.badRequest("兑换目录参数不完整");
        }
        MemberRewardCatalogItem item = new MemberRewardCatalogItem();
        item.itemCode = request.itemCode.trim().toUpperCase(Locale.ROOT);
        item.itemName = request.itemName.trim();
        item.descriptionText = trim(request.descriptionText);
        item.status = "DRAFT";
        item.createdBy = currentUserId();
        item.updatedBy = item.createdBy;
        item.createTime = LocalDateTime.now();
        item.updateTime = item.createTime;
        item.deleted = 0;
        itemMapper.insert(item);
        return response(item, null, null);
    }

    @Transactional
    public MemberRewardCatalogResponse publishVersion(Long itemId, PublishMemberRewardVersionRequest request) {
        if (itemId == null || itemId <= 0) {
            throw BusinessException.badRequest("兑换目录不能为空");
        }
        MemberRewardCatalogItem item = itemMapper.selectByIdForUpdate(itemId);
        if (item == null || Integer.valueOf(1).equals(item.deleted)) throw BusinessException.notFound("兑换目录不存在");
        if (request == null || request.pointsCost == null || request.pointsCost <= 0
            || request.pointsCost > MAX_POINTS_COST
            || request.minimumLevelCode == null || request.minimumLevelCode.isBlank()
            || request.minimumLevelCode.trim().length() > 32
            || request.couponTemplateId == null || request.couponTemplateId <= 0
            || (request.validityDays != null
                && (request.validityDays <= 0 || request.validityDays > MAX_VALIDITY_DAYS))) {
            throw BusinessException.badRequest("兑换版本参数不完整");
        }
        if (request.effectiveStartTime != null && request.effectiveEndTime != null
            && !request.effectiveEndTime.isAfter(request.effectiveStartTime)) {
            throw BusinessException.badRequest("兑换版本结束时间必须晚于开始时间");
        }
        MemberLevel level = levelMapper.findByCode(request.minimumLevelCode.trim().toUpperCase(Locale.ROOT));
        if (level == null || Integer.valueOf(1).equals(level.deleted)) throw BusinessException.notFound("会员等级不存在");
        couponService.validateCatalogTemplate(request.couponTemplateId);
        String fromStatus = item.status;
        MemberRewardCatalogVersion version = new MemberRewardCatalogVersion();
        version.catalogItemId = item.id;
        version.versionNo = versionMapper.maxVersionNo(item.id) + 1;
        version.pointsCost = request.pointsCost;
        version.minimumLevelCode = level.levelCode;
        version.minimumLevelName = level.levelName;
        version.minimumGrowthThreshold = level.growthThreshold;
        version.couponTemplateId = request.couponTemplateId;
        version.validityDays = request.validityDays;
        version.effectiveStartTime = request.effectiveStartTime;
        version.effectiveEndTime = request.effectiveEndTime;
        version.configSummaryJson = json(version);
        version.status = "PUBLISHED";
        version.publishedBy = currentUserId();
        version.publishedAt = LocalDateTime.now();
        version.createTime = version.publishedAt;
        versionMapper.retirePublished(item.id);
        versionMapper.insert(version);
        item.status = "ACTIVE";
        item.updatedBy = version.publishedBy;
        item.updateTime = version.publishedAt;
        itemMapper.updateById(item);
        eventService.catalogVersionPublished(item, version, fromStatus);
        return response(item, version, null);
    }

    /**
     * 后台异常兑换列表（SHIGUANG-BATCH-04-REFACTOR-20260914 S03 / B4-M1）。
     *
     * <p>默认范围保持既有三态 {@code PENDING/FAILED/REJECTED}；显式传入 {@code status} 时按该状态精确过滤，
     * 使 {@code SUCCEEDED}（补偿的唯一合法前置）在后台可达：补偿端点只接受 {@code SUCCEEDED} 且券非空，
     * 而默认列表不含该状态，因此此前无法从后台取得可补偿的兑换 id。状态白名单与用户侧历史共用
     * {@link #normalizeHistoryStatus(String)}，非法值沿用同一 400 文案。</p>
     */
    public PageResult<MemberRewardRedemptionResponse> exceptions(PageQuery query, String status) {
        PageQuery safe = query == null ? new PageQuery() : query;
        String normalizedStatus = normalizeHistoryStatus(status);
        QueryWrapper<MemberRewardRedemption> wrapper = new QueryWrapper<>();
        if (normalizedStatus == null) {
            wrapper.in("status", "PENDING", "FAILED", "REJECTED");
        } else {
            wrapper.eq("status", normalizedStatus);
        }
        Page<MemberRewardRedemption> page = redemptionMapper.selectPage(
            new Page<>(safe.getPageNum(), safe.getPageSize()),
            wrapper.orderByDesc("update_time").orderByDesc("id"));
        return PageResult.of(page.getRecords().stream().map(MemberRewardRedemptionResponse::from).toList(),
            page.getTotal(), safe.getPageNum(), safe.getPageSize());
    }

    public MemberRewardRedemptionResponse retry(Long id) {
        Long operatorId = currentUserId();
        MemberRewardRetryService.RetryAttempt attempt = retryService.start(id, operatorId);
        try {
            executionService.execute(id);
        } catch (RuntimeException unexpected) {
            MemberRewardRedemption failed = failureService.markFailed(id, "RETRY_EXECUTION_ERROR");
            if (failed == null) throw unexpected;
        }
        return MemberRewardRedemptionResponse.from(retryService.finish(attempt));
    }

    public MemberRewardRedemptionResponse compensate(Long id, String reason) {
        if (reason == null || reason.isBlank()) throw BusinessException.badRequest("补偿原因不能为空");
        return MemberRewardRedemptionResponse.from(executionService.compensate(id, currentUserId(), reason.trim()));
    }

    public PageResult<MemberRewardEventResponse> events(PageQuery query) {
        PageQuery safe = query == null ? new PageQuery() : query;
        Page<MemberRewardEvent> page = eventMapper.selectPage(new Page<>(safe.getPageNum(), safe.getPageSize()),
            new QueryWrapper<MemberRewardEvent>().orderByDesc("occurred_at").orderByDesc("id"));
        return PageResult.of(page.getRecords().stream().map(MemberRewardEventResponse::from).toList(),
            page.getTotal(), safe.getPageNum(), safe.getPageSize());
    }

    private MemberRewardRedemption command(MemberRewardCatalogItem item, MemberRewardCatalogVersion version,
                                            Long userId, String idempotencyKey, String requestHash) {
        MemberRewardRedemption command = new MemberRewardRedemption();
        command.userId = userId;
        command.catalogItemId = item.id;
        command.catalogVersionId = version.id;
        command.itemCodeSnapshot = item.itemCode;
        command.itemNameSnapshot = item.itemName;
        command.catalogVersionNo = version.versionNo;
        command.pointsCost = version.pointsCost;
        command.minimumLevelCodeSnapshot = version.minimumLevelCode;
        command.minimumLevelNameSnapshot = version.minimumLevelName;
        command.minimumGrowthThresholdSnapshot = version.minimumGrowthThreshold;
        command.couponTemplateId = version.couponTemplateId;
        command.validityDaysSnapshot = version.validityDays;
        command.idempotencyKey = idempotencyKey;
        command.requestHash = requestHash;
        command.sourceType = "USER";
        command.createdBy = userId;
        return command;
    }

    private MemberRewardCatalogResponse response(MemberRewardCatalogItem item,
                                                  MemberRewardCatalogVersion version,
                                                  MemberAccountResponse account) {
        MemberRewardCatalogResponse response = new MemberRewardCatalogResponse();
        response.itemId = item.id;
        response.itemCode = item.itemCode;
        response.itemName = item.itemName;
        response.descriptionText = item.descriptionText;
        response.status = item.status;
        if (version == null) {
            response.eligible = false;
            response.unavailableReason = "当前没有可兑换版本";
            return response;
        }
        response.versionId = version.id;
        response.versionNo = version.versionNo;
        response.pointsCost = version.pointsCost;
        response.minimumLevelCode = version.minimumLevelCode;
        response.minimumLevelName = version.minimumLevelName;
        response.minimumGrowthThreshold = version.minimumGrowthThreshold;
        response.couponTemplateId = version.couponTemplateId;
        response.validityDays = version.validityDays;
        response.effectiveStartTime = version.effectiveStartTime;
        response.effectiveEndTime = version.effectiveEndTime;
        if (account != null) {
            response.availablePoints = Math.max(0, account.pointsBalance);
            response.pendingOffsetPoints = Math.max(0, -account.pointsBalance);
            response.eligible = account.growthValue >= version.minimumGrowthThreshold
                && account.pointsBalance >= version.pointsCost;
            response.unavailableReason = response.eligible ? null
                : account.growthValue < version.minimumGrowthThreshold ? "会员等级未达到兑换门槛" : "可用积分不足";
        }
        return response;
    }

    private MemberRewardCatalogItem requireItem(Long id) {
        MemberRewardCatalogItem item = itemMapper.selectById(id);
        if (item == null || Integer.valueOf(1).equals(item.deleted)) throw BusinessException.notFound("兑换目录不存在");
        return item;
    }

    private Long currentUserId() {
        Long id = SecurityContext.currentUserId();
        if (id == null) throw BusinessException.forbidden("未登录");
        return id;
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private String requestHash(Long userId, Long itemId, Long catalogVersionId) {
        return sha256(userId + ":" + itemId + ":" + catalogVersionId);
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw BusinessException.badRequest("兑换版本摘要无法序列化");
        }
    }

    private String trim(String value) { return value == null || value.isBlank() ? null : value.trim(); }
}
