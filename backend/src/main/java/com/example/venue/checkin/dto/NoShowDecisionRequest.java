package com.example.venue.checkin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public class NoShowDecisionRequest {
    // A26-P3-01：reasonCode/reasonText 与 reservation_lifecycle 列族对齐
    // （reason_code varchar(64)，reason_text 经 V10 加长到 varchar(500)）。
    @NotBlank
    @Size(max = 64)
    public String reasonCode;
    @NotBlank
    @Size(max = 500)
    public String reasonText;
}
