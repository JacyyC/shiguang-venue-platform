package com.example.venue.member.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public class SaveMemberRewardCatalogItemRequest {
    @NotBlank @Size(max = 64) public String itemCode;
    @NotBlank @Size(max = 128) public String itemName;
    @Size(max = 500) public String descriptionText;
}
