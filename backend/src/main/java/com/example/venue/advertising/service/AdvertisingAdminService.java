package com.example.venue.advertising.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.example.venue.advertising.dto.AdvertisingRequests;
import com.example.venue.advertising.dto.AdvertisingResponses;
import com.example.venue.advertising.entity.AdvertisingAdvertiser;
import com.example.venue.advertising.entity.AdvertisingAuditEvent;
import com.example.venue.advertising.entity.AdvertisingCampaign;
import com.example.venue.advertising.entity.AdvertisingCreativeVersion;
import com.example.venue.advertising.enums.AdvertisingLifecycleStatus;
import com.example.venue.advertising.enums.AdvertisingTargetType;
import com.example.venue.advertising.mapper.AdvertisingAdvertiserMapper;
import com.example.venue.advertising.mapper.AdvertisingAuditEventMapper;
import com.example.venue.advertising.mapper.AdvertisingCampaignMapper;
import com.example.venue.advertising.mapper.AdvertisingCreativeVersionMapper;
import com.example.venue.common.BusinessException;
import com.example.venue.common.Constants;
import com.example.venue.common.CurrentUser;
import com.example.venue.common.EntityExistenceService;
import com.example.venue.security.SecurityContext;
import com.example.venue.venue.service.VenueAccessService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * 商业广告管理服务。广告事实只在本域写入，所有状态改变都经过锁定、状态白名单和追加审计。
 * 本服务不读取 R6 placement、预约、订单或支付事实，也不接受外链/上传型创意。
 */
@Service
public class AdvertisingAdminService {

    private static final int DEFAULT_LIST_LIMIT = 50;
    private static final int MAX_LIST_LIMIT = 100;
    private static final String ACTIVE = "ACTIVE";
    private static final String SUSPENDED = "SUSPENDED";
    private static final String RETIRED = "RETIRED";
    private static final String SOURCE_TYPE = "OPERATOR_API";
    private static final DateTimeFormatter NO_FORMAT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final AdvertisingAdvertiserMapper advertiserMapper;
    private final AdvertisingCampaignMapper campaignMapper;
    private final AdvertisingCreativeVersionMapper creativeMapper;
    private final AdvertisingAuditEventMapper auditMapper;
    private final EntityExistenceService entityExistenceService;
    private final VenueAccessService venueAccessService;

    public AdvertisingAdminService(AdvertisingAdvertiserMapper advertiserMapper,
                                   AdvertisingCampaignMapper campaignMapper,
                                   AdvertisingCreativeVersionMapper creativeMapper,
                                   AdvertisingAuditEventMapper auditMapper,
                                   EntityExistenceService entityExistenceService,
                                   VenueAccessService venueAccessService) {
        this.advertiserMapper = advertiserMapper;
        this.campaignMapper = campaignMapper;
        this.creativeMapper = creativeMapper;
        this.auditMapper = auditMapper;
        this.entityExistenceService = entityExistenceService;
        this.venueAccessService = venueAccessService;
    }

    @Transactional(readOnly = true)
    public List<AdvertisingResponses.AdvertiserResponse> listAdvertisers(Integer limit) {
        requireCurrentUser();
        return advertiserMapper.selectList(new QueryWrapper<AdvertisingAdvertiser>()
                .orderByDesc("id")
                .last("LIMIT " + boundedLimit(limit)))
            .stream()
            .map(AdvertisingResponses.AdvertiserResponse::from)
            .toList();
    }

    @Transactional
    public AdvertisingResponses.AdvertiserResponse createAdvertiser(
        AdvertisingRequests.CreateAdvertiserRequest request
    ) {
        CurrentUser actor = requireCurrentUser();
        if (request == null) {
            throw BusinessException.badRequest("广告主请求不能为空");
        }
        String name = normalizePlainText(request.advertiserName(), "广告主名称", 128);
        LocalDateTime now = LocalDateTime.now();
        AdvertisingAdvertiser advertiser = new AdvertisingAdvertiser();
        advertiser.advertiserNo = nextNo("ADV-ADVER", now);
        advertiser.advertiserName = name;
        advertiser.status = ACTIVE;
        advertiser.createdBy = actor.id();
        advertiser.createTime = now;
        advertiser.updateTime = now;
        advertiserMapper.insert(advertiser);
        appendAudit("ADVERTISER", advertiser.id, advertiser.advertiserNo, null,
            null, null, "CREATE", actor, null, null, ACTIVE);
        return AdvertisingResponses.AdvertiserResponse.from(advertiser);
    }

