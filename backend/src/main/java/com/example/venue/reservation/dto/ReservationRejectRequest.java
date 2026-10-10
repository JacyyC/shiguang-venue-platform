package com.example.venue.reservation.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public class ReservationRejectRequest {
    @NotBlank
    @Size(max = 500)
    public String reason;
}
