package com.example.venue.payment.dto;

import com.example.venue.checkin.dto.ReservationLifecycleResponse;

public class PaymentCallbackProcessResult {
    public String paymentNo;
    public Long orderId;
    public String status;
    public String orderStatus;
    /**
     * 核销短码明文（6 位）。仅在 DEV 模拟支付成功并首次签发凭证时填充，
     * 用于一次性交付给前端；不持久化。非 SUCCESS 路径或已签发过的预约为 null。
     * 对应 {@link ReservationLifecycleResponse#checkinCode}。
     */
    public String checkinCode;
    /**
     * 二维码载荷，格式 {@code CHECKIN:<reservationId>:<checkinCode>}。与 {@link #checkinCode} 同生命周期。
     * 对应 {@link ReservationLifecycleResponse#qrPayload}。
     */
    public String qrPayload;

    public static PaymentCallbackProcessResult of(String paymentNo, Long orderId, String status, String orderStatus) {
        PaymentCallbackProcessResult result = new PaymentCallbackProcessResult();
        result.paymentNo = paymentNo;
        result.orderId = orderId;
        result.status = status;
        result.orderStatus = orderStatus;
        return result;
    }

    /**
     * A26-P1-01：SUCCESS 路径专用工厂，把签发响应中的核销短码与二维码载荷一并交付给前端。
     * {@code checkinCode}/{@code qrPayload} 可为 null（如凭证已签发、非首次签发场景），此时与 4 参工厂行为一致。
     */
    public static PaymentCallbackProcessResult of(String paymentNo, Long orderId, String status, String orderStatus,
                                                   String checkinCode, String qrPayload) {
        PaymentCallbackProcessResult result = of(paymentNo, orderId, status, orderStatus);
        result.checkinCode = checkinCode;
        result.qrPayload = qrPayload;
        return result;
    }
}