    @Transactional
    public AdvertisingResponses.AdvertiserResponse suspendAdvertiser(
        Long advertiserId, AdvertisingRequests.TransitionRequest request
    ) {
        return transitionAdvertiser(advertiserId, SUSPENDED, List.of(ACTIVE), "SUSPEND", request);
    }

    @Transactional
    public AdvertisingResponses.AdvertiserResponse retireAdvertiser(
        Long advertiserId, AdvertisingRequests.TransitionRequest request
    ) {
        return transitionAdvertiser(advertiserId, RETIRED, List.of(ACTIVE, SUSPENDED), "RETIRE", request);
    }

    @Transactional(readOnly = true)
    public List<AdvertisingResponses.CampaignResponse> listCampaigns(Long venueId, Integer limit) {
        CurrentUser actor = requireCurrentUser();
        QueryWrapper<AdvertisingCampaign> wrapper = new QueryWrapper<>();
        applyVenueScope(wrapper, actor, venueId);
        return campaignMapper.selectList(wrapper.orderByDesc("id").last("LIMIT " + boundedLimit(limit)))
            .stream()
            .map(AdvertisingResponses.CampaignResponse::from)
            .toList();
    }

    @Transactional
    public AdvertisingResponses.CampaignResponse createCampaign(
        AdvertisingRequests.CreateCampaignRequest request
    ) {
        CurrentUser actor = requireCurrentUser();
        if (request == null) {
            throw BusinessException.badRequest("广告计划请求不能为空");
        }
        validateWindow(request.effectiveFrom(), request.effectiveUntil());
        String name = normalizePlainText(request.campaignName(), "计划名称", 128);
        requireVenueScopeForWrite(request.venueId());
        AdvertisingAdvertiser advertiser = requireAdvertiserForUpdate(request.advertiserId());
        if (!ACTIVE.equals(advertiser.status)) {
            throw BusinessException.conflict("广告主当前不可创建计划");
        }

        LocalDateTime now = LocalDateTime.now();
        AdvertisingCampaign campaign = new AdvertisingCampaign();
        campaign.campaignNo = nextNo("ADV-CAMP", now);
        campaign.advertiserId = advertiser.id;
        campaign.venueId = request.venueId();
        campaign.campaignName = name;
        campaign.effectiveFrom = request.effectiveFrom();
        campaign.effectiveUntil = request.effectiveUntil();
        campaign.status = AdvertisingLifecycleStatus.DRAFT.name();
        campaign.createdBy = actor.id();
        campaign.createTime = now;
        campaign.updateTime = now;
        campaignMapper.insert(campaign);
        appendAudit("CAMPAIGN", campaign.id, campaign.campaignNo, campaign.venueId,
            "ADVERTISER", advertiser.id, "CREATE", actor, null, null, campaign.status);
        return AdvertisingResponses.CampaignResponse.from(campaign);
    }

    @Transactional
    public AdvertisingResponses.CampaignResponse submitCampaignReview(Long campaignId) {
        CurrentUser actor = requireCurrentUser();
        AdvertisingCampaign campaign = requireCampaignForUpdate(campaignId);
        requireCampaignScope(campaign);
        requireStatus(campaign.status, List.of(
            AdvertisingLifecycleStatus.DRAFT.name(), AdvertisingLifecycleStatus.REJECTED.name()),
            "计划当前状态不能提交审核");
        String before = campaign.status;
        LocalDateTime now = LocalDateTime.now();
        campaign.status = AdvertisingLifecycleStatus.PENDING_REVIEW.name();
        campaign.submittedBy = actor.id();
        campaign.submittedAt = now;
        campaign.updateTime = now;
        campaignMapper.updateById(campaign);
        appendAudit("CAMPAIGN", campaign.id, campaign.campaignNo, campaign.venueId,
            "ADVERTISER", campaign.advertiserId, "SUBMIT_REVIEW", actor, null, before, campaign.status);
        return AdvertisingResponses.CampaignResponse.from(campaign);
    }

