package com.example.venue.inventory;

import com.example.venue.business.dto.BusinessEventRecordRequest;
import com.example.venue.business.enums.BusinessDomain;
import com.example.venue.business.enums.BusinessEventType;
import com.example.venue.business.service.BusinessEventService;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.example.venue.common.BusinessException;
import com.example.venue.common.CurrentUser;
import com.example.venue.common.EntityExistenceService;
import com.example.venue.inventory.dto.BookabilityDecision;
import com.example.venue.inventory.dto.InventorySlot;
import com.example.venue.inventory.dto.SaveBusinessCalendarRequest;
import com.example.venue.inventory.dto.SaveUnavailableBlockRequest;
import com.example.venue.inventory.entity.VenueBusinessCalendar;
import com.example.venue.inventory.entity.VenueInventoryAudit;
import com.example.venue.inventory.entity.VenueUnavailableBlock;
import com.example.venue.inventory.service.InventoryConflictEventRecorder;
import com.example.venue.inventory.mapper.VenueBusinessCalendarMapper;
import com.example.venue.inventory.mapper.VenueInventoryAuditMapper;
import com.example.venue.inventory.mapper.VenueUnavailableBlockMapper;
import com.example.venue.inventory.service.VenueInventoryService;
import com.example.venue.reservation.entity.Reservation;
import com.example.venue.reservation.enums.ReservationStatus;
import com.example.venue.reservation.mapper.ReservationMapper;
import com.example.venue.resource.dto.ResourceAvailabilityDecision;
import com.example.venue.resource.enums.BookingMode;
import com.example.venue.resource.service.ResourceAllocationService;
import com.example.venue.system.entity.SysConfig;
import com.example.venue.system.mapper.SysConfigMapper;
import com.example.venue.system.service.SysConfigService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.example.venue.venue.entity.Venue;
import com.example.venue.venue.entity.VenueFacilityMaintenance;
import com.example.venue.venue.entity.VenueSpace;
import com.example.venue.venue.mapper.VenueFacilityMaintenanceMapper;
import com.example.venue.venue.mapper.VenueMapper;
import com.example.venue.venue.mapper.VenueSpaceMapper;
import com.example.venue.venue.service.VenueAccessService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VenueInventoryServiceTest {
    @Mock
    private VenueMapper venueMapper;
    @Mock
    private VenueSpaceMapper spaceMapper;
    @Mock
    private VenueBusinessCalendarMapper calendarMapper;
    @Mock
    private VenueUnavailableBlockMapper blockMapper;
    @Mock
    private VenueInventoryAuditMapper auditMapper;
    @Mock
    private VenueFacilityMaintenanceMapper maintenanceMapper;
    @Mock
    private ReservationMapper reservationMapper;
    @Mock
    private VenueAccessService accessService;
    @Mock
    private SysConfigMapper configMapper;
    @Mock
    private BusinessEventService businessEventService;
    @Mock
    private InventoryConflictEventRecorder conflictEventRecorder;
    @Mock
    private ResourceAllocationService resourceAllocationService;

    private VenueInventoryService service;

    @BeforeEach
    void setUp() {
        SysConfigService sysConfigService = new SysConfigService(configMapper);
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        // DC-03：用真实 EntityExistenceService 注入 mock mapper，复用下方 selectByIdForUpdate→selectById 委托 mock；
        // userMapper 传 null（VenueInventoryService 不调 requireActiveUser）
        EntityExistenceService entityExistenceService = new EntityExistenceService(null, venueMapper, spaceMapper);
        service = new VenueInventoryService(venueMapper, spaceMapper, entityExistenceService, calendarMapper, blockMapper, auditMapper,
            maintenanceMapper, reservationMapper, accessService, sysConfigService, businessEventService,
            conflictEventRecorder, objectMapper);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
            new CurrentUser(7L, "inventory-admin", Set.of("SYSTEM_ADMIN"), Set.of()), null, List.of()));
        lenient().when(configMapper.findByKey("reservation.advance_days")).thenReturn(config("7"));
        // AUD-P1-02：createBlock/saveCalendar 现在用 FOR UPDATE 锁场馆行/场地行，单元测试委托给 selectById。
        lenient().when(venueMapper.selectByIdForUpdate(org.mockito.ArgumentMatchers.anyLong()))
            .thenAnswer(invocation -> venueMapper.selectById(invocation.getArgument(0)));
        lenient().when(spaceMapper.selectByIdForUpdate(org.mockito.ArgumentMatchers.anyLong()))
            .thenAnswer(invocation -> spaceMapper.selectById(invocation.getArgument(0)));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void dailyInventoryUsesCalendarClosureAndUnavailableReasons() {
        LocalDate date = LocalDate.now().plusDays(2);
        when(spaceMapper.selectById(10L)).thenReturn(activeSpace());
        when(venueMapper.selectById(20L)).thenReturn(activeVenue());
        when(calendarMapper.findByVenueAndDate(20L, date))
            .thenReturn(calendar(date, "CLOSED", null, null, "VENUE_CLOSED", "校内活动闭馆"));

        List<InventorySlot> slots = service.dailyInventory(20L, 10L, date, true);

        assertThat(slots).hasSize(10);
        assertThat(slots).allSatisfy(slot -> {
            assertThat(slot.status).isEqualTo("CLOSED");
            assertThat(slot.reasonCode).isEqualTo("VENUE_CLOSED");
            assertThat(slot.reasonText).isEqualTo("校内活动闭馆");
        });
        assertThat(slots.get(0).startTime).isEqualTo(date.atTime(8, 0));
    }

    @Test
    void dailyInventoryMarksBlockBuffersAndReservationsWithStablePriority() {
        LocalDate date = LocalDate.now().plusDays(2);
        VenueUnavailableBlock cleaning = block(44L, 20L, 10L, "CLEANING", "CLEANING_BUFFER",
            date.atTime(12, 0), date.atTime(13, 0), 30, 30);
        Reservation reservation = reservation(99L, 10L, date.atTime(15, 0), date.atTime(16, 0), "PAID");
        when(spaceMapper.selectById(10L)).thenReturn(activeSpace());
        when(venueMapper.selectById(20L)).thenReturn(activeVenue());
        when(calendarMapper.findByVenueAndDate(20L, date)).thenReturn(null);
        when(blockMapper.findActiveOverlaps(eq(20L), eq(10L), eq(date.atStartOfDay()), eq(date.plusDays(1).atStartOfDay())))
            .thenReturn(List.of(cleaning));
        when(maintenanceMapper.findActiveOverlaps(eq(10L), eq(date.atStartOfDay()), eq(date.plusDays(1).atStartOfDay())))
            .thenReturn(List.of());
        when(reservationMapper.findOverlapsByStatuses(eq(10L), eq(date.atStartOfDay()), eq(date.plusDays(1).atStartOfDay()), anyCollection()))
            .thenReturn(List.of(reservation));

        List<InventorySlot> slots = service.dailyInventory(20L, 10L, date, true);

        InventorySlot bufferBefore = slotStarting(slots, date.atTime(11, 0));
        assertThat(bufferBefore.status).isEqualTo("BUFFER");
        assertThat(bufferBefore.blockId).isEqualTo(44L);

        InventorySlot blocked = slotStarting(slots, date.atTime(12, 0));
        assertThat(blocked.status).isEqualTo("UNAVAILABLE");
        assertThat(blocked.blockType).isEqualTo("CLEANING");
        assertThat(blocked.reasonCode).isEqualTo("CLEANING_BUFFER");

        InventorySlot reserved = slotStarting(slots, date.atTime(15, 0));
        assertThat(reserved.status).isEqualTo("RESERVED");
        assertThat(reserved.reservationId).isEqualTo(99L);
        assertThat(reserved.reservationStatus).isEqualTo("PAID");
    }

    @Test
    void dailyInventoryHidesInternalIdentifiersOutsideAdminView() {
        LocalDate date = LocalDate.now().plusDays(2);
        VenueUnavailableBlock hold = block(45L, 20L, 10L, "MANUAL_HOLD", "MANUAL_HOLD",
            date.atTime(12, 0), date.atTime(13, 0), 0, 0);
        hold.relatedEntityType = "PRIVATE_NOTE";
        hold.relatedEntityId = 333L;
        when(spaceMapper.selectById(10L)).thenReturn(activeSpace());
        when(venueMapper.selectById(20L)).thenReturn(activeVenue());
        when(calendarMapper.findByVenueAndDate(20L, date)).thenReturn(null);
        when(blockMapper.findActiveOverlaps(eq(20L), eq(10L), any(), any())).thenReturn(List.of(hold));
        when(maintenanceMapper.findActiveOverlaps(eq(10L), any(), any())).thenReturn(List.of());
        when(reservationMapper.findOverlapsByStatuses(eq(10L), any(), any(), anyCollection())).thenReturn(List.of());

        InventorySlot slot = slotStarting(service.dailyInventory(20L, 10L, date, false), date.atTime(12, 0));

        assertThat(slot.status).isEqualTo("MANUAL_HOLD");
        assertThat(slot.reasonCode).isEqualTo("MANUAL_HOLD");
        assertThat(slot.blockId).isNull();
        assertThat(slot.blockType).isNull();
        assertThat(slot.relatedEntityType).isNull();
        assertThat(slot.relatedEntityId).isNull();
        assertThat(slot.sourceType).isNull();
    }

    @Test
    void dailyInventoryRequiresVenueManageAccessForAdminViewOnly() {
        LocalDate date = LocalDate.now().plusDays(2);
        when(spaceMapper.selectById(10L)).thenReturn(activeSpace());
        when(venueMapper.selectById(20L)).thenReturn(activeVenue());
        when(calendarMapper.findByVenueAndDate(20L, date)).thenReturn(null);
        when(blockMapper.findActiveOverlaps(eq(20L), eq(10L), any(), any())).thenReturn(List.of());
        when(maintenanceMapper.findActiveOverlaps(eq(10L), any(), any())).thenReturn(List.of());
        when(reservationMapper.findOverlapsByStatuses(eq(10L), any(), any(), anyCollection())).thenReturn(List.of());

        service.dailyInventory(20L, 10L, date, true);
        service.dailyInventory(20L, 10L, date, false);

        verify(accessService).requireVenueManageAccess(20L);
    }

    @Test
    void rescheduleInventoryExcludesCurrentReservationFromSlotDecisions() {
        LocalDate date = LocalDate.now().plusDays(2);
        Reservation reservation = reservation(99L, 10L, date.atTime(10, 0), date.atTime(11, 0), "PENDING_PAYMENT");
        when(spaceMapper.selectById(10L)).thenReturn(activeSpace());
        when(venueMapper.selectById(20L)).thenReturn(activeVenue());
        when(calendarMapper.findByVenueAndDate(20L, date)).thenReturn(null);
        when(blockMapper.findActiveOverlaps(eq(20L), eq(10L), any(), any())).thenReturn(List.of());
        when(maintenanceMapper.findActiveOverlaps(eq(10L), any(), any())).thenReturn(List.of());
        when(reservationMapper.findOverlapsByStatuses(eq(10L), any(), any(), anyCollection()))
            .thenReturn(List.of(reservation));
        when(resourceAllocationService.checkAvailability(
            eq(10L), any(LocalDateTime.class), any(LocalDateTime.class), eq(1), eq(List.of()), eq(99L)))
            .thenReturn(ResourceAvailabilityDecision.available(BookingMode.EXCLUSIVE, 1));
        service.setResourceAllocationService(resourceAllocationService);

        InventorySlot slot = slotStarting(service.dailyInventory(20L, 10L, date, false, 99L), date.atTime(10, 0));

        assertThat(slot.status).isEqualTo("AVAILABLE");
        verify(resourceAllocationService).checkAvailability(
            10L, date.atTime(10, 0), date.atTime(11, 0), 1, List.of(), 99L);
    }

    @Test
    void crossDayBlockIsSlicedIntoQueriedDateInventory() {
        LocalDate date = LocalDate.now().plusDays(2);
        VenueUnavailableBlock crossDay = block(46L, 20L, 10L, "PRIVATE_EVENT", "PRIVATE_EVENT",
            date.minusDays(1).atTime(23, 0), date.atTime(2, 0), 0, 0);
        when(spaceMapper.selectById(10L)).thenReturn(activeSpace());
        when(venueMapper.selectById(20L)).thenReturn(activeVenue());
        when(calendarMapper.findByVenueAndDate(20L, date)).thenReturn(null);
        when(blockMapper.findActiveOverlaps(eq(20L), eq(10L), eq(date.atStartOfDay()), eq(date.plusDays(1).atStartOfDay())))
            .thenReturn(List.of(crossDay));
        when(maintenanceMapper.findActiveOverlaps(eq(10L), eq(date.atStartOfDay()), eq(date.plusDays(1).atStartOfDay())))
            .thenReturn(List.of());
        when(reservationMapper.findOverlapsByStatuses(eq(10L), eq(date.atStartOfDay()), eq(date.plusDays(1).atStartOfDay()), anyCollection()))
            .thenReturn(List.of());

        List<InventorySlot> slots = service.dailyInventory(20L, 10L, date, true);

        assertThat(slotStarting(slots, date.atTime(0, 0)).status).isEqualTo("UNAVAILABLE");
        assertThat(slotStarting(slots, date.atTime(0, 0)).blockId).isEqualTo(46L);
        assertThat(slotStarting(slots, date.atTime(1, 0)).status).isEqualTo("UNAVAILABLE");
        assertThat(slotStarting(slots, date.atTime(1, 0)).blockId).isEqualTo(46L);
        assertThat(slotStarting(slots, date.atTime(2, 0)).status).isEqualTo("CLOSED");
        assertThat(slotStarting(slots, date.atTime(2, 0)).blockId).isNull();
    }

    @Test
    void dailyInventoryPrefersActualBlockOverlapOverAnotherBlockBuffer() {
        LocalDate date = LocalDate.now().plusDays(2);
        VenueUnavailableBlock bufferedCleaning = block(46L, 20L, 10L, "CLEANING", "CLEANING_BUFFER",
            date.atTime(12, 0), date.atTime(13, 0), 60, 0);
        VenueUnavailableBlock manualHold = block(47L, 20L, 10L, "MANUAL_HOLD", "MANUAL_HOLD",
            date.atTime(11, 0), date.atTime(12, 0), 0, 0);
        when(spaceMapper.selectById(10L)).thenReturn(activeSpace());
        when(venueMapper.selectById(20L)).thenReturn(activeVenue());
        when(calendarMapper.findByVenueAndDate(20L, date)).thenReturn(null);
        when(blockMapper.findActiveOverlaps(eq(20L), eq(10L), eq(date.atStartOfDay()), eq(date.plusDays(1).atStartOfDay())))
            .thenReturn(List.of(bufferedCleaning, manualHold));
        when(maintenanceMapper.findActiveOverlaps(eq(10L), eq(date.atStartOfDay()), eq(date.plusDays(1).atStartOfDay())))
            .thenReturn(List.of());
        when(reservationMapper.findOverlapsByStatuses(eq(10L), eq(date.atStartOfDay()), eq(date.plusDays(1).atStartOfDay()), anyCollection()))
            .thenReturn(List.of());

        InventorySlot slot = slotStarting(service.dailyInventory(20L, 10L, date, true), date.atTime(11, 0));

        assertThat(slot.status).isEqualTo("MANUAL_HOLD");
        assertThat(slot.blockId).isEqualTo(47L);
    }

    @Test
    void checkBookableRejectsActualRequestedRangeConflictsAcrossHourBoundary() {
        LocalDate date = LocalDate.now().plusDays(2);
        LocalDateTime start = date.atTime(10, 30);
        LocalDateTime end = date.atTime(11, 30);
        VenueUnavailableBlock setup = block(47L, 20L, 10L, "SETUP", "SETUP_TEARDOWN",
            date.atTime(11, 0), date.atTime(12, 0), 0, 0);
        when(spaceMapper.selectById(10L)).thenReturn(activeSpace());
        when(venueMapper.selectById(20L)).thenReturn(activeVenue());
        when(calendarMapper.findByVenueAndDate(20L, date)).thenReturn(null);
        when(reservationMapper.findOverlapsByStatuses(eq(10L), eq(start), eq(end), anyCollection())).thenReturn(List.of());
        when(blockMapper.findActiveOverlaps(20L, 10L, start, end)).thenReturn(List.of(setup));

        BookabilityDecision decision = service.checkBookable(10L, start, end);

        assertThat(decision.bookable).isFalse();
        assertThat(decision.status).isEqualTo("UNAVAILABLE");
        assertThat(decision.conflictBlockId).isEqualTo(47L);
        assertThat(decision.reasonCode).isEqualTo("SETUP_TEARDOWN");
    }

    @Test
    void checkBookablePrefersActualBlockOverlapOverEarlierBufferOnlyHit() {
        LocalDate date = LocalDate.now().plusDays(2);
        LocalDateTime start = date.atTime(11, 30);
        LocalDateTime end = date.atTime(11, 45);
        VenueUnavailableBlock cleaning = block(48L, 20L, 10L, "CLEANING", "CLEANING_BUFFER",
            date.atTime(10, 0), date.atTime(11, 0), 0, 60);
        VenueUnavailableBlock manualHold = block(49L, 20L, 10L, "MANUAL_HOLD", "MANUAL_HOLD",
            date.atTime(11, 30), date.atTime(12, 0), 0, 0);
        when(spaceMapper.selectById(10L)).thenReturn(activeSpace());
        when(venueMapper.selectById(20L)).thenReturn(activeVenue());
        when(calendarMapper.findByVenueAndDate(20L, date)).thenReturn(null);
        when(reservationMapper.findOverlapsByStatuses(eq(10L), eq(start), eq(end), anyCollection()))
            .thenReturn(List.of());
        when(blockMapper.findActiveOverlaps(20L, 10L, start, end)).thenReturn(List.of(cleaning, manualHold));

        BookabilityDecision decision = service.checkBookable(10L, start, end);

        assertThat(decision.bookable).isFalse();
        assertThat(decision.status).isEqualTo("MANUAL_HOLD");
        assertThat(decision.reasonCode).isEqualTo("MANUAL_HOLD");
        assertThat(decision.reasonText).isEqualTo("MANUAL_HOLD");
        assertThat(decision.conflictBlockId).isEqualTo(49L);
    }

    @Test
    void checkBookableUsesCapacityDecisionInsteadOfRejectingAnyReservationOverlap() {
        LocalDate date = LocalDate.now().plusDays(2);
        LocalDateTime start = date.atTime(10, 0);
        LocalDateTime end = date.atTime(11, 0);
        when(spaceMapper.selectById(10L)).thenReturn(activeSpace());
        when(venueMapper.selectById(20L)).thenReturn(activeVenue());
        when(calendarMapper.findByVenueAndDate(20L, date)).thenReturn(null);
        when(resourceAllocationService.checkRequestedAvailability(10L, start, end, 2, List.of(), null))
            .thenReturn(ResourceAvailabilityDecision.available(BookingMode.CAPACITY, 1));
        when(blockMapper.findActiveOverlaps(20L, 10L, start, end)).thenReturn(List.of());
        when(maintenanceMapper.findActiveOverlaps(10L, start, end)).thenReturn(List.of());
        service.setResourceAllocationService(resourceAllocationService);

        BookabilityDecision decision = service.checkBookable(10L, start, end, 2, List.of());

        assertThat(decision.bookable).isTrue();
        verify(reservationMapper, never()).findOverlapsByStatuses(any(), any(), any(), anyCollection());
    }

    @Test
    void createBlockRejectsBufferConflictWithExistingReservation() {
        LocalDate date = LocalDate.now().plusDays(2);
        SaveUnavailableBlockRequest request = blockRequest(20L, 10L, "SETUP", "SETUP_TEARDOWN",
            date.atTime(13, 0), date.atTime(14, 0), 60, 0);
        when(spaceMapper.selectById(10L)).thenReturn(activeSpace());
        when(venueMapper.selectById(20L)).thenReturn(activeVenue());
        when(blockMapper.findActiveOverlaps(20L, 10L, date.atTime(12, 0), date.atTime(14, 0))).thenReturn(List.of());
        when(maintenanceMapper.findActiveOverlaps(10L, date.atTime(12, 0), date.atTime(14, 0))).thenReturn(List.of());
        when(reservationMapper.findOverlapsByStatuses(eq(10L), eq(date.atTime(12, 0)), eq(date.atTime(14, 0)), anyCollection()))
            .thenReturn(List.of(reservation(77L, 10L, date.atTime(12, 0), date.atTime(13, 0), "PAID")));

        assertThatThrownBy(() -> service.createBlock(request))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("缓冲时间冲突");

        verify(blockMapper, never()).insert(any(VenueUnavailableBlock.class));
        verify(auditMapper, never()).insert(any(VenueInventoryAudit.class));
        verify(conflictEventRecorder).recordBlockConflictRejection(
            eq(BusinessEventType.INVENTORY_CONFLICT_REJECTED),
            eq(20L),
            eq(10L),
            eq(date.atTime(13, 0)),
            eq(date.atTime(14, 0)),
            eq(date.atTime(12, 0)),
            eq(date.atTime(14, 0)),
            eq("RESERVATION"),
            eq(77L),
            any(),
            any()
        );
        verify(businessEventService, never()).record(any(BusinessEventRecordRequest.class));
    }

    @Test
    void createBlockRecordsConflictRejectedEventForExistingUnavailableBlock() {
        LocalDate date = LocalDate.now().plusDays(2);
        SaveUnavailableBlockRequest request = blockRequest(20L, 10L, "SETUP", "SETUP_TEARDOWN",
            date.atTime(13, 0), date.atTime(14, 0), 15, 30);
        VenueUnavailableBlock conflict = block(501L, 20L, 10L, "MANUAL_HOLD", "MANUAL_HOLD",
            date.atTime(12, 45), date.atTime(13, 30), 0, 0);
        when(spaceMapper.selectById(10L)).thenReturn(activeSpace());
        when(venueMapper.selectById(20L)).thenReturn(activeVenue());
        when(blockMapper.findActiveOverlaps(20L, 10L, date.atTime(12, 45), date.atTime(14, 30)))
            .thenReturn(List.of(conflict));

        assertThatThrownBy(() -> service.createBlock(request))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("不可售时段冲突");

        verify(conflictEventRecorder).recordBlockConflictRejection(
            eq(BusinessEventType.INVENTORY_CONFLICT_REJECTED),
            eq(20L),
            eq(10L),
            eq(date.atTime(13, 0)),
            eq(date.atTime(14, 0)),
            eq(date.atTime(12, 45)),
            eq(date.atTime(14, 30)),
            eq("UNAVAILABLE_BLOCK"),
            eq(501L),
            any(),
            any()
        );
        verify(blockMapper, never()).insert(any(VenueUnavailableBlock.class));
    }

    @Test
    void createBlockRejectsProviderSourceTypeImpersonation() {
        LocalDate date = LocalDate.now().plusDays(2);
        SaveUnavailableBlockRequest request = blockRequest(20L, 10L, "WEATHER", "WEATHER_HOLD",
            date.atTime(13, 0), date.atTime(14, 0), 0, 0);
        request.sourceType = "PROVIDER";
        request.providerEnv = "PROD";
        request.providerName = "WeatherProvider";
        when(spaceMapper.selectById(10L)).thenReturn(activeSpace());
        when(venueMapper.selectById(20L)).thenReturn(activeVenue());

        assertThatThrownBy(() -> service.createBlock(request))
            .isInstanceOf(BusinessException.class)
            .hasMessage("B1 暂不支持外部 provider 创建不可售");

        verify(accessService).requireVenueManageAccess(20L);
        verify(blockMapper, never()).findActiveOverlaps(any(), any(), any(), any());
        verify(maintenanceMapper, never()).findActiveOverlaps(any(), any(), any());
        verify(reservationMapper, never()).findOverlapsByStatuses(any(), any(), any(), anyCollection());
        verify(blockMapper, never()).insert(any(VenueUnavailableBlock.class));
    }

    @Test
    void createBlockClearsWeatherProviderFieldsForManualWeatherHold() {
        LocalDate date = LocalDate.now().plusDays(2);
        SaveUnavailableBlockRequest request = blockRequest(20L, 10L, "WEATHER", "WEATHER_HOLD",
            date.atTime(13, 0), date.atTime(14, 0), 0, 0);
        request.sourceType = "ADMIN";
        request.providerEnv = "PROD";
        request.providerName = "WeatherProvider";
        when(spaceMapper.selectById(10L)).thenReturn(activeSpace());
        when(venueMapper.selectById(20L)).thenReturn(activeVenue());
        when(blockMapper.findActiveOverlaps(20L, 10L, date.atTime(13, 0), date.atTime(14, 0))).thenReturn(List.of());
        when(maintenanceMapper.findActiveOverlaps(10L, date.atTime(13, 0), date.atTime(14, 0))).thenReturn(List.of());
        when(reservationMapper.findOverlapsByStatuses(eq(10L), eq(date.atTime(13, 0)), eq(date.atTime(14, 0)), anyCollection()))
            .thenReturn(List.of());
        doAnswer(invocation -> {
            VenueUnavailableBlock inserted = invocation.getArgument(0);
            inserted.id = 91L;
            return 1;
        }).when(blockMapper).insert(any(VenueUnavailableBlock.class));

        VenueUnavailableBlock created = service.createBlock(request);

        assertThat(created.sourceType).isEqualTo("ADMIN");
        assertThat(created.providerEnv).isNull();
        assertThat(created.providerName).isNull();
    }

    @Test
    void createBlockInsertsAuditAndUnavailableBlockCreatedBusinessEvent() {
        LocalDate date = LocalDate.now().plusDays(2);
        SaveUnavailableBlockRequest request = blockRequest(20L, 10L, "MANUAL_HOLD", "MANUAL_HOLD",
            date.atTime(13, 0), date.atTime(14, 0), null, 15);
        request.reasonText = "校队训练保留";
        when(spaceMapper.selectById(10L)).thenReturn(activeSpace());
        when(venueMapper.selectById(20L)).thenReturn(activeVenue());
        when(blockMapper.findActiveOverlaps(20L, 10L, date.atTime(13, 0), date.atTime(14, 15))).thenReturn(List.of());
        when(maintenanceMapper.findActiveOverlaps(10L, date.atTime(13, 0), date.atTime(14, 15))).thenReturn(List.of());
        when(reservationMapper.findOverlapsByStatuses(eq(10L), eq(date.atTime(13, 0)), eq(date.atTime(14, 15)), anyCollection()))
            .thenReturn(List.of());
        doAnswer(invocation -> {
            VenueUnavailableBlock inserted = invocation.getArgument(0);
            inserted.id = 88L;
            return 1;
        }).when(blockMapper).insert(any(VenueUnavailableBlock.class));

        VenueUnavailableBlock created = service.createBlock(request);

        assertThat(created.id).isEqualTo(88L);
        assertThat(created.status).isEqualTo("ACTIVE");
        assertThat(created.bufferBeforeMinutes).isZero();
        assertThat(created.bufferAfterMinutes).isEqualTo(15);
        assertThat(created.createdBy).isEqualTo(7L);
        verify(accessService).requireVenueManageAccess(20L);

        ArgumentCaptor<VenueInventoryAudit> auditCaptor = ArgumentCaptor.forClass(VenueInventoryAudit.class);
        verify(auditMapper).insert(auditCaptor.capture());
        assertThat(auditCaptor.getValue().entityType).isEqualTo("VENUE_UNAVAILABLE_BLOCK");
        assertThat(auditCaptor.getValue().entityId).isEqualTo(88L);
        assertThat(auditCaptor.getValue().action).isEqualTo("UNAVAILABLE_BLOCK_CREATED");
        assertThat(auditCaptor.getValue().operatorId).isEqualTo(7L);
        assertThat(auditCaptor.getValue().afterJson).contains("校队训练保留");

        ArgumentCaptor<BusinessEventRecordRequest> eventCaptor = ArgumentCaptor.forClass(BusinessEventRecordRequest.class);
        verify(businessEventService).record(eventCaptor.capture());
        BusinessEventRecordRequest event = eventCaptor.getValue();
        assertThat(event.eventType).isEqualTo(BusinessEventType.UNAVAILABLE_BLOCK_CREATED);
        assertThat(event.businessDomain).isEqualTo(BusinessDomain.VENUE_AVAILABILITY);
        assertThat(event.entityType).isEqualTo("VENUE_UNAVAILABLE_BLOCK");
        assertThat(event.entityId).isEqualTo(88L);
        assertThat(event.reasonCode).isEqualTo("MANUAL_HOLD");
        assertThat(event.reasonText).isEqualTo("校队训练保留");
        assertThat(event.idempotencyKey).isEqualTo("UNAVAILABLE_BLOCK_CREATED:88");
        assertThat(event.metadata).containsEntry("venueId", 20L).containsEntry("spaceId", 10L);
    }

    @Test
    void saveCalendarRejectsClosureWhenItConflictsWithAnEffectiveReservation() {
        LocalDate date = LocalDate.now().plusDays(2);
        Reservation reservation = reservation(99L, 10L, date.atTime(10, 0), date.atTime(11, 0), "PAID");
        when(venueMapper.selectById(20L)).thenReturn(activeVenue());
        when(reservationMapper.findVenueOverlapsByStatuses(eq(20L), eq(date.atStartOfDay()),
            eq(date.plusDays(1).atStartOfDay()), anyCollection())).thenReturn(List.of(reservation));
        SaveBusinessCalendarRequest request = calendarRequest(date, "CLOSED", null, null,
            "VENUE_CLOSED", "校内活动闭馆");

        assertThatThrownBy(() -> service.saveCalendar(request))
            .isInstanceOf(BusinessException.class)
            .hasMessage("营业日历与有效预约冲突，无法保存");

        verify(calendarMapper, never()).insert(any(VenueBusinessCalendar.class));
        verify(calendarMapper, never()).updateById(any(VenueBusinessCalendar.class));
    }

    @Test
    void saveCalendarRejectsShortenedHoursWhenTheyExcludeAnEffectiveReservation() {
        LocalDate date = LocalDate.now().plusDays(2);
        Reservation reservation = reservation(99L, 10L, date.atTime(10, 0), date.atTime(11, 0), "PAID");
        when(venueMapper.selectById(20L)).thenReturn(activeVenue());
        when(reservationMapper.findVenueOverlapsByStatuses(eq(20L), eq(date.atStartOfDay()),
            eq(date.plusDays(1).atStartOfDay()), anyCollection())).thenReturn(List.of(reservation));
        SaveBusinessCalendarRequest request = calendarRequest(date, "SPECIAL_HOURS", LocalTime.of(12, 0),
            LocalTime.of(18, 0), "SPECIAL_HOURS", "午后开放");

        assertThatThrownBy(() -> service.saveCalendar(request))
            .isInstanceOf(BusinessException.class)
            .hasMessage("营业日历与有效预约冲突，无法保存");

        verify(calendarMapper, never()).insert(any(VenueBusinessCalendar.class));
        verify(calendarMapper, never()).updateById(any(VenueBusinessCalendar.class));
    }

    @Test
    void saveCalendarRejectsReversedNormalOpenHours() {
        LocalDate date = LocalDate.now().plusDays(2);
        SaveBusinessCalendarRequest request = calendarRequest(date, "OPEN", LocalTime.of(18, 0),
            LocalTime.of(8, 0), "NORMAL_OPEN", "常规开放");

        assertThatThrownBy(() -> service.saveCalendar(request))
            .isInstanceOf(BusinessException.class)
            .hasMessage("营业时间必须包含有效开闭时间");

        verify(venueMapper, never()).selectById(any());
        verify(calendarMapper, never()).insert(any(VenueBusinessCalendar.class));
    }

    @Test
    void saveCalendarUsesDistinctBusinessEventIdempotencyKeysForRepeatedDateChanges() {
        LocalDate date = LocalDate.now().plusDays(2);
        VenueBusinessCalendar[] storedCalendar = new VenueBusinessCalendar[1];
        when(venueMapper.selectById(20L)).thenReturn(activeVenue());
        when(calendarMapper.findByVenueAndDate(20L, date)).thenAnswer(invocation -> storedCalendar[0]);
        doAnswer(invocation -> {
            VenueBusinessCalendar inserted = invocation.getArgument(0);
            inserted.id = 101L;
            storedCalendar[0] = inserted;
            return 1;
        }).when(calendarMapper).insert(any(VenueBusinessCalendar.class));
        doAnswer(invocation -> {
            storedCalendar[0] = invocation.getArgument(0);
            return 1;
        }).when(calendarMapper).update(any(), any(UpdateWrapper.class));

        SaveBusinessCalendarRequest first = calendarRequest(date, "SPECIAL_HOURS",
            LocalTime.of(8, 0), LocalTime.of(18, 0), "HOLIDAY_HOURS", "节假日延长开放");
        SaveBusinessCalendarRequest second = calendarRequest(date, "INTERNAL_ONLY",
            LocalTime.of(9, 0), LocalTime.of(12, 0), "TRAINING_ONLY", "校队训练专用");

        service.saveCalendar(first);
        service.saveCalendar(second);

        ArgumentCaptor<BusinessEventRecordRequest> eventCaptor = ArgumentCaptor.forClass(BusinessEventRecordRequest.class);
        verify(businessEventService, org.mockito.Mockito.times(2)).record(eventCaptor.capture());
        assertThat(eventCaptor.getAllValues())
            .extracting(event -> event.eventType)
            .containsExactly(BusinessEventType.VENUE_CALENDAR_CHANGED, BusinessEventType.VENUE_CALENDAR_CHANGED);
        assertThat(eventCaptor.getAllValues())
            .extracting(event -> event.idempotencyKey)
            .allSatisfy(key -> assertThat(key).startsWith("VENUE_CALENDAR_CHANGED:20:" + date + ":"))
            .doesNotHaveDuplicates();
    }

    @Test
    void saveCalendarUsesUpdateWrapperToPersistNullFieldsWhenSwitchingToClosed() {
        // A26-P3-13：SPECIAL_HOURS 改存 CLOSED 时，openTime/closeTime/reasonText 应被显式置 null 落库，
        // 而非被 NOT_NULL 策略静默丢弃；否则审计 afterJson 与实际库内残留值背离。
        LocalDate date = LocalDate.now().plusDays(2);
        VenueBusinessCalendar[] storedCalendar = new VenueBusinessCalendar[1];
        when(venueMapper.selectById(20L)).thenReturn(activeVenue());
        when(calendarMapper.findByVenueAndDate(20L, date)).thenAnswer(invocation -> storedCalendar[0]);
        doAnswer(invocation -> {
            VenueBusinessCalendar inserted = invocation.getArgument(0);
            inserted.id = 101L;
            storedCalendar[0] = inserted;
            return 1;
        }).when(calendarMapper).insert(any(VenueBusinessCalendar.class));

        SaveBusinessCalendarRequest first = calendarRequest(date, "SPECIAL_HOURS",
            LocalTime.of(8, 0), LocalTime.of(18, 0), "HOLIDAY_HOURS", "节假日延长开放");
        service.saveCalendar(first);

        SaveBusinessCalendarRequest second = calendarRequest(date, "CLOSED",
            null, null, "VENUE_CLOSED", null);
        service.saveCalendar(second);

        // 必须走 UpdateWrapper 显式 set null，而非 updateById 静默丢弃
        verify(calendarMapper).update(eq(storedCalendar[0]), any(UpdateWrapper.class));
        verify(calendarMapper, never()).updateById(any(VenueBusinessCalendar.class));
        // 内存对象已被置 null，审计 afterJson 与落库一致
        assertThat(storedCalendar[0].openTime).isNull();
        assertThat(storedCalendar[0].closeTime).isNull();
        assertThat(storedCalendar[0].reasonText).isNull();
        assertThat(storedCalendar[0].businessStatus).isEqualTo("CLOSED");
    }

    @Test
    void saveCalendarUpdateWrapperSetsAllNullableColumns() {
        // A26-P3-13：进一步断言 UpdateWrapper set 的字段覆盖所有可空列，确保任一字段从有值改 null 都能落库。
        LocalDate date = LocalDate.now().plusDays(2);
        VenueBusinessCalendar existing = calendar(date, "SPECIAL_HOURS",
            LocalTime.of(8, 0), LocalTime.of(18, 0), "HOLIDAY_HOURS", "节假日延长开放");
        existing.id = 101L;
        when(venueMapper.selectById(20L)).thenReturn(activeVenue());
        when(calendarMapper.findByVenueAndDate(20L, date)).thenReturn(existing);

        SaveBusinessCalendarRequest request = calendarRequest(date, "CLOSED",
            null, null, "VENUE_CLOSED", null);

        service.saveCalendar(request);

        ArgumentCaptor<UpdateWrapper<VenueBusinessCalendar>> wrapperCaptor =
            ArgumentCaptor.forClass(UpdateWrapper.class);
        verify(calendarMapper).update(eq(existing), wrapperCaptor.capture());
        String sqlSet = wrapperCaptor.getValue().getSqlSet();
        // 关键可空字段必须出现在 set 子句中，否则 NOT_NULL 策略会丢弃 null
        assertThat(sqlSet).contains("open_time");
        assertThat(sqlSet).contains("close_time");
        assertThat(sqlSet).contains("reason_code");
        assertThat(sqlSet).contains("reason_text");
        assertThat(sqlSet).contains("provider_env");
        assertThat(sqlSet).contains("provider_name");
        assertThat(sqlSet).contains("external_ref");
        assertThat(sqlSet).contains("idempotency_key");
        assertThat(sqlSet).contains("metadata_json");
    }

    @Test
    void cancelBlockMarksCanceledAndWritesAuditAndBusinessEvent() {
        VenueUnavailableBlock existing = block(89L, 20L, 10L, "CLEANING", "CLEANING_BUFFER",
            LocalDate.now().plusDays(2).atTime(10, 0), LocalDate.now().plusDays(2).atTime(11, 0), 0, 0);
        when(blockMapper.selectById(89L)).thenReturn(existing);

        service.cancelBlock(89L, "场地恢复");

        assertThat(existing.status).isEqualTo("CANCELED");
        assertThat(existing.updatedBy).isEqualTo(7L);
        verify(accessService).requireVenueManageAccess(20L);
        verify(blockMapper).updateById(existing);

        ArgumentCaptor<VenueInventoryAudit> auditCaptor = ArgumentCaptor.forClass(VenueInventoryAudit.class);
        verify(auditMapper).insert(auditCaptor.capture());
        assertThat(auditCaptor.getValue().action).isEqualTo("UNAVAILABLE_BLOCK_CANCELED");
        assertThat(auditCaptor.getValue().reasonText).isEqualTo("场地恢复");
        assertThat(auditCaptor.getValue().beforeJson).contains("\"status\":\"ACTIVE\"");
        assertThat(auditCaptor.getValue().afterJson).contains("\"status\":\"CANCELED\"");

        ArgumentCaptor<BusinessEventRecordRequest> eventCaptor = ArgumentCaptor.forClass(BusinessEventRecordRequest.class);
        verify(businessEventService).record(eventCaptor.capture());
        assertThat(eventCaptor.getValue().eventType).isEqualTo(BusinessEventType.UNAVAILABLE_BLOCK_CANCELED);
        assertThat(eventCaptor.getValue().idempotencyKey).isEqualTo("UNAVAILABLE_BLOCK_CANCELED:89");
        assertThat(eventCaptor.getValue().reasonText).isEqualTo("场地恢复");
    }

    @Test
    void cancelBlockAlsoCancelsBoundLegacyMaintenanceRecord() {
        VenueUnavailableBlock existing = block(89L, 20L, 10L, "MAINTENANCE", "MAINTENANCE",
            LocalDate.now().plusDays(2).atTime(10, 0), LocalDate.now().plusDays(2).atTime(11, 0), 0, 0);
        VenueFacilityMaintenance maintenance = maintenance(301L, 10L, "ACTIVE", 89L);
        when(blockMapper.selectById(89L)).thenReturn(existing);
        when(maintenanceMapper.findByUnavailableBlockId(89L)).thenReturn(maintenance);

        service.cancelBlock(89L, "场地恢复");

        assertThat(existing.status).isEqualTo("CANCELED");
        assertThat(maintenance.status).isEqualTo("CANCELED");
        verify(blockMapper).updateById(existing);
        verify(maintenanceMapper).updateById(maintenance);
    }

    @Test
    void dailyInventoryDoesNotTreatCanceledBoundMaintenanceAsUnavailable() {
        LocalDate date = LocalDate.now().plusDays(2);
        VenueFacilityMaintenance canceledMaintenance = maintenance(301L, 10L, "CANCELED", 89L);
        when(spaceMapper.selectById(10L)).thenReturn(activeSpace());
        when(venueMapper.selectById(20L)).thenReturn(activeVenue());
        when(calendarMapper.findByVenueAndDate(20L, date)).thenReturn(null);
        when(blockMapper.findActiveOverlaps(eq(20L), eq(10L), any(), any())).thenReturn(List.of());
        when(maintenanceMapper.findActiveOverlaps(eq(10L), any(), any())).thenReturn(List.of(canceledMaintenance));
        when(reservationMapper.findOverlapsByStatuses(eq(10L), any(), any(), anyCollection())).thenReturn(List.of());

        List<InventorySlot> slots = service.dailyInventory(20L, 10L, date, true);

        assertThat(slots).allSatisfy(slot -> assertThat(slot.reasonCode).isNotEqualTo("MAINTENANCE"));
        assertThat(slotStarting(slots, date.atTime(10, 0)).status).isEqualTo("AVAILABLE");
    }

    @Test
    void blocksListActiveAndCanceledBlocksOverlappingTheQueriedDate() {
        // SHIGUANG-BATCH-02 S04：资源面"维护与不可售记录"需要"已取消 · 保留记录"展示（B2-T5 落实），
        // 列表读取含 CANCELED；库存槽位推导（dailyInventory）仍只消费 findActiveOverlaps。
        LocalDate date = LocalDate.now().plusDays(2);
        VenueUnavailableBlock block = block(90L, 20L, 10L, "PRIVATE_EVENT", "PRIVATE_EVENT",
            date.minusDays(1).atTime(23, 0), date.atTime(1, 0), 0, 0);
        VenueUnavailableBlock canceled = block(91L, 20L, 10L, "MANUAL_HOLD", "MANUAL_HOLD",
            date.atTime(2, 0), date.atTime(4, 0), 0, 0);
        canceled.status = "CANCELED";
        when(venueMapper.selectById(20L)).thenReturn(activeVenue());
        when(blockMapper.findListedOverlaps(20L, 10L, date.atStartOfDay(), date.plusDays(1).atStartOfDay()))
            .thenReturn(List.of(block, canceled));

        List<VenueUnavailableBlock> blocks = service.blocks(20L, 10L, date);

        assertThat(blocks).containsExactly(block, canceled);
        verify(accessService).requireVenueManageAccess(20L);
    }

    private InventorySlot slotStarting(List<InventorySlot> slots, LocalDateTime startTime) {
        return slots.stream()
            .filter(slot -> slot.startTime.equals(startTime))
            .findFirst()
            .orElseThrow(() -> new AssertionError("missing slot " + startTime));
    }

    private SaveUnavailableBlockRequest blockRequest(Long venueId, Long spaceId, String blockType, String reasonCode,
                                                     LocalDateTime startTime, LocalDateTime endTime,
                                                     Integer bufferBeforeMinutes, Integer bufferAfterMinutes) {
        SaveUnavailableBlockRequest request = new SaveUnavailableBlockRequest();
        request.venueId = venueId;
        request.spaceId = spaceId;
        request.blockType = blockType;
        request.reasonCode = reasonCode;
        request.reasonText = reasonCode;
        request.startTime = startTime;
        request.endTime = endTime;
        request.bufferBeforeMinutes = bufferBeforeMinutes;
        request.bufferAfterMinutes = bufferAfterMinutes;
        return request;
    }

    private SaveBusinessCalendarRequest calendarRequest(LocalDate date, String businessStatus, LocalTime openTime,
                                                        LocalTime closeTime, String reasonCode, String reasonText) {
        SaveBusinessCalendarRequest request = new SaveBusinessCalendarRequest();
        request.venueId = 20L;
        request.calendarDate = date;
        request.businessStatus = businessStatus;
        request.openTime = openTime;
        request.closeTime = closeTime;
        request.reasonCode = reasonCode;
        request.reasonText = reasonText;
        request.sourceType = "ADMIN";
        return request;
    }

    private Venue activeVenue() {
        Venue venue = new Venue();
        venue.id = 20L;
        venue.status = "ACTIVE";
        venue.openTime = LocalTime.of(8, 0);
        venue.closeTime = LocalTime.of(18, 0);
        venue.deleted = 0;
        return venue;
    }

    private VenueSpace activeSpace() {
        VenueSpace space = new VenueSpace();
        space.id = 10L;
        space.venueId = 20L;
        space.status = "ACTIVE";
        space.pricePerHour = BigDecimal.valueOf(100);
        space.deleted = 0;
        return space;
    }

    private VenueBusinessCalendar calendar(LocalDate date, String status, LocalTime openTime, LocalTime closeTime,
                                           String reasonCode, String reasonText) {
        VenueBusinessCalendar calendar = new VenueBusinessCalendar();
        calendar.venueId = 20L;
        calendar.calendarDate = date;
        calendar.businessStatus = status;
        calendar.openTime = openTime;
        calendar.closeTime = closeTime;
        calendar.reasonCode = reasonCode;
        calendar.reasonText = reasonText;
        calendar.sourceType = "ADMIN";
        return calendar;
    }

    private VenueUnavailableBlock block(Long id, Long venueId, Long spaceId, String blockType, String reasonCode,
                                        LocalDateTime startTime, LocalDateTime endTime,
                                        Integer bufferBeforeMinutes, Integer bufferAfterMinutes) {
        VenueUnavailableBlock block = new VenueUnavailableBlock();
        block.id = id;
        block.venueId = venueId;
        block.spaceId = spaceId;
        block.blockType = blockType;
        block.reasonCode = reasonCode;
        block.reasonText = reasonCode;
        block.startTime = startTime;
        block.endTime = endTime;
        block.bufferBeforeMinutes = bufferBeforeMinutes;
        block.bufferAfterMinutes = bufferAfterMinutes;
        block.sourceType = "ADMIN";
        block.status = "ACTIVE";
        block.deleted = 0;
        return block;
    }

    private VenueFacilityMaintenance maintenance(Long id, Long spaceId, String status, Long unavailableBlockId) {
        VenueFacilityMaintenance maintenance = new VenueFacilityMaintenance();
        maintenance.id = id;
        maintenance.spaceId = spaceId;
        maintenance.startTime = LocalDate.now().plusDays(2).atTime(10, 0);
        maintenance.endTime = maintenance.startTime.plusHours(1);
        maintenance.reason = "灯光检修";
        maintenance.status = status;
        maintenance.unavailableBlockId = unavailableBlockId;
        return maintenance;
    }

    private Reservation reservation(Long id, Long spaceId, LocalDateTime startTime, LocalDateTime endTime, String status) {
        Reservation reservation = new Reservation();
        reservation.id = id;
        reservation.venueId = 20L;
        reservation.spaceId = spaceId;
        reservation.startTime = startTime;
        reservation.endTime = endTime;
        reservation.status = status;
        reservation.deleted = 0;
        return reservation;
    }

    private SysConfig config(String value) {
        SysConfig config = new SysConfig();
        config.configValue = value;
        return config;
    }
}
