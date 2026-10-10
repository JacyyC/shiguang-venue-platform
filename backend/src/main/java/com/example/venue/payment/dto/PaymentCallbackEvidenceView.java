package com.example.venue.payment.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 管理端支付回调最小证据出参（B5-H1）。
 *
 * 只保留事件引用摘要与处理结果；原始第三方载荷（payload）、请求/响应原始摘要 JSON、
 * 元数据 JSON 与内部幂等键不出参，对齐冻结参考「仅保留事件摘要与处理结果，不展示原始第三方载荷」。
 */
public class PaymentCallbackEvidenceView {
    public Long id;
    public String channel;
    public String orderNo;
    public String transactionId;
    public String status;
    public String message;
    public String sourceType;
    public BigDecimal confidence;
    public String providerEnv;
    public String providerName;
    public String externalRef;
    public String failureCode;
    public String configVersion;
    public Long durationMs;
    public LocalDateTime createTime;

    public static PaymentCallbackEvidenceView from(com.example.venue.order.entity.PaymentCallbackLog log) {
        if (log == null) {
            return null;
        }
        PaymentCallbackEvidenceView view = new PaymentCallbackEvidenceView();
        view.id = log.id;
        view.channel = log.channel;
        view.orderNo = log.orderNo;
        view.transactionId = log.transactionId;
        view.status = log.status;
        view.message = log.message;
        view.sourceType = log.sourceType;
        view.confidence = log.confidence;
        view.providerEnv = log.providerEnv;
        view.providerName = log.providerName;
        view.externalRef = log.externalRef;
        view.failureCode = log.failureCode;
        view.configVersion = log.configVersion;
        view.durationMs = log.durationMs;
        view.createTime = log.createTime;
        return view;
    }
}