    @Transactional
    public AdvertisingResponses.CampaignResponse approveCampaign(Long campaignId) {
        return reviewCampaign(campaignId, true, null);
    }

    @Transactional
    public AdvertisingResponses.CampaignResponse rejectCampaign(
        Long campaignId, AdvertisingRequests.ReviewDecisionRequest request
    ) {
        return reviewCampaign(campaignId, false, requireReviewReason(request));
    }

    @Transactional
    public AdvertisingResponses.CampaignResponse publishCampaign(Long campaignId) {
        return transitionCampaign(campaignId, AdvertisingLifecycleStatus.PUBLISHED,
            List.of(AdvertisingLifecycleStatus.APPROVED.name(), AdvertisingLifecycleStatus.PAUSED.name()),
            "PUBLISH", null);
    }

    @Transactional
    public AdvertisingResponses.CampaignResponse pauseCampaign(
        Long campaignId, AdvertisingRequests.TransitionRequest request
    ) {
        return transitionCampaign(campaignId, AdvertisingLifecycleStatus.PAUSED,
            List.of(AdvertisingLifecycleStatus.PUBLISHED.name()), "PAUSE", request);
    }

    @Transactional
    public AdvertisingResponses.CampaignResponse retireCampaign(
        Long campaignId, AdvertisingRequests.TransitionRequest request
    ) {
        return transitionCampaign(campaignId, AdvertisingLifecycleStatus.RETIRED,
            List.of(AdvertisingLifecycleStatus.APPROVED.name(),
                AdvertisingLifecycleStatus.PUBLISHED.name(), AdvertisingLifecycleStatus.PAUSED.name()),
            "RETIRE", request);
    }

    @Transactional(readOnly = true)
    public List<AdvertisingResponses.CreativeResponse> listCreatives(Long campaignId, Integer limit) {
        AdvertisingCampaign campaign = requireCampaign(campaignId);
        requireCampaignScope(campaign);
        return creativeMapper.selectList(new QueryWrapper<AdvertisingCreativeVersion>()
                .eq("campaign_id", campaign.id)
                .orderByDesc("version_no", "id")
                .last("LIMIT " + boundedLimit(limit)))
            .stream()
            .map(AdvertisingResponses.CreativeResponse::from)
            .toList();
    }

    @Transactional
    public AdvertisingResponses.CreativeResponse createCreative(
        AdvertisingRequests.CreateCreativeRequest request
    ) {
        CurrentUser actor = requireCurrentUser();
        if (request == null) {
            throw BusinessException.badRequest("创意请求不能为空");
        }
        AdvertisingCampaign campaign = requireCampaignForUpdate(request.campaignId());
        requireCampaignScope(campaign);
        if (AdvertisingLifecycleStatus.RETIRED.name().equals(campaign.status)) {
            throw BusinessException.conflict("已停用计划不能新增创意");
        }
        String headline = normalizePlainText(request.headline(), "创意标题", 80);
        String bodyText = normalizePlainText(request.bodyText(), "创意正文", 500);
        String callToAction = normalizeCallToAction(request.callToAction());
        AdvertisingTargetType targetType = parseTargetType(request.targetType());
        validateTarget(targetType, request.targetId());
        if (creativeMapper.selectOne(new QueryWrapper<AdvertisingCreativeVersion>()
            .eq("campaign_id", campaign.id)
            .eq("version_no", request.versionNo())) != null) {
            throw BusinessException.conflict("同一计划的创意版本号已存在");
        }

        LocalDateTime now = LocalDateTime.now();
        AdvertisingCreativeVersion creative = new AdvertisingCreativeVersion();
        creative.creativeNo = nextNo("ADV-CREAT", now);
        creative.campaignId = campaign.id;
        creative.versionNo = request.versionNo();
        creative.headline = headline;
        creative.bodyText = bodyText;
        creative.callToAction = callToAction;
        creative.targetType = targetType.name();
        creative.targetId = request.targetId();
        creative.status = AdvertisingLifecycleStatus.DRAFT.name();
        creative.createdBy = actor.id();
        creative.createTime = now;
        creative.updateTime = now;
        creativeMapper.insert(creative);
        appendAudit("CREATIVE", creative.id, creative.creativeNo, campaign.venueId,
            "CAMPAIGN", campaign.id, "CREATE", actor, null, null, creative.status);
        return AdvertisingResponses.CreativeResponse.from(creative);
    }

