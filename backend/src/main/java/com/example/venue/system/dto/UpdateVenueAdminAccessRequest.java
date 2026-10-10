package com.example.venue.system.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.List;

public class UpdateVenueAdminAccessRequest {
    @NotNull(message = "场馆授权不能为空")
    @Size(max = 100, message = "单次最多维护 100 个场馆授权")
    public List<@Positive(message = "场馆 ID 必须为正数") Long> venueIds;
}
