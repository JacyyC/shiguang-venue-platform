package com.example.venue.feedback.dto;

import java.math.BigDecimal;

public record VenueRatingAggregate(
    Long venueId,
    BigDecimal averageRating,
    Long reviewCount
) {
}