    @Transactional
    public AdvertisingResponses.CreativeResponse submitCreativeReview(Long creativeId) {
        CurrentUser actor = requireCurrentUser();
        CreativeContext context = requireCreativeContextForUpdate(creativeId);
        ensureCampaignAvailable(context.campaign());
        AdvertisingCreativeVersion creative = context.creative();
        requireStatus(creative.status, List.of(
            AdvertisingLifecycleStatus.DRAFT.name(), AdvertisingLifecycleStatus.REJECTED.name()),
            "创意当前状态不能提交审核");
        String before = creative.status;
        LocalDateTime now = LocalDateTime.now();
        creative.status = AdvertisingLifecycleStatus.PENDING_REVIEW.name();
        creative.submittedBy = actor.id();
        creative.submittedAt = now;
        creative.updateTime = now;
        creativeMapper.updateById(creative);
        appendAudit("CREATIVE", creative.id, creative.creativeNo, context.campaign().venueId,
            "CAMPAIGN", creative.campaignId, "SUBMIT_REVIEW", actor, null, before, creative.status);
        return AdvertisingResponses.CreativeResponse.from(creative);
    }

    @Transactional
    public AdvertisingResponses.CreativeResponse approveCreative(Long creativeId) {
        return reviewCreative(creativeId, true, null);
    }

    @Transactional
    public AdvertisingResponses.CreativeResponse rejectCreative(
        Long creativeId, AdvertisingRequests.ReviewDecisionRequest request
    ) {
        return reviewCreative(creativeId, false, requireReviewReason(request));
    }

    @Transactional
    public AdvertisingResponses.CreativeResponse publishCreative(Long creativeId) {
        CreativeContext context = requireCreativeContextForUpdate(creativeId);
        ensureCampaignAvailable(context.campaign());
        if (!List.of(AdvertisingLifecycleStatus.APPROVED.name(),
            AdvertisingLifecycleStatus.PUBLISHED.name(), AdvertisingLifecycleStatus.PAUSED.name())
            .contains(context.campaign().status)) {
            throw BusinessException.conflict("计划尚未通过审核，创意不能发布");
        }
        requireAdvertiserActive(context.campaign().advertiserId);
        return transitionCreative(context, AdvertisingLifecycleStatus.PUBLISHED,
            List.of(AdvertisingLifecycleStatus.APPROVED.name(), AdvertisingLifecycleStatus.PAUSED.name()),
            "PUBLISH", null);
    }

    @Transactional
    public AdvertisingResponses.CreativeResponse pauseCreative(
        Long creativeId, AdvertisingRequests.TransitionRequest request
    ) {
        CreativeContext context = requireCreativeContextForUpdate(creativeId);
        ensureCampaignAvailable(context.campaign());
        return transitionCreative(context, AdvertisingLifecycleStatus.PAUSED,
            List.of(AdvertisingLifecycleStatus.PUBLISHED.name()), "PAUSE", request);
    }

    @Transactional
    public AdvertisingResponses.CreativeResponse retireCreative(
        Long creativeId, AdvertisingRequests.TransitionRequest request
    ) {
        CreativeContext context = requireCreativeContextForUpdate(creativeId);
        ensureCampaignAvailable(context.campaign());
        return transitionCreative(context, AdvertisingLifecycleStatus.RETIRED,
            List.of(AdvertisingLifecycleStatus.APPROVED.name(),
                AdvertisingLifecycleStatus.PUBLISHED.name(), AdvertisingLifecycleStatus.PAUSED.name()),
            "RETIRE", request);
    }

