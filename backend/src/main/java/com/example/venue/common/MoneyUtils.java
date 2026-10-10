package com.example.venue.common;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 金额处理工具类，统一各服务中重复的 BigDecimal 空值处理逻辑。
 */
public final class MoneyUtils {
    /** 零金额常量，统一精度为两位小数 */
    public static final BigDecimal ZERO_AMOUNT = new BigDecimal("0.00");

    private MoneyUtils() {
    }

    /**
     * 返回金额值，如果为 null 则返回 fallback 的零值保护。
     *
     * @param amount   首选金额
     * @param fallback 备选金额（也可能为 null）
     * @return amount 不为 null 时返回 amount，否则返回 zeroIfNull(fallback)
     */
    public static BigDecimal amountOrDefault(BigDecimal amount, BigDecimal fallback) {
        return amount == null ? zeroIfNull(fallback) : normalize(amount);
    }

    /**
     * 将可能为 null 的金额转为零值，避免 NPE。
     */
    public static BigDecimal zeroIfNull(BigDecimal amount) {
        return amount == null ? ZERO_AMOUNT : normalize(amount);
    }

    /**
     * 强制金额精度为两位小数，null 返回 ZERO_AMOUNT。
     */
    private static BigDecimal normalize(BigDecimal amount) {
        if (amount == null) {
            return ZERO_AMOUNT;
        }
        return amount.setScale(2, RoundingMode.HALF_UP);
    }
}
