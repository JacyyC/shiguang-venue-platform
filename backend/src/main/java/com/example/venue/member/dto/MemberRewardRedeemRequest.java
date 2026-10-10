package com.example.venue.member.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public class MemberRewardRedeemRequest {
    @NotNull @Positive
    public Long catalogVersionId;

    @NotBlank @Size(max = 128)
    public String idempotencyKey;
}