    @Transactional(readOnly = true)
    public List<AdvertisingResponses.AuditResponse> listAudits(
        String entityType, Long entityId, Long venueId, Integer limit
    ) {
        CurrentUser actor = requireCurrentUser();
        QueryWrapper<AdvertisingAuditEvent> wrapper = new QueryWrapper<>();
        if (entityType != null && !entityType.isBlank()) {
            wrapper.eq("entity_type", normalizeAuditEntityType(entityType));
        }
        if (entityId != null) {
            if (entityId <= 0) {
                throw BusinessException.badRequest("关联实体 ID 不合法");
            }
            wrapper.eq("entity_id", entityId);
        }
        applyVenueScope(wrapper, actor, venueId);
        return auditMapper.selectList(wrapper.orderByDesc("id").last("LIMIT " + boundedLimit(limit)))
            .stream()
            .map(AdvertisingResponses.AuditResponse::from)
            .toList();
    }

    private AdvertisingResponses.AdvertiserResponse transitionAdvertiser(
        Long advertiserId, String target, List<String> allowedFrom, String action,
        AdvertisingRequests.TransitionRequest request
    ) {
        CurrentUser actor = requireCurrentUser();
        AdvertisingAdvertiser advertiser = requireAdvertiserForUpdate(advertiserId);
        requireStatus(advertiser.status, allowedFrom, "广告主当前状态不能执行该操作");
        String before = advertiser.status;
        advertiser.status = target;
        advertiser.updateTime = LocalDateTime.now();
        advertiserMapper.updateById(advertiser);
        Reason reason = normalizeReason(request);
        appendAudit("ADVERTISER", advertiser.id, advertiser.advertiserNo, null,
            null, null, action, actor, reason, before, target);
        return AdvertisingResponses.AdvertiserResponse.from(advertiser);
    }

    private AdvertisingResponses.CampaignResponse reviewCampaign(
        Long campaignId, boolean approve, Reason reason
    ) {
        CurrentUser actor = requireCurrentUser();
        AdvertisingCampaign campaign = requireCampaignForUpdate(campaignId);
        requireCampaignScope(campaign);
        requireStatus(campaign.status, List.of(AdvertisingLifecycleStatus.PENDING_REVIEW.name()),
            "计划当前状态不能审核");
        rejectSelfReview(campaign.createdBy, actor.id());
        String before = campaign.status;
        LocalDateTime now = LocalDateTime.now();
        campaign.status = approve
            ? AdvertisingLifecycleStatus.APPROVED.name()
            : AdvertisingLifecycleStatus.REJECTED.name();
        campaign.reviewedBy = actor.id();
        campaign.reviewedAt = now;
        campaign.updateTime = now;
        campaignMapper.updateById(campaign);
        appendAudit("CAMPAIGN", campaign.id, campaign.campaignNo, campaign.venueId,
            "ADVERTISER", campaign.advertiserId, approve ? "APPROVE" : "REJECT", actor,
            reason, before, campaign.status);
        return AdvertisingResponses.CampaignResponse.from(campaign);
    }

    private AdvertisingResponses.CampaignResponse transitionCampaign(
        Long campaignId, AdvertisingLifecycleStatus target, List<String> allowedFrom,
        String action, AdvertisingRequests.TransitionRequest request
    ) {
        CurrentUser actor = requireCurrentUser();
        AdvertisingCampaign campaign = requireCampaignForUpdate(campaignId);
        requireCampaignScope(campaign);
        requireStatus(campaign.status, allowedFrom, "计划当前状态不能执行该操作");
        if (target == AdvertisingLifecycleStatus.PUBLISHED) {
            requireAdvertiserActive(campaign.advertiserId);
            if (campaign.reviewedBy == null) {
                throw BusinessException.conflict("计划尚未完成审核");
            }
        }
        String before = campaign.status;
        LocalDateTime now = LocalDateTime.now();
        campaign.status = target.name();
        if (target == AdvertisingLifecycleStatus.PUBLISHED) {
            campaign.publishedBy = actor.id();
            campaign.publishedAt = now;
        } else if (target == AdvertisingLifecycleStatus.PAUSED) {
            campaign.pausedBy = actor.id();
            campaign.pausedAt = now;
        } else if (target == AdvertisingLifecycleStatus.RETIRED) {
            campaign.retiredBy = actor.id();
            campaign.retiredAt = now;
        }
        campaign.updateTime = now;
        campaignMapper.updateById(campaign);
        appendAudit("CAMPAIGN", campaign.id, campaign.campaignNo, campaign.venueId,
            "ADVERTISER", campaign.advertiserId, action, actor, normalizeReason(request), before, target.name());
        return AdvertisingResponses.CampaignResponse.from(campaign);
    }

