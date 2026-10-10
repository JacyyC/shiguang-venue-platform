package com.example.venue.resource;

import com.example.venue.common.EntityExistenceService;
import com.example.venue.inventory.mapper.VenueInventoryAuditMapper;
import com.example.venue.reservation.mapper.ReservationOccupancyMapper;
import com.example.venue.resource.dto.ResourceRuleResponse;
import com.example.venue.resource.entity.VenueResourceRule;
import com.example.venue.resource.entity.VenueResourceSeat;
import com.example.venue.resource.mapper.ReservationOccupancySeatMapper;
import com.example.venue.resource.mapper.VenueResourceRuleMapper;
import com.example.venue.resource.mapper.VenueResourceSeatMapper;
import com.example.venue.resource.service.VenueResourceRuleService;
import com.example.venue.venue.mapper.VenueSpaceMapper;
import com.example.venue.venue.service.VenueAccessService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * SHIGUANG-BATCH-02-REFACTOR-20260913 S04：currentRuleSummaries 批量水合座位。
 * 资源面"资源定义"（B2-T8）需要座位编码目录与座位数副标；无规则场地回退 legacyDefault 空座位。
 */
@ExtendWith(MockitoExtension.class)
class VenueResourceRuleSummariesTest {

    @Mock
    private VenueResourceRuleMapper ruleMapper;
    @Mock
    private VenueResourceSeatMapper seatMapper;
    @Mock
    private ReservationOccupancyMapper occupancyMapper;
    @Mock
    private ReservationOccupancySeatMapper occupancySeatMapper;
    @Mock
    private EntityExistenceService entityExistenceService;
    @Mock
    private VenueSpaceMapper spaceMapper;
    @Mock
    private VenueAccessService venueAccessService;
    @Mock
    private VenueInventoryAuditMapper auditMapper;
    @Mock
    private ObjectMapper objectMapper;

    private VenueResourceRuleService service;

    @BeforeEach
    void setUp() {
        service = new VenueResourceRuleService(ruleMapper, seatMapper, occupancyMapper, occupancySeatMapper,
            entityExistenceService, spaceMapper, venueAccessService, auditMapper, objectMapper);
    }

    @Test
    void summariesHydrateActiveSeatsPerRule() {
        VenueResourceRule reading = rule(5L, 9L, "ASSIGNED_SEAT");
        VenueResourceRule court = rule(6L, 10L, "EXCLUSIVE");
        when(ruleMapper.selectActiveBySpaceIds(List.of(9L, 10L))).thenReturn(List.of(reading, court));
        when(seatMapper.findActiveByRuleIds(List.of(5L, 6L))).thenReturn(List.of(
            seat(1L, 5L, "A-01"), seat(2L, 5L, "A-02")
        ));

        Map<Long, ResourceRuleResponse> summaries = service.currentRuleSummaries(List.of(9L, 10L));

        assertEquals(2, summaries.get(9L).seats().size());
        assertEquals("A-01", summaries.get(9L).seats().get(0).seatCode());
        assertTrue(summaries.get(10L).seats().isEmpty());
    }

    @Test
    void summariesFallBackToLegacyDefaultWithoutRule() {
        when(ruleMapper.selectActiveBySpaceIds(List.of(11L))).thenReturn(List.of());
        lenient().when(seatMapper.findActiveByRuleIds(anyList())).thenReturn(List.of());

        Map<Long, ResourceRuleResponse> summaries = service.currentRuleSummaries(List.of(11L));

        assertEquals("EXCLUSIVE", summaries.get(11L).bookingMode());
        assertTrue(summaries.get(11L).seats().isEmpty());
    }

    @Test
    void emptyInputReturnsEmptyMap() {
        assertTrue(service.currentRuleSummaries(List.of()).isEmpty());
    }

    private VenueResourceRule rule(Long id, Long spaceId, String mode) {
        VenueResourceRule rule = new VenueResourceRule();
        rule.id = id;
        rule.spaceId = spaceId;
        rule.resourceCategory = "SPORTS_FIELD";
        rule.bookingMode = mode;
        rule.capacityLimit = "EXCLUSIVE".equals(mode) ? 1 : 20;
        rule.versionNo = 1;
        rule.status = "ACTIVE";
        return rule;
    }

    private VenueResourceSeat seat(Long id, Long ruleId, String code) {
        VenueResourceSeat seat = new VenueResourceSeat();
        seat.id = id;
        seat.ruleId = ruleId;
        seat.seatCode = code;
        seat.seatLabel = "座位 " + code;
        seat.sortOrder = id.intValue();
        seat.status = "ACTIVE";
        return seat;
    }
}
