package com.example.venue.checkin.dto;

import java.time.LocalDateTime;
import java.util.List;

public class ReservationLifecycleResponse {
    public Long reservationId;
    public Long orderId;
    public Long venueId;
    public Long spaceId;
    public String credentialStatus;
    public String checkinCodeLast4;
    public LocalDateTime checkinWindowStart;
    public LocalDateTime checkinWindowEnd;
    public String attendanceStatus;
    public String checkinStatus;
    public String noShowStatus;
    public LocalDateTime checkedInAt;
    public Integer lateMinutes;
    public String reasonCode;
    public String reasonText;
    public List<LifecycleEventResponse> events = List.of();
    /**
     * 核销短码明文（6 位）。仅在签发响应（{@code issueCredentialForPaidReservation}）中填充，
     * 用于一次性交付给用户/前端；不持久化，查询接口（{@code myLifecycle}/{@code adminLifecycle} 等）不返回。
     */
    public String checkinCode;
    /**
     * 二维码载荷，格式 {@code CHECKIN:<reservationId>:<checkinCode>}。仅在签发响应中填充，
     * 与 {@link #checkinCode} 同生命周期，不持久化，查询接口不返回。
     */
    public String qrPayload;
}
