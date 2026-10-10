package com.example.venue.resource.service;

import com.example.venue.business.dto.BusinessEventRecordRequest;
import com.example.venue.business.enums.BusinessDomain;
import com.example.venue.business.enums.BusinessEventType;
import com.example.venue.business.enums.BusinessSourceType;
import com.example.venue.business.service.BusinessEventService;
import com.example.venue.resource.enums.AllocationConflictCode;
import com.example.venue.resource.enums.BookingMode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

@Service
public class ResourceAllocationConflictEventRecorder {
    private final BusinessEventService businessEventService;

    public ResourceAllocationConflictEventRecorder(BusinessEventService businessEventService) {
        this.businessEventService = businessEventService;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(Long venueId,
                       Long spaceId,
                       BookingMode bookingMode,
                       LocalDateTime startTime,
                       LocalDateTime endTime,
                       int requestedQuantity,
                       AllocationConflictCode conflictCode,
                       String reasonText) {
        businessEventService.record(BusinessEventRecordRequest.of(
                BusinessEventType.RESOURCE_ALLOCATION_CONFLICT_REJECTED,
                BusinessDomain.VENUE_AVAILABILITY,
                "RESOURCE_ALLOCATION_CONFLICT",
                spaceId,
                BusinessSourceType.USER,
                "REJECTED")
            .reasonCode(conflictCode.name())
            .reasonText(reasonText)
            .idempotencyKey("RESOURCE_ALLOCATION_CONFLICT:" + spaceId + ":" + UUID.randomUUID())
            .metadata("venueId", venueId)
            .metadata("spaceId", spaceId)
            .metadata("bookingMode", bookingMode.name())
            .metadata("startTime", startTime)
            .metadata("endTime", endTime)
            .metadata("requestedQuantity", requestedQuantity));
    }
}
