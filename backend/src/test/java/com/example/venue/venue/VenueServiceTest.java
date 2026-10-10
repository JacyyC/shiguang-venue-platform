package com.example.venue.venue;

import com.example.venue.common.BusinessException;
import com.example.venue.common.PageResult;
import com.example.venue.feedback.dto.VenueRatingAggregate;
import com.example.venue.feedback.mapper.VenueReviewMapper;
import com.example.venue.inventory.dto.SaveUnavailableBlockRequest;
import com.example.venue.inventory.entity.VenueUnavailableBlock;
import com.example.venue.inventory.service.VenueInventoryService;
import com.example.venue.reservation.entity.Reservation;
import com.example.venue.reservation.mapper.ReservationMapper;
import com.example.venue.reservation.service.ReservationService;
import com.example.venue.resource.service.VenueResourceRuleService;
import com.example.venue.venue.dto.SaveMaintenanceRequest;
import com.example.venue.venue.dto.SaveSpaceRequest;
import com.example.venue.venue.dto.SaveVenueRequest;
import com.example.venue.venue.dto.VenueFacetsResponse;
import com.example.venue.venue.dto.VenueListItemResponse;
import com.example.venue.venue.dto.VenueQuery;
import com.example.venue.venue.dto.VenueSpaceAggregate;
import com.example.venue.venue.entity.Venue;
import com.example.venue.venue.entity.VenueFacilityMaintenance;
import com.example.venue.venue.entity.VenueSpace;
import com.example.venue.venue.mapper.VenueFacilityMaintenanceMapper;
import com.example.venue.venue.mapper.VenueMapper;
import com.example.venue.venue.mapper.VenueSpaceMapper;
import com.example.venue.venue.service.VenueAccessService;
import com.example.venue.venue.service.VenueService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isA;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VenueServiceTest {
    @Mock
    private VenueMapper venueMapper;
    @Mock
    private VenueSpaceMapper spaceMapper;
    @Mock
    private VenueFacilityMaintenanceMapper maintenanceMapper;
    @Mock
    private ReservationMapper reservationMapper;
    @Mock
    private ReservationService reservationService;
    @Mock
    private VenueAccessService accessService;
    @Mock
    private VenueInventoryService inventoryService;
    @Mock
    private VenueResourceRuleService resourceRuleService;
    @Mock
    private VenueReviewMapper reviewMapper;

    private VenueService venueService;

    @BeforeEach
    void setUp() {
        venueService = new VenueService(venueMapper, spaceMapper, maintenanceMapper, reservationMapper, reservationService,
            accessService, inventoryService, resourceRuleService, reviewMapper);
    }

    @Test
    @SuppressWarnings("unchecked")
    void listAggregatesPublishedRatingsAndActiveSpaceStartingPriceForVisibleVenues() {
        Venue venue = new Venue();
        venue.id = 2L;
        venue.name = "星河体育馆";
        venue.address = "东区 1 号";
        venue.status = "ACTIVE";
        venue.deleted = 0;
        Page<Venue> page = new Page<>(1, 8);
        page.setRecords(List.of(venue));
        page.setTotal(1);
        when(venueMapper.selectPage(any(Page.class), any())).thenReturn(page);
        when(reviewMapper.selectAggregatesByVenueIds(List.of(2L))).thenReturn(List.of(
            new VenueRatingAggregate(2L, new BigDecimal("4.75"), 12L)));
        when(spaceMapper.selectAggregatesByVenueIds(List.of(2L))).thenReturn(List.of(
            new VenueSpaceAggregate(2L, new BigDecimal("68.00"), 6L, "BADMINTON,TENNIS")));
        VenueQuery query = new VenueQuery();
        query.setPageSize(8);
        query.status = "ACTIVE";

        PageResult<VenueListItemResponse> result = venueService.list(query);

        assertThat(result.getTotal()).isEqualTo(1);
        assertThat(result.getRecords()).singleElement().satisfies(item -> {
            assertThat(item.id()).isEqualTo(2L);
            assertThat(item.averageRating()).isEqualByComparingTo("4.75");
            assertThat(item.reviewCount()).isEqualTo(12);
            assertThat(item.minPrice()).isEqualByComparingTo("68.00");
            assertThat(item.activeSpaceCount()).isEqualTo(6);
            assertThat(item.spaceTypes()).containsExactly("BADMINTON", "TENNIS");
        });
        verify(reviewMapper).selectAggregatesByVenueIds(List.of(2L));
        verify(spaceMapper).selectAggregatesByVenueIds(List.of(2L));
    }

    @Test
    void updateSpaceStatusRejectsUnknownStatus() {
        VenueSpace space = activeSpace();
        when(spaceMapper.selectByIdForUpdate(11L)).thenReturn(space);

        assertThatThrownBy(() -> venueService.updateSpaceStatus(11L, "BROKEN"))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("场地状态只能为 ACTIVE、MAINTENANCE、CLOSED");

        verify(spaceMapper).selectByIdForUpdate(11L);
        verify(spaceMapper, never()).selectById(11L);
        verify(spaceMapper, never()).updateById(any(VenueSpace.class));
    }

    @Test
    void updateSpaceStatusCancelsFutureReservationsWhenUnavailable() {
        VenueSpace space = activeSpace();
        Reservation reservation = new Reservation();
        reservation.id = 99L;
        when(spaceMapper.selectByIdForUpdate(11L)).thenReturn(space);
        when(reservationMapper.selectList(any())).thenReturn(List.of(reservation));

        venueService.updateSpaceStatus(11L, "MAINTENANCE");

        verify(spaceMapper).selectByIdForUpdate(11L);
        verify(spaceMapper, never()).selectById(11L);
        verify(spaceMapper).updateById(isA(VenueSpace.class));
        verify(reservationService).cancel(eq(99L), contains("场地当前状态为维护中"), eq(true));
    }

    @Test
    void updateSpaceStatusAcquiresRowLockBeforeCancelScan() {
        // A26-P2-10：updateSpaceStatus 必须用 selectByIdForUpdate（而非 selectById）拿场地行锁，
        // 避免 RR 下普通读提前建立读视图导致 cancelFutureReservationsIfUnavailable 漏取消并发预约。
        VenueSpace space = activeSpace();
        Reservation reservation = new Reservation();
        reservation.id = 99L;
        when(spaceMapper.selectByIdForUpdate(11L)).thenReturn(space);
        when(reservationMapper.selectList(any())).thenReturn(List.of(reservation));

        venueService.updateSpaceStatus(11L, "MAINTENANCE");

        InOrder inOrder = inOrder(spaceMapper, reservationMapper);
        inOrder.verify(spaceMapper).selectByIdForUpdate(11L);
        inOrder.verify(reservationMapper).selectList(any());
        verify(spaceMapper, never()).selectById(any());
    }

    @Test
    void updateVenueAcquiresRowLockBeforeCancelScan() {
        // A26-P2-10：updateVenue 必须用 selectByIdForUpdate（而非 detail→selectById）拿场馆行锁，
        // 避免 RR 下普通读提前建立读视图导致 cancelFutureReservationsIfUnavailable 漏取消并发预约。
        Venue venue = new Venue();
        venue.id = 3L;
        venue.status = "ACTIVE";
        venue.deleted = 0;
        SaveVenueRequest request = new SaveVenueRequest();
        request.name = "体育中心";
        request.status = "CLOSED";
        Reservation reservation = new Reservation();
        reservation.id = 99L;
        when(venueMapper.selectByIdForUpdate(3L)).thenReturn(venue);
        when(reservationMapper.selectList(any())).thenReturn(List.of(reservation));

        venueService.updateVenue(3L, request);

        InOrder inOrder = inOrder(venueMapper, reservationMapper);
        inOrder.verify(venueMapper).selectByIdForUpdate(3L);
        inOrder.verify(reservationMapper).selectList(any());
        verify(venueMapper, never()).selectById(3L);
        verify(reservationService).cancel(eq(99L), contains("场馆当前状态为已关闭"), eq(true));
    }

    @Test
    void updateVenueRejectsMissingVenueUnderForUpdateLock() {
        // A26-P2-10：updateVenue 用 selectByIdForUpdate 后仍需正确拒绝不存在/已删除场馆。
        when(venueMapper.selectByIdForUpdate(404L)).thenReturn(null);

        assertThatThrownBy(() -> venueService.updateVenue(404L, new SaveVenueRequest()))
            .isInstanceOf(BusinessException.class)
            .hasMessage("场馆不存在");

        verify(venueMapper).selectByIdForUpdate(404L);
        verify(venueMapper, never()).updateById(any(Venue.class));
        verify(reservationMapper, never()).selectList(any());
    }

    @Test
    void updateSpaceRejectsCrossVenueMoveWhenReservationHistoryExists() {
        VenueSpace space = activeSpace();
        SaveSpaceRequest request = saveSpaceRequest(4L);
        when(spaceMapper.selectByIdForUpdate(11L)).thenReturn(space);
        Venue targetVenue = new Venue();
        targetVenue.id = 4L;
        targetVenue.deleted = 0;
        when(venueMapper.selectById(4L)).thenReturn(targetVenue);
        when(reservationMapper.existsBySpaceId(11L)).thenReturn(true);

        assertThatThrownBy(() -> venueService.updateSpace(11L, request))
            .isInstanceOf(BusinessException.class)
            .hasMessage("存在历史预约的场地不能变更所属场馆");

        verify(spaceMapper).selectByIdForUpdate(11L);
        verify(spaceMapper, never()).updateById(any(VenueSpace.class));
    }

    @Test
    void saveVenueRejectsReversedBusinessHours() {
        SaveVenueRequest request = new SaveVenueRequest();
        request.name = "体育中心";
        request.openTime = LocalTime.of(18, 0);
        request.closeTime = LocalTime.of(18, 0);

        assertThatThrownBy(() -> venueService.saveVenue(request))
            .isInstanceOf(BusinessException.class)
            .hasMessage("场馆开馆时间必须早于闭馆时间");

        verify(venueMapper, never()).insert(any(Venue.class));
    }

    @Test
    void saveSpaceRejectsMissingVenueEvenForSystemAdminAccessPath() {
        SaveSpaceRequest request = saveSpaceRequest(404L);
        when(venueMapper.selectById(404L)).thenReturn(null);

        assertThatThrownBy(() -> venueService.saveSpace(request))
            .isInstanceOf(BusinessException.class)
            .hasMessage("场馆不存在");

        verify(accessService, never()).requireVenueManageAccess(404L);
        verify(spaceMapper, never()).insert(any(VenueSpace.class));
    }

    @Test
    void createMaintenanceCreatesUnavailableBlockAndBindsLegacyRecord() {
        SaveMaintenanceRequest request = new SaveMaintenanceRequest();
        request.spaceId = 11L;
        request.startTime = LocalDateTime.now().plusDays(2).withHour(10).withMinute(0).withSecond(0).withNano(0);
        request.endTime = request.startTime.plusHours(1);
        request.reason = "灯光检修";
        when(spaceMapper.selectById(11L)).thenReturn(activeSpace());
        VenueUnavailableBlock block = new VenueUnavailableBlock();
        block.id = 70L;
        block.venueId = 3L;
        block.spaceId = 11L;
        when(inventoryService.createBlock(any(SaveUnavailableBlockRequest.class))).thenReturn(block);

        venueService.createMaintenance(request);

        ArgumentCaptor<SaveUnavailableBlockRequest> blockRequestCaptor =
            ArgumentCaptor.forClass(SaveUnavailableBlockRequest.class);
        verify(inventoryService).createBlock(blockRequestCaptor.capture());
        SaveUnavailableBlockRequest blockRequest = blockRequestCaptor.getValue();
        assertThat(blockRequest.venueId).isEqualTo(3L);
        assertThat(blockRequest.spaceId).isEqualTo(11L);
        assertThat(blockRequest.blockType).isEqualTo("MAINTENANCE");
        assertThat(blockRequest.reasonCode).isEqualTo("MAINTENANCE");
        assertThat(blockRequest.reasonText).isEqualTo("灯光检修");
        assertThat(blockRequest.startTime).isEqualTo(request.startTime);
        assertThat(blockRequest.endTime).isEqualTo(request.endTime);
        assertThat(blockRequest.sourceType).isEqualTo("ADMIN");

        ArgumentCaptor<VenueFacilityMaintenance> maintenanceCaptor =
            ArgumentCaptor.forClass(VenueFacilityMaintenance.class);
        verify(maintenanceMapper).insert(maintenanceCaptor.capture());
        VenueFacilityMaintenance maintenance = maintenanceCaptor.getValue();
        assertThat(maintenance.unavailableBlockId).isEqualTo(70L);
        assertThat(maintenance.sourceType).isEqualTo("ADMIN");
        assertThat(maintenance.status).isEqualTo("ACTIVE");
        verify(maintenanceMapper, never()).findActiveOverlaps(any(), any(), any());
        verify(reservationMapper, never()).findActiveOverlaps(any(), any(), any());
    }

    @Test
    void cancelMaintenanceAlsoCancelsBoundUnavailableBlock() {
        VenueFacilityMaintenance maintenance = new VenueFacilityMaintenance();
        maintenance.id = 5L;
        maintenance.spaceId = 11L;
        maintenance.status = "ACTIVE";
        maintenance.unavailableBlockId = 70L;
        when(maintenanceMapper.selectById(5L)).thenReturn(maintenance);
        when(spaceMapper.selectById(11L)).thenReturn(activeSpace());

        venueService.cancelMaintenance(5L);

        verify(inventoryService).cancelBlock(70L, "维护时段取消");
        verify(maintenanceMapper).updateById(maintenance);
        assertThat(maintenance.status).isEqualTo("CANCELED");
    }

    @Test
    void cancelMaintenanceStillCancelsMaintenanceWhenBoundBlockAlreadyCanceled() {
        VenueFacilityMaintenance maintenance = new VenueFacilityMaintenance();
        maintenance.id = 5L;
        maintenance.spaceId = 11L;
        maintenance.status = "ACTIVE";
        maintenance.unavailableBlockId = 70L;
        when(maintenanceMapper.selectById(5L)).thenReturn(maintenance);
        when(spaceMapper.selectById(11L)).thenReturn(activeSpace());
        doThrow(BusinessException.badRequest("不可售时段已取消"))
            .when(inventoryService).cancelBlock(70L, "维护时段取消");

        venueService.cancelMaintenance(5L);

        verify(inventoryService).cancelBlock(70L, "维护时段取消");
        verify(maintenanceMapper).updateById(maintenance);
        assertThat(maintenance.status).isEqualTo("CANCELED");
    }

    @Test
    void cancelMaintenanceKeepsLegacyBehaviorWhenBlockBindingMissing() {
        VenueFacilityMaintenance maintenance = new VenueFacilityMaintenance();
        maintenance.id = 5L;
        maintenance.spaceId = 11L;
        maintenance.status = "ACTIVE";
        when(maintenanceMapper.selectById(5L)).thenReturn(maintenance);
        when(spaceMapper.selectById(11L)).thenReturn(activeSpace());

        venueService.cancelMaintenance(5L);

        verify(inventoryService, never()).cancelBlock(any(), any());
        verify(maintenanceMapper).updateById(maintenance);
        assertThat(maintenance.status).isEqualTo("CANCELED");
    }

    @Test
    void updateVenueRejectsBusinessHoursShrinkWhenFutureReservationOutsideWindow() {
        // A26-P3-14：updateVenue 修改默认营业时间窗口收缩时，落在窗外既有有效预约既不能审核通过
        // 也不会被系统取消；参照 saveCalendar 收缩拒绝逻辑拒绝修改。
        Venue venue = new Venue();
        venue.id = 3L;
        venue.status = "ACTIVE";
        venue.openTime = LocalTime.of(8, 0);
        venue.closeTime = LocalTime.of(22, 0);
        venue.deleted = 0;
        SaveVenueRequest request = new SaveVenueRequest();
        request.name = "体育中心";
        request.openTime = LocalTime.of(10, 0); // open 收缩 08:00 → 10:00
        request.status = "ACTIVE"; // 避免 status 变化触发 cancel 链路
        Reservation reservation = new Reservation();
        reservation.id = 99L;
        reservation.startTime = LocalDateTime.now().plusDays(2).withHour(9).withMinute(0).withSecond(0).withNano(0);
        reservation.endTime = reservation.startTime.plusHours(1);
        when(venueMapper.selectByIdForUpdate(3L)).thenReturn(venue);
        when(reservationMapper.selectList(any())).thenReturn(List.of(reservation));

        assertThatThrownBy(() -> venueService.updateVenue(3L, request))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("场馆默认营业时间收缩后存在有效预约落在窗外");

        verify(venueMapper, never()).updateById(any(Venue.class));
    }

    @Test
    void updateVenueAllowsBusinessHoursExpandWithoutConflictCheck() {
        // A26-P3-14：扩张窗口不影响既有预约，无需校验，也不查 reservation。
        Venue venue = new Venue();
        venue.id = 3L;
        venue.status = "ACTIVE";
        venue.openTime = LocalTime.of(10, 0);
        venue.closeTime = LocalTime.of(18, 0);
        venue.deleted = 0;
        SaveVenueRequest request = new SaveVenueRequest();
        request.name = "体育中心";
        request.openTime = LocalTime.of(8, 0); // 扩张 10:00 → 08:00
        request.closeTime = LocalTime.of(22, 0); // 扩张 18:00 → 22:00
        request.status = "ACTIVE";
        when(venueMapper.selectByIdForUpdate(3L)).thenReturn(venue);

        venueService.updateVenue(3L, request);

        verify(venueMapper).updateById(any(Venue.class));
        verify(reservationMapper, never()).selectList(any());
    }

    @Test
    void updateVenueAllowsBusinessHoursShrinkWhenNoFutureReservations() {
        // A26-P3-14：窗口收缩但无未来有效预约落在窗外时，正常通过。
        Venue venue = new Venue();
        venue.id = 3L;
        venue.status = "ACTIVE";
        venue.openTime = LocalTime.of(8, 0);
        venue.closeTime = LocalTime.of(22, 0);
        venue.deleted = 0;
        SaveVenueRequest request = new SaveVenueRequest();
        request.name = "体育中心";
        request.openTime = LocalTime.of(10, 0);
        request.status = "ACTIVE";
        when(venueMapper.selectByIdForUpdate(3L)).thenReturn(venue);
        when(reservationMapper.selectList(any())).thenReturn(List.of());

        venueService.updateVenue(3L, request);

        verify(venueMapper).updateById(any(Venue.class));
        verify(reservationMapper).selectList(any()); // rejectBusinessHoursShrinkConflict 查询一次
    }

    @Test
    void updateSpaceRejectsCrossVenueMoveWhenActiveUnavailableBlocksExist() {
        // A26-P3-15：变更场馆时若该场地下存在 ACTIVE 不可售块，块 venue_id 会变陈旧，
        // 人工保留/不可售时段在新旧场馆下都查不到，可被预订或展示与决策分叉。
        VenueSpace space = activeSpace(); // venueId=3L
        SaveSpaceRequest request = saveSpaceRequest(4L);
        when(spaceMapper.selectByIdForUpdate(11L)).thenReturn(space);
        Venue targetVenue = new Venue();
        targetVenue.id = 4L;
        targetVenue.deleted = 0;
        when(venueMapper.selectById(4L)).thenReturn(targetVenue);
        when(reservationMapper.existsBySpaceId(11L)).thenReturn(false);
        when(inventoryService.hasActiveUnavailableBlocksBySpace(11L)).thenReturn(true);

        assertThatThrownBy(() -> venueService.updateSpace(11L, request))
            .isInstanceOf(BusinessException.class)
            .hasMessage("存在有效不可售时段的场地不能变更所属场馆，请先取消或调整");

        verify(inventoryService).hasActiveUnavailableBlocksBySpace(11L);
        verify(spaceMapper, never()).updateById(any(VenueSpace.class));
    }

    @Test
    void updateSpaceAllowsCrossVenueMoveWhenNoActiveUnavailableBlocks() {
        // A26-P3-15：无历史预约且无 ACTIVE 不可售块时，允许变更场馆。
        VenueSpace space = activeSpace(); // venueId=3L, status=ACTIVE
        SaveSpaceRequest request = saveSpaceRequest(4L); // status=ACTIVE
        when(spaceMapper.selectByIdForUpdate(11L)).thenReturn(space);
        Venue targetVenue = new Venue();
        targetVenue.id = 4L;
        targetVenue.deleted = 0;
        when(venueMapper.selectById(4L)).thenReturn(targetVenue);
        when(reservationMapper.existsBySpaceId(11L)).thenReturn(false);
        when(inventoryService.hasActiveUnavailableBlocksBySpace(11L)).thenReturn(false);

        venueService.updateSpace(11L, request);

        verify(inventoryService).hasActiveUnavailableBlocksBySpace(11L);
        verify(spaceMapper).updateById(any(VenueSpace.class));
    }

    @Test
    void updateVenueKeepsPreviousStatusWhenRequestStatusOmitted() {
        // A26-P3-16：updateVenue 省略 status 时保持 DB 原值，不应被静默重置为 ACTIVE。
        Venue venue = new Venue();
        venue.id = 3L;
        venue.status = "CLOSED"; // 已停业
        venue.openTime = LocalTime.of(8, 0);
        venue.closeTime = LocalTime.of(22, 0);
        venue.deleted = 0;
        SaveVenueRequest request = new SaveVenueRequest();
        request.name = "体育中心";
        request.status = null; // 省略 status
        when(venueMapper.selectByIdForUpdate(3L)).thenReturn(venue);

        venueService.updateVenue(3L, request);

        assertThat(venue.status).isEqualTo("CLOSED");
        verify(venueMapper).updateById(venue);
    }

    @Test
    void updateSpaceKeepsPreviousStatusWhenRequestStatusOmitted() {
        // A26-P3-16：updateSpace 省略 status 时保持 DB 原值，不应被静默重置为 ACTIVE。
        VenueSpace space = new VenueSpace();
        space.id = 11L;
        space.venueId = 3L;
        space.status = "MAINTENANCE"; // 已停用
        space.deleted = 0;
        SaveSpaceRequest request = saveSpaceRequest(3L);
        request.status = null; // 省略 status
        when(spaceMapper.selectByIdForUpdate(11L)).thenReturn(space);
        Venue existingVenue = new Venue();
        existingVenue.id = 3L;
        existingVenue.deleted = 0;
        when(venueMapper.selectById(3L)).thenReturn(existingVenue);

        venueService.updateSpace(11L, request);

        assertThat(space.status).isEqualTo("MAINTENANCE");
        verify(spaceMapper).updateById(space);
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void listAppliesSpaceTypeExistsFilterWhenTypeProvided() {
        // V2-UX-REMEDIATION/S01：VenueQuery.type 此前绑定后从不消费；现在按 ACTIVE 场地 exists 过滤。
        Page<Venue> page = new Page<>(1, 8);
        page.setRecords(List.of());
        when(venueMapper.selectPage(any(Page.class), any())).thenReturn(page);

        VenueQuery query = new VenueQuery();
        query.type = " BASKETBALL ";
        venueService.list(query);

        ArgumentCaptor<QueryWrapper> wrapperCaptor = ArgumentCaptor.forClass(QueryWrapper.class);
        verify(venueMapper).selectPage(any(Page.class), wrapperCaptor.capture());
        QueryWrapper<Venue> wrapper = wrapperCaptor.getValue();
        assertThat(wrapper.getSqlSegment()).containsIgnoringCase("exists");
        assertThat(wrapper.getParamNameValuePairs()).containsValue("BASKETBALL");
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void listWithoutTypeKeepsOriginalFilterSemantics() {
        Page<Venue> page = new Page<>(1, 8);
        page.setRecords(List.of());
        when(venueMapper.selectPage(any(Page.class), any())).thenReturn(page);

        VenueQuery query = new VenueQuery();
        query.type = "  ";
        venueService.list(query);

        ArgumentCaptor<QueryWrapper> wrapperCaptor = ArgumentCaptor.forClass(QueryWrapper.class);
        verify(venueMapper).selectPage(any(Page.class), wrapperCaptor.capture());
        assertThat(wrapperCaptor.getValue().getSqlSegment()).doesNotContainIgnoringCase("exists");
    }

    @Test
    void facetsReturnsDistinctAreasAndSpaceTypesFromMappers() {
        when(venueMapper.selectDistinctAreas()).thenReturn(List.of("东区", "西区"));
        when(spaceMapper.selectDistinctSpaceTypes()).thenReturn(List.of("BADMINTON", "BASKETBALL"));

        VenueFacetsResponse facets = venueService.facets();

        assertThat(facets.areas()).containsExactly("东区", "西区");
        assertThat(facets.spaceTypes()).containsExactly("BADMINTON", "BASKETBALL");
    }

    private VenueSpace activeSpace() {
        VenueSpace space = new VenueSpace();
        space.id = 11L;
        space.venueId = 3L;
        space.status = "ACTIVE";
        space.deleted = 0;
        return space;
    }

    private SaveSpaceRequest saveSpaceRequest(Long venueId) {
        SaveSpaceRequest request = new SaveSpaceRequest();
        request.venueId = venueId;
        request.name = "羽毛球 1 号场";
        request.type = "BADMINTON";
        request.status = "ACTIVE";
        return request;
    }
}
