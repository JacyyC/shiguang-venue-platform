package com.example.venue.siteops;

import com.example.venue.common.AuditLog;
import com.example.venue.common.PageQuery;
import com.example.venue.common.PageResult;
import com.example.venue.common.Result;
import com.example.venue.siteops.controller.AssetController;
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
import com.example.venue.siteops.service.AssetLifecycleService;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.lang.reflect.Method;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AssetControllerTest {
    @Test
    void assetControllerUsesAdminRoutePrefix() {
        RequestMapping mapping = AssetController.class.getAnnotation(RequestMapping.class);

        assertThat(mapping.value()).containsExactly("/api/admin");
    }

    @Test
    void assetLedgerMutationsRequireAssetManagePermissionAndAuditLog() throws Exception {
        assertPostEndpoint("createAsset", new Class<?>[]{AssetRequest.class}, "/assets", "asset:manage", "创建设备资产");
        assertPutEndpoint("updateAsset", new Class<?>[]{Long.class, AssetRequest.class}, "/assets/{id}",
            "asset:manage", "更新设备资产");
        assertPostEndpoint("changeStatus", new Class<?>[]{Long.class, AssetStatusRequest.class}, "/assets/{id}/status",
            "asset:manage", "变更资产状态");
    }

    @Test
    void assetLedgerReadsRequireAssetManagePermission() throws Exception {
        assertReadEndpoint("assets", new Class<?>[]{AssetQuery.class}, "/assets", "asset:manage");
        assertReadEndpoint("assetEvents", new Class<?>[]{Long.class, PageQuery.class}, "/assets/{id}/events",
            "asset:manage");
    }

    @Test
    void maintenanceMutationsRequireMaintenancePermissionAndAuditLog() throws Exception {
        assertPostEndpoint("recordInspection", new Class<?>[]{Long.class, AssetInspectionRequest.class},
            "/assets/{id}/inspections", "asset:maintenance:manage", "记录资产巡检");
        assertPostEndpoint("requestRepair", new Class<?>[]{Long.class, AssetRepairRequest.class},
            "/assets/{id}/repairs", "asset:maintenance:manage", "提交资产维修");
        assertPostEndpoint("startRepair", new Class<?>[]{Long.class}, "/assets/repairs/{id}/start",
            "asset:maintenance:manage", "开始资产维修");
        assertPostEndpoint("completeRepair", new Class<?>[]{Long.class, AssetRepairCompleteRequest.class},
            "/assets/repairs/{id}/complete", "asset:maintenance:manage", "完成资产维修");
        assertPostEndpoint("cancelRepair", new Class<?>[]{Long.class, AssetRepairCancelRequest.class},
            "/assets/repairs/{id}/cancel", "asset:maintenance:manage", "取消资产维修");
    }

    @Test
    void maintenanceReadsRequireMaintenancePermission() throws Exception {
        assertReadEndpoint("inspections", new Class<?>[]{AssetQuery.class}, "/assets/inspections",
            "asset:maintenance:manage");
        assertReadEndpoint("repairs", new Class<?>[]{AssetQuery.class}, "/assets/repairs",
            "asset:maintenance:manage");
    }

    @Test
    void assetContextRequiresAnalyticsPermission() throws Exception {
        Method method = AssetController.class.getMethod("assetContext", AssetQuery.class);

        assertThat(method.getAnnotation(PreAuthorize.class).value())
            .isEqualTo("hasAuthority('asset:analytics:view')");
        assertThat(method.getAnnotation(GetMapping.class).value()).containsExactly("/site-ops/assets/context");
        assertThat(method.getAnnotation(AuditLog.class)).isNull();
    }

    @Test
    void assetLedgerActionsDelegateToService() {
        AssetLifecycleService service = mock(AssetLifecycleService.class);
        AssetController controller = new AssetController(service);
        AssetQuery query = new AssetQuery();
        PageQuery eventQuery = new PageQuery();
        AssetRequest createRequest = new AssetRequest();
        AssetRequest updateRequest = new AssetRequest();
        AssetStatusRequest statusRequest = new AssetStatusRequest();
        AssetItem created = new AssetItem();
        AssetItem updated = new AssetItem();
        AssetItem changed = new AssetItem();
        AssetLifecycleEvent event = new AssetLifecycleEvent();
        PageResult<AssetItem> page = PageResult.of(java.util.List.of(created), 1, 1, 10);
        PageResult<AssetLifecycleEvent> events = PageResult.of(java.util.List.of(event), 1, 1, 10);
        when(service.assets(query)).thenReturn(page);
        when(service.createAsset(createRequest)).thenReturn(created);
        when(service.updateAsset(7L, updateRequest)).thenReturn(updated);
        when(service.changeStatus(7L, statusRequest)).thenReturn(changed);
        when(service.assetEvents(7L, eventQuery)).thenReturn(events);

        assertThat(controller.assets(query).getData()).isSameAs(page);
        Result<AssetItem> createdResult = controller.createAsset(createRequest);
        Result<AssetItem> updatedResult = controller.updateAsset(7L, updateRequest);
        Result<AssetItem> changedResult = controller.changeStatus(7L, statusRequest);
        Result<PageResult<AssetLifecycleEvent>> eventResult = controller.assetEvents(7L, eventQuery);

        assertThat(updatedResult.getData()).isSameAs(updated);
        assertThat(createdResult.getData()).isSameAs(created);
        assertThat(changedResult.getData()).isSameAs(changed);
        assertThat(eventResult.getData()).isSameAs(events);
        verify(service).assets(query);
        verify(service).createAsset(createRequest);
        verify(service).updateAsset(7L, updateRequest);
        verify(service).changeStatus(7L, statusRequest);
        verify(service).assetEvents(7L, eventQuery);
    }

    @Test
    void inspectionAndRepairActionsDelegateToService() {
        AssetLifecycleService service = mock(AssetLifecycleService.class);
        AssetController controller = new AssetController(service);
        AssetQuery query = new AssetQuery();
        AssetInspectionRequest inspectionRequest = new AssetInspectionRequest();
        AssetRepairRequest repairRequest = new AssetRepairRequest();
        AssetRepairCompleteRequest completeRequest = new AssetRepairCompleteRequest();
        AssetRepairCancelRequest cancelRequest = new AssetRepairCancelRequest();
        AssetInspectionRecord inspection = new AssetInspectionRecord();
        AssetRepairRecord requestedRepair = new AssetRepairRecord();
        AssetRepairRecord startedRepair = new AssetRepairRecord();
        AssetRepairRecord completedRepair = new AssetRepairRecord();
        AssetRepairRecord canceledRepair = new AssetRepairRecord();
        PageResult<AssetInspectionRecord> inspections = PageResult.of(java.util.List.of(inspection), 1, 1, 10);
        PageResult<AssetRepairRecord> repairs = PageResult.of(java.util.List.of(requestedRepair), 1, 1, 10);
        Map<String, Object> context = Map.of("totalAssets", 1L);
        when(service.inspections(query)).thenReturn(inspections);
        when(service.recordInspection(7L, inspectionRequest)).thenReturn(inspection);
        when(service.repairs(query)).thenReturn(repairs);
        when(service.requestRepair(7L, repairRequest)).thenReturn(requestedRepair);
        when(service.startRepair(70L)).thenReturn(startedRepair);
        when(service.completeRepair(70L, completeRequest)).thenReturn(completedRepair);
        when(service.cancelRepair(70L, cancelRequest)).thenReturn(canceledRepair);
        when(service.assetContext(query)).thenReturn(context);

        assertThat(controller.inspections(query).getData()).isSameAs(inspections);
        assertThat(controller.recordInspection(7L, inspectionRequest).getData()).isSameAs(inspection);
        assertThat(controller.repairs(query).getData()).isSameAs(repairs);
        assertThat(controller.requestRepair(7L, repairRequest).getData()).isSameAs(requestedRepair);
        assertThat(controller.startRepair(70L).getData()).isSameAs(startedRepair);
        assertThat(controller.completeRepair(70L, completeRequest).getData()).isSameAs(completedRepair);
        assertThat(controller.cancelRepair(70L, cancelRequest).getData()).isSameAs(canceledRepair);
        assertThat(controller.assetContext(query).getData()).isSameAs(context);

        verify(service).inspections(query);
        verify(service).recordInspection(7L, inspectionRequest);
        verify(service).repairs(query);
        verify(service).requestRepair(7L, repairRequest);
        verify(service).startRepair(70L);
        verify(service).completeRepair(70L, completeRequest);
        verify(service).cancelRepair(70L, cancelRequest);
        verify(service).assetContext(query);
    }

    private void assertReadEndpoint(String methodName, Class<?>[] parameterTypes, String path, String permission)
        throws Exception {
        Method method = AssetController.class.getMethod(methodName, parameterTypes);

        assertThat(method.getAnnotation(PreAuthorize.class).value())
            .isEqualTo("hasAuthority('" + permission + "')");
        assertThat(method.getAnnotation(GetMapping.class).value()).containsExactly(path);
        assertThat(method.getAnnotation(AuditLog.class)).isNull();
    }

    private void assertPostEndpoint(String methodName, Class<?>[] parameterTypes, String path, String permission,
                                    String auditAction) throws Exception {
        Method method = AssetController.class.getMethod(methodName, parameterTypes);

        assertThat(method.getAnnotation(PreAuthorize.class).value())
            .isEqualTo("hasAuthority('" + permission + "')");
        assertThat(method.getAnnotation(PostMapping.class).value()).containsExactly(path);
        assertAuditLog(method, auditAction);
    }

    private void assertPutEndpoint(String methodName, Class<?>[] parameterTypes, String path, String permission,
                                   String auditAction) throws Exception {
        Method method = AssetController.class.getMethod(methodName, parameterTypes);

        assertThat(method.getAnnotation(PreAuthorize.class).value())
            .isEqualTo("hasAuthority('" + permission + "')");
        assertThat(method.getAnnotation(PutMapping.class).value()).containsExactly(path);
        assertAuditLog(method, auditAction);
    }

    private void assertAuditLog(Method method, String auditAction) {
        AuditLog auditLog = method.getAnnotation(AuditLog.class);
        assertThat(auditLog).isNotNull();
        assertThat(auditLog.module()).isEqualTo("资产设备");
        assertThat(auditLog.action()).isEqualTo(auditAction);
    }
}
