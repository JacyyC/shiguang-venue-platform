package com.example.venue.member.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public class UpdateMemberRewardCatalogStatusRequest {
    @NotBlank
    @Size(max = 32)
    public String status;
}
