package com.example.venue.promotion.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.venue.business.dto.BusinessEventRecordRequest;
import com.example.venue.business.enums.BusinessDomain;
import com.example.venue.business.enums.BusinessEventType;
import com.example.venue.business.enums.BusinessSourceType;
import com.example.venue.business.service.BusinessEventService;
import com.example.venue.common.BusinessException;
import com.example.venue.common.Constants;
import com.example.venue.common.CurrentUser;
import com.example.venue.common.PageQuery;
import com.example.venue.common.PageResult;
import com.example.venue.notice.dto.NoticeRequest;
import com.example.venue.notice.service.NoticeService;
import com.example.venue.pricing.CampaignDiscountRule;
import com.example.venue.promotion.dto.CouponTemplateResponse;
import com.example.venue.promotion.dto.PriceRuleResponse;
import com.example.venue.promotion.dto.PromotionCampaignResponse;
import com.example.venue.promotion.dto.PromotionOverviewResponse;
import com.example.venue.promotion.dto.SaveCouponTemplateRequest;
import com.example.venue.promotion.dto.SavePriceRuleRequest;
import com.example.venue.promotion.dto.SavePromotionCampaignRequest;
import com.example.venue.promotion.dto.UserCouponResponse;
import com.example.venue.promotion.entity.CouponStatusLog;
import com.example.venue.promotion.entity.CouponTemplate;
import com.example.venue.promotion.entity.PriceRule;
import com.example.venue.promotion.entity.PromotionCampaign;
import com.example.venue.promotion.entity.UserCoupon;
import com.example.venue.promotion.mapper.CouponStatusLogMapper;
import com.example.venue.promotion.mapper.CouponTemplateMapper;
import com.example.venue.promotion.mapper.PriceRuleMapper;
import com.example.venue.promotion.mapper.PromotionCampaignMapper;
import com.example.venue.promotion.mapper.UserCouponMapper;
import com.example.venue.security.SecurityContext;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Service
public class PromotionAdminService {
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };
    private static final BigDecimal ZERO_AMOUNT = new BigDecimal("0.00");
    private static final Set<String> ADMIN_STATUSES = Set.of("DRAFT", "SCHEDULED", "ACTIVE", "PAUSED", "ENDED");
    private static final Set<String> COUPON_STATUSES = Set.of("CLAIMED", "LOCKED", "USED", "RELEASED", "EXPIRED", "VOIDED", "REFUNDED");
    private static final Set<String> ADJUSTMENT_TYPES = Set.of("FIXED_PRICE", "PERCENT", "DELTA");

    private final PromotionCampaignMapper campaignMapper;
    private final CouponTemplateMapper couponTemplateMapper;
    private final PriceRuleMapper priceRuleMapper;
    private final UserCouponMapper userCouponMapper;
    private final CouponStatusLogMapper couponStatusLogMapper;
    private final BusinessEventService businessEventService;
    private final NoticeService noticeService;
    private final ObjectMapper objectMapper;

    public PromotionAdminService(PromotionCampaignMapper campaignMapper, CouponTemplateMapper couponTemplateMapper,
                                 PriceRuleMapper priceRuleMapper, UserCouponMapper userCouponMapper,
                                 CouponStatusLogMapper couponStatusLogMapper,
                                 BusinessEventService businessEventService,
                                 NoticeService noticeService,
                                 ObjectMapper objectMapper) {
        this.campaignMapper = campaignMapper;
        this.couponTemplateMapper = couponTemplateMapper;
        this.priceRuleMapper = priceRuleMapper;
        this.userCouponMapper = userCouponMapper;
        this.couponStatusLogMapper = couponStatusLogMapper;
        this.businessEventService = businessEventService;
        this.noticeService = noticeService;
        this.objectMapper = objectMapper;
    }

    public PageResult<PromotionCampaignResponse> campaigns(PageQuery query) {
        PageQuery safeQuery = safeQuery(query);
        Page<PromotionCampaign> page = campaignMapper.selectPage(
            new Page<>(safeQuery.getPageNum(), safeQuery.getPageSize()),
            new QueryWrapper<PromotionCampaign>()
                .eq("deleted", 0)
                .orderByDesc("create_time")
                .orderByDesc("id"));
        return PageResult.of(page.getRecords().stream().map(this::toCampaignResponse).toList(),
            page.getTotal(), safeQuery.getPageNum(), safeQuery.getPageSize());
    }

    @Transactional
    public PromotionCampaignResponse saveCampaign(SavePromotionCampaignRequest request) {
        validateCampaignRequest(request);
        PromotionCampaign campaign = new PromotionCampaign();
        copyCampaign(request, campaign, normalizeStatus(request.status, "DRAFT"));
        LocalDateTime now = LocalDateTime.now();
        campaign.budgetUsedAmount = ZERO_AMOUNT;
        campaign.createdBy = currentUserIdOrNull();
        campaign.updatedBy = campaign.createdBy;
        campaign.createTime = now;
        campaign.updateTime = now;
        campaign.deleted = 0;
        campaignMapper.insert(campaign);
        recordCampaignCreated(campaign);
        return toCampaignResponse(campaign);
    }

    @Transactional
    public PromotionCampaignResponse updateCampaign(Long id, SavePromotionCampaignRequest request) {
        PromotionCampaign campaign = requireCampaign(id);
        validateCampaignRequest(request);
        copyCampaign(request, campaign, normalizeStatus(request.status, campaign.status));
        LocalDateTime now = LocalDateTime.now();
        Long updatedBy = currentUserIdOrNull();
        // A26-P2-07：用 UpdateWrapper 仅更新非计数列，避免整实体写回覆盖并发累加的 budget_used_amount；
        // budget_used_amount 由 consumeBudgetIfAvailable/releaseBudgetIfAvailable 原子维护。
        UpdateWrapper<PromotionCampaign> wrapper = new UpdateWrapper<PromotionCampaign>()
            .eq("id", id)
            .eq("deleted", 0)
            .set("campaign_code", campaign.campaignCode)
            .set("campaign_name", campaign.campaignName)
            .set("campaign_type", campaign.campaignType)
            .set("venue_id", campaign.venueId)
            .set("space_id", campaign.spaceId)
            .set("member_level_code", campaign.memberLevelCode)
            .set("discount_type", campaign.discountType)
            .set("discount_value", campaign.discountValue)
            .set("priority", campaign.priority)
            .set("stackable", campaign.stackable)
            .set("start_time", campaign.startTime)
            .set("end_time", campaign.endTime)
            .set("budget_amount", campaign.budgetAmount)
            .set("status", campaign.status)
            .set("metadata_json", campaign.metadataJson)
            .set("updated_by", updatedBy)
            .set("update_time", now);
        campaignMapper.update(null, wrapper);
        campaign.updatedBy = updatedBy;
        campaign.updateTime = now;
        return toCampaignResponse(campaign);
    }

    @Transactional
    public void updateCampaignStatus(Long id, String status) {
        PromotionCampaign campaign = requireCampaign(id);
        String fromStatus = campaign.status;
        String toStatus = normalizeStatus(status, fromStatus);
        if (Objects.equals(fromStatus, toStatus)) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        Long updatedBy = currentUserIdOrNull();
        // A26-P2-07：状态切换同样用条件更新，避免整实体写回把并发累加的 budget_used_amount 覆盖回旧值。
        UpdateWrapper<PromotionCampaign> wrapper = new UpdateWrapper<PromotionCampaign>()
            .eq("id", id)
            .eq("deleted", 0)
            .set("status", toStatus)
            .set("updated_by", updatedBy)
            .set("update_time", now);
        campaignMapper.update(null, wrapper);
        campaign.status = toStatus;
        campaign.updatedBy = updatedBy;
        campaign.updateTime = now;
        recordCampaignStatusChanged(campaign, fromStatus, toStatus);
    }

    /**
     * 券模板列表（SHIGUANG-BATCH-04-REFACTOR-20260914 S03 / B4-P9）。
     *
     * <p>此前该方法忽略全部入参，包括调用方传入的 {@code status}——会员权益目录在发布版本时按
     * {@code status:'ACTIVE'} 拉取「可选券模板」，实际拿到的是**含草稿/暂停/结束**的全量列表，
     * 可能把不可发放的模板绑进目录版本（到会员兑换时才以 {@code 兑换优惠券模板不可发放} 失败）。
     * 现补可选状态过滤：缺省不改变既有行为，显式传值时按模板状态（{@code ADMIN_STATUSES}）精确过滤，
     * 非法值 400 而不是静默忽略。</p>
     */
    public PageResult<CouponTemplateResponse> couponTemplates(PageQuery query, String status) {
        PageQuery safeQuery = safeQuery(query);
        String normalizedStatus = normalizeAdminStatusOrNull(status);
        QueryWrapper<CouponTemplate> wrapper = new QueryWrapper<CouponTemplate>().eq("deleted", 0);
        if (normalizedStatus != null) {
            wrapper.eq("status", normalizedStatus);
        }
        Page<CouponTemplate> page = couponTemplateMapper.selectPage(
            new Page<>(safeQuery.getPageNum(), safeQuery.getPageSize()),
            wrapper.orderByDesc("create_time").orderByDesc("id"));
        return PageResult.of(page.getRecords().stream().map(this::toCouponTemplateResponse).toList(),
            page.getTotal(), safeQuery.getPageNum(), safeQuery.getPageSize());
    }

    @Transactional
    public CouponTemplateResponse saveCouponTemplate(SaveCouponTemplateRequest request) {
        validateCouponTemplateRequest(request);
        CouponTemplate template = new CouponTemplate();
        copyCouponTemplate(request, template, normalizeStatus(request.status, "DRAFT"));
        LocalDateTime now = LocalDateTime.now();
        template.issuedQuantity = 0;
        template.usedQuantity = 0;
        template.sourceType = "ADMIN";
        template.sortOrder = 0;
        template.createdBy = currentUserIdOrNull();
        template.updatedBy = template.createdBy;
        template.createTime = now;
        template.updateTime = now;
        template.deleted = 0;
        couponTemplateMapper.insert(template);
        notifyCouponTemplatePublished(template);
        return toCouponTemplateResponse(template);
    }

    @Transactional
    public CouponTemplateResponse updateCouponTemplate(Long id, SaveCouponTemplateRequest request) {
        CouponTemplate template = requireCouponTemplate(id);
        validateCouponTemplateRequest(request);
        // B4-P6：编辑路径补「发行总量不得小于已发行」下界校验。领取守卫是
        // (total_quantity <= 0 or issued_quantity < total_quantity)（CouponTemplateMapper.incrementIssuedIfAvailable），
        // 因此把总量改到低于已发行会让该模板此后永久无法再发——属运营不可逆操作，且此前无任何提示路径。
        // total_quantity <= 0 是既有「不限量」约定，下调到 0 只解除上限、不影响已发券，故放行。
        if (request.totalQuantity != null && request.totalQuantity > 0
            && template.issuedQuantity != null && request.totalQuantity < template.issuedQuantity) {
            throw BusinessException.badRequest("发行总量不能小于已发行数量");
        }
        copyCouponTemplate(request, template, normalizeStatus(request.status, template.status));
        LocalDateTime now = LocalDateTime.now();
        Long updatedBy = currentUserIdOrNull();
        // A26-P2-08：用 UpdateWrapper 仅更新非计数列，避免整实体写回覆盖并发累加的
        // issued_quantity/used_quantity；这两个计数列由 incrementIssuedIfAvailable*/incrementUsed 原子维护。
        UpdateWrapper<CouponTemplate> wrapper = new UpdateWrapper<CouponTemplate>()
            .eq("id", id)
            .eq("deleted", 0)
            .set("template_code", template.templateCode)
            .set("template_name", template.templateName)
            .set("coupon_type", template.couponType)
            .set("venue_id", template.venueId)
            .set("space_id", template.spaceId)
            .set("campaign_id", template.campaignId)
            .set("discount_value", template.discountValue)
            .set("threshold_amount", template.thresholdAmount)
            .set("max_discount_amount", template.maxDiscountAmount)
            .set("total_quantity", template.totalQuantity)
            .set("per_user_limit", template.perUserLimit)
            .set("claim_start_time", template.claimStartTime)
            .set("claim_end_time", template.claimEndTime)
            .set("valid_start_time", template.validStartTime)
            .set("valid_end_time", template.validEndTime)
            .set("allow_activity_stack", template.allowActivityStack)
            .set("cost_owner", template.costOwner)
            .set("status", template.status)
            .set("updated_by", updatedBy)
            .set("update_time", now);
        couponTemplateMapper.update(null, wrapper);
        template.updatedBy = updatedBy;
        template.updateTime = now;
        return toCouponTemplateResponse(template);
    }

    @Transactional
    public void updateCouponTemplateStatus(Long id, String status) {
        CouponTemplate template = requireCouponTemplate(id);
        String toStatus = normalizeStatus(status, template.status);
        if (Objects.equals(template.status, toStatus)) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        Long updatedBy = currentUserIdOrNull();
        // A26-P2-08：ACTIVE↔PAUSED 切换同样用条件更新，避免整实体写回覆盖并发累加的 issued_quantity/used_quantity。
        UpdateWrapper<CouponTemplate> wrapper = new UpdateWrapper<CouponTemplate>()
            .eq("id", id)
            .eq("deleted", 0)
            .set("status", toStatus)
            .set("updated_by", updatedBy)
            .set("update_time", now);
        couponTemplateMapper.update(null, wrapper);
        template.status = toStatus;
        template.updatedBy = updatedBy;
        template.updateTime = now;
        if ("ACTIVE".equals(toStatus)) {
            notifyCouponTemplatePublished(template);
        }
    }

    public PageResult<PriceRuleResponse> priceRules(PageQuery query) {
        PageQuery safeQuery = safeQuery(query);
        Page<PriceRule> page = priceRuleMapper.selectPage(
            new Page<>(safeQuery.getPageNum(), safeQuery.getPageSize()),
            new QueryWrapper<PriceRule>()
                .eq("deleted", 0)
                .orderByDesc("priority")
                .orderByDesc("create_time")
                .orderByDesc("id"));
        return PageResult.of(page.getRecords().stream().map(this::toPriceRuleResponse).toList(),
            page.getTotal(), safeQuery.getPageNum(), safeQuery.getPageSize());
    }

    @Transactional
    public PriceRuleResponse savePriceRule(SavePriceRuleRequest request) {
        validatePriceRuleRequest(request);
        PriceRule rule = new PriceRule();
        copyPriceRule(request, rule, normalizeStatus(request.status, "DRAFT"));
        LocalDateTime now = LocalDateTime.now();
        rule.createTime = now;
        rule.updateTime = now;
        rule.deleted = 0;
        priceRuleMapper.insert(rule);
        recordPriceRuleChanged(rule, "PRICE_RULE_CHANGED:" + rule.id, null, rule.status);
        return toPriceRuleResponse(rule);
    }

    @Transactional
    public PriceRuleResponse updatePriceRule(Long id, SavePriceRuleRequest request) {
        PriceRule rule = requirePriceRule(id);
        validatePriceRuleRequest(request);
        String fromStatus = rule.status;
        copyPriceRule(request, rule, normalizeStatus(request.status, rule.status));
        rule.updateTime = LocalDateTime.now();
        priceRuleMapper.updateById(rule);
        recordPriceRuleChanged(rule, "PRICE_RULE_CHANGED:" + id + ":UPDATE:" + rule.updateTime, fromStatus, rule.status);
        return toPriceRuleResponse(rule);
    }

    @Transactional
    public void updatePriceRuleStatus(Long id, String status) {
        PriceRule rule = requirePriceRule(id);
        String fromStatus = rule.status;
        String toStatus = normalizeStatus(status, fromStatus);
        if (Objects.equals(fromStatus, toStatus)) {
            return;
        }
        rule.status = toStatus;
        rule.updateTime = LocalDateTime.now();
        priceRuleMapper.updateById(rule);
        recordPriceRuleChanged(rule, "PRICE_RULE_CHANGED:" + id + ":" + fromStatus + ":" + toStatus, fromStatus, toStatus);
    }

    public PromotionOverviewResponse overview() {
        PromotionOverviewResponse response = new PromotionOverviewResponse();
        response.activeCampaigns = countActiveCampaigns();
        response.activeCouponTemplates = countActiveCouponTemplates();
        response.activePriceRules = countActivePriceRules();
        response.claimedCoupons = countCouponsByStatus("CLAIMED");
        response.lockedCoupons = countCouponsByStatus("LOCKED");
        response.usedCoupons = countCouponsByStatus("USED");
        // 已退款券保持 REFUNDED 终态；状态日志仍是历史退款次数的权威证据。
        response.refundedCoupons = countRefundedCoupons();
        // 总优惠金额仅统计当前处于 USED 状态的有效核销，不包含已退款终态券。
        response.totalCouponDiscountAmount = userCouponMapper.selectList(new QueryWrapper<UserCoupon>()
                .eq("deleted", 0)
                .eq("status", "USED"))
            .stream()
            .map(coupon -> normalizeAmount(coupon.discountAmount))
            .reduce(ZERO_AMOUNT, BigDecimal::add)
            .setScale(2, RoundingMode.HALF_UP);
        return response;
    }

    public PageResult<UserCouponResponse> couponRedemptions(PageQuery query, String status) {
        PageQuery safeQuery = safeQuery(query);
        QueryWrapper<UserCoupon> wrapper = new QueryWrapper<UserCoupon>().eq("deleted", 0);
        String normalizedStatus = normalizeCouponStatus(status);
        if (normalizedStatus != null) {
            wrapper.eq("status", normalizedStatus);
        }
        Page<UserCoupon> page = userCouponMapper.selectPage(new Page<>(safeQuery.getPageNum(), safeQuery.getPageSize()),
            wrapper.orderByDesc("claim_time").orderByDesc("id"));
        return PageResult.of(page.getRecords().stream()
                .map(coupon -> toUserCouponResponse(coupon, couponTemplateMapper.selectById(coupon.templateId)))
                .toList(),
            page.getTotal(), safeQuery.getPageNum(), safeQuery.getPageSize());
    }

    private void validateCampaignRequest(SavePromotionCampaignRequest request) {
        if (request == null) {
            throw BusinessException.badRequest("活动参数不能为空");
        }
        requireText(request.campaignCode, "活动编码不能为空");
        requireText(request.campaignName, "活动名称不能为空");
        validateTimeRange(request.startTime, request.endTime, "活动");
        validateNonNegative(request.budgetAmount, "活动预算不合法");
        validateNonNegative(request.maxDiscountAmount, "活动最大优惠金额不合法");
        validateCampaignDiscount(request);
        if (request.priority != null && request.priority < 0) {
            throw BusinessException.badRequest("活动优先级不能小于 0");
        }
    }

    private void validateCampaignDiscount(SavePromotionCampaignRequest request) {
        String violation = CampaignDiscountRule.violation(request.discountType, request.discountValue);
        if (violation != null) {
            throw BusinessException.badRequest(violation);
        }
    }

    private void validateCouponTemplateRequest(SaveCouponTemplateRequest request) {
        if (request == null) {
            throw BusinessException.badRequest("优惠券模板参数不能为空");
        }
        requireText(request.templateCode, "优惠券模板编码不能为空");
        requireText(request.templateName, "优惠券模板名称不能为空");
        requireText(request.couponType, "优惠券类型不能为空");
        validateTimeRange(request.validStartTime, request.validEndTime, "优惠券有效期");
        String couponType = normalizeCode(request.couponType);
        if (request.discountValue == null) {
            throw BusinessException.badRequest("优惠券折扣不能为空");
        }
        if ("PERCENT".equals(couponType)) {
            if (request.discountValue.compareTo(BigDecimal.ZERO) <= 0
                || request.discountValue.compareTo(BigDecimal.ONE) > 0) {
                throw BusinessException.badRequest("PERCENT 类型优惠券折扣比例必须大于 0 且不超过 1");
            }
        } else if (request.discountValue.compareTo(BigDecimal.ZERO) <= 0) {
            throw BusinessException.badRequest("优惠券折扣不合法");
        }
        if (request.totalQuantity != null && request.totalQuantity < 0) {
            throw BusinessException.badRequest("优惠券数量不合法");
        }
        if (request.perUserLimit != null && request.perUserLimit <= 0) {
            throw BusinessException.badRequest("每人限领数量不合法");
        }
        validateNonNegative(request.thresholdAmount, "优惠券门槛金额不合法");
        validateNonNegative(request.maxDiscountAmount, "优惠券最大优惠金额不合法");
    }

    private void validatePriceRuleRequest(SavePriceRuleRequest request) {
        if (request == null) {
            throw BusinessException.badRequest("价格规则参数不能为空");
        }
        requireText(request.ruleCode, "价格规则编码不能为空");
        requireText(request.ruleName, "价格规则名称不能为空");
        requireText(request.ruleType, "价格规则类型不能为空");
        requireText(request.adjustmentType, "价格调整类型不能为空");
        validateTimeRange(request.startTime, request.endTime, "价格规则");
        if (request.startClock != null && request.endClock != null && !request.startClock.isBefore(request.endClock)) {
            throw BusinessException.badRequest("价格规则时段不合法");
        }
        String adjustmentType = normalizeCode(request.adjustmentType);
        if (!ADJUSTMENT_TYPES.contains(adjustmentType)) {
            throw BusinessException.badRequest("价格调整类型不支持");
        }
        if (request.adjustmentValue == null) {
            throw BusinessException.badRequest("价格调整值不能为空");
        }
        if ("PERCENT".equals(adjustmentType)
            && (request.adjustmentValue.compareTo(BigDecimal.ZERO) <= 0
            || request.adjustmentValue.compareTo(BigDecimal.ONE) > 0)) {
            throw BusinessException.badRequest("PERCENT 类型价格调整比例必须大于 0 且不超过 1");
        }
    }

    private void validateTimeRange(LocalDateTime start, LocalDateTime end, String label) {
        if (start == null || end == null || !start.isBefore(end)) {
            throw BusinessException.badRequest(label + "时间不合法");
        }
    }

    private void validateNonNegative(BigDecimal amount, String message) {
        if (amount != null && amount.compareTo(BigDecimal.ZERO) < 0) {
            throw BusinessException.badRequest(message);
        }
    }

    private void copyCampaign(SavePromotionCampaignRequest request, PromotionCampaign campaign, String status) {
        campaign.campaignCode = normalizeText(request.campaignCode);
        campaign.campaignName = normalizeText(request.campaignName);
        campaign.campaignType = normalizeCodeOrDefault(request.campaignType, "DISCOUNT");
        campaign.venueId = request.venueId;
        campaign.spaceId = request.spaceId;
        campaign.memberLevelCode = normalizeNullableCode(request.targetMemberLevel);
        campaign.discountType = normalizeCodeOrDefault(request.discountType, "CASH");
        campaign.discountValue = normalizeAmount(request.discountValue);
        campaign.priority = normalizePriority(request.priority == null ? campaign.priority : request.priority);
        campaign.stackable = Boolean.TRUE.equals(request.allowCouponStack) ? 1 : 0;
        campaign.startTime = request.startTime;
        campaign.endTime = request.endTime;
        campaign.budgetAmount = normalizeNullableAmount(request.budgetAmount);
        campaign.status = status;
        campaign.metadataJson = toJson(metadata("maxDiscountAmount", normalizeNullableAmount(request.maxDiscountAmount)));
    }

    private void copyCouponTemplate(SaveCouponTemplateRequest request, CouponTemplate template, String status) {
        template.templateCode = normalizeText(request.templateCode);
        template.templateName = normalizeText(request.templateName);
        template.couponType = normalizeCode(request.couponType);
        template.venueId = request.venueId;
        template.spaceId = request.spaceId;
        template.campaignId = request.campaignId;
        template.discountValue = normalizeAmount(request.discountValue);
        template.thresholdAmount = normalizeAmount(request.thresholdAmount);
        template.maxDiscountAmount = normalizeNullableAmount(request.maxDiscountAmount);
        template.totalQuantity = request.totalQuantity == null ? 0 : request.totalQuantity;
        template.perUserLimit = request.perUserLimit == null ? 1 : request.perUserLimit;
        template.claimStartTime = request.validStartTime;
        template.claimEndTime = request.validEndTime;
        template.validStartTime = request.validStartTime;
        template.validEndTime = request.validEndTime;
        template.allowActivityStack = !Boolean.FALSE.equals(request.allowActivityStack);
        template.costOwner = normalizeTextOrNull(request.costOwner);
        template.status = status;
    }

    private void copyPriceRule(SavePriceRuleRequest request, PriceRule rule, String status) {
        String adjustmentType = normalizeCode(request.adjustmentType);
        rule.ruleCode = normalizeText(request.ruleCode);
        rule.ruleName = normalizeText(request.ruleName);
        rule.ruleType = normalizeCode(request.ruleType);
        rule.venueId = request.venueId;
        rule.spaceId = request.spaceId;
        rule.memberLevelCode = normalizeNullableCode(request.memberLevelCode);
        rule.startTime = request.startTime;
        rule.endTime = request.endTime;
        rule.dayOfWeekMask = normalizeTextOrNull(request.dayOfWeekMask);
        rule.startClock = request.startClock;
        rule.endClock = request.endClock;
        rule.adjustmentType = adjustmentType;
        rule.adjustmentValue = normalizeAmount(request.adjustmentValue);
        rule.fixedPrice = "FIXED_PRICE".equals(adjustmentType) ? rule.adjustmentValue : null;
        rule.priority = request.priority == null ? 0 : request.priority;
        rule.status = status;
        rule.reasonText = normalizeTextOrNull(request.reasonText);
        rule.metadataJson = null;
    }

    private PromotionCampaign requireCampaign(Long id) {
        if (id == null) {
            throw BusinessException.badRequest("活动ID不能为空");
        }
        PromotionCampaign campaign = campaignMapper.selectById(id);
        if (campaign == null || Integer.valueOf(1).equals(campaign.deleted)) {
            throw BusinessException.notFound("活动不存在");
        }
        return campaign;
    }

    private CouponTemplate requireCouponTemplate(Long id) {
        if (id == null) {
            throw BusinessException.badRequest("优惠券模板ID不能为空");
        }
        CouponTemplate template = couponTemplateMapper.selectById(id);
        if (template == null || Integer.valueOf(1).equals(template.deleted)) {
            throw BusinessException.notFound("优惠券模板不存在");
        }
        return template;
    }

    private PriceRule requirePriceRule(Long id) {
        if (id == null) {
            throw BusinessException.badRequest("价格规则ID不能为空");
        }
        PriceRule rule = priceRuleMapper.selectById(id);
        if (rule == null || Integer.valueOf(1).equals(rule.deleted)) {
            throw BusinessException.notFound("价格规则不存在");
        }
        return rule;
    }

    private PromotionCampaignResponse toCampaignResponse(PromotionCampaign campaign) {
        PromotionCampaignResponse response = new PromotionCampaignResponse();
        response.id = campaign.id;
        response.campaignCode = campaign.campaignCode;
        response.campaignName = campaign.campaignName;
        response.campaignType = campaign.campaignType;
        response.venueId = campaign.venueId;
        response.spaceId = campaign.spaceId;
        response.targetMemberLevel = campaign.memberLevelCode;
        response.startTime = campaign.startTime;
        response.endTime = campaign.endTime;
        response.budgetAmount = normalizeAmount(campaign.budgetAmount);
        response.discountType = campaign.discountType;
        response.discountValue = normalizeAmount(campaign.discountValue);
        response.maxDiscountAmount = metadataAmount(campaign.metadataJson, "maxDiscountAmount");
        response.priority = normalizePriority(campaign.priority);
        response.allowCouponStack = Integer.valueOf(1).equals(campaign.stackable);
        response.status = campaign.status;
        return response;
    }

    private CouponTemplateResponse toCouponTemplateResponse(CouponTemplate template) {
        CouponTemplateResponse response = new CouponTemplateResponse();
        response.id = template.id;
        response.templateCode = template.templateCode;
        response.templateName = template.templateName;
        response.couponType = template.couponType;
        response.discountValue = normalizeAmount(template.discountValue);
        response.thresholdAmount = normalizeAmount(template.thresholdAmount);
        response.maxDiscountAmount = normalizeNullableAmount(template.maxDiscountAmount);
        response.totalQuantity = template.totalQuantity;
        response.issuedQuantity = template.issuedQuantity;
        response.usedQuantity = template.usedQuantity;
        response.perUserLimit = template.perUserLimit;
        response.validStartTime = template.validStartTime;
        response.validEndTime = template.validEndTime;
        response.campaignId = template.campaignId;
        response.venueId = template.venueId;
        response.spaceId = template.spaceId;
        response.allowActivityStack = !Boolean.FALSE.equals(template.allowActivityStack);
        response.status = template.status;
        // B4-P5：补成本承担方的只读出参，使该既有列写入后可核。
        response.costOwner = template.costOwner;
        return response;
    }

    private PriceRuleResponse toPriceRuleResponse(PriceRule rule) {
        PriceRuleResponse response = new PriceRuleResponse();
        response.id = rule.id;
        response.ruleCode = rule.ruleCode;
        response.ruleName = rule.ruleName;
        response.ruleType = rule.ruleType;
        response.venueId = rule.venueId;
        response.spaceId = rule.spaceId;
        response.memberLevelCode = rule.memberLevelCode;
        response.startTime = rule.startTime;
        response.endTime = rule.endTime;
        response.dayOfWeekMask = rule.dayOfWeekMask;
        response.startClock = rule.startClock;
        response.endClock = rule.endClock;
        response.adjustmentType = rule.adjustmentType;
        response.adjustmentValue = normalizeAmount(rule.adjustmentValue);
        response.priority = rule.priority;
        response.status = rule.status;
        response.reasonText = rule.reasonText;
        return response;
    }

    private UserCouponResponse toUserCouponResponse(UserCoupon coupon, CouponTemplate template) {
        UserCouponResponse response = new UserCouponResponse();
        response.id = coupon.id;
        response.templateId = coupon.templateId;
        response.status = coupon.status;
        response.claimTime = coupon.claimTime;
        response.validStartTime = coupon.validStartTime;
        response.validEndTime = coupon.validEndTime;
        response.reservationId = coupon.reservationId;
        response.orderId = coupon.orderId;
        response.discountAmount = normalizeAmount(coupon.discountAmount);
        if (template != null) {
            response.templateCode = template.templateCode;
            response.templateName = template.templateName;
            response.couponType = template.couponType;
            response.discountValue = normalizeAmount(template.discountValue);
            response.thresholdAmount = normalizeAmount(template.thresholdAmount);
        }
        return response;
    }

    private Long countActiveCampaigns() {
        return campaignMapper.selectCount(new QueryWrapper<PromotionCampaign>().eq("deleted", 0).eq("status", "ACTIVE"));
    }

    private Long countActiveCouponTemplates() {
        return couponTemplateMapper.selectCount(new QueryWrapper<CouponTemplate>().eq("deleted", 0).eq("status", "ACTIVE"));
    }

    private Long countActivePriceRules() {
        return priceRuleMapper.selectCount(new QueryWrapper<PriceRule>().eq("deleted", 0).eq("status", "ACTIVE"));
    }

    private Long countCouponsByStatus(String status) {
        return userCouponMapper.selectCount(new QueryWrapper<UserCoupon>().eq("deleted", 0).eq("status", status));
    }

    private Long countRefundedCoupons() {
        return couponStatusLogMapper.selectCount(new QueryWrapper<CouponStatusLog>()
            .eq("reason_code", "ORDER_REFUNDED"));
    }

    private void recordCampaignCreated(PromotionCampaign campaign) {
        businessEventService.record(BusinessEventRecordRequest.of(
                BusinessEventType.ACTIVITY_CREATED,
                BusinessDomain.ACTIVITY,
                "PROMOTION_CAMPAIGN",
                campaign.id,
                BusinessSourceType.ADMIN,
                "SUCCESS")
            .idempotencyKey("ACTIVITY_CREATED:" + campaign.id)
            .metadata("campaignCode", campaign.campaignCode)
            .metadata("discountType", campaign.discountType));
    }

    private void recordCampaignStatusChanged(PromotionCampaign campaign, String fromStatus, String toStatus) {
        businessEventService.record(BusinessEventRecordRequest.of(
                BusinessEventType.ACTIVITY_STATUS_CHANGED,
                BusinessDomain.ACTIVITY,
                "PROMOTION_CAMPAIGN",
                campaign.id,
                BusinessSourceType.ADMIN,
                "SUCCESS")
            .idempotencyKey("ACTIVITY_STATUS_CHANGED:" + campaign.id + ":" + fromStatus + ":" + toStatus)
            .fromStatus(fromStatus)
            .toStatus(toStatus)
            .metadata("campaignCode", campaign.campaignCode));
    }

    private void recordPriceRuleChanged(PriceRule rule, String idempotencyKey, String fromStatus, String toStatus) {
        businessEventService.record(BusinessEventRecordRequest.of(
                BusinessEventType.PRICE_RULE_CHANGED,
                BusinessDomain.PRICING,
                "PRICE_RULE",
                rule.id,
                BusinessSourceType.ADMIN,
                "SUCCESS")
            .idempotencyKey(idempotencyKey)
            .fromStatus(fromStatus)
            .toStatus(toStatus)
            .metadata("ruleCode", rule.ruleCode)
            .metadata("adjustmentType", rule.adjustmentType)
            .metadata("adjustmentValue", normalizeAmount(rule.adjustmentValue)));
    }

    private PageQuery safeQuery(PageQuery query) {
        return query == null ? new PageQuery() : query;
    }

    private void requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw BusinessException.badRequest(message);
        }
    }

    private Integer normalizePriority(Integer priority) {
        return priority == null ? 0 : priority;
    }

    private String normalizeStatus(String status, String fallback) {
        String value = status == null || status.isBlank() ? fallback : status.trim().toUpperCase(Locale.ROOT);
        if (!ADMIN_STATUSES.contains(value)) {
            throw BusinessException.badRequest("状态不支持");
        }
        return value;
    }

    private String normalizeCouponStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        String value = status.trim().toUpperCase(Locale.ROOT);
        if (!COUPON_STATUSES.contains(value)) {
            throw BusinessException.badRequest("优惠券状态不支持");
        }
        return value;
    }

    /** 活动/券模板/定价规则共用的对象状态归一化；空值返回 null 表示「不过滤」。 */
    private String normalizeAdminStatusOrNull(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        String value = status.trim().toUpperCase(Locale.ROOT);
        if (!ADMIN_STATUSES.contains(value)) {
            throw BusinessException.badRequest("状态不支持");
        }
        return value;
    }

    private String normalizeCode(String value) {
        return normalizeText(value).toUpperCase(Locale.ROOT);
    }

    private String normalizeCodeOrDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : normalizeCode(value);
    }

    private String normalizeNullableCode(String value) {
        String normalized = normalizeTextOrNull(value);
        return normalized == null ? null : normalized.toUpperCase(Locale.ROOT);
    }

    private String normalizeText(String value) {
        return value == null ? null : value.trim();
    }

    private String normalizeTextOrNull(String value) {
        String normalized = normalizeText(value);
        return normalized == null || normalized.isBlank() ? null : normalized;
    }

    private BigDecimal normalizeAmount(BigDecimal amount) {
        return amount == null ? ZERO_AMOUNT : amount.setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal normalizeNullableAmount(BigDecimal amount) {
        return amount == null ? null : normalizeAmount(amount);
    }

    private String toJson(Map<String, Object> value) {
        Map<String, Object> compact = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : value.entrySet()) {
            if (entry.getValue() != null) {
                compact.put(entry.getKey(), entry.getValue());
            }
        }
        try {
            return objectMapper.writeValueAsString(compact);
        } catch (JsonProcessingException ex) {
            throw BusinessException.badRequest("促销管理元数据无法序列化");
        }
    }

    private Map<String, Object> metadata(Object... keyValues) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        for (int index = 0; index + 1 < keyValues.length; index += 2) {
            metadata.put((String) keyValues[index], keyValues[index + 1]);
        }
        return metadata;
    }

    private Map<String, Object> metadataMap(String metadataJson) {
        if (metadataJson == null || metadataJson.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(metadataJson, MAP_TYPE);
        } catch (JsonProcessingException ex) {
            throw BusinessException.badRequest("促销管理元数据无法解析");
        }
    }

    private BigDecimal metadataAmount(String metadataJson, String key) {
        Object value = metadataMap(metadataJson).get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return normalizeAmount(new BigDecimal(number.toString()));
        }
        String text = String.valueOf(value);
        return text.isBlank() ? null : normalizeAmount(new BigDecimal(text));
    }

    private void notifyCouponTemplatePublished(CouponTemplate template) {
        CurrentUser user = SecurityContext.currentUser();
        if (user == null || !user.hasRole(Constants.ROLE_SYSTEM_ADMIN)) {
            return;
        }
        try {
            NoticeRequest request = new NoticeRequest();
            request.title = "新优惠券上架";
            request.content = "优惠券「" + template.templateName + "」已上架，"
                + couponDiscountDescription(template) + "，快来领取吧！";
            request.type = template.venueId == null ? "GLOBAL" : "VENUE";
            request.targetVenueId = template.venueId;
            request.status = "PUBLISHED";
            request.targetScope = "ALL_USERS";
            noticeService.publish(request);
        } catch (RuntimeException ignored) {
            // 通知发送失败不影响优惠券上架
        }
    }

    private String couponDiscountDescription(CouponTemplate template) {
        String couponType = template.couponType == null ? "" : template.couponType.toUpperCase(Locale.ROOT);
        BigDecimal discountValue = normalizeAmount(template.discountValue);
        return switch (couponType) {
            case "CASH" -> "面值 " + discountValue + " 元";
            case "PERCENT" -> "享 " + discountValue.multiply(BigDecimal.TEN).stripTrailingZeros().toPlainString() + " 折";
            // A26-P3-12：FIXED 券的 discountValue 是券后一口价（用户最终支付金额），
            // 不是优惠金额；通知文案必须与计价语义一致，避免误报成"立减 X 元"。
            case "FIXED" -> "券后一口价 " + discountValue + " 元";
            default -> "优惠 " + discountValue + " 元";
        };
    }

    private Long currentUserIdOrNull() {
        CurrentUser user = SecurityContext.currentUser();
        return user == null ? null : user.id();
    }
}
