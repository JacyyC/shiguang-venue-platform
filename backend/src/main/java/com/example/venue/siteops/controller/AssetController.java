package com.example.venue.siteops.controller;

import com.example.venue.common.AuditLog;
import com.example.venue.common.PageQuery;
import com.example.venue.common.PageResult;
import com.example.venue.common.Result;
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
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/admin")
public class AssetController {
    private final AssetLifecycleService service;

    public AssetController(AssetLifecycleService service) {
        this.service = service;
    }

    @GetMapping("/assets")
    @PreAuthorize("hasAuthority('asset:manage')")
    public Result<PageResult<AssetItem>> assets(@Valid AssetQuery query) {
        return Result.ok(service.assets(query));
    }

    @AuditLog(module = "资产设备", action = "创建设备资产")
    @PostMapping("/assets")
    @PreAuthorize("hasAuthority('asset:manage')")
    public Result<AssetItem> createAsset(@RequestBody AssetRequest request) {
        return Result.ok(service.createAsset(request));
    }

    @AuditLog(module = "资产设备", action = "更新设备资产")
    @PutMapping("/assets/{id}")
    @PreAuthorize("hasAuthority('asset:manage')")
    public Result<AssetItem> updateAsset(@PathVariable Long id, @RequestBody AssetRequest request) {
        return Result.ok(service.updateAsset(id, request));
    }

    @AuditLog(module = "资产设备", action = "变更资产状态")
    @PostMapping("/assets/{id}/status")
    @PreAuthorize("hasAuthority('asset:manage')")
    public Result<AssetItem> changeStatus(@PathVariable Long id, @RequestBody AssetStatusRequest request) {
        return Result.ok(service.changeStatus(id, request));
    }

    @GetMapping("/assets/{id}/events")
    @PreAuthorize("hasAuthority('asset:manage')")
    public Result<PageResult<AssetLifecycleEvent>> assetEvents(@PathVariable Long id, @Valid PageQuery query) {
        return Result.ok(service.assetEvents(id, query));
    }

    @GetMapping("/assets/inspections")
    @PreAuthorize("hasAuthority('asset:maintenance:manage')")
    public Result<PageResult<AssetInspectionRecord>> inspections(@Valid AssetQuery query) {
        return Result.ok(service.inspections(query));
    }

    @AuditLog(module = "资产设备", action = "记录资产巡检")
    @PostMapping("/assets/{id}/inspections")
    @PreAuthorize("hasAuthority('asset:maintenance:manage')")
    public Result<AssetInspectionRecord> recordInspection(@PathVariable Long id,
                                                          @RequestBody AssetInspectionRequest request) {
        return Result.ok(service.recordInspection(id, request));
    }

    @GetMapping("/assets/repairs")
    @PreAuthorize("hasAuthority('asset:maintenance:manage')")
    public Result<PageResult<AssetRepairRecord>> repairs(@Valid AssetQuery query) {
        return Result.ok(service.repairs(query));
    }

    @AuditLog(module = "资产设备", action = "提交资产维修")
    @PostMapping("/assets/{id}/repairs")
    @PreAuthorize("hasAuthority('asset:maintenance:manage')")
    public Result<AssetRepairRecord> requestRepair(@PathVariable Long id, @RequestBody AssetRepairRequest request) {
        return Result.ok(service.requestRepair(id, request));
    }

    @AuditLog(module = "资产设备", action = "开始资产维修")
    @PostMapping("/assets/repairs/{id}/start")
    @PreAuthorize("hasAuthority('asset:maintenance:manage')")
    public Result<AssetRepairRecord> startRepair(@PathVariable Long id) {
        return Result.ok(service.startRepair(id));
    }

    @AuditLog(module = "资产设备", action = "完成资产维修")
    @PostMapping("/assets/repairs/{id}/complete")
    @PreAuthorize("hasAuthority('asset:maintenance:manage')")
    public Result<AssetRepairRecord> completeRepair(@PathVariable Long id,
                                                    @RequestBody AssetRepairCompleteRequest request) {
        return Result.ok(service.completeRepair(id, request));
    }

    @AuditLog(module = "资产设备", action = "取消资产维修")
    @PostMapping("/assets/repairs/{id}/cancel")
    @PreAuthorize("hasAuthority('asset:maintenance:manage')")
    public Result<AssetRepairRecord> cancelRepair(@PathVariable Long id,
                                                  @RequestBody AssetRepairCancelRequest request) {
        return Result.ok(service.cancelRepair(id, request));
    }

    @GetMapping("/site-ops/assets/context")
    @PreAuthorize("hasAuthority('asset:analytics:view')")
    public Result<Map<String, Object>> assetContext(@Valid AssetQuery query) {
        return Result.ok(service.assetContext(query));
    }
}
