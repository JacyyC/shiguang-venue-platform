package com.example.venue.venue.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

public class SaveSpaceRequest {
    @NotNull
    public Long venueId;
    @NotBlank
    public String name;
    public String type;
    public String code;
    public Integer capacity;
    public BigDecimal pricePerHour;
    public String facilityText;
    public String description;
    public String status;
}
