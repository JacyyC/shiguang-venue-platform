package com.example.venue.venue.dto;

import jakarta.validation.constraints.NotBlank;

import java.time.LocalTime;

public class SaveVenueRequest {
    @NotBlank
    public String name;
    public String address;
    public String area;
    public String phone;
    public LocalTime openTime;
    public LocalTime closeTime;
    public String description;
    public String image;
    public String status;
}
