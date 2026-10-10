package com.example.venue.pricing;

import com.example.venue.member.dto.MemberAccountResponse;
import com.example.venue.member.dto.MemberLevelResponse;
import com.example.venue.promotion.dto.CouponPricingView;
import com.example.venue.promotion.dto.PriceRuleResponse;
import com.example.venue.promotion.dto.PromotionCampaignResponse;
import com.example.venue.promotion.dto.PromotionPricingContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PricingSnapshotFactoryTest {
    private final PricingSnapshotFactory factory = new PricingSnapshotFactory(new ObjectMapper().findAndRegisterModules());
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void baseAmountBreakdownNormalizesBaseAmountsToTwoDecimals() {
        AmountBreakdown breakdown = AmountBreakdown.base(new BigDecimal("120"));

        assertThat(breakdown.originalAmount()).isEqualByComparingTo("120.00");
        assertThat(breakdown.payableAmount()).isEqualByComparingTo("120.00");
        assertThat(breakdown.memberDiscountAmount()).isEqualByComparingTo("0.00");
        assertThat(breakdown.couponDiscountAmount()).isEqualByComparingTo("0.00");
        assertThat(breakdown.activityDiscountAmount()).isEqualByComparingTo("0.00");
        assertThat(breakdown.paidAmount()).isEqualByComparingTo("0.00");
        assertThat(breakdown.feeAmount()).isEqualByComparingTo("0.00");
        assertThat(breakdown.netAmount()).isEqualByComparingTo("0.00");
    }

    @Test
    void baseSnapshotWritesDeterministicNumericJson() throws Exception {
        LocalDateTime start = LocalDateTime.of(2026, 7, 1, 10, 0);
        LocalDateTime end = start.plusHours(2);
        AmountBreakdown breakdown = AmountBreakdown.base(new BigDecimal("120.00"));

        String json = factory.baseSnapshot(20L, 10L, start, end, new BigDecimal("60.00"), breakdown);

        assertThat(json).contains("\"pricingVersion\":\"B0_5_BASE_V1\"");
        assertThat(json).contains("\"venueId\":20");
        assertThat(json).contains("\"spaceId\":10");
        assertThat(json).contains("\"unitPrice\":60.00");
        assertThat(json).contains("\"originalAmount\":120.00");
        assertThat(json).contains("\"payableAmount\":120.00");
        assertThat(json).contains("\"discountAmount\":0.00");

        JsonNode root = objectMapper.readTree(json);
        assertThat(root.get("payableAmount").isNumber()).isTrue();
        assertThat(root.get("payableAmount").decimalValue()).isEqualByComparingTo("120.00");
    }

    @Test
    void memberSnapshotIncludesStableMemberContextAndDiscountComponent() throws Exception {
        LocalDateTime start = LocalDateTime.of(2026, 7, 1, 10, 0);
        LocalDateTime end = start.plusHours(2);
        AmountBreakdown breakdown = new AmountBreakdown(
            new BigDecimal("120.00"),
            new BigDecimal("12.00"),
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            new BigDecimal("108.00"),
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO
        );
        MemberLevelResponse level = new MemberLevelResponse();
        level.id = 3L;
        level.levelCode = "GOLD";
        level.levelName = "黄金会员";
        level.discountRate = new BigDecimal("0.90");
        level.ruleVersion = "B2_MEMBER_V1";
        MemberAccountResponse account = new MemberAccountResponse();
        account.userId = 7L;
        account.memberAccountId = 77L;
        account.levelCode = "GOLD";
        account.levelName = "黄金会员";

        String json = factory.memberSnapshot(20L, 10L, start, end, new BigDecimal("60.00"), breakdown, level, account);

        JsonNode root = objectMapper.readTree(json);
        assertThat(root.get("pricingVersion").asText()).isEqualTo("B2_MEMBER_V1");
        assertThat(root.get("schemaVersion").asText()).isEqualTo("B2_MEMBER_V1");
        assertThat(root.get("memberAccountId").asLong()).isEqualTo(77L);
        assertThat(root.get("memberLevel").get("levelCode").asText()).isEqualTo("GOLD");
        assertThat(root.get("discountComponents").get(0).get("type").asText()).isEqualTo("MEMBER");
        assertThat(root.get("discountComponents").get(0).get("amount").decimalValue()).isEqualByComparingTo("12.00");
        assertThat(root.get("discountComponents").get(0).get("discountAmount").decimalValue()).isEqualByComparingTo("12.00");
        assertThat(root.get("amountBreakdown").get("original").decimalValue()).isEqualByComparingTo("120.00");
        assertThat(root.get("amountBreakdown").get("member").decimalValue()).isEqualByComparingTo("12.00");
        assertThat(root.get("amountBreakdown").get("coupon").decimalValue()).isEqualByComparingTo("0.00");
        assertThat(root.get("amountBreakdown").get("activity").decimalValue()).isEqualByComparingTo("0.00");
        assertThat(root.get("amountBreakdown").get("payable").decimalValue()).isEqualByComparingTo("108.00");
        assertThat(root.get("amountBreakdown").get("originalAmount").decimalValue()).isEqualByComparingTo("120.00");
        assertThat(root.get("amountBreakdown").get("memberDiscountAmount").decimalValue()).isEqualByComparingTo("12.00");
        assertThat(root.get("amountBreakdown").get("couponDiscountAmount").decimalValue()).isEqualByComparingTo("0.00");
        assertThat(root.get("amountBreakdown").get("activityDiscountAmount").decimalValue()).isEqualByComparingTo("0.00");
        assertThat(root.get("amountBreakdown").get("payableAmount").decimalValue()).isEqualByComparingTo("108.00");
        assertThat(root.get("amountBreakdown").get("paid").decimalValue()).isEqualByComparingTo("0.00");
        assertThat(root.get("amountBreakdown").get("fee").decimalValue()).isEqualByComparingTo("0.00");
        assertThat(root.get("amountBreakdown").get("net").decimalValue()).isEqualByComparingTo("0.00");
        assertThat(root.get("payableAmount").decimalValue()).isEqualByComparingTo("108.00");
    }

    @Test
    void promotionSnapshotIncludesB3ComponentsAndSkippedReasons() throws Exception {
        LocalDateTime start = LocalDateTime.of(2026, 7, 4, 10, 0);
        LocalDateTime end = start.plusHours(2);
        PromotionPricingContext context = new PromotionPricingContext();
        context.venueId = 1L;
        context.spaceId = 10L;
        context.startTime = start;
        context.endTime = end;
        context.unitPrice = new BigDecimal("60.00");
        context.baseAmount = new BigDecimal("120.00");
        context.priceRuleAdjustmentAmount = new BigDecimal("20.00");
        context.amountBreakdown = new AmountBreakdown(
            new BigDecimal("140.00"),
            new BigDecimal("14.00"),
            new BigDecimal("20.00"),
            new BigDecimal("10.00"),
            new BigDecimal("96.00"),
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO
        );
        context.priceRule = new PriceRuleResponse();
        context.priceRule.ruleCode = "PEAK_WEEKEND";
        context.campaign = new PromotionCampaignResponse();
        context.campaign.campaignCode = "SUMMER_CAMP";
        context.coupon = new CouponPricingView();
        context.coupon.templateCode = "B3_CASH_20";
        context.discountComponents = List.of(
            Map.of("type", "MEMBER", "discountAmount", new BigDecimal("14.00")),
            Map.of("type", "ACTIVITY", "discountAmount", new BigDecimal("10.00")),
            Map.of("type", "COUPON", "discountAmount", new BigDecimal("20.00"))
        );
        context.skippedComponents = List.of(Map.of(
            "type", "COUPON",
            "reason", "STACKING_NOT_ALLOWED"
        ));

        String json = factory.promotionSnapshot(context);

        JsonNode root = objectMapper.readTree(json);
        assertThat(root.get("pricingVersion").asText()).isEqualTo("B3_PROMOTION_PRICING_V1");
        assertThat(root.get("priceRule").get("ruleCode").asText()).isEqualTo("PEAK_WEEKEND");
        assertThat(root.get("campaign").get("campaignCode").asText()).isEqualTo("SUMMER_CAMP");
        assertThat(root.get("coupon").get("templateCode").asText()).isEqualTo("B3_CASH_20");
        assertThat(root.get("discountComponents")).hasSize(3);
        assertThat(root.get("skippedComponents").get(0).get("reason").asText()).isEqualTo("STACKING_NOT_ALLOWED");
        assertThat(root.get("amountBreakdown").get("originalAmount").decimalValue()).isEqualByComparingTo("140.00");
        assertThat(root.get("amountBreakdown").get("memberDiscountAmount").decimalValue()).isEqualByComparingTo("14.00");
        assertThat(root.get("amountBreakdown").get("activityDiscountAmount").decimalValue()).isEqualByComparingTo("10.00");
        assertThat(root.get("amountBreakdown").get("couponDiscountAmount").decimalValue()).isEqualByComparingTo("20.00");
        assertThat(root.get("payableAmount").decimalValue()).isEqualByComparingTo("96.00");
    }
}
