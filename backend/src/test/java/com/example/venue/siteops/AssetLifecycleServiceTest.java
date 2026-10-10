package com.example.venue.siteops;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.venue.business.dto.BusinessEventRecordRequest;
import com.example.venue.business.enums.BusinessDomain;
import com.example.venue.business.enums.BusinessEventType;
import com.example.venue.business.service.BusinessEventService;
import com.example.venue.common.BusinessException;
import com.example.venue.common.CurrentUser;
import com.example.venue.common.EntityExistenceService;
import com.example.venue.common.PageQuery;
import com.example.venue.common.PageResult;
import com.example.venue.inventory.dto.SaveUnavailableBlockRequest;
import com.example.venue.inventory.entity.VenueUnavailableBlock;
import com.example.venue.inventory.service.VenueInventoryService;
import com.example.venue.siteops.dto.AssetInspectionRequest;
import com.example.venue.siteops.dto.AssetQuery;
import com.example.venue.siteops.dto.AssetRepairCancelRequest;
import com.example.venue.siteops.dto.AssetRepairCompleteRequest;
import com.example.venue.siteops.dto.AssetRepairRequest;
import com.example.venue.siteops.dto.AssetRequest;
import com.example.venue.siteops.dto.AssetStatusRequest;
import com.example.venue.siteops.entity.AssetInspectionRecord;
import com.example.venue.siteops.entity.AssetItem;
import com.example.venue.siteops.entity.AssetLifecycleEvent;
import com.example.venue.siteops.entity.AssetRepairRecord;
import com.example.venue.siteops.mapper.AssetInspectionRecordMapper;
import com.example.venue.siteops.mapper.AssetItemMapper;
import com.example.venue.siteops.mapper.AssetLifecycleEventMapper;
import com.example.venue.siteops.mapper.AssetRepairRecordMapper;
import com.example.venue.siteops.service.AssetLifecycleService;
import com.example.venue.venue.mapper.VenueAdminRelationMapper;
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
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AssetLifecycleServiceTest {
    @Mock
    private AssetItemMapper assetMapper;
    @Mock
    private AssetLifecycleEventMapper lifecycleEventMapper;
    @Mock
    private AssetInspectionRecordMapper inspectionMapper;
    @Mock
    private AssetRepairRecordMapper repairMapper;
    @Mock
    private VenueAccessService venueAccessService;
    @Mock
    private VenueInventoryService inventoryService;
    @Mock
    private BusinessEventService businessEventService;
    @Mock
    private EntityExistenceService entityExistenceService;
    @Mock
    private VenueAdminRelationMapper venueAdminRelationMapper;

    private AssetLifecycleService service;

    @BeforeEach
    void setUp() {
        service = new AssetLifecycleService(assetMapper, lifecycleEventMapper, inspectionMapper, repairMapper,
            venueAccessService, inventoryService, businessEventService, entityExistenceService,
            venueAdminRelationMapper);
        lenient().when(assetMapper.selectByIdForUpdate(org.mockito.ArgumentMatchers.anyLong()))
            .thenAnswer(invocation -> assetMapper.selectById(invocation.getArgument(0)));
        // AUD-P2-02: selectByIdForUpdate 默认委托到 selectById，保持既有测试 mock 不变
        lenient().when(repairMapper.selectByIdForUpdate(org.mockito.ArgumentMatchers.anyLong()))
            .thenAnswer(invocation -> repairMapper.selectById(invocation.getArgument(0)));
        // A26-P3-20：responsibleUserId/inspectorUserId/repairOwnerId 默认放行，
        // 既有测试未显式 stub 时仍能通过校验，仅在拒绝场景显式 stub 抛错或返回 0。
        lenient().when(venueAdminRelationMapper.countRelation(
            org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong())).thenReturn(1);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
            new CurrentUser(7L, "asset-admin", Set.of("SYSTEM_ADMIN"), Set.of()), null, List.of()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void createAssetPersistsStockedAssetAndLifecycleEvent() {
        when(assetMapper.insert(any(AssetItem.class))).thenAnswer(invocation -> {
            AssetItem asset = invocation.getArgument(0);
            asset.id = 7L;
            return 1;
        });
        doAnswer(invocation -> {
            BusinessEventRecordRequest event = invocation.getArgument(0);
            assertThat(event.metadata).containsEntry("venueId", 20L);
            assertThat(event.metadata).containsEntry("assetType", "NET");
            assertThat(event.metadata).containsKey("assetNo");
            return null;
        }).when(businessEventService).record(any(BusinessEventRecordRequest.class));

        AssetItem asset = service.createAsset(assetRequest());

        assertThat(asset.assetNo).startsWith("ASSET");
        assertThat(asset.status).isEqualTo("STOCKED");
        assertThat(asset.purchaseAmount).isEqualByComparingTo("1280.00");
        assertThat(asset.createTime).isNotNull();
        verify(venueAccessService).requireVenueManageAccess(20L);
        verify(assetMapper).insert(asset);

        AssetLifecycleEvent lifecycleEvent = capturedLifecycleEvent();
        assertThat(lifecycleEvent.eventType).isEqualTo("STOCKED");
        assertThat(lifecycleEvent.assetId).isEqualTo(7L);
        assertThat(lifecycleEvent.venueId).isEqualTo(20L);
        assertThat(lifecycleEvent.toStatus).isEqualTo("STOCKED");

        BusinessEventRecordRequest event = capturedBusinessEvent();
        assertThat(event.eventType).isEqualTo(BusinessEventType.ASSET_STATUS_CHANGED);
        assertThat(event.businessDomain).isEqualTo(BusinessDomain.ASSET);
        assertThat(event.entityType).isEqualTo("ASSET");
        assertThat(event.entityId).isEqualTo(7L);
        assertThat(event.toStatus).isEqualTo("STOCKED");
        assertThat(event.idempotencyKey).isEqualTo("ASSET_STATUS_CHANGED:asset/create/7");
    }

    @Test
    void listAssetsWithSpecificVenueRequiresAccessAndReturnsPage() {
        AssetQuery query = new AssetQuery();
        query.venueId = 20L;
        query.status = "IN_USE";
        Page<AssetItem> page = new Page<>(1, 10);
        page.setRecords(List.of(asset("IN_USE")));
        page.setTotal(1);
        when(assetMapper.selectPage(any(), any())).thenReturn(page);

        PageResult<AssetItem> result = service.assets(query);

        assertThat(result.getRecords()).hasSize(1);
        assertThat(result.getTotal()).isEqualTo(1);
        verify(venueAccessService).requireVenueManageAccess(20L);
        verify(assetMapper).selectPage(any(), any());
    }

    @Test
    void listRepairsWithoutVenueAndNoManageableVenuesReturnsEmptyPage() {
        AssetQuery query = new AssetQuery();
        query.setPageNum(3);
        query.setPageSize(6);
        when(venueAccessService.manageableVenueIds()).thenReturn(List.of());

        PageResult<AssetRepairRecord> result = service.repairs(query);

        assertThat(result.getRecords()).isEmpty();
        assertThat(result.getTotal()).isZero();
        assertThat(result.getPageNum()).isEqualTo(3);
        assertThat(result.getPageSize()).isEqualTo(6);
        verify(venueAccessService).manageableVenueIds();
        verify(repairMapper, never()).selectPage(any(), any());
    }

    @Test
    void updateAssetChangesEditableFieldsWithoutChangingStatus() {
        AssetItem asset = asset("IN_USE");
        when(assetMapper.selectById(7L)).thenReturn(asset);
        AssetRequest request = assetRequest();
        request.name = "新羽毛球网";
        request.brand = "Victor";
        request.responsibleUserId = 12L;

        AssetItem result = service.updateAsset(7L, request);

        assertThat(result.status).isEqualTo("IN_USE");
        assertThat(result.name).isEqualTo("新羽毛球网");
        assertThat(result.brand).isEqualTo("Victor");
        assertThat(result.responsibleUserId).isEqualTo(12L);
        assertThat(result.updateTime).isNotNull();
        verify(venueAccessService).requireVenueManageAccess(20L);
        // A26-P2-16：updateAsset 用 UpdateWrapper.set 显式 set 可空字段（spaceId/brand/model/...）。
        verify(assetMapper).update(eq(asset), any());
    }

    @Test
    void changeStatusPersistsTransitionAndRecordsEvents() {
        AssetItem asset = asset("STOCKED");
        when(assetMapper.selectById(7L)).thenReturn(asset);
        // A26-P2-13：changeStatus 用 CAS 更新，需 mock update 返回 1 行。
        when(assetMapper.update(any(AssetItem.class), any())).thenReturn(1);
        when(lifecycleEventMapper.insert(any(AssetLifecycleEvent.class))).thenAnswer(invocation -> {
            AssetLifecycleEvent event = invocation.getArgument(0);
            event.id = 800L;
            return 1;
        });
        AssetStatusRequest request = new AssetStatusRequest();
        request.toStatus = "IN_USE";
        request.reasonCode = "ACTIVATED";
        request.reasonText = "验收完成";

        AssetItem result = service.changeStatus(7L, request);

        assertThat(result.status).isEqualTo("IN_USE");
        verify(venueAccessService).requireVenueManageAccess(20L);
        // A26-P2-13：changeStatus 改用 requireAssetForUpdate + CAS UpdateWrapper。
        verify(assetMapper).selectByIdForUpdate(7L);
        verify(assetMapper).update(eq(asset), any());

        AssetLifecycleEvent lifecycleEvent = capturedLifecycleEvent();
        assertThat(lifecycleEvent.eventType).isEqualTo("STATUS_CHANGED");
        assertThat(lifecycleEvent.fromStatus).isEqualTo("STOCKED");
        assertThat(lifecycleEvent.toStatus).isEqualTo("IN_USE");
        assertThat(lifecycleEvent.reasonText).isEqualTo("验收完成");

        BusinessEventRecordRequest event = capturedBusinessEvent();
        assertThat(event.eventType).isEqualTo(BusinessEventType.ASSET_STATUS_CHANGED);
        assertThat(event.fromStatus).isEqualTo("STOCKED");
        assertThat(event.toStatus).isEqualTo("IN_USE");
        assertThat(event.reasonCode).isEqualTo("ACTIVATED");
        assertThat(event.idempotencyKey)
            .isEqualTo("ASSET_STATUS_CHANGED:asset/status/7/STOCKED/IN_USE/lifecycle-event/800");
    }

    @Test
    void repeatedSameStatusTransitionRecordsDistinctBusinessEvents() {
        AssetItem asset = asset("IN_USE");
        when(assetMapper.selectById(7L)).thenReturn(asset);
        // A26-P2-13：changeStatus 用 CAS 更新，3 次状态变更需 mock update 返回 1 行。
        when(assetMapper.update(any(AssetItem.class), any())).thenReturn(1);
        long[] nextLifecycleEventId = {900L};
        when(lifecycleEventMapper.insert(any(AssetLifecycleEvent.class))).thenAnswer(invocation -> {
            AssetLifecycleEvent event = invocation.getArgument(0);
            event.id = nextLifecycleEventId[0]++;
            return 1;
        });

        service.changeStatus(7L, statusRequest("OUT_OF_SERVICE", "BROKEN", "暂停使用"));
        service.changeStatus(7L, statusRequest("IN_USE", "FIXED", "恢复使用"));
        service.changeStatus(7L, statusRequest("OUT_OF_SERVICE", "BROKEN_AGAIN", "再次暂停使用"));

        List<BusinessEventRecordRequest> events = capturedBusinessEvents(3);
        assertThat(events).extracting(event -> event.idempotencyKey)
            .containsExactly(
                "ASSET_STATUS_CHANGED:asset/status/7/IN_USE/OUT_OF_SERVICE/lifecycle-event/900",
                "ASSET_STATUS_CHANGED:asset/status/7/OUT_OF_SERVICE/IN_USE/lifecycle-event/901",
                "ASSET_STATUS_CHANGED:asset/status/7/IN_USE/OUT_OF_SERVICE/lifecycle-event/902");
        assertThat(events.get(0).idempotencyKey).isNotEqualTo(events.get(2).idempotencyKey);
    }

    @Test
    void changeStatusRejectsNonAssetLifecycleStatusWithoutSideEffects() {
        AssetItem asset = asset("IN_USE");
        when(assetMapper.selectById(7L)).thenReturn(asset);
        AssetStatusRequest request = statusRequest("COMPLETED", "REPAIR_DONE", "维修流程状态不能写入资产状态");

        assertThatThrownBy(() -> service.changeStatus(7L, request))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("资产状态不受支持");

        assertThat(asset.status).isEqualTo("IN_USE");
        verify(venueAccessService).requireVenueManageAccess(20L);
        verify(assetMapper, never()).updateById(any(AssetItem.class));
        verify(inventoryService, never()).createBlock(any(SaveUnavailableBlockRequest.class));
        verify(lifecycleEventMapper, never()).insert(any(AssetLifecycleEvent.class));
        verify(businessEventService, never()).record(any(BusinessEventRecordRequest.class));
    }

    @Test
    void listAssetEventsRequiresAssetVenueAccessAndReturnsTimeline() {
        AssetItem asset = asset("IN_USE");
        when(assetMapper.selectById(7L)).thenReturn(asset);
        AssetLifecycleEvent event = new AssetLifecycleEvent();
        event.id = 800L;
        event.assetId = 7L;
        Page<AssetLifecycleEvent> page = new Page<>(1, 10);
        page.setRecords(List.of(event));
        page.setTotal(1);
        when(lifecycleEventMapper.selectPage(any(), any())).thenReturn(page);

        PageResult<AssetLifecycleEvent> result = service.assetEvents(7L, new PageQuery());

        assertThat(result.getRecords()).containsExactly(event);
        assertThat(result.getTotal()).isEqualTo(1);
        verify(venueAccessService).requireVenueManageAccess(20L);
        verify(lifecycleEventMapper).selectPage(any(), any());
    }

    @Test
    void retiredAssetCannotReturnToUse() {
        AssetItem asset = asset("RETIRED");
        when(assetMapper.selectById(7L)).thenReturn(asset);
        AssetStatusRequest request = new AssetStatusRequest();
        request.toStatus = "IN_USE";
        request.reasonText = "误操作";

        assertThatThrownBy(() -> service.changeStatus(7L, request))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("已报废资产不能重新启用");

        verify(venueAccessService).requireVenueManageAccess(20L);
        verify(assetMapper, never()).updateById(any(AssetItem.class));
    }

    @Test
    void retiredAssetCannotRecordRepairRequiredInspection() {
        AssetItem asset = asset("RETIRED");
        when(assetMapper.selectById(7L)).thenReturn(asset);

        assertThatThrownBy(() -> service.recordInspection(7L, inspectionRequest("REPAIR_REQUIRED")))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("已报废资产不能变更状态");

        verify(venueAccessService).requireVenueManageAccess(20L);
        verify(inspectionMapper, never()).insert(any(AssetInspectionRecord.class));
        verify(assetMapper, never()).updateById(any(AssetItem.class));
        verify(lifecycleEventMapper, never()).insert(any(AssetLifecycleEvent.class));
        verify(businessEventService, never()).record(any(BusinessEventRecordRequest.class));
    }

    @Test
    void recordInspectionWithRepairRequiredChangesAssetStatusToInspectionDue() {
        AssetItem asset = asset("IN_USE");
        when(assetMapper.selectById(7L)).thenReturn(asset);
        // A26-P2-13：recordInspection REPAIR_REQUIRED 分支用 CAS 更新，需 mock update 返回 1 行。
        when(assetMapper.update(any(AssetItem.class), any())).thenReturn(1);
        when(inspectionMapper.insert(any(AssetInspectionRecord.class))).thenAnswer(invocation -> {
            AssetInspectionRecord record = invocation.getArgument(0);
            record.id = 60L;
            return 1;
        });

        AssetInspectionRecord record = service.recordInspection(7L, inspectionRequest("REPAIR_REQUIRED"));

        assertThat(record.inspectionNo).startsWith("ASSET_INSPECTION");
        assertThat(record.inspectionResult).isEqualTo("REPAIR_REQUIRED");
        assertThat(asset.status).isEqualTo("INSPECTION_DUE");
        verify(venueAccessService).requireVenueManageAccess(20L);
        verify(inspectionMapper).insert(record);
        // A26-P2-13：recordInspection 改用 requireAssetForUpdate + CAS UpdateWrapper。
        verify(assetMapper).selectByIdForUpdate(7L);
        verify(assetMapper).update(eq(asset), any());

        AssetLifecycleEvent lifecycleEvent = capturedLifecycleEvent();
        assertThat(lifecycleEvent.eventType).isEqualTo("INSPECTION");
        assertThat(lifecycleEvent.inspectionRecordId).isEqualTo(60L);
        assertThat(lifecycleEvent.toStatus).isEqualTo("INSPECTION_DUE");

        BusinessEventRecordRequest event = capturedBusinessEvent();
        assertThat(event.eventType).isEqualTo(BusinessEventType.ASSET_INSPECTION_RECORDED);
        assertThat(event.entityType).isEqualTo("ASSET_INSPECTION");
        assertThat(event.entityId).isEqualTo(60L);
        assertThat(event.idempotencyKey).isEqualTo("ASSET_INSPECTION_RECORDED:asset/inspection/60");
    }

    @Test
    void requestRepairForSpaceAssetCreatesUnavailableBlockThroughInventoryService() {
        AssetItem asset = asset("IN_USE");
        asset.spaceId = 20L;
        when(assetMapper.selectById(7L)).thenReturn(asset);
        when(repairMapper.insert(any(AssetRepairRecord.class))).thenAnswer(invocation -> {
            AssetRepairRecord repair = invocation.getArgument(0);
            repair.id = 70L;
            return 1;
        });
        VenueUnavailableBlock block = new VenueUnavailableBlock();
        block.id = 300L;
        when(inventoryService.createBlock(any(SaveUnavailableBlockRequest.class))).thenReturn(block);

        AssetRepairRecord repair = service.requestRepair(7L, repairRequestWithBlockWindow());

        assertThat(repair.repairNo).startsWith("ASSET_REPAIR");
        assertThat(repair.status).isEqualTo("REQUESTED");
        assertThat(repair.unavailableBlockId).isEqualTo(300L);
        assertThat(asset.status).isEqualTo("REPAIRING");
        assertThat(asset.currentUnavailableBlockId).isEqualTo(300L);
        verify(venueAccessService).requireVenueManageAccess(20L);
        verify(repairMapper).insert(repair);
        verify(repairMapper).updateById(repair);
        verify(assetMapper).updateById(asset);
        verify(inventoryService).createBlock(argThat(req ->
            "MAINTENANCE".equals(req.blockType)
                && "ASSET_REPAIR".equals(req.reasonCode)
                && "ASSET_REPAIR".equals(req.relatedEntityType)
                && Long.valueOf(70L).equals(req.relatedEntityId)
                && "ASSET_REPAIR_BLOCK:70".equals(req.idempotencyKey)
                && Long.valueOf(20L).equals(req.spaceId)));

        List<BusinessEventRecordRequest> events = capturedBusinessEvents(2);
        assertThat(events).extracting(event -> event.eventType)
            .contains(BusinessEventType.ASSET_REPAIR_RECORDED, BusinessEventType.ASSET_UNAVAILABLE_LINKED);
        assertThat(events).extracting(event -> event.idempotencyKey)
            .containsExactly(
                "ASSET_REPAIR_RECORDED:asset/repair/70/requested",
                "ASSET_UNAVAILABLE_LINKED:asset/repair/70/unavailable-linked");

        List<AssetLifecycleEvent> lifecycleEvents = capturedLifecycleEvents(2);
        assertThat(lifecycleEvents.get(0).eventType).isEqualTo("REPAIR_REQUESTED");
        assertThat(lifecycleEvents.get(0).fromStatus).isEqualTo("IN_USE");
        assertThat(lifecycleEvents.get(0).toStatus).isEqualTo("REPAIRING");
        assertThat(lifecycleEvents.get(1).fromStatus).isEqualTo("IN_USE");
        assertThat(lifecycleEvents.get(1).toStatus).isEqualTo("REPAIRING");
    }

    @Test
    void requestRepairRejectsWhenAssetAlreadyHasOpenRepair() {
        AssetItem asset = asset("REPAIRING");
        when(assetMapper.selectById(7L)).thenReturn(asset);
        when(repairMapper.selectCount(any())).thenReturn(1L);

        assertThatThrownBy(() -> service.requestRepair(7L, repairRequestWithoutBlock()))
            .isInstanceOf(BusinessException.class)
            .hasMessage("资产已有开放维修");

        verify(assetMapper).selectByIdForUpdate(7L);
        verify(repairMapper, never()).insert(any(AssetRepairRecord.class));
    }

    @Test
    void changeStatusToOutOfServiceWithBlockWindowCreatesUnavailableBlockAndLinksLifecycleEvent() throws Exception {
        AssetItem asset = asset("IN_USE");
        asset.spaceId = 20L;
        when(assetMapper.selectById(7L)).thenReturn(asset);
        // A26-P2-13：changeStatus 用 CAS 更新，需 mock update 返回 1 行。
        when(assetMapper.update(any(AssetItem.class), any())).thenReturn(1);
        VenueUnavailableBlock block = new VenueUnavailableBlock();
        block.id = 301L;
        when(inventoryService.createBlock(any(SaveUnavailableBlockRequest.class))).thenReturn(block);
        AssetStatusRequest request = statusRequest("OUT_OF_SERVICE", "ASSET_REPAIR", "灯光故障停用");
        setStatusRequestField(request, "blockStartTime", LocalDateTime.of(2026, 7, 7, 10, 0));
        setStatusRequestField(request, "blockEndTime", LocalDateTime.of(2026, 7, 7, 12, 0));

        AssetItem result = service.changeStatus(7L, request);

        assertThat(result.status).isEqualTo("OUT_OF_SERVICE");
        assertThat(result.currentUnavailableBlockId).isEqualTo(301L);
        verify(inventoryService).createBlock(argThat(blockRequest ->
            "MAINTENANCE".equals(blockRequest.blockType)
                && "ASSET_REPAIR".equals(blockRequest.reasonCode)
                && "ASSET_STATUS".equals(blockRequest.relatedEntityType)
                && Long.valueOf(7L).equals(blockRequest.relatedEntityId)
                && "ASSET_STATUS_BLOCK:7:OUT_OF_SERVICE".equals(blockRequest.idempotencyKey)));

        AssetLifecycleEvent lifecycleEvent = capturedLifecycleEvent();
        assertThat(lifecycleEvent.fromStatus).isEqualTo("IN_USE");
        assertThat(lifecycleEvent.toStatus).isEqualTo("OUT_OF_SERVICE");
        assertThat(lifecycleEvent.unavailableBlockId).isEqualTo(301L);
    }

    @Test
    void changeStatusRejectsPartialUnavailableBlockWindow() throws Exception {
        AssetItem asset = asset("IN_USE");
        asset.spaceId = 20L;
        when(assetMapper.selectById(7L)).thenReturn(asset);
        AssetStatusRequest request = statusRequest("OUT_OF_SERVICE", "ASSET_REPAIR", "灯光故障停用");
        setStatusRequestField(request, "blockStartTime", LocalDateTime.of(2026, 7, 7, 10, 0));

        assertThatThrownBy(() -> service.changeStatus(7L, request))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("不可售开始和结束时间不能为空");

        verify(inventoryService, never()).createBlock(any(SaveUnavailableBlockRequest.class));
        verify(assetMapper, never()).updateById(any(AssetItem.class));
    }

    @Test
    void changeStatusRejectsUnavailableBlockForAssetWithoutSpace() throws Exception {
        AssetItem asset = asset("IN_USE");
        asset.spaceId = null;
        when(assetMapper.selectById(7L)).thenReturn(asset);
        AssetStatusRequest request = statusRequest("OUT_OF_SERVICE", "ASSET_REPAIR", "灯光故障停用");
        setStatusRequestField(request, "blockStartTime", LocalDateTime.of(2026, 7, 7, 10, 0));
        setStatusRequestField(request, "blockEndTime", LocalDateTime.of(2026, 7, 7, 12, 0));

        assertThatThrownBy(() -> service.changeStatus(7L, request))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("未绑定场地的资产不能创建不可售联动");

        verify(inventoryService, never()).createBlock(any(SaveUnavailableBlockRequest.class));
        verify(assetMapper, never()).updateById(any(AssetItem.class));
    }

    @Test
    void retiredAssetCannotRequestRepair() {
        AssetItem asset = asset("RETIRED");
        when(assetMapper.selectById(7L)).thenReturn(asset);

        assertThatThrownBy(() -> service.requestRepair(7L, repairRequestWithoutBlock()))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("已报废资产不能变更状态");

        verify(venueAccessService).requireVenueManageAccess(20L);
        verify(repairMapper, never()).insert(any(AssetRepairRecord.class));
        verify(assetMapper, never()).updateById(any(AssetItem.class));
        verify(inventoryService, never()).createBlock(any(SaveUnavailableBlockRequest.class));
        verify(lifecycleEventMapper, never()).insert(any(AssetLifecycleEvent.class));
        verify(businessEventService, never()).record(any(BusinessEventRecordRequest.class));
    }

    @Test
    void startRepairMovesRequestedRepairToInProgress() {
        AssetRepairRecord repair = repair("REQUESTED");
        when(repairMapper.selectById(70L)).thenReturn(repair);
        when(assetMapper.selectById(7L)).thenReturn(asset("REPAIRING"));
        // A26-P2-14：startRepair 用 CAS 更新，需 mock update 返回 1 行。
        when(repairMapper.update(any(AssetRepairRecord.class), any())).thenReturn(1);

        AssetRepairRecord result = service.startRepair(70L);

        assertThat(result.status).isEqualTo("IN_PROGRESS");
        verify(venueAccessService).requireVenueManageAccess(20L);
        // A26-P2-14：startRepair 改用 requireRepairForUpdate + requireAssetForUpdate + CAS UpdateWrapper。
        verify(repairMapper).selectByIdForUpdate(70L);
        verify(assetMapper).selectByIdForUpdate(7L);
        verify(repairMapper).update(eq(repair), any());

        AssetLifecycleEvent lifecycleEvent = capturedLifecycleEvent();
        assertThat(lifecycleEvent.eventType).isEqualTo("REPAIR_STARTED");
        assertThat(lifecycleEvent.fromStatus).isEqualTo("REPAIRING");
        assertThat(lifecycleEvent.toStatus).isEqualTo("REPAIRING");
        assertThat(lifecycleEvent.repairRecordId).isEqualTo(70L);

        BusinessEventRecordRequest event = capturedBusinessEvent();
        assertThat(event.eventType).isEqualTo(BusinessEventType.ASSET_REPAIR_RECORDED);
        assertThat(event.fromStatus).isEqualTo("REQUESTED");
        assertThat(event.toStatus).isEqualTo("IN_PROGRESS");
        assertThat(event.idempotencyKey).isEqualTo("ASSET_REPAIR_RECORDED:asset/repair/70/in-progress");
    }

    @Test
    void completeRepairSetsAssetInUseAndCancelsUnavailableBlockWhenRequested() {
        AssetRepairRecord repair = repair("IN_PROGRESS");
        repair.unavailableBlockId = 300L;
        repair.downtimeStart = LocalDateTime.of(2026, 7, 7, 10, 0);
        AssetItem asset = asset("REPAIRING");
        asset.currentUnavailableBlockId = 300L;
        when(repairMapper.selectById(70L)).thenReturn(repair);
        when(assetMapper.selectById(7L)).thenReturn(asset);
        AssetRepairCompleteRequest request = new AssetRepairCompleteRequest();
        request.actualCostAmount = new BigDecimal("680.50");
        request.resolution = "更换球网并复检通过";
        request.cancelUnavailableBlock = true;

        AssetRepairRecord result = service.completeRepair(70L, request);

        assertThat(result.status).isEqualTo("COMPLETED");
        assertThat(result.actualCostAmount).isEqualByComparingTo("680.50");
        assertThat(result.resolution).isEqualTo("更换球网并复检通过");
        assertThat(result.downtimeEnd).isNotNull();
        assertThat(asset.status).isEqualTo("IN_USE");
        assertThat(asset.currentUnavailableBlockId).isNull();
        verify(venueAccessService).requireVenueManageAccess(20L);
        verify(repairMapper).updateById(repair);
        // A26-P2-15：completeRepair 用 UpdateWrapper.set 显式 set current_unavailable_block_id，确保置空落库。
        verify(assetMapper).update(eq(asset), any());
        verify(inventoryService).cancelBlock(300L, "资产维修完成");

        AssetLifecycleEvent lifecycleEvent = capturedLifecycleEvent();
        assertThat(lifecycleEvent.eventType).isEqualTo("REPAIR_COMPLETED");
        assertThat(lifecycleEvent.fromStatus).isEqualTo("REPAIRING");
        assertThat(lifecycleEvent.toStatus).isEqualTo("IN_USE");
        assertThat(lifecycleEvent.costAmount).isEqualByComparingTo("680.50");
        assertThat(lifecycleEvent.downtimeStart).isEqualTo(LocalDateTime.of(2026, 7, 7, 10, 0));
        assertThat(lifecycleEvent.downtimeEnd).isNotNull();

        BusinessEventRecordRequest event = capturedBusinessEvent();
        assertThat(event.eventType).isEqualTo(BusinessEventType.ASSET_REPAIR_RECORDED);
        assertThat(event.fromStatus).isEqualTo("IN_PROGRESS");
        assertThat(event.toStatus).isEqualTo("COMPLETED");
        assertThat(event.idempotencyKey).isEqualTo("ASSET_REPAIR_RECORDED:asset/repair/70/completed");
    }

    @Test
    void completeRepairKeepsAssetRepairingWhenAnotherRepairRemainsOpen() {
        AssetRepairRecord repair = repair("IN_PROGRESS");
        AssetItem asset = asset("REPAIRING");
        when(repairMapper.selectById(70L)).thenReturn(repair);
        when(assetMapper.selectById(7L)).thenReturn(asset);
        when(repairMapper.selectCount(any())).thenReturn(1L);

        service.completeRepair(70L, repairCompleteRequest());

        assertThat(asset.status).isEqualTo("REPAIRING");
        // A26-P2-15：completeRepair 用 UpdateWrapper.set 更新 asset（含 current_unavailable_block_id）。
        verify(assetMapper).update(eq(asset), any());
        AssetLifecycleEvent lifecycleEvent = capturedLifecycleEvent();
        assertThat(lifecycleEvent.toStatus).isEqualTo("REPAIRING");
    }

    @Test
    void completeRepairRequiresRepairInProgress() {
        AssetRepairRecord repair = repair("REQUESTED");
        when(repairMapper.selectById(70L)).thenReturn(repair);
        lenient().when(assetMapper.selectById(7L)).thenReturn(asset("REPAIRING"));

        assertThatThrownBy(() -> service.completeRepair(70L, repairCompleteRequest()))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("当前维修状态不可完成");

        verify(repairMapper, never()).updateById(any(AssetRepairRecord.class));
        verify(assetMapper, never()).updateById(any(AssetItem.class));
        verify(lifecycleEventMapper, never()).insert(any(AssetLifecycleEvent.class));
        verify(businessEventService, never()).record(any(BusinessEventRecordRequest.class));
    }

    @Test
    void completeRepairChecksVenueAccessBeforeRequestAndStatusValidation() {
        AssetRepairRecord repair = repair("REQUESTED");
        when(repairMapper.selectById(70L)).thenReturn(repair);
        lenient().doThrow(BusinessException.forbidden("无权管理该场馆数据"))
            .when(venueAccessService).requireVenueManageAccess(20L);

        assertThatThrownBy(() -> service.completeRepair(70L, null))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("无权管理该场馆数据");

        verify(venueAccessService).requireVenueManageAccess(20L);
        verify(assetMapper, never()).selectById(any());
        verify(repairMapper, never()).updateById(any(AssetRepairRecord.class));
        verify(assetMapper, never()).updateById(any(AssetItem.class));
        verify(inventoryService, never()).cancelBlock(any(), any());
        verify(lifecycleEventMapper, never()).insert(any(AssetLifecycleEvent.class));
        verify(businessEventService, never()).record(any(BusinessEventRecordRequest.class));
    }

    @Test
    void retiredAssetCannotCompleteRepairToInUse() {
        AssetRepairRecord repair = repair("IN_PROGRESS");
        when(repairMapper.selectById(70L)).thenReturn(repair);
        AssetItem asset = asset("RETIRED");
        when(assetMapper.selectById(7L)).thenReturn(asset);

        assertThatThrownBy(() -> service.completeRepair(70L, repairCompleteRequest()))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("已报废资产不能变更状态");

        verify(venueAccessService).requireVenueManageAccess(20L);
        verify(repairMapper, never()).updateById(any(AssetRepairRecord.class));
        verify(assetMapper, never()).updateById(any(AssetItem.class));
        verify(inventoryService, never()).cancelBlock(any(), any());
        verify(lifecycleEventMapper, never()).insert(any(AssetLifecycleEvent.class));
        verify(businessEventService, never()).record(any(BusinessEventRecordRequest.class));
    }

    @Test
    void completeRepairDoesNotClearDifferentCurrentUnavailableBlock() {
        AssetRepairRecord repair = repair("IN_PROGRESS");
        repair.unavailableBlockId = 300L;
        AssetItem asset = asset("REPAIRING");
        asset.currentUnavailableBlockId = 301L;
        when(repairMapper.selectById(70L)).thenReturn(repair);
        when(assetMapper.selectById(7L)).thenReturn(asset);
        AssetRepairCompleteRequest request = repairCompleteRequest();
        request.cancelUnavailableBlock = true;

        service.completeRepair(70L, request);

        assertThat(asset.status).isEqualTo("IN_USE");
        assertThat(asset.currentUnavailableBlockId).isEqualTo(301L);
        verify(inventoryService).cancelBlock(300L, "资产维修完成");
        // A26-P2-15：completeRepair 用 UpdateWrapper.set 更新 asset（current_unavailable_block_id=301L 保留）。
        verify(assetMapper).update(eq(asset), any());
    }

    @Test
    void cancelRepairMovesOpenRepairToCanceled() {
        AssetRepairRecord repair = repair("REQUESTED");
        repair.unavailableBlockId = 300L;
        AssetItem asset = asset("REPAIRING");
        asset.currentUnavailableBlockId = 300L;
        when(repairMapper.selectById(70L)).thenReturn(repair);
        when(assetMapper.selectById(7L)).thenReturn(asset);
        AssetRepairCancelRequest request = new AssetRepairCancelRequest();
        request.reason = "重复报修";
        request.cancelUnavailableBlock = true;

        AssetRepairRecord result = service.cancelRepair(70L, request);

        assertThat(result.status).isEqualTo("CANCELED");
        assertThat(result.resolution).isEqualTo("重复报修");
        assertThat(asset.status).isEqualTo("IN_USE");
        assertThat(asset.currentUnavailableBlockId).isNull();
        verify(venueAccessService).requireVenueManageAccess(20L);
        verify(inventoryService).cancelBlock(300L, "资产维修取消");
        verify(repairMapper).updateById(repair);
        // A26-P2-15：cancelRepair 用 UpdateWrapper.set 显式 set current_unavailable_block_id，确保置空落库。
        verify(assetMapper).update(eq(asset), any());

        AssetLifecycleEvent lifecycleEvent = capturedLifecycleEvent();
        assertThat(lifecycleEvent.eventType).isEqualTo("REPAIR_CANCELED");
        assertThat(lifecycleEvent.fromStatus).isEqualTo("REPAIRING");
        assertThat(lifecycleEvent.toStatus).isEqualTo("IN_USE");
    }

    // AUD-P2-02: 维修单已处于终态时，完成/取消必须拒绝并发覆盖

    @Test
    void completeRepairRejectsCompletedRepairAsTerminalState() {
        AssetRepairRecord repair = repair("COMPLETED");
        when(repairMapper.selectByIdForUpdate(70L)).thenReturn(repair);

        assertThatThrownBy(() -> service.completeRepair(70L, repairCompleteRequest()))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("维修单已处于终态，不可完成");

        verify(repairMapper, org.mockito.Mockito.times(2)).selectByIdForUpdate(70L);
        verify(assetMapper, never()).selectByIdForUpdate(any());
        verify(repairMapper, never()).updateById(any(AssetRepairRecord.class));
        verify(assetMapper, never()).updateById(any(AssetItem.class));
        verify(inventoryService, never()).cancelBlock(any(), any());
        verify(lifecycleEventMapper, never()).insert(any(AssetLifecycleEvent.class));
        verify(businessEventService, never()).record(any(BusinessEventRecordRequest.class));
    }

    @Test
    void completeRepairRejectsCanceledRepairAsTerminalState() {
        AssetRepairRecord repair = repair("CANCELED");
        when(repairMapper.selectByIdForUpdate(70L)).thenReturn(repair);

        assertThatThrownBy(() -> service.completeRepair(70L, repairCompleteRequest()))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("维修单已处于终态，不可完成");

        verify(repairMapper, org.mockito.Mockito.times(2)).selectByIdForUpdate(70L);
        verify(assetMapper, never()).selectByIdForUpdate(any());
        verify(repairMapper, never()).updateById(any(AssetRepairRecord.class));
        verify(assetMapper, never()).updateById(any(AssetItem.class));
        verify(lifecycleEventMapper, never()).insert(any(AssetLifecycleEvent.class));
        verify(businessEventService, never()).record(any(BusinessEventRecordRequest.class));
    }

    @Test
    void cancelRepairRejectsCompletedRepairAsTerminalState() {
        AssetRepairRecord repair = repair("COMPLETED");
        when(repairMapper.selectByIdForUpdate(70L)).thenReturn(repair);
        AssetRepairCancelRequest request = new AssetRepairCancelRequest();
        request.reason = "误操作";

        assertThatThrownBy(() -> service.cancelRepair(70L, request))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("维修单已处于终态，不可取消");

        verify(repairMapper, org.mockito.Mockito.times(2)).selectByIdForUpdate(70L);
        verify(assetMapper, never()).selectByIdForUpdate(any());
        verify(repairMapper, never()).updateById(any(AssetRepairRecord.class));
        verify(assetMapper, never()).updateById(any(AssetItem.class));
        verify(inventoryService, never()).cancelBlock(any(), any());
        verify(lifecycleEventMapper, never()).insert(any(AssetLifecycleEvent.class));
        verify(businessEventService, never()).record(any(BusinessEventRecordRequest.class));
    }

    @Test
    void cancelRepairRejectsCanceledRepairAsTerminalState() {
        AssetRepairRecord repair = repair("CANCELED");
        when(repairMapper.selectByIdForUpdate(70L)).thenReturn(repair);

        assertThatThrownBy(() -> service.cancelRepair(70L, new AssetRepairCancelRequest()))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("维修单已处于终态，不可取消");

        verify(repairMapper, org.mockito.Mockito.times(2)).selectByIdForUpdate(70L);
        verify(assetMapper, never()).selectByIdForUpdate(any());
        verify(repairMapper, never()).updateById(any(AssetRepairRecord.class));
        verify(assetMapper, never()).updateById(any(AssetItem.class));
        verify(lifecycleEventMapper, never()).insert(any(AssetLifecycleEvent.class));
        verify(businessEventService, never()).record(any(BusinessEventRecordRequest.class));
    }

    @Test
    void assetContextUsesManageableVenueScopeAndReturnsDeterministicCounts() {
        AssetQuery query = new AssetQuery();
        when(venueAccessService.manageableVenueIds()).thenReturn(List.of(20L));
        when(assetMapper.selectCount(any())).thenReturn(5L, 2L, 1L, 1L, 1L, 0L, 0L);
        when(inspectionMapper.selectCount(any())).thenReturn(3L);
        when(repairMapper.selectCount(any())).thenReturn(2L, 1L);

        Map<String, Object> context = service.assetContext(query);

        assertThat(context).containsEntry("totalAssets", 5L);
        assertThat(context).containsEntry("inspectionCount", 3L);
        assertThat(context).containsEntry("openRepairCount", 2L);
        assertThat(context).containsEntry("completedRepairCount", 1L);
        assertThat(context).containsKey("assetsByStatus");
        assertThat(context).containsKey("dataGaps");
        verify(venueAccessService).manageableVenueIds();
        verify(assetMapper, org.mockito.Mockito.times(7)).selectCount(any());
        verify(inspectionMapper).selectCount(any());
        verify(repairMapper, org.mockito.Mockito.times(2)).selectCount(any());
    }

    // A26-P2-13：changeStatus CAS 返回 0 行时抛 conflict，拒绝并发覆盖 RETIRED 终态。
    @Test
    void changeStatusRejectsConcurrentStatusChange() {
        AssetItem asset = asset("IN_USE");
        when(assetMapper.selectById(7L)).thenReturn(asset);
        // CAS 更新返回 0 行，模拟另一事务已把 status 从 IN_USE 改为 RETIRED。
        when(assetMapper.update(any(AssetItem.class), any())).thenReturn(0);
        AssetStatusRequest request = statusRequest("OUT_OF_SERVICE", "BROKEN", "故障停用");

        assertThatThrownBy(() -> service.changeStatus(7L, request))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("资产状态已变更");

        verify(venueAccessService).requireVenueManageAccess(20L);
        verify(assetMapper).selectByIdForUpdate(7L);
        verify(lifecycleEventMapper, never()).insert(any(AssetLifecycleEvent.class));
        verify(businessEventService, never()).record(any(BusinessEventRecordRequest.class));
    }

    // A26-P2-13：recordInspection REPAIR_REQUIRED 分支 CAS 返回 0 行时抛 conflict。
    @Test
    void recordInspectionRejectsConcurrentStatusChangeWhenRepairRequired() {
        AssetItem asset = asset("IN_USE");
        when(assetMapper.selectById(7L)).thenReturn(asset);
        // CAS 更新返回 0 行，模拟另一事务已把 status 从 IN_USE 改为 RETIRED。
        when(assetMapper.update(any(AssetItem.class), any())).thenReturn(0);

        assertThatThrownBy(() -> service.recordInspection(7L, inspectionRequest("REPAIR_REQUIRED")))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("资产状态已变更");

        verify(venueAccessService).requireVenueManageAccess(20L);
        verify(assetMapper).selectByIdForUpdate(7L);
        // inspection 记录已插入（在 CAS 之前），但 asset 状态未变更，lifecycle event 未记录。
        verify(lifecycleEventMapper, never()).insert(any(AssetLifecycleEvent.class));
        verify(businessEventService, never()).record(any(BusinessEventRecordRequest.class));
    }

    // A26-P2-14：startRepair 拒绝终态维修单（COMPLETED/CANCELED 不可改 IN_PROGRESS）。
    @Test
    void startRepairRejectsCompletedRepairAsTerminalState() {
        AssetRepairRecord repair = repair("COMPLETED");
        when(repairMapper.selectByIdForUpdate(70L)).thenReturn(repair);

        assertThatThrownBy(() -> service.startRepair(70L))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("维修单已处于终态，不可开始");

        // selectByIdForUpdate 被调 2 次：1 次 when(stub) 设置 + 1 次 requireRepairForUpdate 实际调用（与
        // completeRepairRejectsCompletedRepairAsTerminalState 的 times(2) 一致）。
        verify(repairMapper, org.mockito.Mockito.times(2)).selectByIdForUpdate(70L);
        verify(assetMapper, never()).selectByIdForUpdate(any());
        verify(repairMapper, never()).update(any(AssetRepairRecord.class), any());
        verify(lifecycleEventMapper, never()).insert(any(AssetLifecycleEvent.class));
        verify(businessEventService, never()).record(any(BusinessEventRecordRequest.class));
    }

    // A26-P2-14：startRepair CAS 返回 0 行时抛 conflict，拒绝并发把 CANCELED 改回 IN_PROGRESS。
    @Test
    void startRepairRejectsConcurrentStatusChange() {
        AssetRepairRecord repair = repair("REQUESTED");
        when(repairMapper.selectById(70L)).thenReturn(repair);
        when(assetMapper.selectById(7L)).thenReturn(asset("REPAIRING"));
        // CAS 更新返回 0 行，模拟另一事务已把 status 从 REQUESTED 改为 CANCELED。
        when(repairMapper.update(any(AssetRepairRecord.class), any())).thenReturn(0);

        assertThatThrownBy(() -> service.startRepair(70L))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("维修状态已变更");

        verify(repairMapper).selectByIdForUpdate(70L);
        verify(repairMapper, never()).updateById(any(AssetRepairRecord.class));
        verify(lifecycleEventMapper, never()).insert(any(AssetLifecycleEvent.class));
        verify(businessEventService, never()).record(any(BusinessEventRecordRequest.class));
    }

    // A26-P2-15：completeRepair 置空 current_unavailable_block_id 时通过 UpdateWrapper.set 显式落库。
    @SuppressWarnings({"unchecked", "rawtypes"})
    @Test
    void completeRepairClearsCurrentUnavailableBlockIdViaUpdateWrapper() {
        AssetRepairRecord repair = repair("IN_PROGRESS");
        repair.unavailableBlockId = 300L;
        AssetItem asset = asset("REPAIRING");
        asset.currentUnavailableBlockId = 300L;
        when(repairMapper.selectById(70L)).thenReturn(repair);
        when(assetMapper.selectById(7L)).thenReturn(asset);
        AssetRepairCompleteRequest request = new AssetRepairCompleteRequest();
        request.actualCostAmount = new BigDecimal("100.00");
        request.resolution = "修复完成";
        request.cancelUnavailableBlock = true;

        service.completeRepair(70L, request);

        assertThat(asset.currentUnavailableBlockId).isNull();
        ArgumentCaptor<UpdateWrapper> wrapperCaptor = ArgumentCaptor.forClass(UpdateWrapper.class);
        verify(assetMapper).update(eq(asset), wrapperCaptor.capture());
        // UpdateWrapper 的 set SQL 应包含 current_unavailable_block_id，确保 null 被显式写入 SET 子句。
        assertThat(wrapperCaptor.getValue().getSqlSet()).contains("current_unavailable_block_id");
    }

    // A26-P2-15：cancelRepair 置空 current_unavailable_block_id 时通过 UpdateWrapper.set 显式落库。
    @SuppressWarnings({"unchecked", "rawtypes"})
    @Test
    void cancelRepairClearsCurrentUnavailableBlockIdViaUpdateWrapper() {
        AssetRepairRecord repair = repair("REQUESTED");
        repair.unavailableBlockId = 300L;
        AssetItem asset = asset("REPAIRING");
        asset.currentUnavailableBlockId = 300L;
        when(repairMapper.selectById(70L)).thenReturn(repair);
        when(assetMapper.selectById(7L)).thenReturn(asset);
        AssetRepairCancelRequest request = new AssetRepairCancelRequest();
        request.reason = "误操作";
        request.cancelUnavailableBlock = true;

        service.cancelRepair(70L, request);

        assertThat(asset.currentUnavailableBlockId).isNull();
        ArgumentCaptor<UpdateWrapper> wrapperCaptor = ArgumentCaptor.forClass(UpdateWrapper.class);
        verify(assetMapper).update(eq(asset), wrapperCaptor.capture());
        // UpdateWrapper 的 set SQL 应包含 current_unavailable_block_id，确保 null 被显式写入 SET 子句。
        assertThat(wrapperCaptor.getValue().getSqlSet()).contains("current_unavailable_block_id");
    }

    // A26-P2-16：updateAsset 把 spaceId 置 null 时通过 UpdateWrapper.set 显式落库。
    @SuppressWarnings({"unchecked", "rawtypes"})
    @Test
    void updateAssetPersistsNullSpaceIdViaUpdateWrapper() {
        AssetItem asset = asset("IN_USE");
        asset.spaceId = 11L; // 原先绑定场地
        when(assetMapper.selectById(7L)).thenReturn(asset);
        AssetRequest request = assetRequest();
        request.spaceId = null; // 解绑场地

        AssetItem result = service.updateAsset(7L, request);

        assertThat(result.spaceId).isNull();
        ArgumentCaptor<UpdateWrapper> wrapperCaptor = ArgumentCaptor.forClass(UpdateWrapper.class);
        verify(assetMapper).update(eq(asset), wrapperCaptor.capture());
        // UpdateWrapper 的 set SQL 应包含 space_id，确保 null 被显式写入 SET 子句。
        assertThat(wrapperCaptor.getValue().getSqlSet()).contains("space_id");
    }

    // A26-P3-17：ASSET_UNAVAILABLE_LINKED 幂等键含 block.id 唯一因子，
    // 第二次同状态联动产生新 block 时不再被同幂等键吞掉，追加式证据完整保留。
    @Test
    void changeStatusWithUnavailableBlockIdempotencyKeyIncludesBlockId() throws Exception {
        AssetItem asset = asset("IN_USE");
        asset.spaceId = 20L;
        when(assetMapper.selectById(7L)).thenReturn(asset);
        when(assetMapper.update(any(AssetItem.class), any())).thenReturn(1);
        VenueUnavailableBlock firstBlock = new VenueUnavailableBlock();
        firstBlock.id = 301L;
        VenueUnavailableBlock secondBlock = new VenueUnavailableBlock();
        secondBlock.id = 302L;
        when(inventoryService.createBlock(any(SaveUnavailableBlockRequest.class)))
            .thenReturn(firstBlock, secondBlock);

        AssetStatusRequest firstReq = statusRequest("OUT_OF_SERVICE", "ASSET_REPAIR", "灯光故障停用");
        setStatusRequestField(firstReq, "blockStartTime", LocalDateTime.of(2026, 7, 7, 10, 0));
        setStatusRequestField(firstReq, "blockEndTime", LocalDateTime.of(2026, 7, 7, 12, 0));
        service.changeStatus(7L, firstReq);

        // 资产恢复使用后再次停用，产生第二个 block——幂等键必须不同，否则第二次联动证据被吞。
        AssetStatusRequest recoverReq = statusRequest("IN_USE", "FIXED", "修复完成");
        service.changeStatus(7L, recoverReq);

        AssetStatusRequest secondReq = statusRequest("OUT_OF_SERVICE", "ASSET_REPAIR", "再次停用");
        setStatusRequestField(secondReq, "blockStartTime", LocalDateTime.of(2026, 7, 8, 10, 0));
        setStatusRequestField(secondReq, "blockEndTime", LocalDateTime.of(2026, 7, 8, 12, 0));
        service.changeStatus(7L, secondReq);

        // 三次 changeStatus 中两次产生 block：共 5 次 businessEvent.record
        // （3 次主事件 + 2 次 ASSET_UNAVAILABLE_LINKED）。
        List<BusinessEventRecordRequest> events = capturedBusinessEvents(5);
        List<BusinessEventRecordRequest> blockEvents = events.stream()
            .filter(e -> e.eventType == BusinessEventType.ASSET_UNAVAILABLE_LINKED).toList();
        assertThat(blockEvents).hasSize(2);
        assertThat(blockEvents.get(0).idempotencyKey)
            .isEqualTo("ASSET_UNAVAILABLE_LINKED:asset/status/7/OUT_OF_SERVICE/unavailable-linked/block/301");
        assertThat(blockEvents.get(1).idempotencyKey)
            .isEqualTo("ASSET_UNAVAILABLE_LINKED:asset/status/7/OUT_OF_SERVICE/unavailable-linked/block/302");
        assertThat(blockEvents.get(0).idempotencyKey).isNotEqualTo(blockEvents.get(1).idempotencyKey);
    }

    // A26-P3-20：createAsset 拒绝 responsibleUserId 不属于该场馆。
    @Test
    void createAssetRejectsResponsibleUserNotBelongingToVenue() {
        AssetRequest request = assetRequest();
        when(venueAdminRelationMapper.countRelation(9L, 20L)).thenReturn(0);

        assertThatThrownBy(() -> service.createAsset(request))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("指派人员不属于该场馆");

        verify(venueAccessService).requireVenueManageAccess(20L);
        verify(entityExistenceService).requireActiveUser(9L);
        verify(assetMapper, never()).insert(any(AssetItem.class));
        verify(lifecycleEventMapper, never()).insert(any(AssetLifecycleEvent.class));
        verify(businessEventService, never()).record(any(BusinessEventRecordRequest.class));
    }

    // A26-P3-20：createAsset 拒绝 responsibleUserId 已禁用。
    @Test
    void createAssetRejectsResponsibleUserDisabled() {
        AssetRequest request = assetRequest();
        org.mockito.Mockito.doThrow(BusinessException.badRequest("用户已禁用"))
            .when(entityExistenceService).requireActiveUser(9L);

        assertThatThrownBy(() -> service.createAsset(request))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("用户已禁用");

        verify(venueAccessService).requireVenueManageAccess(20L);
        verify(venueAdminRelationMapper, never()).countRelation(any(), any());
        verify(assetMapper, never()).insert(any(AssetItem.class));
    }

    // A26-P3-20：createAsset 允许 responsibleUserId 为 null（资产不指定负责人），null 时不校验。
    @Test
    void createAssetAllowsNullResponsibleUserIdWithoutValidation() {
        when(assetMapper.insert(any(AssetItem.class))).thenAnswer(invocation -> {
            AssetItem asset = invocation.getArgument(0);
            asset.id = 7L;
            return 1;
        });
        AssetRequest request = assetRequest();
        request.responsibleUserId = null;

        AssetItem asset = service.createAsset(request);

        assertThat(asset.responsibleUserId).isNull();
        verify(entityExistenceService, never()).requireActiveUser(any());
        verify(venueAdminRelationMapper, never()).countRelation(any(), any());
    }

    // A26-P3-20：updateAsset 校验 responsibleUserId 场馆归属，跨场馆移动时按目标场馆校验。
    @Test
    void updateAssetRejectsResponsibleUserNotBelongingToTargetVenue() {
        AssetItem asset = asset("IN_USE");
        when(assetMapper.selectById(7L)).thenReturn(asset);
        AssetRequest request = assetRequest();
        request.venueId = 21L; // 跨场馆移动
        request.responsibleUserId = 12L;
        when(venueAdminRelationMapper.countRelation(12L, 21L)).thenReturn(0);

        assertThatThrownBy(() -> service.updateAsset(7L, request))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("指派人员不属于该场馆");

        verify(venueAccessService).requireVenueManageAccess(21L);
        verify(entityExistenceService).requireActiveUser(12L);
        verify(assetMapper, never()).update(any(AssetItem.class), any());
    }

    // A26-P3-20：recordInspection 拒绝 inspectorUserId 不属于资产所属场馆。
    @Test
    void recordInspectionRejectsInspectorNotBelongingToAssetVenue() {
        AssetItem asset = asset("IN_USE");
        when(assetMapper.selectById(7L)).thenReturn(asset);
        AssetInspectionRequest request = inspectionRequest("OK");
        when(venueAdminRelationMapper.countRelation(8L, 20L)).thenReturn(0);

        assertThatThrownBy(() -> service.recordInspection(7L, request))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("指派人员不属于该场馆");

        verify(venueAccessService).requireVenueManageAccess(20L);
        verify(entityExistenceService).requireActiveUser(8L);
        verify(inspectionMapper, never()).insert(any(AssetInspectionRecord.class));
    }

    // A26-P3-20：recordInspection 允许 inspectorUserId 为 null，由 SecurityContext 兜底。
    @Test
    void recordInspectionAllowsNullInspectorUserIdWithoutValidation() {
        AssetItem asset = asset("IN_USE");
        when(assetMapper.selectById(7L)).thenReturn(asset);
        when(inspectionMapper.insert(any(AssetInspectionRecord.class))).thenAnswer(invocation -> {
            AssetInspectionRecord record = invocation.getArgument(0);
            record.id = 60L;
            return 1;
        });
        AssetInspectionRequest request = inspectionRequest("OK");
        request.inspectorUserId = null;

        AssetInspectionRecord record = service.recordInspection(7L, request);

        assertThat(record.inspectorUserId).isEqualTo(7L); // SecurityContext.currentUserId()
        verify(entityExistenceService, never()).requireActiveUser(any());
        verify(venueAdminRelationMapper, never()).countRelation(any(), any());
    }

    // A26-P3-20：requestRepair 拒绝 repairOwnerId 不属于资产所属场馆。
    @Test
    void requestRepairRejectsRepairOwnerNotBelongingToAssetVenue() {
        AssetItem asset = asset("IN_USE");
        when(assetMapper.selectById(7L)).thenReturn(asset);
        AssetRepairRequest request = repairRequestWithoutBlock();
        when(venueAdminRelationMapper.countRelation(9L, 20L)).thenReturn(0);

        assertThatThrownBy(() -> service.requestRepair(7L, request))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("指派人员不属于该场馆");

        verify(venueAccessService).requireVenueManageAccess(20L);
        verify(entityExistenceService).requireActiveUser(9L);
        verify(repairMapper, never()).insert(any(AssetRepairRecord.class));
    }

    // A26-P3-22：updateAsset 跨场馆移动时若有开放维修单应拒绝，避免子记录 venue_id 错位。
    @Test
    void updateAssetRejectsCrossVenueMoveWhenOpenRepairExists() {
        AssetItem asset = asset("REPAIRING");
        when(assetMapper.selectById(7L)).thenReturn(asset);
        AssetRequest request = assetRequest();
        request.venueId = 21L; // 跨场馆移动
        when(repairMapper.selectCount(any())).thenReturn(1L);

        assertThatThrownBy(() -> service.updateAsset(7L, request))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("资产有开放维修单，不可跨场馆移动");

        verify(venueAccessService).requireVenueManageAccess(21L);
        verify(assetMapper, never()).update(any(AssetItem.class), any());
    }

    // A26-P3-22：updateAsset 跨场馆移动时无开放维修单允许通过。
    @Test
    void updateAssetAllowsCrossVenueMoveWhenNoOpenRepair() {
        AssetItem asset = asset("IN_USE");
        when(assetMapper.selectById(7L)).thenReturn(asset);
        AssetRequest request = assetRequest();
        request.venueId = 21L;
        when(repairMapper.selectCount(any())).thenReturn(0L);

        AssetItem result = service.updateAsset(7L, request);

        assertThat(result.venueId).isEqualTo(21L);
        verify(venueAccessService).requireVenueManageAccess(21L);
        verify(assetMapper).update(eq(asset), any());
    }

    private AssetLifecycleEvent capturedLifecycleEvent() {
        ArgumentCaptor<AssetLifecycleEvent> eventCaptor = ArgumentCaptor.forClass(AssetLifecycleEvent.class);
        verify(lifecycleEventMapper).insert(eventCaptor.capture());
        return eventCaptor.getValue();
    }

    private List<AssetLifecycleEvent> capturedLifecycleEvents(int count) {
        ArgumentCaptor<AssetLifecycleEvent> eventCaptor = ArgumentCaptor.forClass(AssetLifecycleEvent.class);
        verify(lifecycleEventMapper, org.mockito.Mockito.times(count)).insert(eventCaptor.capture());
        return eventCaptor.getAllValues();
    }

    private BusinessEventRecordRequest capturedBusinessEvent() {
        return capturedBusinessEvents(1).get(0);
    }

    private List<BusinessEventRecordRequest> capturedBusinessEvents(int count) {
        ArgumentCaptor<BusinessEventRecordRequest> eventCaptor =
            ArgumentCaptor.forClass(BusinessEventRecordRequest.class);
        verify(businessEventService, org.mockito.Mockito.times(count)).record(eventCaptor.capture());
        return eventCaptor.getAllValues();
    }

    private AssetRequest assetRequest() {
        AssetRequest request = new AssetRequest();
        request.venueId = 20L;
        request.spaceId = 11L;
        request.assetType = "NET";
        request.name = "羽毛球网";
        request.brand = "Yonex";
        request.model = "N-2026";
        request.serialNo = "SN-20260706";
        request.supplierName = "校内供应商";
        request.purchaseDate = LocalDate.of(2026, 7, 1);
        request.purchaseAmount = new BigDecimal("1280.00");
        request.warrantyStartDate = LocalDate.of(2026, 7, 1);
        request.warrantyEndDate = LocalDate.of(2027, 7, 1);
        request.responsibleUserId = 9L;
        request.metadataJson = "{\"source\":\"manual\"}";
        return request;
    }

    private AssetItem asset(String status) {
        AssetItem asset = new AssetItem();
        asset.id = 7L;
        asset.assetNo = "ASSET20260706080000AAAAAAAA";
        asset.venueId = 20L;
        asset.spaceId = 11L;
        asset.assetType = "NET";
        asset.name = "羽毛球网";
        asset.status = status;
        asset.purchaseAmount = BigDecimal.ZERO;
        asset.deleted = 0;
        return asset;
    }

    private AssetInspectionRequest inspectionRequest(String result) {
        AssetInspectionRequest request = new AssetInspectionRequest();
        request.siteTaskId = 50L;
        request.inspectorUserId = 8L;
        request.inspectionResult = result;
        request.riskLevel = "HIGH";
        request.checklistJson = "{\"net\":\"loose\"}";
        request.issueSummary = "球网松动，需要维修";
        request.nextInspectionDueAt = LocalDateTime.of(2026, 7, 12, 9, 0);
        request.inspectedAt = LocalDateTime.of(2026, 7, 6, 9, 30);
        request.metadataJson = "{\"photo\":\"attached\"}";
        return request;
    }

    private AssetStatusRequest statusRequest(String toStatus, String reasonCode, String reasonText) {
        AssetStatusRequest request = new AssetStatusRequest();
        request.toStatus = toStatus;
        request.reasonCode = reasonCode;
        request.reasonText = reasonText;
        return request;
    }

    private void setStatusRequestField(AssetStatusRequest request, String fieldName, Object value) throws Exception {
        java.lang.reflect.Field field = AssetStatusRequest.class.getField(fieldName);
        field.set(request, value);
    }

    private AssetRepairRequest repairRequestWithBlockWindow() {
        AssetRepairRequest request = repairRequestWithoutBlock();
        request.blockStartTime = LocalDateTime.of(2026, 7, 7, 10, 0);
        request.blockEndTime = LocalDateTime.of(2026, 7, 7, 12, 0);
        return request;
    }

    private AssetRepairRequest repairRequestWithoutBlock() {
        AssetRepairRequest request = new AssetRepairRequest();
        request.siteTaskId = 50L;
        request.repairOwnerId = 9L;
        request.supplierName = "校内供应商";
        request.failureCode = "NET_BROKEN";
        request.failureDescription = "球网断裂，需暂停该场地预约";
        request.estimatedCostAmount = new BigDecimal("500.00");
        request.downtimeStart = LocalDateTime.of(2026, 7, 7, 10, 0);
        request.downtimeEnd = LocalDateTime.of(2026, 7, 7, 12, 0);
        return request;
    }

    private AssetRepairCompleteRequest repairCompleteRequest() {
        AssetRepairCompleteRequest request = new AssetRepairCompleteRequest();
        request.actualCostAmount = new BigDecimal("680.50");
        request.resolution = "更换球网并复检通过";
        return request;
    }

    private AssetRepairRecord repair(String status) {
        AssetRepairRecord repair = new AssetRepairRecord();
        repair.id = 70L;
        repair.repairNo = "ASSET_REPAIR20260706080000BBBBBBBB";
        repair.assetId = 7L;
        repair.venueId = 20L;
        repair.spaceId = 11L;
        repair.status = status;
        repair.failureDescription = "球网断裂";
        repair.estimatedCostAmount = new BigDecimal("500.00");
        repair.deleted = 0;
        return repair;
    }
}
