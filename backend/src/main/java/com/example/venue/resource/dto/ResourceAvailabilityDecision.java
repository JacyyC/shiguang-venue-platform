package com.example.venue.resource.dto;

import com.example.venue.resource.enums.AllocationConflictCode;
import com.example.venue.resource.enums.BookingMode;

public record ResourceAvailabilityDecision(
    boolean bookable,
    BookingMode bookingMode,
    AllocationConflictCode conflictCode,
    String reasonText,
    Integer remainingCapacity
) {
    public static ResourceAvailabilityDecision available(BookingMode bookingMode, Integer remainingCapacity) {
        return new ResourceAvailabilityDecision(true, bookingMode, null, null, remainingCapacity);
    }

    public static ResourceAvailabilityDecision rejected(BookingMode bookingMode,
                                                        AllocationConflictCode conflictCode,
                                                        String reasonText,
                                                        Integer remainingCapacity) {
        return new ResourceAvailabilityDecision(false, bookingMode, conflictCode, reasonText, remainingCapacity);
    }
}
