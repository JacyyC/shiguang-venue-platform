package com.example.venue.venue.dto;

import com.example.venue.resource.dto.ResourceRuleResponse;
import com.example.venue.venue.entity.VenueSpace;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record VenueSpaceResponse(
    Long id,
    Long venueId,
    String name,
    String type,
    String code,
    Integer capacity,
    BigDecimal pricePerHour,
    String facilityText,
    String description,
    String status,
    LocalDateTime createTime,
    LocalDateTime updateTime,
    ResourceRuleResponse resourceRule
) {
    public static VenueSpaceResponse of(VenueSpace space, ResourceRuleResponse resourceRule) {
        return new VenueSpaceResponse(
            space.id,
            space.venueId,
            space.name,
            space.type,
            space.code,
            space.capacity,
            space.pricePerHour,
            space.facilityText,
            space.description,
            space.status,
            space.createTime,
            space.updateTime,
            resourceRule
        );
    }
}
