package com.example.venue.pricing;

import com.example.venue.member.dto.MemberAccountResponse;
import com.example.venue.member.dto.MemberLevelResponse;
import com.example.venue.promotion.dto.PromotionPricingContext;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class PricingSnapshotFactory {
    public static final String PRICING_VERSION = "B0_5_BASE_V1";
    public static final String B2_MEMBER_PRICING_VERSION = "B2_MEMBER_V1";
    public static final String B3_PROMOTION_PRICING_VERSION = "B3_PROMOTION_PRICING_V1";

    private final ObjectMapper objectMapper;

    public PricingSnapshotFactory(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String baseSnapshot(Long venueId, Long spaceId, LocalDateTime startTime, LocalDateTime endTime,
                               BigDecimal unitPrice, AmountBreakdown amountBreakdown) {
        AmountBreakdown breakdown = amountBreakdown == null ? AmountBreakdown.base(BigDecimal.ZERO) : amountBreakdown;
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("pricingVersion", PRICING_VERSION);
        snapshot.put("venueId", venueId);
        snapshot.put("spaceId", spaceId);
        snapshot.put("startTime", startTime == null ? null : startTime.toString());
        snapshot.put("endTime", endTime == null ? null : endTime.toString());
        snapshot.put("unitPrice", normalize(unitPrice));
        putAmountFields(snapshot, breakdown);
        try {
            return objectMapper.writeValueAsString(snapshot);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to build pricing snapshot", ex);
        }
    }

    public String memberSnapshot(Long venueId, Long spaceId, LocalDateTime startTime, LocalDateTime endTime,
                                 BigDecimal unitPrice, AmountBreakdown amountBreakdown,
                                 MemberLevelResponse level, MemberAccountResponse account) {
        AmountBreakdown breakdown = amountBreakdown == null ? AmountBreakdown.base(BigDecimal.ZERO) : amountBreakdown;
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("pricingVersion", B2_MEMBER_PRICING_VERSION);
        snapshot.put("schemaVersion", B2_MEMBER_PRICING_VERSION);
        snapshot.put("venueId", venueId);
        snapshot.put("spaceId", spaceId);
        snapshot.put("startTime", startTime == null ? null : startTime.toString());
        snapshot.put("endTime", endTime == null ? null : endTime.toString());
        snapshot.put("unitPrice", normalize(unitPrice));
        putAmountFields(snapshot, breakdown);
        snapshot.put("memberAccountId", account == null ? null : account.memberAccountId);
        snapshot.put("memberLevel", level);
        Map<String, Object> memberDiscount = new LinkedHashMap<>();
        memberDiscount.put("type", "MEMBER");
        memberDiscount.put("amount", breakdown.memberDiscountAmount());
        memberDiscount.put("discountAmount", breakdown.memberDiscountAmount());
        memberDiscount.put("levelCode", level == null ? null : level.levelCode);
        memberDiscount.put("discountRate", level == null ? null : level.discountRate);
        snapshot.put("discountComponents", List.of(memberDiscount));
        snapshot.put("amountBreakdown", amountBreakdown(breakdown));
        try {
            return objectMapper.writeValueAsString(snapshot);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to build pricing snapshot", ex);
        }
    }

    public String promotionSnapshot(PromotionPricingContext context) {
        PromotionPricingContext pricingContext = context == null ? new PromotionPricingContext() : context;
        AmountBreakdown breakdown = pricingContext.amountBreakdown == null
            ? AmountBreakdown.base(BigDecimal.ZERO)
            : pricingContext.amountBreakdown;
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("pricingVersion", B3_PROMOTION_PRICING_VERSION);
        snapshot.put("schemaVersion", B3_PROMOTION_PRICING_VERSION);
        snapshot.put("venueId", pricingContext.venueId);
        snapshot.put("spaceId", pricingContext.spaceId);
        snapshot.put("startTime", pricingContext.startTime == null ? null : pricingContext.startTime.toString());
        snapshot.put("endTime", pricingContext.endTime == null ? null : pricingContext.endTime.toString());
        snapshot.put("unitPrice", normalize(pricingContext.unitPrice));
        snapshot.put("baseAmount", normalize(pricingContext.baseAmount));
        snapshot.put("priceRuleAdjustmentAmount", normalize(pricingContext.priceRuleAdjustmentAmount));
        putAmountFields(snapshot, breakdown);
        snapshot.put("memberAccount", pricingContext.memberAccount);
        snapshot.put("memberLevel", pricingContext.memberLevel);
        snapshot.put("priceRule", pricingContext.priceRule);
        snapshot.put("campaign", pricingContext.campaign);
        snapshot.put("coupon", pricingContext.coupon);
        snapshot.put("discountComponents", pricingContext.discountComponents);
        snapshot.put("skippedComponents", pricingContext.skippedComponents);
        snapshot.put("amountBreakdown", amountBreakdown(breakdown));
        snapshot.put("calculatedAt", LocalDateTime.now().toString());
        try {
            return objectMapper.writeValueAsString(snapshot);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to build pricing snapshot", ex);
        }
    }

    private void putAmountFields(Map<String, Object> snapshot, AmountBreakdown breakdown) {
        snapshot.put("originalAmount", breakdown.originalAmount());
        snapshot.put("memberDiscountAmount", breakdown.memberDiscountAmount());
        snapshot.put("couponDiscountAmount", breakdown.couponDiscountAmount());
        snapshot.put("activityDiscountAmount", breakdown.activityDiscountAmount());
        snapshot.put("discountAmount", breakdown.discountAmount());
        snapshot.put("payableAmount", breakdown.payableAmount());
        snapshot.put("paidAmount", breakdown.paidAmount());
        snapshot.put("feeAmount", breakdown.feeAmount());
        snapshot.put("netAmount", breakdown.netAmount());
    }

    private Map<String, Object> amountBreakdown(AmountBreakdown breakdown) {
        Map<String, Object> amountBreakdown = new LinkedHashMap<>();
        amountBreakdown.put("original", breakdown.originalAmount());
        amountBreakdown.put("member", breakdown.memberDiscountAmount());
        amountBreakdown.put("coupon", breakdown.couponDiscountAmount());
        amountBreakdown.put("activity", breakdown.activityDiscountAmount());
        amountBreakdown.put("discount", breakdown.discountAmount());
        amountBreakdown.put("payable", breakdown.payableAmount());
        amountBreakdown.put("originalAmount", breakdown.originalAmount());
        amountBreakdown.put("memberDiscountAmount", breakdown.memberDiscountAmount());
        amountBreakdown.put("couponDiscountAmount", breakdown.couponDiscountAmount());
        amountBreakdown.put("activityDiscountAmount", breakdown.activityDiscountAmount());
        amountBreakdown.put("payableAmount", breakdown.payableAmount());
        amountBreakdown.put("paid", breakdown.paidAmount());
        amountBreakdown.put("fee", breakdown.feeAmount());
        amountBreakdown.put("net", breakdown.netAmount());
        return amountBreakdown;
    }

    private BigDecimal normalize(BigDecimal amount) {
        if (amount == null) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }
        return amount.setScale(2, RoundingMode.HALF_UP);
    }
}
