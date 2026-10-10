package com.example.venue.payment.enums;

/**
 * 支付来源类型枚举。
 * 用于 PaymentCallbackLog.sourceType 和 PaymentRecord.sourceType 字段，
 * 替代原硬编码字符串 "PROVIDER"。
 * 持久化时使用 .name()，与原有字符串值字节一致。
 */
public enum PaymentSourceType {
    PROVIDER
}
