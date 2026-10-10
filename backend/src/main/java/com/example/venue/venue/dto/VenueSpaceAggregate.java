package com.example.venue.venue.dto;

import java.math.BigDecimal;

public record VenueSpaceAggregate(
    Long venueId,
    BigDecimal minPrice,
    Long activeSpaceCount,
    String spaceTypesCsv
) {
}
