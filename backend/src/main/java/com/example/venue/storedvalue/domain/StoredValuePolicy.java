package com.example.venue.storedvalue.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Objects;

/** Immutable policy snapshot used by a recharge; historical rows keep its version. */
public record StoredValuePolicy(
    String version,
    BigDecimal minimumRecharge,
    BigDecimal maximumRecharge,
    BigDecimal dailyRechargeLimit,
    BigDecimal principalBalanceLimit,
    BigDecimal totalBalanceLimit,
    int maxPendingRecharges,
    int maxDailyInitiations,
    BigDecimal bonusRatePercent,
    BigDecimal bonusPerRechargeLimit,
    BigDecimal monthlyBonusLimit,
    int bonusValidDays,
    int refundCompensationValidDays,
    ZoneId zoneId
) {
    public static final String VERSION = "R5-DEV-SANDBOX-20260802-v1";
    public static final ZoneId CST = ZoneId.of("Asia/Shanghai");

    public StoredValuePolicy {
        if (version == null || version.isBlank()) {
            throw new IllegalArgumentException("policy version must not be blank");
        }
        zoneId = Objects.requireNonNull(zoneId, "zoneId");
        requireMoney(minimumRecharge, "minimumRecharge");
        requireMoney(maximumRecharge, "maximumRecharge");
        requireMoney(dailyRechargeLimit, "dailyRechargeLimit");
        requireMoney(principalBalanceLimit, "principalBalanceLimit");
        requireMoney(totalBalanceLimit, "totalBalanceLimit");
        requireMoney(bonusRatePercent, "bonusRatePercent");
        requireMoney(bonusPerRechargeLimit, "bonusPerRechargeLimit");
        requireMoney(monthlyBonusLimit, "monthlyBonusLimit");
        if (minimumRecharge.compareTo(maximumRecharge) > 0
            || maxPendingRecharges <= 0 || maxDailyInitiations <= 0
            || bonusValidDays <= 0 || refundCompensationValidDays <= 0) {
            throw new IllegalArgumentException("invalid stored value policy limits");
        }
    }

    public static StoredValuePolicy standard() {
        return standard(VERSION);
    }

    public static StoredValuePolicy standard(String version) {
        return new StoredValuePolicy(
            version,
            new BigDecimal("10.00"),
            new BigDecimal("5000.00"),
            new BigDecimal("20000.00"),
            new BigDecimal("50000.00"),
            new BigDecimal("60000.00"),
            5,
            20,
            new BigDecimal("5.0000"),
            new BigDecimal("300.00"),
            new BigDecimal("2000.00"),
            180,
            30,
            CST);
    }

    /** Calculates the grant in cents, with no rounding of the principal input. */
    public BigDecimal calculateBonus(BigDecimal principal, BigDecimal monthlyRemaining) {
        StoredValueMoney money = StoredValueMoney.positive(principal);
        StoredValueMoney remaining = monthlyRemaining == null
            ? StoredValueMoney.of(monthlyBonusLimit)
            : StoredValueMoney.of(monthlyRemaining);
        BigDecimal percentage = money.amount().multiply(bonusRatePercent)
            .divide(new BigDecimal("100"), 2, RoundingMode.FLOOR);
        return percentage.min(bonusPerRechargeLimit).min(remaining.amount()).setScale(2, RoundingMode.DOWN);
    }

    public LocalDate today(LocalDateTime now) {
        return now.atZone(zoneId).toLocalDate();
    }

    public String month(LocalDateTime now) {
        LocalDate date = today(now);
        return "%04d-%02d".formatted(date.getYear(), date.getMonthValue());
    }

    public LocalDateTime bonusExpiry(LocalDateTime issuedAt) {
        return issuedAt.toLocalDate().plusDays(bonusValidDays).atStartOfDay(zoneId).toLocalDateTime();
    }

    private static void requireMoney(BigDecimal value, String field) {
        if (value == null || value.signum() < 0 || value.scale() > 4) {
            throw new IllegalArgumentException("invalid policy value: " + field);
        }
    }
}
