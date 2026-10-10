package com.example.venue.advertising.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;

public final class AdvertisingRequests {
    private AdvertisingRequests() {
    }

    public record CreateAdvertiserRequest(
        @NotBlank @Size(max = 128) String advertiserName
    ) {
    }

    public record CreateCampaignRequest(
        @NotNull Long advertiserId,
        Long venueId,
        @NotBlank @Size(max = 128) String campaignName,
        @NotNull LocalDateTime effectiveFrom,
        @NotNull LocalDateTime effectiveUntil
    ) {
    }

    public record CreateCreativeRequest(
        @NotNull Long campaignId,
        @NotNull @Positive Integer versionNo,
        @NotBlank @Size(max = 80) String headline,
        @NotBlank @Size(max = 500) String bodyText,
        @NotBlank String callToAction,
        @NotBlank String targetType,
        @Positive Long targetId
    ) {
    }

    public record ReviewDecisionRequest(
        @NotBlank @Size(max = 64) String reasonCode,
        @NotBlank @Size(max = 500) String reasonText
    ) {
    }

    public record TransitionRequest(
        @Size(max = 64) String reasonCode,
        @Size(max = 500) String reasonText
    ) {
    }
}