    private AdvertisingResponses.CreativeResponse reviewCreative(
        Long creativeId, boolean approve, Reason reason
    ) {
        CurrentUser actor = requireCurrentUser();
        CreativeContext context = requireCreativeContextForUpdate(creativeId);
        ensureCampaignAvailable(context.campaign());
        AdvertisingCreativeVersion creative = context.creative();
        requireStatus(creative.status, List.of(AdvertisingLifecycleStatus.PENDING_REVIEW.name()),
            "创意当前状态不能审核");
        rejectSelfReview(creative.createdBy, actor.id());
        String before = creative.status;
        LocalDateTime now = LocalDateTime.now();
        creative.status = approve
            ? AdvertisingLifecycleStatus.APPROVED.name()
            : AdvertisingLifecycleStatus.REJECTED.name();
        creative.reviewedBy = actor.id();
        creative.reviewedAt = now;
        creative.updateTime = now;
        creativeMapper.updateById(creative);
        appendAudit("CREATIVE", creative.id, creative.creativeNo, context.campaign().venueId,
            "CAMPAIGN", creative.campaignId, approve ? "APPROVE" : "REJECT", actor,
            reason, before, creative.status);
        return AdvertisingResponses.CreativeResponse.from(creative);
    }

    private AdvertisingResponses.CreativeResponse transitionCreative(
        CreativeContext context, AdvertisingLifecycleStatus target, List<String> allowedFrom,
        String action, AdvertisingRequests.TransitionRequest request
    ) {
        CurrentUser actor = requireCurrentUser();
        AdvertisingCreativeVersion creative = context.creative();
        requireStatus(creative.status, allowedFrom, "创意当前状态不能执行该操作");
        String before = creative.status;
        LocalDateTime now = LocalDateTime.now();
        creative.status = target.name();
        if (target == AdvertisingLifecycleStatus.PUBLISHED) {
            creative.publishedBy = actor.id();
            creative.publishedAt = now;
        } else if (target == AdvertisingLifecycleStatus.PAUSED) {
            creative.pausedBy = actor.id();
            creative.pausedAt = now;
        } else if (target == AdvertisingLifecycleStatus.RETIRED) {
            creative.retiredBy = actor.id();
            creative.retiredAt = now;
        }
        creative.updateTime = now;
        creativeMapper.updateById(creative);
        appendAudit("CREATIVE", creative.id, creative.creativeNo, context.campaign().venueId,
            "CAMPAIGN", creative.campaignId, action, actor, normalizeReason(request), before, target.name());
        return AdvertisingResponses.CreativeResponse.from(creative);
    }

    private CreativeContext requireCreativeContextForUpdate(Long creativeId) {
        AdvertisingCreativeVersion creative = requireCreativeForUpdate(creativeId);
        AdvertisingCampaign campaign = requireCampaign(creative.campaignId);
        requireCampaignScope(campaign);
        return new CreativeContext(creative, campaign);
    }

    private void ensureCampaignAvailable(AdvertisingCampaign campaign) {
        if (AdvertisingLifecycleStatus.RETIRED.name().equals(campaign.status)) {
            throw BusinessException.conflict("已停用计划不能继续审核或投放");
        }
    }

    private void requireAdvertiserActive(Long advertiserId) {
        AdvertisingAdvertiser advertiser = advertiserMapper.selectById(advertiserId);
        if (advertiser == null) {
            throw BusinessException.notFound("广告主不存在");
        }
        if (!ACTIVE.equals(advertiser.status)) {
            throw BusinessException.conflict("广告主当前不可投放");
        }
    }

    private AdvertisingAdvertiser requireAdvertiserForUpdate(Long advertiserId) {
        requirePositiveId(advertiserId, "广告主 ID");
        AdvertisingAdvertiser advertiser = advertiserMapper.selectByIdForUpdate(advertiserId);
        if (advertiser == null) {
            throw BusinessException.notFound("广告主不存在");
        }
        return advertiser;
    }

