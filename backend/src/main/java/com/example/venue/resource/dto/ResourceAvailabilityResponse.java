package com.example.venue.resource.dto;

public record ResourceAvailabilityResponse(
    Long spaceId,
    ResourceRuleResponse rule,
    Integer participantCount,
    Integer remainingCapacity,
    boolean bookable,
    String reasonCode,
    String reasonText
) {
    public ResourceAvailabilityResponse withDecision(boolean accepted, String code, String text) {
        return new ResourceAvailabilityResponse(
            spaceId,
            rule,
            participantCount,
            remainingCapacity,
            accepted,
            accepted ? null : code,
            accepted ? null : text
        );
    }
}
