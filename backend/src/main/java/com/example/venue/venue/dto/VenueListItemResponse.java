package com.example.venue.venue.dto;

import com.example.venue.feedback.dto.VenueRatingAggregate;
import com.example.venue.venue.entity.Venue;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.List;

public record VenueListItemResponse(
    Long id,
    String name,
    String address,
    String area,
    String phone,
    LocalTime openTime,
    LocalTime closeTime,
    String description,
    String image,
    String status,
    LocalDateTime createTime,
    LocalDateTime updateTime,
    Integer deleted,
    BigDecimal averageRating,
    Long reviewCount,
    BigDecimal minPrice,
    Long activeSpaceCount,
    List<String> spaceTypes
) {
    public static VenueListItemResponse of(Venue venue, VenueRatingAggregate rating, VenueSpaceAggregate spaces) {
        return new VenueListItemResponse(
            venue.id,
            venue.name,
            venue.address,
            venue.area,
            venue.phone,
            venue.openTime,
            venue.closeTime,
            venue.description,
            venue.image,
            venue.status,
            venue.createTime,
            venue.updateTime,
            venue.deleted,
            rating == null || rating.averageRating() == null ? new BigDecimal("0.00") : rating.averageRating(),
            rating == null || rating.reviewCount() == null ? 0L : rating.reviewCount(),
            spaces == null ? null : spaces.minPrice(),
            spaces == null || spaces.activeSpaceCount() == null ? 0L : spaces.activeSpaceCount(),
            parseSpaceTypes(spaces == null ? null : spaces.spaceTypesCsv())
        );
    }

    private static List<String> parseSpaceTypes(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return Arrays.stream(value.split(","))
            .map(String::trim)
            .filter(item -> !item.isEmpty())
            .distinct()
            .toList();
    }
}
