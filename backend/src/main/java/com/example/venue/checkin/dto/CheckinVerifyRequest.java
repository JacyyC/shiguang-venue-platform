package com.example.venue.checkin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public class CheckinVerifyRequest {
    // A26-P3-01：credential 为 6 位短码或 "CHECKIN:reservationId:code" QR 载荷，128 足够且与
    // reservation_lifecycle.device_ref 列族对齐；reasonCode/reasonText 与 lifecycle 两表列族对齐
    // （reason_code varchar(64)，reason_text 经 V10 加长到 varchar(500)）。
    @NotBlank
    @Size(max = 128)
    public String credential;
    @Size(max = 128)
    public String deviceRef;
    @Size(max = 64)
    public String reasonCode;
    @Size(max = 500)
    public String reasonText;
}
