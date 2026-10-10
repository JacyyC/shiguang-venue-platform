package com.example.venue.storedvalue;

import com.example.venue.storedvalue.domain.StoredValueMoney;
import com.example.venue.storedvalue.domain.StoredValuePolicy;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StoredValueMoneyPolicyTest {
    @Test
    void rejectsUnsupportedCurrencyNegativeAndScaleBeforeRounding() {
        assertThatThrownBy(() -> StoredValueMoney.of("USD", new BigDecimal("10.00")))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> StoredValueMoney.of(new BigDecimal("1.239")))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> StoredValueMoney.of(new BigDecimal("-1.00")))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void appliesHardRechargeAndBonusBoundariesWithCstExpiry() {
        StoredValuePolicy policy = StoredValuePolicy.standard();
        assertThat(policy.calculateBonus(new BigDecimal("10.00"), new BigDecimal("2000.00")))
            .isEqualByComparingTo("0.50");
        assertThat(policy.calculateBonus(new BigDecimal("6000.00"), new BigDecimal("2000.00")))
            .isEqualByComparingTo("300.00");
        assertThat(policy.calculateBonus(new BigDecimal("5000.00"), new BigDecimal("0.49")))
            .isEqualByComparingTo("0.49");
        assertThat(policy.bonusExpiry(java.time.LocalDateTime.of(2026, 8, 2, 10, 0)))
            .isEqualTo(java.time.LocalDateTime.of(2027, 1, 29, 0, 0));
    }
}
