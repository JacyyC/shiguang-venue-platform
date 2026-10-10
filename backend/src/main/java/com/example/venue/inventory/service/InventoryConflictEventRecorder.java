package com.example.venue.inventory.service;

import com.example.venue.business.dto.BusinessEventRecordRequest;
import com.example.venue.business.enums.BusinessDomain;
import com.example.venue.business.enums.BusinessEventType;
import com.example.venue.business.enums.BusinessSourceType;
import com.example.venue.business.service.BusinessEventService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

@Service
public class InventoryConflictEventRecorder {
    private final BusinessEventService businessEventService;

    public InventoryConflictEventRecorder(BusinessEventService businessEventService) {
        this.businessEventService = businessEventService;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordBlockConflictRejection(BusinessEventType eventType,
                                             Long venueId,
                                             Long spaceId,
                                             LocalDateTime requestedStartTime,
                                             LocalDateTime requestedEndTime,
                                             LocalDateTime effectiveStartTime,
                                             LocalDateTime effectiveEndTime,
                                             String conflictType,
                                             Long conflictEntityId,
                                             String reasonCode,
                                             String reasonText) {
        businessEventService.record(BusinessEventRecordRequest.of(
                eventType,
                BusinessDomain.VENUE_AVAILABILITY,
                "VENUE_INVENTORY_CONFLICT",
                venueId,
                BusinessSourceType.ADMIN,
                "REJECTED")
            .reasonCode(reasonCode)
            .reasonText(reasonText)
            .idempotencyKey(idempotencyKey(venueId, spaceId, requestedStartTime))
            .metadata("venueId", venueId)
            .metadata("spaceId", spaceId)
            .metadata("requestedStartTime", requestedStartTime)
            .metadata("requestedEndTime", requestedEndTime)
            .metadata("effectiveStartTime", effectiveStartTime)
            .metadata("effectiveEndTime", effectiveEndTime)
            .metadata("conflictType", conflictType)
            .metadata("conflictEntityId", conflictEntityId));
    }

    private String idempotencyKey(Long venueId, Long spaceId, LocalDateTime requestedStartTime) {
        return "INVENTORY_CONFLICT_REJECTED:" + venueId + ":" + spaceId + ":" + requestedStartTime
            + ":" + UUID.randomUUID();
    }
}
