package com.example.venue.common;

/**
 * 退款 failure_code 契约常量。
 *
 * <p>这些字符串既是写入端（PaymentService 标记迟到支付退款、OrderService 判定迟到退款分支）
 * 也是退款状态读取口径的共享契约，提取到公共常量避免不同业务路径的字符串漂移。
 */
public final class RefundFailureCodes {

    /** 迟到支付确认后转入退款审核流程的 failure_code，写入 payment_record / refund_record / 相关日志。 */
    public static final String LATE_SUCCESS_REFUND_PENDING = "LATE_SUCCESS_REFUND_PENDING";

    private RefundFailureCodes() {
    }
}
