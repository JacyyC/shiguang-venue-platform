package com.example.venue.storedvalue.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * CNY money for stored value. The scale check deliberately happens before any
 * normalization so an input such as 1.239 can never be silently rounded.
 */
public record StoredValueMoney(String currency, BigDecimal amount) {
    public static final String CNY = "CNY";

    public StoredValueMoney {
        if (!CNY.equals(currency)) {
            throw new IllegalArgumentException("stored value currency must be CNY");
        }
        Objects.requireNonNull(amount, "amount");
        if (amount.scale() > 2) {
            throw new IllegalArgumentException("stored value amount has more than two decimals");
        }
        if (amount.signum() < 0) {
            throw new IllegalArgumentException("stored value amount must not be negative");
        }
        amount = amount.setScale(2, RoundingMode.UNNECESSARY);
    }

    public static StoredValueMoney of(BigDecimal amount) {
        return new StoredValueMoney(CNY, amount);
    }

    public static StoredValueMoney of(String currency, BigDecimal amount) {
        return new StoredValueMoney(currency, amount);
    }

    public static StoredValueMoney zero() {
        return of(BigDecimal.ZERO.setScale(2));
    }

    public static StoredValueMoney positive(BigDecimal amount) {
        StoredValueMoney money = of(amount);
        if (money.amount.signum() <= 0) {
            throw new IllegalArgumentException("stored value amount must be positive");
        }
        return money;
    }

    public StoredValueMoney add(StoredValueMoney other) {
        requireSameCurrency(other);
        return of(amount.add(other.amount));
    }

    public StoredValueMoney subtract(StoredValueMoney other) {
        requireSameCurrency(other);
        BigDecimal result = amount.subtract(other.amount);
        if (result.signum() < 0) {
            throw new IllegalArgumentException("stored value amount cannot be negative");
        }
        return of(result);
    }

    private void requireSameCurrency(StoredValueMoney other) {
        if (other == null || !currency.equals(other.currency)) {
            throw new IllegalArgumentException("stored value currency mismatch");
        }
    }
}
