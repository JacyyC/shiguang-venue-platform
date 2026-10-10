package com.example.venue.order.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * HMAC 支付回调请求。
 * <p>A26-P3-05：各字段除 @NotBlank 外补 @Size（对齐 payment_callback_log 列长度），
 * 避免超长值导致 RECEIVED 证据插入本身失败。rawPayload 对应 text 列不需要 @Size。
 */
public class PaymentCallbackRequest {
    @NotBlank
    @Size(max = 64)
    public String orderNo;
    @NotBlank
    @Size(max = 128)
    public String transactionId;
    @NotNull
    public BigDecimal amount;
    @NotBlank
    @Size(max = 32)
    public String channel;
    @NotBlank
    @Size(max = 256)
    public String sign;
    @NotBlank
    public String rawPayload;
}
