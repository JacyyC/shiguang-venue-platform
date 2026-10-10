package com.example.venue.promotion.service;

import com.example.venue.member.dto.MemberAccountResponse;
import com.example.venue.member.dto.MemberLevelResponse;
import com.example.venue.member.entity.MemberLevel;
import com.example.venue.member.mapper.MemberLevelMapper;
import com.example.venue.member.service.MemberService;
import com.example.venue.pricing.AmountBreakdown;
import com.example.venue.pricing.CampaignDiscountRule;
import com.example.venue.pricing.PricingSnapshotFactory;
import com.example.venue.promotion.dto.CouponPricingView;
import com.example.venue.promotion.dto.PriceRuleResponse;
import com.example.venue.promotion.dto.PromotionCampaignResponse;
import com.example.venue.promotion.dto.PromotionPricingContext;
import com.example.venue.promotion.entity.PriceRule;
import com.example.venue.promotion.entity.PromotionCampaign;
import com.example.venue.promotion.mapper.PriceRuleMapper;
import com.example.venue.promotion.mapper.PromotionCampaignMapper;
import com.example.venue.reservation.entity.Reservation;
import com.example.venue.reservation.mapper.ReservationMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class PromotionPricingService {
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };
    private static final BigDecimal ZERO_AMOUNT = new BigDecimal("0.00");
    private static final Logger log = LoggerFactory.getLogger(PromotionPricingService.class);

    private final MemberService memberService;
    private final MemberLevelMapper memberLevelMapper;
    private final PriceRuleMapper priceRuleMapper;
    private final PromotionCampaignMapper campaignMapper;
    private final CouponService couponService;
    private final PricingSnapshotFactory pricingSnapshotFactory;
    private final ObjectMapper objectMapper;
    private final ReservationMapper reservationMapper;

    public PromotionPricingService(MemberService memberService, MemberLevelMapper memberLevelMapper,
                                   PriceRuleMapper priceRuleMapper, PromotionCampaignMapper campaignMapper,
                                   CouponService couponService, PricingSnapshotFactory pricingSnapshotFactory,
                                   ObjectMapper objectMapper, ReservationMapper reservationMapper) {
        this.memberService = memberService;
        this.memberLevelMapper = memberLevelMapper;
        this.priceRuleMapper = priceRuleMapper;
        this.campaignMapper = campaignMapper;
        this.couponService = couponService;
        this.pricingSnapshotFactory = pricingSnapshotFactory;
        this.objectMapper = objectMapper;
        this.reservationMapper = reservationMapper;
    }

    @Transactional(readOnly = true)
    public PromotionPricingContext previewForReservation(Long userId, Long venueId, Long spaceId,
                                                         LocalDateTime start, LocalDateTime end,
                                                         BigDecimal unitPrice, Long userCouponId) {
        BigDecimal baseAmount = durationAmount(unitPrice, start, end);
        MemberAccountResponse account = memberService.accountSnapshotOrDefault(userId);
        MemberLevel pricingLevel = memberLevelMapper.findActiveLevelForGrowth(
            account == null || account.growthValue == null ? 0 : account.growthValue);
        MemberLevelResponse levelResponse = pricingLevel == null ? currentLevel(account) : toLevelResponse(pricingLevel);
        String memberLevelCode = levelResponse == null ? null : levelResponse.levelCode;

        PriceRule rule = priceRuleMapper.findBestRule(venueId, spaceId, memberLevelCode,
            start, end, dayOfWeek(start), clock(start), clock(end));
        BigDecimal originalAmount = applyPriceRule(baseAmount, rule);
        BigDecimal priceRuleAdjustmentAmount = originalAmount.subtract(baseAmount).setScale(2, RoundingMode.HALF_UP);

        BigDecimal memberDiscountAmount = memberDiscount(originalAmount, pricingLevel);
        BigDecimal afterMember = originalAmount.subtract(memberDiscountAmount).max(BigDecimal.ZERO)
            .setScale(2, RoundingMode.HALF_UP);

        PromotionCampaign campaign = bestCampaign(venueId, spaceId, memberLevelCode, start, end);
        BigDecimal activityDiscountAmount = activityDiscount(afterMember, campaign);
        BigDecimal afterActivity = afterMember.subtract(activityDiscountAmount).max(BigDecimal.ZERO)
            .setScale(2, RoundingMode.HALF_UP);

        // A26-P0-01：先用 afterMember 判定券门槛和基础折扣，确保"按会员价达标、按活动价不达标"的券仍可用，
        // 且不可叠加保券场景的券折扣基于会员折扣后金额（而非含活动折扣的偏低基数）。
        CouponPricingView coupon = couponService.couponForPricing(userId, userCouponId, afterMember,
            venueId, spaceId, start, end);
        BigDecimal couponDiscountAmount = couponDiscount(coupon);

        PromotionPricingContext context = new PromotionPricingContext();
        context.venueId = venueId;
        context.spaceId = spaceId;
        context.startTime = start;
        context.endTime = end;
        context.unitPrice = normalize(unitPrice);
        context.baseAmount = baseAmount;
        context.priceRuleAdjustmentAmount = priceRuleAdjustmentAmount;
        context.memberAccount = account;
        context.memberLevel = levelResponse;
        context.priceRule = toPriceRuleResponse(rule);
        context.campaign = toCampaignResponse(campaign);
        context.coupon = coupon == null ? new CouponPricingView() : coupon;

        if (coupon != null && coupon.unavailableReason != null && !coupon.unavailableReason.isBlank()) {
            context.skippedComponents.add(component("COUPON", ZERO_AMOUNT, coupon.templateCode, coupon.unavailableReason));
            couponDiscountAmount = ZERO_AMOUNT;
        }

        boolean canStack = canStack(campaign, coupon, activityDiscountAmount, couponDiscountAmount);
        // A26-P0-01：可叠加且活动折扣 > 0 时，按 afterActivity 重算券折扣（叠加语义）。
        // 第一次用 afterMember 判门槛和基础折扣；可叠加时用 afterActivity 重算折扣，
        // 保证可叠加场景下券折扣基于活动后金额。若 afterActivity 未达门槛则保持 afterMember 折扣
        // （门槛已按 afterMember 通过，不应因活动折扣使券失效）。
        if (canStack && activityDiscountAmount.compareTo(ZERO_AMOUNT) > 0
            && coupon != null && coupon.userCouponId != null
            && (coupon.unavailableReason == null || coupon.unavailableReason.isBlank())) {
            CouponPricingView stackedCoupon = couponService.couponForPricing(userId, userCouponId, afterActivity,
                venueId, spaceId, start, end);
            if (stackedCoupon.unavailableReason == null || stackedCoupon.unavailableReason.isBlank()) {
                couponDiscountAmount = couponDiscount(stackedCoupon);
            }
        }

        if (!canStack) {
            if (activityDiscountAmount.compareTo(couponDiscountAmount) >= 0) {
                context.skippedComponents.add(component("COUPON", couponDiscountAmount,
                    coupon == null ? null : coupon.templateCode, "STACKING_NOT_ALLOWED"));
                couponDiscountAmount = ZERO_AMOUNT;
                // A26-P0-02：清空 context.coupon，使 reservation.userCouponId 不写入、
                // lockForReservation 不锁该券、settleSuccessfulPayment 不核销该券。
                // 保留 skippedComponents 中的 STACKING_NOT_ALLOWED 记录以告知调用方弃券原因。
                context.coupon = new CouponPricingView();
            } else {
                context.skippedComponents.add(component("ACTIVITY", activityDiscountAmount,
                    campaign == null ? null : campaign.campaignCode, "STACKING_NOT_ALLOWED"));
                activityDiscountAmount = ZERO_AMOUNT;
                // A26-P0-01：couponDiscountAmount 已按 afterMember 计算（保券弃活动时券折扣基于会员折扣后金额）。
            }
        }

        BigDecimal payableAmount = originalAmount
            .subtract(memberDiscountAmount)
            .subtract(activityDiscountAmount)
            .subtract(couponDiscountAmount)
            .max(BigDecimal.ZERO)
            .setScale(2, RoundingMode.HALF_UP);
        context.amountBreakdown = new AmountBreakdown(
            originalAmount,
            memberDiscountAmount,
            couponDiscountAmount,
            activityDiscountAmount,
            payableAmount,
            ZERO_AMOUNT,
            ZERO_AMOUNT,
            ZERO_AMOUNT
        );
        addDiscountComponents(context, memberDiscountAmount, activityDiscountAmount, couponDiscountAmount);
        context.pricingSnapshotJson = pricingSnapshotFactory.promotionSnapshot(context);
        return context;
    }

    /** Atomically reserves the discount budget during the reservation write transaction. */
    public boolean consumeCampaignBudget(Long campaignId, BigDecimal discountAmount) {
        if (campaignId == null || discountAmount == null || discountAmount.compareTo(ZERO_AMOUNT) <= 0) {
            return true;
        }
        return campaignMapper.consumeBudgetIfAvailable(campaignId, normalize(discountAmount), LocalDateTime.now()) == 1;
    }

    // AUD-P2-04：原子回补活动折扣预算，用于改期等场景释放原预约占用的活动预算，与 consumeCampaignBudget 对称。
    // 对 null/0 入参视为无操作返回 true；真实回补由 SQL 的 budget_used_amount >= discountAmount 条件防超额。
    public boolean releaseCampaignBudget(Long campaignId, BigDecimal discountAmount) {
        if (campaignId == null || discountAmount == null || discountAmount.compareTo(ZERO_AMOUNT) <= 0) {
            return true;
        }
        return campaignMapper.releaseBudgetIfAvailable(campaignId, normalize(discountAmount), LocalDateTime.now()) == 1;
    }

    // AUD-P2-04：单点 public 回补 reservation 占用的活动预算，消除 ReservationService/OrderService 双实现 DRY。
    // CAS 幂等：先 markBudgetReleased 占位（返回 0 表示已回补则跳过），再 releaseCampaignBudget 实际回补，
    // 防 expireOrder（reservation→EXPIRED）+ markRefundSuccess（reservation→REFUNDED，rescheduledLateRefund=false）
    // 双终态路径对同一 reservation 重复回补。改期绑定新活动时由 ReservationService.applyPromotionPricing
    // 重置 budget_released=0，使新活动预算可在后续终态回补。
    public void releaseCampaignBudgetForReservation(Reservation reservation) {
        if (reservation == null || reservation.promotionCampaignId == null
            || reservation.activityDiscountAmount == null
            || reservation.activityDiscountAmount.compareTo(ZERO_AMOUNT) <= 0) {
            return;
        }
        int updated = reservationMapper.markBudgetReleased(reservation.id);
        if (updated == 0) {
            // CAS 返回 0 表示已回补（budget_released=1），幂等跳过，避免双终态路径重复回补。
            return;
        }
        boolean released = releaseCampaignBudget(reservation.promotionCampaignId, reservation.activityDiscountAmount);
        if (!released) {
            // 回补未生效（used<amount 数据损坏或活动已逻辑删除），占位已标记不回滚，避免重复占位导致重复回补尝试。
            log.warn("AUD-P2-04 回补活动预算未生效 reservationId={} campaignId={} amount={}",
                reservation.id, reservation.promotionCampaignId, reservation.activityDiscountAmount);
        }
    }

    public PromotionPricingContext previewForReservationExcludingCampaign(Long userId, Long venueId, Long spaceId,
                                                                           LocalDateTime start, LocalDateTime end,
                                                                           BigDecimal unitPrice, Long userCouponId,
                                                                           Long excludedCampaignId) {
        PromotionPricingContext context = previewForReservation(userId, venueId, spaceId, start, end, unitPrice, userCouponId);
        if (excludedCampaignId == null || context.campaign == null || !excludedCampaignId.equals(context.campaign.id)) {
            return context;
        }
        context.campaign = null;
        BigDecimal original = context.amountBreakdown.originalAmount();
        BigDecimal memberDiscount = context.amountBreakdown.memberDiscountAmount();
        BigDecimal afterMember = original.subtract(memberDiscount).max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
        CouponPricingView coupon = couponService.couponForPricing(userId, userCouponId, afterMember,
            venueId, spaceId, start, end);
        BigDecimal couponDiscount = couponDiscount(coupon);
        context.coupon = coupon == null ? new CouponPricingView() : coupon;
        context.amountBreakdown = new AmountBreakdown(original, memberDiscount, couponDiscount, ZERO_AMOUNT,
            original.subtract(memberDiscount).subtract(couponDiscount).max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP),
            ZERO_AMOUNT, ZERO_AMOUNT, ZERO_AMOUNT);
        context.discountComponents.clear();
        context.skippedComponents.add(component("ACTIVITY", ZERO_AMOUNT, null, "BUDGET_EXHAUSTED"));
        addDiscountComponents(context, memberDiscount, ZERO_AMOUNT, couponDiscount);
        context.pricingSnapshotJson = pricingSnapshotFactory.promotionSnapshot(context);
        return context;
    }

    private void addDiscountComponents(PromotionPricingContext context, BigDecimal memberDiscountAmount,
                                       BigDecimal activityDiscountAmount, BigDecimal couponDiscountAmount) {
        if (memberDiscountAmount.compareTo(ZERO_AMOUNT) > 0) {
            context.discountComponents.add(component("MEMBER", memberDiscountAmount,
                context.memberLevel == null ? null : context.memberLevel.levelCode, null));
        }
        if (activityDiscountAmount.compareTo(ZERO_AMOUNT) > 0) {
            context.discountComponents.add(component("ACTIVITY", activityDiscountAmount,
                context.campaign == null ? null : context.campaign.campaignCode, null));
        }
        if (couponDiscountAmount.compareTo(ZERO_AMOUNT) > 0) {
            context.discountComponents.add(component("COUPON", couponDiscountAmount,
                context.coupon == null ? null : context.coupon.templateCode, null));
        }
    }

    private Map<String, Object> component(String type, BigDecimal amount, String code, String reason) {
        Map<String, Object> component = new LinkedHashMap<>();
        component.put("type", type);
        component.put("amount", normalize(amount));
        component.put("discountAmount", normalize(amount));
        component.put("code", code);
        if (reason != null) {
            component.put("reason", reason);
        }
        return component;
    }

    private PromotionCampaign bestCampaign(Long venueId, Long spaceId, String memberLevelCode,
                                           LocalDateTime start, LocalDateTime end) {
        List<PromotionCampaign> campaigns = campaignMapper.findActiveCampaigns(venueId, spaceId, memberLevelCode,
            start, end);
        if (campaigns == null || campaigns.isEmpty()) {
            return null;
        }
        PromotionCampaign campaign = campaigns.get(0);
        requirePricingCampaignConfig(campaign);
        return campaign;
    }

    /**
     * F-18：库内既有的非法活动折扣配置不能参与计价。与 applyPercentPriceRule 同类，配置损坏抛错而非裁剪、
     * 跳过或按 0 优惠处理，报价/预检/创建因此不会给出由非法配置算出的金额。
     */
    private void requirePricingCampaignConfig(PromotionCampaign campaign) {
        String violation = CampaignDiscountRule.violation(campaign.discountType, campaign.discountValue);
        if (violation != null) {
            throw new IllegalStateException("非法活动折扣配置（" + violation + "）campaign=" + campaign.campaignCode);
        }
    }

    private boolean canStack(PromotionCampaign campaign, CouponPricingView coupon,
                             BigDecimal activityDiscountAmount, BigDecimal couponDiscountAmount) {
        if (campaign == null || coupon == null) {
            return true;
        }
        if (activityDiscountAmount.compareTo(ZERO_AMOUNT) <= 0 || couponDiscountAmount.compareTo(ZERO_AMOUNT) <= 0) {
            return true;
        }
        return Integer.valueOf(1).equals(campaign.stackable) && !Boolean.FALSE.equals(coupon.allowActivityStack);
    }

    private BigDecimal durationAmount(BigDecimal unitPrice, LocalDateTime start, LocalDateTime end) {
        if (unitPrice == null || start == null || end == null || !start.isBefore(end)) {
            return ZERO_AMOUNT;
        }
        long minutes = Duration.between(start, end).toMinutes();
        BigDecimal hours = BigDecimal.valueOf(minutes).divide(BigDecimal.valueOf(60), 4, RoundingMode.HALF_UP);
        return unitPrice.multiply(hours).setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal applyPriceRule(BigDecimal baseAmount, PriceRule rule) {
        if (rule == null) {
            return normalize(baseAmount);
        }
        BigDecimal adjustmentValue = rule.adjustmentValue == null ? ZERO_AMOUNT : rule.adjustmentValue;
        String adjustmentType = normalizeCode(rule.adjustmentType);
        BigDecimal adjusted = switch (adjustmentType) {
            case "FIXED_PRICE" -> normalize(adjustmentValue);
            case "PERCENT" -> applyPercentPriceRule(baseAmount, rule);
            case "DELTA" -> normalize(baseAmount).add(normalize(adjustmentValue));
            default -> normalize(baseAmount);
        };
        return adjusted.max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal applyPercentPriceRule(BigDecimal baseAmount, PriceRule rule) {
        BigDecimal multiplier = rule.adjustmentValue;
        if (multiplier == null || multiplier.compareTo(BigDecimal.ZERO) <= 0 || multiplier.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalStateException("PriceRule PERCENT adjustmentValue must be > 0 and <= 1.00");
        }
        return normalize(baseAmount).multiply(multiplier);
    }

    private BigDecimal memberDiscount(BigDecimal originalAmount, MemberLevel pricingLevel) {
        BigDecimal discountRate = pricingLevel == null || pricingLevel.discountRate == null
            ? BigDecimal.ONE
            : pricingLevel.discountRate;
        BigDecimal payableAmount = originalAmount.multiply(discountRate).setScale(2, RoundingMode.HALF_UP);
        if (payableAmount.compareTo(ZERO_AMOUNT) < 0) {
            payableAmount = ZERO_AMOUNT;
        }
        BigDecimal memberDiscountAmount = originalAmount.subtract(payableAmount).setScale(2, RoundingMode.HALF_UP);
        if (memberDiscountAmount.compareTo(ZERO_AMOUNT) < 0) {
            return ZERO_AMOUNT;
        }
        return memberDiscountAmount;
    }

    private BigDecimal activityDiscount(BigDecimal amount, PromotionCampaign campaign) {
        if (campaign == null) {
            return ZERO_AMOUNT;
        }
        BigDecimal normalizedAmount = normalize(amount);
        BigDecimal discountValue = normalize(campaign.discountValue);
        BigDecimal discountAmount = switch (normalizeCode(campaign.discountType)) {
            case "CASH" -> discountValue.min(normalizedAmount);
            case "PERCENT" -> normalizedAmount.subtract(normalizedAmount.multiply(discountValue));
            case "FIXED" -> normalizedAmount.subtract(discountValue).max(BigDecimal.ZERO);
            default -> ZERO_AMOUNT;
        };
        BigDecimal cappedDiscount = discountAmount.max(BigDecimal.ZERO).min(normalizedAmount);
        BigDecimal maxDiscountAmount = campaignMaxDiscountAmount(campaign);
        if (maxDiscountAmount != null) {
            cappedDiscount = cappedDiscount.min(maxDiscountAmount.max(ZERO_AMOUNT));
        }
        return normalize(cappedDiscount);
    }

    private BigDecimal couponDiscount(CouponPricingView coupon) {
        if (coupon == null || coupon.discountAmount == null) {
            return ZERO_AMOUNT;
        }
        return normalize(coupon.discountAmount).max(BigDecimal.ZERO);
    }

    private MemberLevelResponse currentLevel(MemberAccountResponse account) {
        if (account == null) {
            return null;
        }
        MemberLevelResponse level = new MemberLevelResponse();
        level.levelCode = account.levelCode;
        level.levelName = account.levelName;
        level.discountRate = account.discountRate;
        level.benefitText = account.benefitText;
        return level;
    }

    private MemberLevelResponse toLevelResponse(MemberLevel level) {
        if (level == null) {
            return null;
        }
        MemberLevelResponse response = new MemberLevelResponse();
        response.id = level.id;
        response.levelCode = level.levelCode;
        response.levelName = level.levelName;
        response.growthThreshold = level.growthThreshold;
        response.discountRate = level.discountRate;
        response.benefitText = level.benefitText;
        response.ruleVersion = level.ruleVersion;
        response.status = level.status;
        return response;
    }

    private PriceRuleResponse toPriceRuleResponse(PriceRule rule) {
        if (rule == null) {
            return null;
        }
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
        response.adjustmentValue = normalize(rule.adjustmentValue);
        response.priority = rule.priority;
        response.status = rule.status;
        response.reasonText = rule.reasonText;
        return response;
    }

    private PromotionCampaignResponse toCampaignResponse(PromotionCampaign campaign) {
        if (campaign == null) {
            return null;
        }
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
        response.budgetAmount = campaign.budgetAmount == null ? null : normalize(campaign.budgetAmount);
        response.discountType = campaign.discountType;
        response.discountValue = normalize(campaign.discountValue);
        response.maxDiscountAmount = campaignMaxDiscountAmount(campaign);
        response.priority = campaign.priority == null ? 0 : campaign.priority;
        response.allowCouponStack = Integer.valueOf(1).equals(campaign.stackable);
        response.status = campaign.status;
        return response;
    }

    private BigDecimal campaignMaxDiscountAmount(PromotionCampaign campaign) {
        if (campaign == null) {
            return null;
        }
        return metadataAmount(campaign.metadataJson, "maxDiscountAmount");
    }

    private Map<String, Object> metadataMap(String metadataJson) {
        if (metadataJson == null || metadataJson.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(metadataJson, MAP_TYPE);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Promotion campaign metadata cannot be parsed", ex);
        }
    }

    private BigDecimal metadataAmount(String metadataJson, String key) {
        Object value = metadataMap(metadataJson).get(key);
        if (value == null) {
            return null;
        }
        try {
            if (value instanceof Number number) {
                return normalize(new BigDecimal(number.toString()));
            }
            String text = String.valueOf(value);
            return text.isBlank() ? null : normalize(new BigDecimal(text));
        } catch (NumberFormatException ex) {
            throw new IllegalStateException("Promotion campaign metadata amount cannot be parsed", ex);
        }
    }

    private int dayOfWeek(LocalDateTime value) {
        return value == null ? 0 : value.getDayOfWeek().getValue();
    }

    private LocalTime clock(LocalDateTime value) {
        return value == null ? null : value.toLocalTime();
    }

    private String normalizeCode(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private BigDecimal normalize(BigDecimal amount) {
        if (amount == null) {
            return ZERO_AMOUNT;
        }
        return amount.setScale(2, RoundingMode.HALF_UP);
    }
}
