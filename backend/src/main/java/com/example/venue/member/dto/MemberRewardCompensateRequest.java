package com.example.venue.member.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public class MemberRewardCompensateRequest {
    @NotBlank @Size(max = 255) public String reason;
}
