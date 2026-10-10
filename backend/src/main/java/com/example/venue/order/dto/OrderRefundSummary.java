package com.example.venue.order.dto;

import java.math.BigDecimal;

/**
 * G-T1（SHIGUANG-BATCH-01-REFACTOR-20260913 S03）：用户订单列表附带的本人退款概要。
 * 只读透出，不改变任何写链路；reason 字段按用户可见口径脱敏，管理端内部 auditReason 原文不出现在用户侧。
 */
public record OrderRefundSummary(
    Long refundId,
    String refundNo,
    BigDecimal refundAmount,
    String status,
    String userVisibleReason
) {
}