    private AdvertisingCampaign requireCampaign(Long campaignId) {
        requirePositiveId(campaignId, "计划 ID");
        AdvertisingCampaign campaign = campaignMapper.selectById(campaignId);
        if (campaign == null) {
            throw BusinessException.notFound("广告计划不存在");
        }
        return campaign;
    }

    private AdvertisingCampaign requireCampaignForUpdate(Long campaignId) {
        requirePositiveId(campaignId, "计划 ID");
        AdvertisingCampaign campaign = campaignMapper.selectByIdForUpdate(campaignId);
        if (campaign == null) {
            throw BusinessException.notFound("广告计划不存在");
        }
        return campaign;
    }

    private AdvertisingCreativeVersion requireCreativeForUpdate(Long creativeId) {
        requirePositiveId(creativeId, "创意 ID");
        AdvertisingCreativeVersion creative = creativeMapper.selectByIdForUpdate(creativeId);
        if (creative == null) {
            throw BusinessException.notFound("广告创意不存在");
        }
        return creative;
    }

    private void requireCampaignScope(AdvertisingCampaign campaign) {
        if (campaign.venueId != null) {
            entityExistenceService.requireVenueExists(campaign.venueId);
            requireVenueScope(campaign.venueId);
        }
    }

    private void requireVenueScopeForWrite(Long venueId) {
        if (venueId == null) {
            return;
        }
        entityExistenceService.requireVenueExists(venueId);
        requireVenueScope(venueId);
    }

    private void requireVenueScope(Long venueId) {
        CurrentUser actor = requireCurrentUser();
        if (!actor.hasRole(Constants.ROLE_SYSTEM_ADMIN)) {
            venueAccessService.requireVenueManageAccess(venueId);
        }
    }

    private void applyVenueScope(QueryWrapper<?> wrapper, CurrentUser actor, Long venueId) {
        if (venueId != null) {
            requirePositiveId(venueId, "场馆 ID");
            entityExistenceService.requireVenueExists(venueId);
            requireVenueScope(venueId);
            wrapper.eq("venue_id", venueId);
            return;
        }
        if (actor.hasRole(Constants.ROLE_SYSTEM_ADMIN)) {
            return;
        }
        List<Long> manageableVenueIds = venueAccessService.manageableVenueIds();
        if (manageableVenueIds == null) {
            return;
        }
        if (manageableVenueIds.isEmpty()) {
            wrapper.isNull("venue_id");
            return;
        }
        wrapper.and(scope -> scope.isNull("venue_id").or().in("venue_id", manageableVenueIds));
    }

    private void appendAudit(String entityType, Long entityId, String entityNo, Long venueId,
                             String relatedEntityType, Long relatedEntityId, String action,
                             CurrentUser actor, Reason reason, String beforeStatus, String afterStatus) {
        AdvertisingAuditEvent event = new AdvertisingAuditEvent();
        event.auditNo = nextNo("ADV-AUDIT", LocalDateTime.now());
        event.entityType = entityType;
        event.entityId = entityId;
        event.entityNo = entityNo;
        event.venueId = venueId;
        event.relatedEntityType = relatedEntityType;
        event.relatedEntityId = relatedEntityId;
        event.action = action;
        event.actorId = actor.id();
        event.sourceType = SOURCE_TYPE;
        event.reasonCode = reason == null ? null : reason.code();
        event.reasonText = reason == null ? null : reason.text();
        event.beforeStatus = beforeStatus;
        event.afterStatus = afterStatus;
        event.resultStatus = "ACCEPTED";
        event.createTime = LocalDateTime.now();
        auditMapper.insert(event);
    }

    private CurrentUser requireCurrentUser() {
        CurrentUser actor = SecurityContext.currentUser();
        if (actor == null || actor.id() == null) {
            throw BusinessException.forbidden("请先登录");
        }
        return actor;
    }

    private void rejectSelfReview(Long creatorId, Long reviewerId) {
        if (creatorId != null && creatorId.equals(reviewerId)) {
            throw BusinessException.forbidden("创建者不能审核自己的广告内容");
        }
    }

    private void requireStatus(String actual, List<String> allowed, String message) {
        if (!allowed.contains(actual)) {
            throw BusinessException.conflict(message);
        }
    }

