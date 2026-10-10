package com.example.venue.venue.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDateTime;

public class SaveMaintenanceRequest {
    public Long spaceId;
    @NotNull
    public LocalDateTime startTime;
    @NotNull
    public LocalDateTime endTime;
    @NotBlank
    public String reason;
}
