package com.example.venue.resource.dto;

import java.util.List;

public record ResourceRuleChangeRequest(
    String resourceCategory,
    String bookingMode,
    Integer capacityLimit,
    List<ResourceSeatDefinition> seats,
    String reasonText
) {
}
