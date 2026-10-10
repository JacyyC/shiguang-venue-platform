package com.example.venue.inventory;

import com.example.venue.business.dto.BusinessEventRecordRequest;
import com.example.venue.business.enums.BusinessDomain;
import com.example.venue.business.enums.BusinessEventType;
import com.example.venue.business.service.BusinessEventService;
import com.example.venue.inventory.service.InventoryConflictEventRecorder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class InventoryConflictEventRecorderTest {
    @Mock
    private BusinessEventService businessEventService;

    @Test
    void recordBlockConflictRejectionWritesInventoryConflictRejectedMetadata() {
        InventoryConflictEventRecorder recorder = new InventoryConflictEventRecorder(businessEventService);
        LocalDateTime requestedStart = LocalDateTime.parse("2026-07-03T13:00:00");
        LocalDateTime requestedEnd = LocalDateTime.parse("2026-07-03T14:00:00");
        LocalDateTime effectiveStart = LocalDateTime.parse("2026-07-03T12:45:00");
        LocalDateTime effectiveEnd = LocalDateTime.parse("2026-07-03T14:30:00");

        recorder.recordBlockConflictRejection(
            BusinessEventType.INVENTORY_CONFLICT_REJECTED,
            20L,
            10L,
            requestedStart,
            requestedEnd,
            effectiveStart,
            effectiveEnd,
            "RESERVATION",
            77L,
            "SETUP_TEARDOWN",
            "缓冲时间冲突：预约时段冲突"
        );

        ArgumentCaptor<BusinessEventRecordRequest> eventCaptor =
            ArgumentCaptor.forClass(BusinessEventRecordRequest.class);
        verify(businessEventService).record(eventCaptor.capture());
        BusinessEventRecordRequest event = eventCaptor.getValue();
        assertThat(event.eventType).isEqualTo(BusinessEventType.INVENTORY_CONFLICT_REJECTED);
        assertThat(event.businessDomain).isEqualTo(BusinessDomain.VENUE_AVAILABILITY);
        assertThat(event.entityType).isEqualTo("VENUE_INVENTORY_CONFLICT");
        assertThat(event.entityId).isEqualTo(20L);
        assertThat(event.resultStatus).isEqualTo("REJECTED");
        assertThat(event.reasonCode).isEqualTo("SETUP_TEARDOWN");
        assertThat(event.reasonText).isEqualTo("缓冲时间冲突：预约时段冲突");
        assertThat(event.idempotencyKey).startsWith("INVENTORY_CONFLICT_REJECTED:20:10:2026-07-03T13:00");
        assertThat(event.metadata)
            .containsEntry("venueId", 20L)
            .containsEntry("spaceId", 10L)
            .containsEntry("requestedStartTime", requestedStart)
            .containsEntry("requestedEndTime", requestedEnd)
            .containsEntry("effectiveStartTime", effectiveStart)
            .containsEntry("effectiveEndTime", effectiveEnd)
            .containsEntry("conflictType", "RESERVATION")
            .containsEntry("conflictEntityId", 77L);
    }
}