    private void validateWindow(LocalDateTime from, LocalDateTime until) {
        if (from == null || until == null || !until.isAfter(from)) {
            throw BusinessException.badRequest("生效时间窗必须满足 effective_until > effective_from");
        }
    }

    private AdvertisingTargetType parseTargetType(String raw) {
        if (raw == null || raw.isBlank()) {
            throw BusinessException.badRequest("站内目标类型不能为空");
        }
        try {
            return AdvertisingTargetType.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw BusinessException.badRequest("站内目标类型不支持");
        }
    }

    private void validateTarget(AdvertisingTargetType targetType, Long targetId) {
        if (targetType == AdvertisingTargetType.VENUE_LIST) {
            if (targetId != null) {
                throw BusinessException.badRequest("场馆列表目标不能携带目标 ID");
            }
            return;
        }
        if (targetId == null || targetId <= 0) {
            throw BusinessException.badRequest("站内详情目标必须携带正数 ID");
        }
        if (targetType == AdvertisingTargetType.VENUE_DETAIL) {
            entityExistenceService.requireVenueExists(targetId);
        }
    }

    private String normalizeCallToAction(String raw) {
        if (raw == null || raw.isBlank()) {
            throw BusinessException.badRequest("按钮类型不能为空");
        }
        String value = raw.trim().toUpperCase(Locale.ROOT);
        if (!List.of("VIEW", "BOOK", "LEARN_MORE").contains(value)) {
            throw BusinessException.badRequest("按钮类型不支持");
        }
        return value;
    }

    private String normalizeAuditEntityType(String raw) {
        String value = raw.trim().toUpperCase(Locale.ROOT);
        if (!List.of("ADVERTISER", "CAMPAIGN", "CREATIVE").contains(value)) {
            throw BusinessException.badRequest("审计实体类型不支持");
        }
        return value;
    }

    private String normalizePlainText(String raw, String field, int maxLength) {
        if (raw == null || raw.isBlank()) {
            throw BusinessException.badRequest(field + "不能为空");
        }
        String value = raw.trim();
        if (value.length() > maxLength) {
            throw BusinessException.badRequest(field + "长度超过限制");
        }
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if ((Character.isISOControl(current) && current != '\n' && current != '\r' && current != '\t')
                || current == '<' || current == '>') {
                throw BusinessException.badRequest(field + "只允许纯文本");
            }
        }
        return value;
    }

    private Reason requireReviewReason(AdvertisingRequests.ReviewDecisionRequest request) {
        if (request == null) {
            throw BusinessException.badRequest("审核拒绝必须填写原因");
        }
        return new Reason(
            normalizeReasonCode(request.reasonCode()),
            normalizePlainText(request.reasonText(), "审核原因", 500)
        );
    }

    private Reason normalizeReason(AdvertisingRequests.TransitionRequest request) {
        if (request == null) {
            return null;
        }
        String code = request.reasonCode() == null || request.reasonCode().isBlank()
            ? "OPERATOR_REQUEST" : normalizeReasonCode(request.reasonCode());
        String text = request.reasonText() == null || request.reasonText().isBlank()
            ? null : normalizePlainText(request.reasonText(), "操作原因", 500);
        return new Reason(code, text);
    }

    private String normalizeReasonCode(String raw) {
        if (raw == null || raw.isBlank()) {
            throw BusinessException.badRequest("原因编码不能为空");
        }
        String value = raw.trim().toUpperCase(Locale.ROOT);
        if (!value.matches("[A-Z0-9_]{1,64}")) {
            throw BusinessException.badRequest("原因编码格式不合法");
        }
        return value;
    }

    private void requirePositiveId(Long id, String label) {
        if (id == null || id <= 0) {
            throw BusinessException.badRequest(label + "不合法");
        }
    }

    private int boundedLimit(Integer limit) {
        if (limit == null || limit <= 0) {
            return DEFAULT_LIST_LIMIT;
        }
        return Math.min(limit, MAX_LIST_LIMIT);
    }

    private String nextNo(String prefix, LocalDateTime now) {
        return prefix + "-" + now.format(NO_FORMAT) + "-"
            + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase(Locale.ROOT);
    }

    private record Reason(String code, String text) {
    }

    private record CreativeContext(AdvertisingCreativeVersion creative, AdvertisingCampaign campaign) {
    }
}
