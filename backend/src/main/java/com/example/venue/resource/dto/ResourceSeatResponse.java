package com.example.venue.resource.dto;

import com.example.venue.resource.entity.VenueResourceSeat;

public record ResourceSeatResponse(
    Long id,
    String seatCode,
    String seatLabel,
    Integer sortOrder,
    String status,
    Boolean available
) {
    public static ResourceSeatResponse configuration(VenueResourceSeat seat) {
        return from(seat, null);
    }

    public static ResourceSeatResponse availability(VenueResourceSeat seat, boolean available) {
        return from(seat, available);
    }

    private static ResourceSeatResponse from(VenueResourceSeat seat, Boolean available) {
        return new ResourceSeatResponse(
            seat.id,
            seat.seatCode,
            seat.seatLabel,
            seat.sortOrder,
            seat.status,
            available
        );
    }
}
