package com.example.venue.promotion;

import com.example.venue.member.dto.MemberAccountResponse;
import com.example.venue.member.entity.MemberLevel;
import com.example.venue.member.mapper.MemberLevelMapper;
import com.example.venue.member.service.MemberService;
import com.example.venue.pricing.PricingSnapshotFactory;
import com.example.venue.promotion.dto.CouponPricingView;
import com.example.venue.promotion.dto.PromotionPricingContext;
import com.example.venue.promotion.entity.PriceRule;
import com.example.venue.promotion.entity.PromotionCampaign;
import com.example.venue.promotion.mapper.PriceRuleMapper;
import com.example.venue.promotion.mapper.PromotionCampaignMapper;
import com.example.venue.promotion.service.CouponService;
import com.example.venue.promotion.service.PromotionPricingService;
import com.example.venue.reservation.entity.Reservation;
import com.example.venue.reservation.mapper.ReservationMapper;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import com.fasterxml.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class PromotionPricingServiceTest {
    @Mock
    private MemberService memberService;
    @Mock
    private MemberLevelMapper memberLevelMapper;
    @Mock
    private PriceRuleMapper priceRuleMapper;
    @Mock
    private PromotionCampaignMapper campaignMapper;
    @Mock
    private CouponService couponService;
    @Mock
    private ReservationMapper reservationMapper;

    private PromotionPricingService service;

    @BeforeEach
    void setUp() {
        service = new PromotionPricingService(memberService, memberLevelMapper, priceRuleMapper,
            campaignMapper, couponService, new PricingSnapshotFactory(new ObjectMapper().findAndRegisterModules()),
            new ObjectMapper().findAndRegisterModules(), reservationMapper);
    }

    @Test
    void previewAppliesPriceRuleMemberActivityAndCouponInStableOrder() {
        LocalDateTime start = LocalDateTime.of(2026, 7, 4, 10, 0);
        LocalDateTime end = start.plusHours(2);
        stubMember(7L, account("GOLD", 2600), level("GOLD", "0.90"));
        PriceRule rule = priceRule(11L, "PEAK_WEEKEND", "DELTA", "20.00");
        when(priceRuleMapper.findBestRule(1L, 10L, "GOLD", start, end, 6,
            LocalTime.of(10, 0), LocalTime.of(12, 0))).thenReturn(rule);
        PromotionCampaign campaign = campaign(21L, "SUMMER_CAMP", "CASH", "10.00", true);
        when(campaignMapper.findActiveCampaigns(1L, 10L, "GOLD", start, end)).thenReturn(List.of(campaign));
        CouponPricingView coupon = coupon("20.00", true, null);
        // A26-P0-01：第一次按 afterMember(126) 判门槛和基础折扣，可叠加时第二次按 afterActivity(116) 重算折扣。
        when(couponService.couponForPricing(7L, 70L, new BigDecimal("126.00"), 1L, 10L, start, end))
            .thenReturn(coupon);
        when(couponService.couponForPricing(7L, 70L, new BigDecimal("116.00"), 1L, 10L, start, end))
            .thenReturn(coupon);

        PromotionPricingContext context = service.previewForReservation(
            7L, 1L, 10L, start, end, new BigDecimal("60.00"), 70L);

        assertThat(context.baseAmount).isEqualByComparingTo("120.00");
        assertThat(context.priceRuleAdjustmentAmount).isEqualByComparingTo("20.00");
        assertThat(context.amountBreakdown.originalAmount()).isEqualByComparingTo("140.00");
        assertThat(context.amountBreakdown.memberDiscountAmount()).isEqualByComparingTo("14.00");
        assertThat(context.amountBreakdown.activityDiscountAmount()).isEqualByComparingTo("10.00");
        assertThat(context.amountBreakdown.couponDiscountAmount()).isEqualByComparingTo("20.00");
        assertThat(context.amountBreakdown.payableAmount()).isEqualByComparingTo("96.00");
        assertThat(context.pricingVersion).isEqualTo("B3_PROMOTION_PRICING_V1");
        assertThat(context.priceRule.ruleCode).isEqualTo("PEAK_WEEKEND");
        assertThat(context.campaign.campaignCode).isEqualTo("SUMMER_CAMP");
        assertThat(context.coupon.templateCode).isEqualTo("B3_CASH_20");
        assertThat(context.discountComponents).hasSize(3);
        assertThat(context.pricingSnapshotJson).contains("\"pricingVersion\":\"B3_PROMOTION_PRICING_V1\"");
        verify(couponService, never()).lockForReservation(any(), any(), any(), any());
    }

    @Test
    void previewCapsActivityDiscountByCampaignMaxDiscountAndSnapshotsIt() {
        LocalDateTime start = LocalDateTime.of(2026, 7, 4, 10, 0);
        LocalDateTime end = start.plusHours(2);
        stubMember(7L, account("BASIC", 0), level("BASIC", "1.00"));
        when(priceRuleMapper.findBestRule(1L, 10L, "BASIC", start, end, 6,
            LocalTime.of(10, 0), LocalTime.of(12, 0))).thenReturn(null);
        PromotionCampaign campaign = campaign(22L, "HALF_OFF_CAPPED", "PERCENT", "0.50", true);
        campaign.metadataJson = "{\"maxDiscountAmount\":30.00}";
        when(campaignMapper.findActiveCampaigns(1L, 10L, "BASIC", start, end)).thenReturn(List.of(campaign));
        when(couponService.couponForPricing(7L, null, new BigDecimal("200.00"), 1L, 10L, start, end))
            .thenReturn(new CouponPricingView());

        PromotionPricingContext context = service.previewForReservation(
            7L, 1L, 10L, start, end, new BigDecimal("100.00"), null);

        assertThat(context.amountBreakdown.originalAmount()).isEqualByComparingTo("200.00");
        assertThat(context.amountBreakdown.activityDiscountAmount()).isEqualByComparingTo("30.00");
        assertThat(context.amountBreakdown.payableAmount()).isEqualByComparingTo("170.00");
        assertThat(context.campaign.maxDiscountAmount).isEqualByComparingTo("30.00");
        assertThat(context.pricingSnapshotJson).contains("\"maxDiscountAmount\":30.00");
        verify(couponService, never()).lockForReservation(any(), any(), any(), any());
    }

    @Test
    void previewFailsClosedOnIllegalStoredCampaignConfig() {
        LocalDateTime start = LocalDateTime.of(2026, 7, 4, 10, 0);
        LocalDateTime end = start.plusHours(2);
        stubMember(7L, account("BASIC", 0), level("BASIC", "1.00"));
        when(priceRuleMapper.findBestRule(1L, 10L, "BASIC", start, end, 6,
            LocalTime.of(10, 0), LocalTime.of(12, 0))).thenReturn(null);

        // F-18：库内既有非法配置不能算出任何金额。PERCENT=0 原会被读成整单免费。
        PromotionCampaign zeroPercent = campaign(22L, "SG_ILLEGAL_ZERO", "PERCENT", "0.00", true);
        PromotionCampaign overPercent = campaign(22L, "SG_ILLEGAL_OVER", "PERCENT", "1.50", true);
        PromotionCampaign zeroCash = campaign(22L, "SG_ILLEGAL_CASH", "CASH", "0.00", true);
        PromotionCampaign zeroFixed = campaign(22L, "SG_ILLEGAL_FIXED", "FIXED", "0.00", true);
        PromotionCampaign unknownType = campaign(22L, "SG_ILLEGAL_TYPE", "DISCOUNT_9", "20.00", true);
        PromotionCampaign missingValue = campaign(22L, "SG_ILLEGAL_NULL", "PERCENT", "0.50", true);
        missingValue.discountValue = null;

        for (PromotionCampaign illegal : List.of(zeroPercent, overPercent, zeroCash, zeroFixed, unknownType, missingValue)) {
            when(campaignMapper.findActiveCampaigns(1L, 10L, "BASIC", start, end)).thenReturn(List.of(illegal));

            assertThatThrownBy(() -> service.previewForReservation(
                7L, 1L, 10L, start, end, new BigDecimal("100.00"), null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("非法活动折扣配置")
                .hasMessageContaining(illegal.campaignCode);
        }

        // 报价链在计价前就中断：券侧不得被读取，也不得产出应付金额。
        verify(couponService, never()).couponForPricing(any(), any(), any(), any(), any(), any(), any());
        verify(couponService, never()).lockForReservation(any(), any(), any(), any());
    }

    @Test
    void campaignSelectionUsesHighestPriorityThenNewestId() throws Exception {
        Method method = PromotionCampaignMapper.class.getDeclaredMethod("findActiveCampaigns", Long.class, Long.class,
            String.class, LocalDateTime.class, LocalDateTime.class);
        Select select = method.getAnnotation(Select.class);
        assertThat(select).isNotNull();
        assertThat(String.join("\n", select.value())).contains("order by priority desc, id desc");

        LocalDateTime start = LocalDateTime.of(2026, 7, 4, 10, 0);
        LocalDateTime end = start.plusHours(2);
        stubMember(7L, account("BASIC", 0), level("BASIC", "1.00"));
        when(priceRuleMapper.findBestRule(1L, 10L, "BASIC", start, end, 6,
            LocalTime.of(10, 0), LocalTime.of(12, 0))).thenReturn(null);
        PromotionCampaign highPriority = campaign(31L, "SG_PRIORITY_HIGH", "CASH", "30.00", true);
        highPriority.priority = 80;
        PromotionCampaign lowPriority = campaign(30L, "SG_PRIORITY_LOW", "CASH", "5.00", true);
        lowPriority.priority = 10;
        // 两条重叠活动同时命中，选取顺序由上面的 SQL 决定；服务只取首个并把它回报给调用方。
        when(campaignMapper.findActiveCampaigns(1L, 10L, "BASIC", start, end))
            .thenReturn(List.of(highPriority, lowPriority));
        when(couponService.couponForPricing(7L, null, new BigDecimal("200.00"), 1L, 10L, start, end))
            .thenReturn(new CouponPricingView());

        PromotionPricingContext context = service.previewForReservation(
            7L, 1L, 10L, start, end, new BigDecimal("100.00"), null);

        assertThat(context.campaign.campaignCode).isEqualTo("SG_PRIORITY_HIGH");
        assertThat(context.campaign.priority).isEqualTo(80);
        assertThat(context.amountBreakdown.activityDiscountAmount()).isEqualByComparingTo("30.00");
        assertThat(context.amountBreakdown.payableAmount()).isEqualByComparingTo("170.00");
    }

    @Test
    void previewAppliesPercentPriceRuleAsMultiplierWithoutWritingState() {
        LocalDateTime start = LocalDateTime.of(2026, 7, 4, 10, 0);
        LocalDateTime end = start.plusHours(2);
        stubMember(7L, account("BASIC", 0), level("BASIC", "1.00"));
        PriceRule rule = priceRule(12L, "EARLY_BIRD_80", "PERCENT", "0.80");
        when(priceRuleMapper.findBestRule(1L, 10L, "BASIC", start, end, 6,
            LocalTime.of(10, 0), LocalTime.of(12, 0))).thenReturn(rule);
        when(campaignMapper.findActiveCampaigns(1L, 10L, "BASIC", start, end)).thenReturn(List.of());
        when(couponService.couponForPricing(7L, null, new BigDecimal("80.00"), 1L, 10L, start, end))
            .thenReturn(new CouponPricingView());

        PromotionPricingContext context = service.previewForReservation(
            7L, 1L, 10L, start, end, new BigDecimal("50.00"), null);

        assertThat(context.baseAmount).isEqualByComparingTo("100.00");
        assertThat(context.amountBreakdown.originalAmount()).isEqualByComparingTo("80.00");
        assertThat(context.priceRuleAdjustmentAmount).isEqualByComparingTo("-20.00");
        assertThat(context.amountBreakdown.payableAmount()).isEqualByComparingTo("80.00");
        assertThat(context.priceRule.adjustmentType).isEqualTo("PERCENT");
        assertThat(context.priceRule.adjustmentValue).isEqualByComparingTo("0.80");
        verify(couponService, never()).lockForReservation(any(), any(), any(), any());
    }

    @Test
    void previewRejectsInvalidPercentPriceRuleMultiplierWithoutWritingState() {
        LocalDateTime start = LocalDateTime.of(2026, 7, 4, 10, 0);
        LocalDateTime end = start.plusHours(2);
        stubMember(7L, account("BASIC", 0), level("BASIC", "1.00"));
        PriceRule rule = priceRule(13L, "INVALID_PERCENT", "PERCENT", "1.20");
        when(priceRuleMapper.findBestRule(1L, 10L, "BASIC", start, end, 6,
            LocalTime.of(10, 0), LocalTime.of(12, 0))).thenReturn(rule);

        assertThatThrownBy(() -> service.previewForReservation(
            7L, 1L, 10L, start, end, new BigDecimal("50.00"), null))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("PERCENT")
            .hasMessageContaining("> 0")
            .hasMessageContaining("<= 1.00");
        verify(couponService, never()).couponForPricing(any(), any(), any(), any(), any(), any(), any());
        verify(couponService, never()).lockForReservation(any(), any(), any(), any());
    }

    @Test
    void consumesBudgetWithOneAtomicConditionalMapperUpdate() {
        when(campaignMapper.consumeBudgetIfAvailable(eq(22L), eq(new BigDecimal("10.00")), any()))
            .thenReturn(1);

        assertThat(service.consumeCampaignBudget(22L, new BigDecimal("10.00"))).isTrue();
        verify(campaignMapper).consumeBudgetIfAvailable(eq(22L), eq(new BigDecimal("10.00")), any());
    }

    @Test
    void releasesBudgetWithOneAtomicConditionalMapperUpdate() {
        // AUD-P2-04：releaseCampaignBudget 应单次原子调用 releaseBudgetIfAvailable，
        // 与 consumeCampaignBudget 对称；mapper 返回 1 表示回补生效。
        when(campaignMapper.releaseBudgetIfAvailable(eq(22L), eq(new BigDecimal("10.00")), any()))
            .thenReturn(1);

        assertThat(service.releaseCampaignBudget(22L, new BigDecimal("10.00"))).isTrue();
        verify(campaignMapper).releaseBudgetIfAvailable(eq(22L), eq(new BigDecimal("10.00")), any());
    }

    @Test
    void releasesBudgetNoOpsOnNullOrZeroAmountWithoutInvokingMapper() {
        // AUD-P2-04：null 或 0 金额视为无操作，不调用 mapper，与 consumeCampaignBudget 容错一致。
        assertThat(service.releaseCampaignBudget(null, new BigDecimal("10.00"))).isTrue();
        assertThat(service.releaseCampaignBudget(22L, null)).isTrue();
        assertThat(service.releaseCampaignBudget(22L, new BigDecimal("0.00"))).isTrue();
        assertThat(service.releaseCampaignBudget(22L, new BigDecimal("-1.00"))).isTrue();
        verify(campaignMapper, never()).releaseBudgetIfAvailable(any(), any(), any());
    }

    @Test
    void releasesBudgetReturnsFalseWhenMapperUpdateAffectsNoRow() {
        // AUD-P2-04：mapper 返回 0（如 used<amount 防超额、活动已逻辑删除、无 budget 活动恒 used=0）
        // 时 service 返回 false，调用方可据此记 WARN，但不阻塞业务流程。
        when(campaignMapper.releaseBudgetIfAvailable(eq(22L), eq(new BigDecimal("10.00")), any()))
            .thenReturn(0);

        assertThat(service.releaseCampaignBudget(22L, new BigDecimal("10.00"))).isFalse();
        verify(campaignMapper).releaseBudgetIfAvailable(eq(22L), eq(new BigDecimal("10.00")), any());
    }

    @Test
    void releaseForReservationReleasesBudgetWhenCasMarkSucceeds() {
        // AUD-P2-04：CAS 占位成功（markBudgetReleased 返回 1）时调用 releaseCampaignBudget 实际回补。
        Reservation reservation = reservationWithCampaign(1L, 8L, "10.00");
        when(reservationMapper.markBudgetReleased(1L)).thenReturn(1);
        when(campaignMapper.releaseBudgetIfAvailable(eq(8L), eq(new BigDecimal("10.00")), any()))
            .thenReturn(1);

        service.releaseCampaignBudgetForReservation(reservation);

        verify(reservationMapper).markBudgetReleased(1L);
        verify(campaignMapper).releaseBudgetIfAvailable(eq(8L), eq(new BigDecimal("10.00")), any());
    }

    @Test
    void releaseForReservationSkipsReleaseWhenCasMarkReturnsZero() {
        // AUD-P2-04：CAS 占位返回 0（已回补 budget_released=1）时幂等跳过，不调 releaseCampaignBudget，
        // 防 expireOrder+markRefundSuccess 双终态路径对同一 reservation 重复回补。
        Reservation reservation = reservationWithCampaign(1L, 8L, "10.00");
        when(reservationMapper.markBudgetReleased(1L)).thenReturn(0);

        service.releaseCampaignBudgetForReservation(reservation);

        verify(reservationMapper).markBudgetReleased(1L);
        verify(campaignMapper, never()).releaseBudgetIfAvailable(any(), any(), any());
    }

    @Test
    void releaseForReservationSkipsWhenNoCampaignOrZeroAmount() {
        // AUD-P2-04：无活动（campaignId=null）或无折扣金额（<=0）跳过，不调 markBudgetReleased，
        // 与 releaseCampaignBudget 的 null/0 容错一致。
        Reservation noCampaign = reservationWithCampaign(1L, null, "10.00");
        Reservation zeroAmount = reservationWithCampaign(2L, 8L, "0.00");

        service.releaseCampaignBudgetForReservation(noCampaign);
        service.releaseCampaignBudgetForReservation(zeroAmount);

        verify(reservationMapper, never()).markBudgetReleased(any());
        verify(campaignMapper, never()).releaseBudgetIfAvailable(any(), any(), any());
    }

    private Reservation reservationWithCampaign(Long id, Long campaignId, String discountAmount) {
        Reservation reservation = new Reservation();
        reservation.id = id;
        reservation.promotionCampaignId = campaignId;
        reservation.activityDiscountAmount = new BigDecimal(discountAmount);
        return reservation;
    }

    @Test
    void previewChoosesBetterDiscountWhenActivityAndCouponCannotStack() {
        LocalDateTime start = LocalDateTime.of(2026, 7, 4, 10, 0);
        LocalDateTime end = start.plusHours(2);
        stubMember(7L, account("GOLD", 2600), level("GOLD", "0.90"));
        when(priceRuleMapper.findBestRule(1L, 10L, "GOLD", start, end, 6,
            LocalTime.of(10, 0), LocalTime.of(12, 0))).thenReturn(null);
        PromotionCampaign campaign = campaign(21L, "SUMMER_CAMP", "CASH", "30.00", false);
        when(campaignMapper.findActiveCampaigns(1L, 10L, "GOLD", start, end)).thenReturn(List.of(campaign));
        // A26-P0-01：不可叠加时只调用一次 couponForPricing(afterMember=108)，不重算。
        when(couponService.couponForPricing(7L, 70L, new BigDecimal("108.00"), 1L, 10L, start, end))
            .thenReturn(coupon("20.00", false, null));

        PromotionPricingContext context = service.previewForReservation(
            7L, 1L, 10L, start, end, new BigDecimal("60.00"), 70L);

        assertThat(context.amountBreakdown.originalAmount()).isEqualByComparingTo("120.00");
        assertThat(context.amountBreakdown.memberDiscountAmount()).isEqualByComparingTo("12.00");
        assertThat(context.amountBreakdown.activityDiscountAmount()).isEqualByComparingTo("30.00");
        assertThat(context.amountBreakdown.couponDiscountAmount()).isEqualByComparingTo("0.00");
        assertThat(context.amountBreakdown.payableAmount()).isEqualByComparingTo("78.00");
        assertThat(context.skippedComponents).hasSize(1);
        assertThat(context.skippedComponents.get(0).get("type")).isEqualTo("COUPON");
        assertThat(context.skippedComponents.get(0).get("reason")).isEqualTo("STACKING_NOT_ALLOWED");
        // A26-P0-02：保活动弃券时 context.coupon.userCouponId 应被清空，
        // 使 ReservationService 不写入 reservation.userCouponId、不调 lockForReservation。
        assertThat(context.coupon.userCouponId).isNull();
        verify(couponService, never()).lockForReservation(any(), any(), any(), any());
    }

    @Test
    void previewKeepsCouponDiscountOnAfterMemberWhenActivityDroppedForStacking() {
        // A26-P0-01：原价 100 + 五折券 + 不可叠加八折活动 → 用户应付 50（仅用券），券折扣按 100 计算 = 50。
        // 修复前：券折扣按 afterActivity=80 算 = 40，保券弃活动后 payable = 100 - 40 = 60，用户多付 10。
        LocalDateTime start = LocalDateTime.of(2026, 7, 4, 10, 0);
        LocalDateTime end = start.plusHours(2);
        stubMember(7L, account("BASIC", 0), level("BASIC", "1.00"));
        when(priceRuleMapper.findBestRule(1L, 10L, "BASIC", start, end, 6,
            LocalTime.of(10, 0), LocalTime.of(12, 0))).thenReturn(null);
        // 八折活动（PERCENT 0.80）：activityDiscount = 100 - 80 = 20
        PromotionCampaign campaign = campaign(21L, "EIGHTY_PERCENT_OFF", "PERCENT", "0.80", false);
        when(campaignMapper.findActiveCampaigns(1L, 10L, "BASIC", start, end)).thenReturn(List.of(campaign));
        // 五折券（PERCENT 0.50）：afterMember=100 折扣 = 100 - 50 = 50
        CouponPricingView coupon = couponWith("B3_PERCENT_50", "PERCENT", "0.50", "50.00", false, null);
        when(couponService.couponForPricing(7L, 70L, new BigDecimal("100.00"), 1L, 10L, start, end))
            .thenReturn(coupon);

        PromotionPricingContext context = service.previewForReservation(
            7L, 1L, 10L, start, end, new BigDecimal("50.00"), 70L);

        assertThat(context.amountBreakdown.originalAmount()).isEqualByComparingTo("100.00");
        assertThat(context.amountBreakdown.memberDiscountAmount()).isEqualByComparingTo("0.00");
        assertThat(context.amountBreakdown.activityDiscountAmount()).isEqualByComparingTo("0.00");
        // 关键断言：券折扣按 afterMember=100 算 = 50，不是按 afterActivity=80 算 = 40
        assertThat(context.amountBreakdown.couponDiscountAmount()).isEqualByComparingTo("50.00");
        assertThat(context.amountBreakdown.payableAmount()).isEqualByComparingTo("50.00");
        assertThat(context.skippedComponents).hasSize(1);
        assertThat(context.skippedComponents.get(0).get("type")).isEqualTo("ACTIVITY");
        assertThat(context.skippedComponents.get(0).get("reason")).isEqualTo("STACKING_NOT_ALLOWED");
        // 保券弃活动时 context.coupon.userCouponId 保留（券被锁定和核销）
        assertThat(context.coupon.userCouponId).isEqualTo(70L);
        verify(couponService, never()).lockForReservation(any(), any(), any(), any());
    }

    @Test
    void previewUsesFixedCouponFaceValueWhenActivityDroppedForStacking() {
        // A26-P0-01：FIXED 50 券用于 200 元订单 + 不可叠加活动 → 应付 50（券后一口价）。
        // 修复前：券折扣按 afterActivity=160 算 = 110，保券弃活动后 payable = 200 - 110 = 90，偏离一口价。
        LocalDateTime start = LocalDateTime.of(2026, 7, 4, 10, 0);
        LocalDateTime end = start.plusHours(2);
        stubMember(7L, account("BASIC", 0), level("BASIC", "1.00"));
        when(priceRuleMapper.findBestRule(1L, 10L, "BASIC", start, end, 6,
            LocalTime.of(10, 0), LocalTime.of(12, 0))).thenReturn(null);
        // 八折活动（PERCENT 0.80）：activityDiscount = 200 - 160 = 40
        PromotionCampaign campaign = campaign(21L, "EIGHTY_PERCENT_OFF", "PERCENT", "0.80", false);
        when(campaignMapper.findActiveCampaigns(1L, 10L, "BASIC", start, end)).thenReturn(List.of(campaign));
        // FIXED 50 券：afterMember=200 折扣 = 200 - 50 = 150
        CouponPricingView coupon = couponWith("B3_FIXED_50", "FIXED", "50.00", "150.00", false, null);
        when(couponService.couponForPricing(7L, 70L, new BigDecimal("200.00"), 1L, 10L, start, end))
            .thenReturn(coupon);

        PromotionPricingContext context = service.previewForReservation(
            7L, 1L, 10L, start, end, new BigDecimal("100.00"), 70L);

        assertThat(context.amountBreakdown.originalAmount()).isEqualByComparingTo("200.00");
        assertThat(context.amountBreakdown.activityDiscountAmount()).isEqualByComparingTo("0.00");
        // 关键断言：FIXED 券折扣 = 200 - 50 = 150，应付 = 200 - 150 = 50（券后一口价）
        assertThat(context.amountBreakdown.couponDiscountAmount()).isEqualByComparingTo("150.00");
        assertThat(context.amountBreakdown.payableAmount()).isEqualByComparingTo("50.00");
        assertThat(context.skippedComponents).hasSize(1);
        assertThat(context.skippedComponents.get(0).get("type")).isEqualTo("ACTIVITY");
        assertThat(context.coupon.userCouponId).isEqualTo(70L);
        verify(couponService, never()).lockForReservation(any(), any(), any(), any());
    }

    @Test
    void previewDropsCouponWhenActivityDiscountGreaterThanOrEqualAndClearsUserCouponId() {
        // A26-P0-02：券被弃用（活动折扣 ≥ 券折扣）时 context.coupon.userCouponId 应为 null，
        // 使 ReservationService 不写入 reservation.userCouponId、不调 lockForReservation；
        // 支付成功时 settleSuccessfulPayment 不调 markUsedForOrder，券保持 CLAIMED 不被消耗。
        LocalDateTime start = LocalDateTime.of(2026, 7, 4, 10, 0);
        LocalDateTime end = start.plusHours(2);
        stubMember(7L, account("BASIC", 0), level("BASIC", "1.00"));
        when(priceRuleMapper.findBestRule(1L, 10L, "BASIC", start, end, 6,
            LocalTime.of(10, 0), LocalTime.of(12, 0))).thenReturn(null);
        // 八折活动：activityDiscount = 100 - 80 = 20
        PromotionCampaign campaign = campaign(21L, "EIGHTY_PERCENT_OFF", "PERCENT", "0.80", false);
        when(campaignMapper.findActiveCampaigns(1L, 10L, "BASIC", start, end)).thenReturn(List.of(campaign));
        // 五折券：afterMember=100 折扣 = 50。但这里用 CASH 10 券，使 activityDiscount(20) >= couponDiscount(10)
        CouponPricingView coupon = couponWith("B3_CASH_10", "CASH", "10.00", "10.00", false, null);
        when(couponService.couponForPricing(7L, 70L, new BigDecimal("100.00"), 1L, 10L, start, end))
            .thenReturn(coupon);

        PromotionPricingContext context = service.previewForReservation(
            7L, 1L, 10L, start, end, new BigDecimal("50.00"), 70L);

        // 保活动弃券：activityDiscount 保留 20，couponDiscount 置 0
        assertThat(context.amountBreakdown.activityDiscountAmount()).isEqualByComparingTo("20.00");
        assertThat(context.amountBreakdown.couponDiscountAmount()).isEqualByComparingTo("0.00");
        assertThat(context.amountBreakdown.payableAmount()).isEqualByComparingTo("80.00");
        // 关键断言：context.coupon.userCouponId 被清空，ReservationService 不会写入/锁券/核销
        assertThat(context.coupon.userCouponId).isNull();
        assertThat(context.skippedComponents).hasSize(1);
        assertThat(context.skippedComponents.get(0).get("type")).isEqualTo("COUPON");
        assertThat(context.skippedComponents.get(0).get("reason")).isEqualTo("STACKING_NOT_ALLOWED");
        verify(couponService, never()).lockForReservation(any(), any(), any(), any());
    }

    @Test
    void previewUsesAfterActivityDiscountWhenStackable() {
        // A26-P0-01 回归保护：可叠加时券折扣基于 afterActivity（叠加语义），不是 afterMember。
        // 原价 100 + 八折活动（折扣 20）+ 五折券（可叠加）：
        //   afterMember=100, afterActivity=80, 券折扣基于 afterActivity=80 算 = 40, payable = 100-20-40 = 40。
        LocalDateTime start = LocalDateTime.of(2026, 7, 4, 10, 0);
        LocalDateTime end = start.plusHours(2);
        stubMember(7L, account("BASIC", 0), level("BASIC", "1.00"));
        when(priceRuleMapper.findBestRule(1L, 10L, "BASIC", start, end, 6,
            LocalTime.of(10, 0), LocalTime.of(12, 0))).thenReturn(null);
        PromotionCampaign campaign = campaign(21L, "EIGHTY_PERCENT_OFF", "PERCENT", "0.80", true);
        when(campaignMapper.findActiveCampaigns(1L, 10L, "BASIC", start, end)).thenReturn(List.of(campaign));
        // 五折券：afterMember=100 折扣 = 50；afterActivity=80 折扣 = 40
        CouponPricingView couponAfterMember = couponWith("B3_PERCENT_50", "PERCENT", "0.50", "50.00", true, null);
        CouponPricingView couponAfterActivity = couponWith("B3_PERCENT_50", "PERCENT", "0.50", "40.00", true, null);
        when(couponService.couponForPricing(7L, 70L, new BigDecimal("100.00"), 1L, 10L, start, end))
            .thenReturn(couponAfterMember);
        when(couponService.couponForPricing(7L, 70L, new BigDecimal("80.00"), 1L, 10L, start, end))
            .thenReturn(couponAfterActivity);

        PromotionPricingContext context = service.previewForReservation(
            7L, 1L, 10L, start, end, new BigDecimal("50.00"), 70L);

        assertThat(context.amountBreakdown.activityDiscountAmount()).isEqualByComparingTo("20.00");
        // 可叠加：券折扣基于 afterActivity=80 算 = 40，不是基于 afterMember=100 算 = 50
        assertThat(context.amountBreakdown.couponDiscountAmount()).isEqualByComparingTo("40.00");
        assertThat(context.amountBreakdown.payableAmount()).isEqualByComparingTo("40.00");
        assertThat(context.coupon.userCouponId).isEqualTo(70L);
        verify(couponService, never()).lockForReservation(any(), any(), any(), any());
    }

    @Test
    void previewRejectsUnavailableCouponWithoutWritingState() {
        LocalDateTime start = LocalDateTime.of(2026, 7, 4, 10, 0);
        LocalDateTime end = start.plusHours(2);
        stubMember(7L, account("BASIC", 0), level("BASIC", "1.00"));
        when(priceRuleMapper.findBestRule(1L, 10L, "BASIC", start, end, 6,
            LocalTime.of(10, 0), LocalTime.of(12, 0))).thenReturn(null);
        when(campaignMapper.findActiveCampaigns(1L, 10L, "BASIC", start, end)).thenReturn(List.of());
        when(couponService.couponForPricing(7L, 70L, new BigDecimal("120.00"), 1L, 10L, start, end))
            .thenReturn(coupon("0.00", true, "优惠券已过期"));

        PromotionPricingContext context = service.previewForReservation(
            7L, 1L, 10L, start, end, new BigDecimal("60.00"), 70L);

        assertThat(context.amountBreakdown.couponDiscountAmount()).isEqualByComparingTo("0.00");
        assertThat(context.amountBreakdown.payableAmount()).isEqualByComparingTo("120.00");
        assertThat(context.skippedComponents).hasSize(1);
        assertThat(context.skippedComponents.get(0).get("type")).isEqualTo("COUPON");
        assertThat(context.skippedComponents.get(0).get("reason")).isEqualTo("优惠券已过期");
        verify(couponService, never()).lockForReservation(any(), any(), any(), any());
    }

    @Test
    void priceRuleUsesHighestPriorityThenNewestId() throws Exception {
        LocalDateTime start = LocalDateTime.of(2026, 7, 4, 10, 0);
        LocalDateTime end = start.plusHours(2);
        stubMember(7L, account("GOLD", 2600), level("GOLD", "0.90"));
        PriceRule chosen = priceRule(99L, "NEWEST_PRIORITY_RULE", "FIXED_PRICE", "80.00");
        when(priceRuleMapper.findBestRule(1L, 10L, "GOLD", start, end, 6,
            LocalTime.of(10, 0), LocalTime.of(12, 0))).thenReturn(chosen);
        when(campaignMapper.findActiveCampaigns(1L, 10L, "GOLD", start, end)).thenReturn(List.of());
        when(couponService.couponForPricing(7L, null, new BigDecimal("72.00"), 1L, 10L, start, end))
            .thenReturn(new CouponPricingView());

        PromotionPricingContext context = service.previewForReservation(
            7L, 1L, 10L, start, end, new BigDecimal("60.00"), null);

        assertThat(context.priceRule.id).isEqualTo(99L);
        assertThat(context.priceRule.ruleCode).isEqualTo("NEWEST_PRIORITY_RULE");
        assertThat(context.amountBreakdown.originalAmount()).isEqualByComparingTo("80.00");
        assertThat(context.priceRuleAdjustmentAmount).isEqualByComparingTo("-40.00");
        verify(priceRuleMapper).findBestRule(1L, 10L, "GOLD", start, end, 6,
            LocalTime.of(10, 0), LocalTime.of(12, 0));

        Method method = PriceRuleMapper.class.getDeclaredMethod("findBestRule", Long.class, Long.class, String.class,
            LocalDateTime.class, LocalDateTime.class, int.class, LocalTime.class, LocalTime.class);
        Select select = method.getAnnotation(Select.class);
        assertThat(select).isNotNull();
        assertThat(String.join("\n", select.value())).contains("order by priority desc, id desc");
    }

    @Test
    void priceRuleMapperUsesHalfOpenRangeForAdjacentAndOverlappingReservations() throws Exception {
        Method method = PriceRuleMapper.class.getDeclaredMethod("findBestRule", Long.class, Long.class, String.class,
            LocalDateTime.class, LocalDateTime.class, int.class, LocalTime.class, LocalTime.class);
        Select select = method.getAnnotation(Select.class);

        assertThat(select).isNotNull();
        String sql = String.join("\n", select.value()).replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
        assertThat(sql)
            .contains("start_time < #{endtime}", "end_time > #{starttime}")
            .doesNotContain("start_time <= #{endtime}", "end_time >= #{starttime}");
    }

    private void stubMember(Long userId, MemberAccountResponse account, MemberLevel level) {
        when(memberService.accountSnapshotOrDefault(userId)).thenReturn(account);
        when(memberLevelMapper.findActiveLevelForGrowth(account.growthValue == null ? 0 : account.growthValue))
            .thenReturn(level);
    }

    private MemberAccountResponse account(String levelCode, int growthValue) {
        MemberAccountResponse account = new MemberAccountResponse();
        account.userId = 7L;
        account.memberAccountId = 77L;
        account.levelCode = levelCode;
        account.levelName = levelCode + "会员";
        account.growthValue = growthValue;
        account.discountRate = "GOLD".equals(levelCode) ? new BigDecimal("0.90") : new BigDecimal("1.00");
        return account;
    }

    private MemberLevel level(String levelCode, String discountRate) {
        MemberLevel level = new MemberLevel();
        level.id = "GOLD".equals(levelCode) ? 3L : 1L;
        level.levelCode = levelCode;
        level.levelName = levelCode + "会员";
        level.growthThreshold = "GOLD".equals(levelCode) ? 2000 : 0;
        level.discountRate = new BigDecimal(discountRate);
        level.ruleVersion = "B2_MEMBER_V1";
        level.status = "ACTIVE";
        return level;
    }

    private PriceRule priceRule(Long id, String ruleCode, String adjustmentType, String adjustmentValue) {
        PriceRule rule = new PriceRule();
        rule.id = id;
        rule.ruleCode = ruleCode;
        rule.ruleName = "周末高峰价";
        rule.ruleType = "PEAK";
        rule.venueId = 1L;
        rule.spaceId = 10L;
        rule.memberLevelCode = "GOLD";
        rule.startTime = LocalDateTime.of(2026, 7, 1, 0, 0);
        rule.endTime = LocalDateTime.of(2026, 7, 31, 23, 59);
        rule.dayOfWeekMask = "6,7";
        rule.startClock = LocalTime.of(10, 0);
        rule.endClock = LocalTime.of(12, 0);
        rule.adjustmentType = adjustmentType;
        rule.adjustmentValue = new BigDecimal(adjustmentValue);
        rule.priority = 10;
        rule.status = "ACTIVE";
        rule.reasonText = "周末高峰";
        rule.deleted = 0;
        return rule;
    }

    private PromotionCampaign campaign(Long id, String campaignCode, String discountType, String discountValue,
                                       boolean allowCouponStack) {
        PromotionCampaign campaign = new PromotionCampaign();
        campaign.id = id;
        campaign.campaignCode = campaignCode;
        campaign.campaignName = "暑期活动";
        campaign.campaignType = "DISCOUNT";
        campaign.venueId = 1L;
        campaign.spaceId = 10L;
        campaign.memberLevelCode = "GOLD";
        campaign.discountType = discountType;
        campaign.discountValue = new BigDecimal(discountValue);
        campaign.priority = 10;
        campaign.stackable = allowCouponStack ? 1 : 0;
        campaign.startTime = LocalDateTime.of(2026, 7, 1, 0, 0);
        campaign.endTime = LocalDateTime.of(2026, 7, 31, 23, 59);
        campaign.status = "ACTIVE";
        campaign.deleted = 0;
        return campaign;
    }

    private CouponPricingView coupon(String discountAmount, boolean allowActivityStack, String unavailableReason) {
        CouponPricingView view = couponWith("B3_CASH_20", "CASH", "20.00", discountAmount,
            allowActivityStack, unavailableReason);
        view.templateName = "满减券";
        view.thresholdAmount = new BigDecimal("100.00");
        return view;
    }

    private CouponPricingView couponWith(String templateCode, String couponType, String discountValue,
                                          String discountAmount, boolean allowActivityStack,
                                          String unavailableReason) {
        CouponPricingView coupon = new CouponPricingView();
        coupon.userCouponId = 70L;
        coupon.templateId = 18L;
        coupon.templateCode = templateCode;
        coupon.templateName = "测试券";
        coupon.couponType = couponType;
        coupon.discountValue = new BigDecimal(discountValue);
        coupon.thresholdAmount = new BigDecimal("0.00");
        coupon.allowActivityStack = allowActivityStack;
        coupon.discountAmount = new BigDecimal(discountAmount);
        coupon.unavailableReason = unavailableReason;
        return coupon;
    }
}
