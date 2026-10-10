package com.example.venue.pricing;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Set;

/**
 * 活动折扣的合同值域（F-18）：管理写入与计价读取共用同一边界，避免两处各自放宽。
 * PERCENT 取 (0,1]，CASH/FIXED 必须为正；violation 返回 null 表示合法。
 */
public final class CampaignDiscountRule {
    public static final String DEFAULT_DISCOUNT_TYPE = "CASH";
    public static final Set<String> DISCOUNT_TYPES = Set.of("CASH", "PERCENT", "FIXED");

    private CampaignDiscountRule() {
    }

    public static String normalizeType(String discountType) {
        if (discountType == null || discountType.isBlank()) {
            return DEFAULT_DISCOUNT_TYPE;
        }
        return discountType.trim().toUpperCase(Locale.ROOT);
    }

    public static String violation(String discountType, BigDecimal discountValue) {
        String type = normalizeType(discountType);
        if (!DISCOUNT_TYPES.contains(type)) {
            return "活动优惠类型不支持";
        }
        if (discountValue == null) {
            return "活动优惠值不能为空";
        }
        if ("PERCENT".equals(type)) {
            if (discountValue.compareTo(BigDecimal.ZERO) <= 0 || discountValue.compareTo(BigDecimal.ONE) > 0) {
                return "PERCENT 类型活动折扣比例必须大于 0 且不超过 1";
            }
            return null;
        }
        if (discountValue.compareTo(BigDecimal.ZERO) <= 0) {
            return "活动优惠值不合法";
        }
        return null;
    }
}
