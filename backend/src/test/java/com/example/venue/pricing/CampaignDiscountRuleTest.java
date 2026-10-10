package com.example.venue.pricing;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class CampaignDiscountRuleTest {

    @Test
    void rejectsOnlyIllegalDiscountTypesAndValues() {
        assertThat(CampaignDiscountRule.violation(null, null)).isEqualTo("活动优惠值不能为空");
        assertThat(CampaignDiscountRule.violation(" discount ", new BigDecimal("20.00"))).isEqualTo("活动优惠类型不支持");
        assertThat(CampaignDiscountRule.violation("PERCENT", null)).isEqualTo("活动优惠值不能为空");
        assertThat(CampaignDiscountRule.violation("PERCENT", BigDecimal.ZERO)).contains("PERCENT");
        assertThat(CampaignDiscountRule.violation("PERCENT", new BigDecimal("-0.01"))).contains("PERCENT");
        assertThat(CampaignDiscountRule.violation("PERCENT", new BigDecimal("1.01"))).contains("PERCENT");
        assertThat(CampaignDiscountRule.violation("CASH", BigDecimal.ZERO)).isEqualTo("活动优惠值不合法");
        assertThat(CampaignDiscountRule.violation("FIXED", new BigDecimal("-1.00"))).isEqualTo("活动优惠值不合法");
    }

    @Test
    void acceptsLegalValuesOnBothBoundaries() {
        assertThat(CampaignDiscountRule.violation("PERCENT", new BigDecimal("0.01"))).isNull();
        assertThat(CampaignDiscountRule.violation("PERCENT", new BigDecimal("1.00"))).isNull();
        assertThat(CampaignDiscountRule.violation("CASH", new BigDecimal("0.01"))).isNull();
        assertThat(CampaignDiscountRule.violation("FIXED", new BigDecimal("88.00"))).isNull();
        // 缺省优惠方式与计价链一致：空白按 CASH 处理
        assertThat(CampaignDiscountRule.violation("  ", new BigDecimal("20.00"))).isNull();
    }
}
