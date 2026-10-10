package com.example.venue.siteops.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.venue.business.dto.BusinessEventRecordRequest;
import com.example.venue.business.enums.BusinessDomain;
import com.example.venue.business.enums.BusinessEventType;
import com.example.venue.business.enums.BusinessSourceType;
import com.example.venue.business.service.BusinessEventService;
import com.example.venue.common.BusinessException;
import com.example.venue.common.EntityExistenceService;
import com.example.venue.common.PageQuery;
import com.example.venue.common.PageResult;
import com.example.venue.inventory.dto.SaveUnavailableBlockRequest;
import com.example.venue.inventory.entity.VenueUnavailableBlock;
import com.example.venue.inventory.service.VenueInventoryService;
import com.example.venue.security.SecurityContext;
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
import com.example.venue.venue.mapper.VenueAdminRelationMapper;
import com.example.venue.venue.service.VenueAccessService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
public class AssetLifecycleService {
    private static final DateTimeFormatter NO_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final String SOURCE_ADMIN = "ADMIN";
    private static final String STATUS_STOCKED = "STOCKED";
    private static final String STATUS_IN_USE = "IN_USE";
    private static final String STATUS_REPAIRING = "REPAIRING";
    private static final String STATUS_RETIRED = "RETIRED";
    private static final String STATUS_INSPECTION_DUE = "INSPECTION_DUE";
    private static final String STATUS_OUT_OF_SERVICE = "OUT_OF_SERVICE";
    private static final List<String> ASSET_STATUSES = List.of(STATUS_STOCKED, STATUS_IN_USE,
        STATUS_INSPECTION_DUE, STATUS_REPAIRING, STATUS_OUT_OF_SERVICE, STATUS_RETIRED);
    private static final String REPAIR_REQUESTED = "REQUESTED";
    private static final String REPAIR_IN_PROGRESS = "IN_PROGRESS";
    private static final String REPAIR_COMPLETED = "COMPLETED";
    private static final String REPAIR_CANCELED = "CANCELED";
    private static final String RETIRED_ASSET_IMMUTABLE_MESSAGE = "已报废资产不能重新启用，已报废资产不能变更状态";

    private final AssetItemMapper assetMapper;
    private final AssetLifecycleEventMapper lifecycleEventMapper;
    private final AssetInspectionRecordMapper inspectionMapper;
    private final AssetRepairRecordMapper repairMapper;
    private final VenueAccessService venueAccessService;
    private final VenueInventoryService inventoryService;
    private final BusinessEventService businessEventService;
    private final EntityExistenceService entityExistenceService;
    private final VenueAdminRelationMapper venueAdminRelationMapper;

    public AssetLifecycleService(AssetItemMapper assetMapper,
                                 AssetLifecycleEventMapper lifecycleEventMapper,
                                 AssetInspectionRecordMapper inspectionMapper,
                                 AssetRepairRecordMapper repairMapper,
                                 VenueAccessService venueAccessService,
                                 VenueInventoryService inventoryService,
                                 BusinessEventService businessEventService,
                                 EntityExistenceService entityExistenceService,
                                 VenueAdminRelationMapper venueAdminRelationMapper) {
        this.assetMapper = assetMapper;
        this.lifecycleEventMapper = lifecycleEventMapper;
        this.inspectionMapper = inspectionMapper;
        this.repairMapper = repairMapper;
        this.venueAccessService = venueAccessService;
        this.inventoryService = inventoryService;
        this.businessEventService = businessEventService;
        this.entityExistenceService = entityExistenceService;
        this.venueAdminRelationMapper = venueAdminRelationMapper;
    }

    @Transactional(readOnly = true)
    public PageResult<AssetItem> assets(AssetQuery query) {
        AssetQuery safeQuery = safeQuery(query);
        QueryWrapper<AssetItem> wrapper = new QueryWrapper<AssetItem>().eq("deleted", 0);
        if (!applyVenueScope(wrapper, safeQuery)) {
            return emptyPage(safeQuery);
        }
        applyAssetQuery(wrapper, safeQuery, "id", true);
        Page<AssetItem> page = assetMapper.selectPage(new Page<>(safeQuery.getPageNum(), safeQuery.getPageSize()),
            wrapper.orderByDesc("id"));
        return PageResult.of(page.getRecords(), page.getTotal(), safeQuery.getPageNum(), safeQuery.getPageSize());
    }

    @Transactional
    public AssetItem createAsset(AssetRequest request) {
        if (request == null) {
            throw BusinessException.badRequest("资产参数不能为空");
        }
        requireVenueId(request.venueId);
        if (isBlank(request.assetType) || isBlank(request.name)) {
            throw BusinessException.badRequest("资产信息不完整");
        }
        venueAccessService.requireVenueManageAccess(request.venueId);
        // A26-P3-20：responsibleUserId 非空时校验存在+启用+场馆归属（AUD-P2-03 同型模式，
        // 资产侧原遗漏）。资产允许不指定负责人（null 不校验）。
        if (request.responsibleUserId != null) {
            requireAssignedUserForVenue(request.responsibleUserId, request.venueId);
        }

        LocalDateTime now = LocalDateTime.now();
        AssetItem asset = new AssetItem();
        asset.assetNo = nextNo("ASSET");
        asset.venueId = request.venueId;
        asset.spaceId = request.spaceId;
        asset.assetType = request.assetType;
        asset.name = request.name;
        asset.brand = blankToNull(request.brand);
        asset.model = blankToNull(request.model);
        asset.serialNo = blankToNull(request.serialNo);
        asset.supplierName = blankToNull(request.supplierName);
        asset.purchaseDate = request.purchaseDate;
        asset.purchaseAmount = amountOrZero(request.purchaseAmount);
        asset.warrantyStartDate = request.warrantyStartDate;
        asset.warrantyEndDate = request.warrantyEndDate;
        asset.responsibleUserId = request.responsibleUserId;
        asset.status = STATUS_STOCKED;
        asset.metadataJson = blankToNull(request.metadataJson);
        asset.createTime = now;
        asset.updateTime = now;
        asset.deleted = 0;
        assetMapper.insert(asset);

        AssetLifecycleEvent lifecycleEvent = insertLifecycleEvent(asset, "STOCKED", null, STATUS_STOCKED,
            null, null, null, null, null, null, null, null, null, asset.metadataJson);
        BusinessEventRecordRequest event = assetBusinessEvent(BusinessEventType.ASSET_STATUS_CHANGED,
            "ASSET", asset.id, assetCreateOperationKey(asset.id),
            null, STATUS_STOCKED, null, null)
            .metadata("assetNo", asset.assetNo)
            .metadata("venueId", asset.venueId)
            .metadata("spaceId", asset.spaceId)
            .metadata("assetType", asset.assetType);
        businessEventService.record(event);
        return asset;
    }

    @Transactional
    public AssetItem updateAsset(Long assetId, AssetRequest request) {
        AssetItem asset = requireAsset(assetId);
        venueAccessService.requireVenueManageAccess(asset.venueId);
        if (request == null) {
            throw BusinessException.badRequest("资产参数不能为空");
        }
        requireVenueId(request.venueId);
        if (isBlank(request.assetType) || isBlank(request.name)) {
            throw BusinessException.badRequest("资产信息不完整");
        }
        if (!request.venueId.equals(asset.venueId)) {
            venueAccessService.requireVenueManageAccess(request.venueId);
            // A26-P3-22：跨场馆移动前校验无开放维修单，避免子记录 venue_id 错位使原馆管理员
            // 仍能操作现属他馆的资产，且 assetEvents 按当前场馆过滤隐藏移动前生命周期证据。
            if (hasOpenRepair(assetId)) {
                throw BusinessException.conflict("资产有开放维修单，不可跨场馆移动");
            }
        }
        // A26-P3-20：responsibleUserId 非空时校验存在+启用+目标场馆归属（AUD-P2-03 同型模式）。
        // 跨场馆移动时按 request.venueId 校验新馆归属，避免写入跨场馆幻影 ID。
        if (request.responsibleUserId != null) {
            requireAssignedUserForVenue(request.responsibleUserId, request.venueId);
        }

        asset.venueId = request.venueId;
        asset.spaceId = request.spaceId;
        asset.assetType = request.assetType;
        asset.name = request.name;
        asset.brand = blankToNull(request.brand);
        asset.model = blankToNull(request.model);
        asset.serialNo = blankToNull(request.serialNo);
        asset.supplierName = blankToNull(request.supplierName);
        asset.purchaseDate = request.purchaseDate;
        asset.purchaseAmount = amountOrZero(request.purchaseAmount);
        asset.warrantyStartDate = request.warrantyStartDate;
        asset.warrantyEndDate = request.warrantyEndDate;
        asset.responsibleUserId = request.responsibleUserId;
        asset.metadataJson = blankToNull(request.metadataJson);
        asset.updateTime = LocalDateTime.now();
        // A26-P2-16：可空字段显式 set，允许 null 落库（避免 NOT_NULL 策略静默丢弃 spaceId/brand/model/serialNo/
        // supplierName/purchaseDate/warrantyStartDate/warrantyEndDate/responsibleUserId/metadataJson）。
        assetMapper.update(asset, new UpdateWrapper<AssetItem>()
            .eq("id", assetId)
            .eq("deleted", 0)
            .set("space_id", asset.spaceId)
            .set("brand", asset.brand)
            .set("model", asset.model)
            .set("serial_no", asset.serialNo)
            .set("supplier_name", asset.supplierName)
            .set("purchase_date", asset.purchaseDate)
            .set("warranty_start_date", asset.warrantyStartDate)
            .set("warranty_end_date", asset.warrantyEndDate)
            .set("responsible_user_id", asset.responsibleUserId)
            .set("metadata_json", asset.metadataJson));
        return asset;
    }

    @Transactional(readOnly = true)
    public PageResult<AssetLifecycleEvent> assetEvents(Long assetId, PageQuery query) {
        AssetItem asset = requireAsset(assetId);
        venueAccessService.requireVenueManageAccess(asset.venueId);
        PageQuery safeQuery = query == null ? new PageQuery() : query;
        QueryWrapper<AssetLifecycleEvent> wrapper = new QueryWrapper<AssetLifecycleEvent>()
            .eq("asset_id", asset.id)
            .eq("venue_id", asset.venueId);
        Page<AssetLifecycleEvent> page = lifecycleEventMapper.selectPage(
            new Page<>(safeQuery.getPageNum(), safeQuery.getPageSize()),
            wrapper.orderByDesc("occurred_at", "id"));
        return PageResult.of(page.getRecords(), page.getTotal(), safeQuery.getPageNum(), safeQuery.getPageSize());
    }

    @Transactional
    public AssetItem changeStatus(Long assetId, AssetStatusRequest request) {
        // A26-P2-13：先 FOR UPDATE 锁行，避免持旧快照的并发事务把 RETIRED 终态覆盖"复活"。
        AssetItem asset = requireAssetForUpdate(assetId);
        venueAccessService.requireVenueManageAccess(asset.venueId);
        if (request == null || isBlank(request.toStatus)) {
            throw BusinessException.badRequest("资产目标状态不能为空");
        }
        String toStatus = normalize(request.toStatus);
        if (!ASSET_STATUSES.contains(toStatus)) {
            throw BusinessException.badRequest("资产状态不受支持");
        }
        requireMutableAsset(asset);
        if (STATUS_RETIRED.equals(toStatus) && isBlank(request.reasonText)) {
            throw BusinessException.badRequest("资产报废原因不能为空");
        }
        boolean createBlock = hasBlockWindow(request);
        if (createBlock && !List.of(STATUS_OUT_OF_SERVICE, STATUS_REPAIRING).contains(toStatus)) {
            throw BusinessException.badRequest("仅维修中或停用状态支持不可售联动");
        }
        if (createBlock) {
            requireTimeRange(request.blockStartTime, request.blockEndTime, "不可售开始时间必须早于结束时间");
            if (asset.spaceId == null) {
                throw BusinessException.badRequest("未绑定场地的资产不能创建不可售联动");
            }
        }

        String fromStatus = asset.status;
        LocalDateTime now = LocalDateTime.now();
        VenueUnavailableBlock block = createBlock ? inventoryService.createBlock(blockRequest(asset, toStatus, request)) : null;
        asset.status = toStatus;
        if (block != null) {
            asset.currentUnavailableBlockId = block.id;
        }
        if (request.releaseUnavailableBlock && asset.currentUnavailableBlockId != null && STATUS_IN_USE.equals(toStatus)) {
            inventoryService.cancelBlock(asset.currentUnavailableBlockId, "资产恢复使用");
            asset.currentUnavailableBlockId = null;
        }
        asset.updateTime = now;
        // A26-P2-13：CAS 更新，仅当 status 仍为 fromStatus 时成功；返回 0 行说明已被并发改终态（如 RETIRED），拒绝覆盖。
        // A26-P2-15：current_unavailable_block_id 显式 set，允许置空落库（释放块指针时 null 不被 NOT_NULL 策略丢弃）。
        int affected = assetMapper.update(asset, new UpdateWrapper<AssetItem>()
            .eq("id", assetId)
            .eq("status", fromStatus)
            .eq("deleted", 0)
            .set("current_unavailable_block_id", asset.currentUnavailableBlockId));
        if (affected == 0) {
            throw BusinessException.conflict("资产状态已变更");
        }

        String lifecycleType = STATUS_RETIRED.equals(toStatus) ? "RETIRED" : "STATUS_CHANGED";
        AssetLifecycleEvent lifecycleEvent = insertLifecycleEvent(asset, lifecycleType, fromStatus, toStatus,
            request.reasonCode, request.reasonText, null, request.blockStartTime, request.blockEndTime, null, null,
            null, block == null ? null : block.id, null);
        BusinessEventRecordRequest event = assetBusinessEvent(BusinessEventType.ASSET_STATUS_CHANGED,
            "ASSET", asset.id, assetStatusOperationKey(asset.id, fromStatus, toStatus, lifecycleEvent),
            fromStatus, toStatus, request.reasonCode, request.reasonText)
            .metadata("assetNo", asset.assetNo)
            .metadata("venueId", asset.venueId)
            .metadata("spaceId", asset.spaceId)
            .metadata("unavailableBlockId", block == null ? null : block.id);
        businessEventService.record(event);
        if (block != null) {
            // A26-P3-17：ASSET_UNAVAILABLE_LINKED 幂等键加 block.id 唯一因子，
            // 第二次同状态联动（如 IN_USE→OUT_OF_SERVICE→IN_USE→OUT_OF_SERVICE）产生新 block 时
            // 不再被同幂等键吞掉，追加式证据完整保留。对照主事件键已带 lifecycleEvent.id。
            BusinessEventRecordRequest blockEvent = assetBusinessEvent(BusinessEventType.ASSET_UNAVAILABLE_LINKED,
                "ASSET", asset.id,
                "asset/status/" + asset.id + "/" + toStatus + "/unavailable-linked/block/" + block.id,
                fromStatus, toStatus, defaultBlockReasonCode(request.reasonCode), request.reasonText)
                .relatedEntity("VENUE_UNAVAILABLE_BLOCK", block.id)
                .metadata("assetId", asset.id)
                .metadata("venueId", asset.venueId)
                .metadata("spaceId", asset.spaceId)
                .metadata("unavailableBlockId", block.id);
            businessEventService.record(blockEvent);
        }
        return asset;
    }

    @Transactional
    public AssetInspectionRecord recordInspection(Long assetId, AssetInspectionRequest request) {
        // A26-P2-13：先 FOR UPDATE 锁行，避免持旧快照的并发事务把 RETIRED 终态覆盖"复活"。
        AssetItem asset = requireAssetForUpdate(assetId);
        venueAccessService.requireVenueManageAccess(asset.venueId);
        if (request == null || isBlank(request.inspectionResult)) {
            throw BusinessException.badRequest("巡检结果不能为空");
        }
        // A26-P3-20：inspectorUserId 显式指定时校验存在+启用+资产所属场馆归属（AUD-P2-03 同型模式）。
        // null 时由 SecurityContext.currentUserId() 兜底（已通过 requireVenueManageAccess 校验登录态）。
        if (request.inspectorUserId != null) {
            requireAssignedUserForVenue(request.inspectorUserId, asset.venueId);
        }

        LocalDateTime now = LocalDateTime.now();
        String fromStatus = asset.status;
        String inspectionResult = normalize(request.inspectionResult);
        if ("REPAIR_REQUIRED".equals(inspectionResult)) {
            requireMutableAsset(asset);
        }
        AssetInspectionRecord record = new AssetInspectionRecord();
        record.inspectionNo = nextNo("ASSET_INSPECTION");
        record.assetId = asset.id;
        record.venueId = asset.venueId;
        record.spaceId = asset.spaceId;
        record.siteTaskId = request.siteTaskId;
        record.inspectorUserId = request.inspectorUserId == null ? SecurityContext.currentUserId() : request.inspectorUserId;
        record.inspectionResult = inspectionResult;
        record.riskLevel = blankToDefault(request.riskLevel, "LOW").toUpperCase(Locale.ROOT);
        record.checklistJson = blankToNull(request.checklistJson);
        record.issueSummary = blankToNull(request.issueSummary);
        record.nextInspectionDueAt = request.nextInspectionDueAt;
        record.inspectedAt = request.inspectedAt == null ? now : request.inspectedAt;
        record.metadataJson = blankToNull(request.metadataJson);
        record.createTime = now;
        record.updateTime = now;
        record.deleted = 0;
        inspectionMapper.insert(record);

        String toStatus = fromStatus;
        if ("REPAIR_REQUIRED".equals(record.inspectionResult)) {
            asset.status = STATUS_INSPECTION_DUE;
            asset.updateTime = now;
            toStatus = STATUS_INSPECTION_DUE;
            // A26-P2-13：CAS 更新，仅当 status 仍为 fromStatus 时成功；返回 0 行说明已被并发改终态，拒绝覆盖。
            int affected = assetMapper.update(asset, new UpdateWrapper<AssetItem>()
                .eq("id", assetId)
                .eq("status", fromStatus)
                .eq("deleted", 0));
            if (affected == 0) {
                throw BusinessException.conflict("资产状态已变更");
            }
        }

        AssetLifecycleEvent lifecycleEvent = insertLifecycleEvent(asset, "INSPECTION", fromStatus, toStatus,
            record.inspectionResult, record.issueSummary, null, null, null, request.siteTaskId, null, record.id, null,
            record.metadataJson);
        BusinessEventRecordRequest event = assetBusinessEvent(BusinessEventType.ASSET_INSPECTION_RECORDED,
            "ASSET_INSPECTION", record.id,
            assetInspectionOperationKey(record.id), fromStatus, toStatus, record.inspectionResult, record.issueSummary)
            .relatedEntity("ASSET", asset.id)
            .metadata("assetNo", asset.assetNo)
            .metadata("venueId", asset.venueId)
            .metadata("spaceId", asset.spaceId)
            .metadata("riskLevel", record.riskLevel);
        businessEventService.record(event);
        return record;
    }

    @Transactional(readOnly = true)
    public PageResult<AssetInspectionRecord> inspections(AssetQuery query) {
        AssetQuery safeQuery = safeQuery(query);
        QueryWrapper<AssetInspectionRecord> wrapper = new QueryWrapper<AssetInspectionRecord>().eq("deleted", 0);
        if (!applyVenueScope(wrapper, safeQuery)) {
            return emptyPage(safeQuery);
        }
        applyAssetQuery(wrapper, safeQuery, "asset_id", false);
        Page<AssetInspectionRecord> page = inspectionMapper.selectPage(
            new Page<>(safeQuery.getPageNum(), safeQuery.getPageSize()), wrapper.orderByDesc("id"));
        return PageResult.of(page.getRecords(), page.getTotal(), safeQuery.getPageNum(), safeQuery.getPageSize());
    }

    @Transactional
    public AssetRepairRecord requestRepair(Long assetId, AssetRepairRequest request) {
        AssetItem asset = requireAssetForUpdate(assetId);
        venueAccessService.requireVenueManageAccess(asset.venueId);
        if (request == null || isBlank(request.failureDescription)) {
            throw BusinessException.badRequest("故障描述不能为空");
        }
        validateDowntime(request.downtimeStart, request.downtimeEnd);
        boolean createBlock = hasBlockWindow(request);
        if (createBlock) {
            requireTimeRange(request.blockStartTime, request.blockEndTime, "不可售开始时间必须早于结束时间");
            if (asset.spaceId == null) {
                throw BusinessException.badRequest("未绑定场地的资产不能创建不可售联动");
            }
        }
        // A26-P3-20：repairOwnerId 非空时校验存在+启用+资产所属场馆归属（AUD-P2-03 同型模式）。
        // 维修允许不指定负责人（null 不校验）。
        if (request.repairOwnerId != null) {
            requireAssignedUserForVenue(request.repairOwnerId, asset.venueId);
        }
        requireMutableAsset(asset);
        rejectOpenRepair(asset.id);

        String fromStatus = asset.status;
        LocalDateTime now = LocalDateTime.now();
        AssetRepairRecord repair = new AssetRepairRecord();
        repair.repairNo = nextNo("ASSET_REPAIR");
        repair.assetId = asset.id;
        repair.venueId = asset.venueId;
        repair.spaceId = asset.spaceId;
        repair.siteTaskId = request.siteTaskId;
        repair.status = REPAIR_REQUESTED;
        repair.requestedBy = SecurityContext.currentUserId();
        repair.repairOwnerId = request.repairOwnerId;
        repair.supplierName = blankToNull(request.supplierName);
        repair.failureCode = blankToNull(request.failureCode);
        repair.failureDescription = request.failureDescription;
        repair.estimatedCostAmount = amountOrZero(request.estimatedCostAmount);
        repair.actualCostAmount = BigDecimal.ZERO;
        repair.downtimeStart = request.downtimeStart;
        repair.downtimeEnd = request.downtimeEnd;
        repair.sourceType = SOURCE_ADMIN;
        repair.createTime = now;
        repair.updateTime = now;
        repair.deleted = 0;
        repairMapper.insert(repair);

        VenueUnavailableBlock block = null;
        if (createBlock) {
            block = inventoryService.createBlock(blockRequest(asset, repair, request));
            repair.unavailableBlockId = block.id;
            repair.updateTime = LocalDateTime.now();
            repairMapper.updateById(repair);
        }

        asset.status = STATUS_REPAIRING;
        asset.currentUnavailableBlockId = block == null ? asset.currentUnavailableBlockId : block.id;
        asset.updateTime = LocalDateTime.now();
        assetMapper.updateById(asset);

        AssetLifecycleEvent lifecycleEvent = insertLifecycleEvent(asset, "REPAIR_REQUESTED", fromStatus, STATUS_REPAIRING,
            repair.failureCode, repair.failureDescription, repair.estimatedCostAmount, repair.downtimeStart,
            repair.downtimeEnd, repair.siteTaskId, repair.id, null, repair.unavailableBlockId, repair.metadataJson);
        BusinessEventRecordRequest repairEvent = assetBusinessEvent(BusinessEventType.ASSET_REPAIR_RECORDED,
            "ASSET_REPAIR", repair.id,
            assetRepairOperationKey(repair.id, "requested"), fromStatus, STATUS_REPAIRING, repair.failureCode,
            repair.failureDescription)
            .relatedEntity("ASSET", asset.id)
            .metadata("assetNo", asset.assetNo)
            .metadata("venueId", repair.venueId)
            .metadata("spaceId", repair.spaceId)
            .metadata("estimatedCostAmount", repair.estimatedCostAmount)
            .metadata("downtimeStart", repair.downtimeStart)
            .metadata("downtimeEnd", repair.downtimeEnd);
        businessEventService.record(repairEvent);

        if (block != null) {
            AssetLifecycleEvent blockLifecycleEvent = insertLifecycleEvent(asset, "UNAVAILABLE_LINKED", fromStatus,
                STATUS_REPAIRING, "ASSET_REPAIR", repair.failureDescription, null, repair.downtimeStart,
                repair.downtimeEnd, repair.siteTaskId, repair.id, null, block.id, null);
            BusinessEventRecordRequest blockEvent = assetBusinessEvent(BusinessEventType.ASSET_UNAVAILABLE_LINKED,
                "ASSET_REPAIR", repair.id,
                assetRepairOperationKey(repair.id, "unavailable-linked"), null, "ACTIVE", "ASSET_REPAIR",
                repair.failureDescription)
                .relatedEntity("VENUE_UNAVAILABLE_BLOCK", block.id)
                .metadata("assetId", asset.id)
                .metadata("venueId", asset.venueId)
                .metadata("spaceId", asset.spaceId)
                .metadata("unavailableBlockId", block.id);
            businessEventService.record(blockEvent);
        }
        return repair;
    }

    @Transactional(readOnly = true)
    public PageResult<AssetRepairRecord> repairs(AssetQuery query) {
        AssetQuery safeQuery = safeQuery(query);
        QueryWrapper<AssetRepairRecord> wrapper = new QueryWrapper<AssetRepairRecord>().eq("deleted", 0);
        if (!applyVenueScope(wrapper, safeQuery)) {
            return emptyPage(safeQuery);
        }
        applyAssetQuery(wrapper, safeQuery, "asset_id", true);
        Page<AssetRepairRecord> page = repairMapper.selectPage(
            new Page<>(safeQuery.getPageNum(), safeQuery.getPageSize()), wrapper.orderByDesc("id"));
        return PageResult.of(page.getRecords(), page.getTotal(), safeQuery.getPageNum(), safeQuery.getPageSize());
    }

    @Transactional
    public AssetRepairRecord startRepair(Long repairId) {
        // A26-P2-14：先 FOR UPDATE 锁维修单，对照 completeRepair/cancelRepair 串行化，避免并发把 CANCELED 改回 IN_PROGRESS。
        AssetRepairRecord repair = requireRepairForUpdate(repairId);
        venueAccessService.requireVenueManageAccess(repair.venueId);
        // A26-P2-14：终态先行判定（COMPLETED/CANCELED 不可改 IN_PROGRESS）。
        if (REPAIR_COMPLETED.equals(repair.status) || REPAIR_CANCELED.equals(repair.status)) {
            throw BusinessException.conflict("维修单已处于终态，不可开始");
        }
        if (!REPAIR_REQUESTED.equals(repair.status)) {
            throw BusinessException.conflict("当前维修状态不可开始");
        }
        // A26-P2-14：asset 也走 FOR UPDATE，对照 completeRepair/cancelRepair，避免基于旧快照判定 mutable。
        AssetItem asset = requireAssetForUpdate(repair.assetId);
        requireMutableAsset(asset);
        String fromStatus = repair.status;
        repair.status = REPAIR_IN_PROGRESS;
        repair.updateTime = LocalDateTime.now();
        // A26-P2-14：CAS 更新，仅当 status 仍为 REQUESTED 时成功；返回 0 行说明已被并发改终态，拒绝覆盖。
        int affected = repairMapper.update(repair, new UpdateWrapper<AssetRepairRecord>()
            .eq("id", repairId)
            .eq("status", REPAIR_REQUESTED)
            .eq("deleted", 0));
        if (affected == 0) {
            throw BusinessException.conflict("维修状态已变更");
        }

        AssetLifecycleEvent lifecycleEvent = insertLifecycleEvent(asset, "REPAIR_STARTED", asset.status,
            asset.status, repair.failureCode, repair.failureDescription, repair.estimatedCostAmount,
            repair.downtimeStart, repair.downtimeEnd, repair.siteTaskId, repair.id, null, repair.unavailableBlockId,
            repair.metadataJson);
        BusinessEventRecordRequest event = assetBusinessEvent(BusinessEventType.ASSET_REPAIR_RECORDED,
            "ASSET_REPAIR", repair.id,
            assetRepairOperationKey(repair.id, "in-progress"), fromStatus, REPAIR_IN_PROGRESS, repair.failureCode,
            repair.failureDescription)
            .relatedEntity("ASSET", repair.assetId)
            .metadata("venueId", repair.venueId)
            .metadata("spaceId", repair.spaceId);
        businessEventService.record(event);
        return repair;
    }

    @Transactional
    public AssetRepairRecord completeRepair(Long repairId, AssetRepairCompleteRequest request) {
        // AUD-P2-02: 先 FOR UPDATE 锁维修单，防止完成/取消并发覆盖终态
        AssetRepairRecord repair = requireRepairForUpdate(repairId);
        venueAccessService.requireVenueManageAccess(repair.venueId);
        if (request == null || isBlank(request.resolution)) {
            throw BusinessException.badRequest("维修结果不能为空");
        }
        // AUD-P2-02: 禁止终态回退（COMPLETED/CANCELED 不可改 COMPLETED）
        if (REPAIR_COMPLETED.equals(repair.status) || REPAIR_CANCELED.equals(repair.status)) {
            throw BusinessException.conflict("维修单已处于终态，不可完成");
        }
        if (!REPAIR_IN_PROGRESS.equals(repair.status)) {
            throw BusinessException.conflict("当前维修状态不可完成");
        }
        AssetItem asset = requireAssetForUpdate(repair.assetId);
        requireMutableAsset(asset);
        String repairFromStatus = repair.status;
        String assetFromStatus = asset.status;
        LocalDateTime now = LocalDateTime.now();

        repair.status = REPAIR_COMPLETED;
        repair.actualCostAmount = amountOrZero(request.actualCostAmount);
        repair.resolution = request.resolution;
        repair.downtimeEnd = repair.downtimeEnd == null ? now : repair.downtimeEnd;
        repair.updateTime = now;
        repairMapper.updateById(repair);

        asset.status = hasOpenRepair(asset.id) ? STATUS_REPAIRING : STATUS_IN_USE;
        if (request.cancelUnavailableBlock && repair.unavailableBlockId != null) {
            inventoryService.cancelBlock(repair.unavailableBlockId, "资产维修完成");
            if (repair.unavailableBlockId.equals(asset.currentUnavailableBlockId)) {
                asset.currentUnavailableBlockId = null;
            }
        }
        asset.updateTime = now;
        // A26-P2-15：current_unavailable_block_id 显式 set，允许置空落库（维修完成释放块指针时 null 不被 NOT_NULL 策略丢弃）。
        assetMapper.update(asset, new UpdateWrapper<AssetItem>()
            .eq("id", asset.id)
            .eq("deleted", 0)
            .set("current_unavailable_block_id", asset.currentUnavailableBlockId));

        AssetLifecycleEvent lifecycleEvent = insertLifecycleEvent(asset, "REPAIR_COMPLETED", assetFromStatus,
            asset.status, repair.failureCode, repair.resolution, repair.actualCostAmount, repair.downtimeStart,
            repair.downtimeEnd, repair.siteTaskId, repair.id, null, repair.unavailableBlockId, repair.metadataJson);
        BusinessEventRecordRequest event = assetBusinessEvent(BusinessEventType.ASSET_REPAIR_RECORDED,
            "ASSET_REPAIR", repair.id,
            assetRepairOperationKey(repair.id, "completed"), repairFromStatus, REPAIR_COMPLETED, repair.failureCode,
            repair.resolution)
            .relatedEntity("ASSET", asset.id)
            .metadata("assetNo", asset.assetNo)
            .metadata("venueId", repair.venueId)
            .metadata("spaceId", repair.spaceId)
            .metadata("actualCostAmount", repair.actualCostAmount)
            .metadata("downtimeStart", repair.downtimeStart)
            .metadata("downtimeEnd", repair.downtimeEnd)
            .metadata("unavailableBlockId", repair.unavailableBlockId);
        businessEventService.record(event);
        return repair;
    }

    @Transactional
    public AssetRepairRecord cancelRepair(Long repairId, AssetRepairCancelRequest request) {
        // AUD-P2-02: 先 FOR UPDATE 锁维修单，防止完成/取消并发覆盖终态
        AssetRepairRecord repair = requireRepairForUpdate(repairId);
        venueAccessService.requireVenueManageAccess(repair.venueId);
        // AUD-P2-02: 禁止终态回退（COMPLETED/CANCELED 不可改 CANCELED）
        if (REPAIR_COMPLETED.equals(repair.status) || REPAIR_CANCELED.equals(repair.status)) {
            throw BusinessException.conflict("维修单已处于终态，不可取消");
        }
        if (!REPAIR_REQUESTED.equals(repair.status) && !REPAIR_IN_PROGRESS.equals(repair.status)) {
            throw BusinessException.conflict("当前维修状态不可取消");
        }
        AssetItem asset = requireAssetForUpdate(repair.assetId);
        String repairFromStatus = repair.status;
        String assetFromStatus = asset.status;
        LocalDateTime now = LocalDateTime.now();

        repair.status = REPAIR_CANCELED;
        repair.resolution = request == null ? null : blankToNull(request.reason);
        repair.updateTime = now;
        repairMapper.updateById(repair);

        boolean cancelBlock = request != null && request.cancelUnavailableBlock && repair.unavailableBlockId != null;
        if (cancelBlock) {
            inventoryService.cancelBlock(repair.unavailableBlockId, "资产维修取消");
        }
        if (!STATUS_RETIRED.equals(asset.status)) {
            asset.status = hasOpenRepair(asset.id) ? STATUS_REPAIRING : STATUS_IN_USE;
            if (cancelBlock && repair.unavailableBlockId.equals(asset.currentUnavailableBlockId)) {
                asset.currentUnavailableBlockId = null;
            }
            asset.updateTime = now;
            // A26-P2-15：current_unavailable_block_id 显式 set，允许置空落库（维修取消释放块指针时 null 不被 NOT_NULL 策略丢弃）。
            assetMapper.update(asset, new UpdateWrapper<AssetItem>()
                .eq("id", asset.id)
                .eq("deleted", 0)
                .set("current_unavailable_block_id", asset.currentUnavailableBlockId));
        }

        AssetLifecycleEvent lifecycleEvent = insertLifecycleEvent(asset, "REPAIR_CANCELED", assetFromStatus,
            asset.status, repair.failureCode, repair.resolution, repair.actualCostAmount, repair.downtimeStart,
            repair.downtimeEnd, repair.siteTaskId, repair.id, null, repair.unavailableBlockId, repair.metadataJson);
        BusinessEventRecordRequest event = assetBusinessEvent(BusinessEventType.ASSET_REPAIR_RECORDED,
            "ASSET_REPAIR", repair.id,
            assetRepairOperationKey(repair.id, "canceled"), repairFromStatus, REPAIR_CANCELED, repair.failureCode,
            repair.resolution)
            .relatedEntity("ASSET", asset.id)
            .metadata("venueId", repair.venueId)
            .metadata("spaceId", repair.spaceId)
            .metadata("unavailableBlockId", repair.unavailableBlockId);
        businessEventService.record(event);
        return repair;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> assetContext(AssetQuery query) {
        AssetQuery safeQuery = safeQuery(query);
        List<Long> scopedVenueIds = null;
        if (safeQuery.venueId != null) {
            venueAccessService.requireVenueManageAccess(safeQuery.venueId);
        } else {
            scopedVenueIds = venueAccessService.manageableVenueIds();
            if (scopedVenueIds != null && scopedVenueIds.isEmpty()) {
                return assetContextResult(0, zeroStatusMap(), 0, 0, 0);
            }
        }

        long totalAssets = countAssets(safeQuery, scopedVenueIds, null);
        Map<String, Long> assetsByStatus = new LinkedHashMap<>();
        for (String status : List.of(STATUS_STOCKED, STATUS_IN_USE, STATUS_REPAIRING, STATUS_INSPECTION_DUE,
            STATUS_OUT_OF_SERVICE, STATUS_RETIRED)) {
            assetsByStatus.put(status, countAssets(safeQuery, scopedVenueIds, status));
        }
        long inspectionCount = countInspections(safeQuery, scopedVenueIds);
        long openRepairCount = countRepairs(safeQuery, scopedVenueIds, List.of(REPAIR_REQUESTED, REPAIR_IN_PROGRESS));
        long completedRepairCount = countRepairs(safeQuery, scopedVenueIds, List.of(REPAIR_COMPLETED));
        return assetContextResult(totalAssets, assetsByStatus, inspectionCount, openRepairCount, completedRepairCount);
    }

    private SaveUnavailableBlockRequest blockRequest(AssetItem asset, AssetRepairRecord repair,
                                                     AssetRepairRequest request) {
        SaveUnavailableBlockRequest blockRequest = new SaveUnavailableBlockRequest();
        blockRequest.venueId = asset.venueId;
        blockRequest.spaceId = asset.spaceId;
        blockRequest.blockType = "MAINTENANCE";
        blockRequest.reasonCode = "ASSET_REPAIR";
        blockRequest.reasonText = repair.failureDescription;
        blockRequest.startTime = request.blockStartTime;
        blockRequest.endTime = request.blockEndTime;
        blockRequest.sourceType = SOURCE_ADMIN;
        blockRequest.relatedEntityType = "ASSET_REPAIR";
        blockRequest.relatedEntityId = repair.id;
        blockRequest.idempotencyKey = "ASSET_REPAIR_BLOCK:" + repair.id;
        blockRequest.metadataJson = "{\"assetId\":" + asset.id + "}";
        return blockRequest;
    }

    private SaveUnavailableBlockRequest blockRequest(AssetItem asset, String toStatus, AssetStatusRequest request) {
        SaveUnavailableBlockRequest blockRequest = new SaveUnavailableBlockRequest();
        blockRequest.venueId = asset.venueId;
        blockRequest.spaceId = asset.spaceId;
        blockRequest.blockType = "MAINTENANCE";
        blockRequest.reasonCode = defaultBlockReasonCode(request.reasonCode);
        blockRequest.reasonText = blankToDefault(request.reasonText, "资产状态不可用");
        blockRequest.startTime = request.blockStartTime;
        blockRequest.endTime = request.blockEndTime;
        blockRequest.sourceType = SOURCE_ADMIN;
        blockRequest.relatedEntityType = "ASSET_STATUS";
        blockRequest.relatedEntityId = asset.id;
        blockRequest.idempotencyKey = "ASSET_STATUS_BLOCK:" + asset.id + ":" + toStatus;
        blockRequest.metadataJson = "{\"assetId\":" + asset.id + ",\"toStatus\":\"" + toStatus + "\"}";
        return blockRequest;
    }

    private AssetItem requireAsset(Long assetId) {
        if (assetId == null) {
            throw BusinessException.badRequest("资产ID不能为空");
        }
        AssetItem asset = assetMapper.selectById(assetId);
        if (asset == null || Integer.valueOf(1).equals(asset.deleted)) {
            throw BusinessException.notFound("资产不存在");
        }
        return asset;
    }

    private AssetItem requireAssetForUpdate(Long assetId) {
        if (assetId == null) {
            throw BusinessException.badRequest("资产ID不能为空");
        }
        AssetItem asset = assetMapper.selectByIdForUpdate(assetId);
        if (asset == null || Integer.valueOf(1).equals(asset.deleted)) {
            throw BusinessException.notFound("资产不存在");
        }
        return asset;
    }

    private void rejectOpenRepair(Long assetId) {
        if (hasOpenRepair(assetId)) {
            throw BusinessException.conflict("资产已有开放维修");
        }
    }

    // A26-P3-20：responsibleUserId/inspectorUserId/repairOwnerId 非空时校验存在+启用+场馆归属，
    // 对照 SiteOpsService.requireAssignedUserForVenue 同型模式（AUD-P2-03 幻影 ID 同类残留，
    // 资产侧原遗漏）。这里校验的是被指派人（不是当前登录用户），不能用 VenueAccessService。
    private void requireAssignedUserForVenue(Long userId, Long venueId) {
        entityExistenceService.requireActiveUser(userId);
        int relationCount = venueAdminRelationMapper.countRelation(userId, venueId);
        if (relationCount <= 0) {
            throw BusinessException.badRequest("指派人员不属于该场馆");
        }
    }

    private boolean hasOpenRepair(Long assetId) {
        Long count = repairMapper.selectCount(new QueryWrapper<AssetRepairRecord>()
            .eq("asset_id", assetId)
            .eq("deleted", 0)
            .in("status", List.of(REPAIR_REQUESTED, REPAIR_IN_PROGRESS)));
        return count != null && count > 0;
    }

    private void requireMutableAsset(AssetItem asset) {
        if (asset != null && STATUS_RETIRED.equals(asset.status)) {
            throw BusinessException.conflict(RETIRED_ASSET_IMMUTABLE_MESSAGE);
        }
    }

    private AssetRepairRecord requireRepair(Long repairId) {
        if (repairId == null) {
            throw BusinessException.badRequest("维修ID不能为空");
        }
        AssetRepairRecord repair = repairMapper.selectById(repairId);
        if (repair == null || Integer.valueOf(1).equals(repair.deleted)) {
            throw BusinessException.notFound("维修记录不存在");
        }
        return repair;
    }

    // AUD-P2-02: FOR UPDATE 行锁读取维修单，确保完成/取消串行化，避免终态被并发覆盖
    private AssetRepairRecord requireRepairForUpdate(Long repairId) {
        if (repairId == null) {
            throw BusinessException.badRequest("维修ID不能为空");
        }
        AssetRepairRecord repair = repairMapper.selectByIdForUpdate(repairId);
        if (repair == null || Integer.valueOf(1).equals(repair.deleted)) {
            throw BusinessException.notFound("维修记录不存在");
        }
        return repair;
    }

    private AssetQuery safeQuery(AssetQuery query) {
        return query == null ? new AssetQuery() : query;
    }

    private <T> boolean applyVenueScope(QueryWrapper<T> wrapper, AssetQuery query) {
        if (query.venueId != null) {
            venueAccessService.requireVenueManageAccess(query.venueId);
            wrapper.eq("venue_id", query.venueId);
            return true;
        }
        List<Long> manageableVenueIds = venueAccessService.manageableVenueIds();
        if (manageableVenueIds != null) {
            if (manageableVenueIds.isEmpty()) {
                return false;
            }
            wrapper.in("venue_id", manageableVenueIds);
        }
        return true;
    }

    private <T> void applyAssetQuery(QueryWrapper<T> wrapper, AssetQuery query, String assetIdColumn,
                                     boolean includeStatus) {
        if (query.spaceId != null) {
            wrapper.eq("space_id", query.spaceId);
        }
        if (query.assetId != null) {
            wrapper.eq(assetIdColumn, query.assetId);
        }
        if (includeStatus && query.status != null && !query.status.isBlank()) {
            wrapper.eq("status", query.status.trim());
        }
    }

    private <T> PageResult<T> emptyPage(AssetQuery query) {
        return PageResult.of(List.of(), 0, query.getPageNum(), query.getPageSize());
    }

    private long countAssets(AssetQuery query, List<Long> scopedVenueIds, String status) {
        QueryWrapper<AssetItem> wrapper = new QueryWrapper<AssetItem>().eq("deleted", 0);
        applyScopedVenue(wrapper, query, scopedVenueIds);
        if (query.spaceId != null) {
            wrapper.eq("space_id", query.spaceId);
        }
        if (query.assetId != null) {
            wrapper.eq("id", query.assetId);
        }
        if (status != null) {
            wrapper.eq("status", status);
        }
        return nullToZero(assetMapper.selectCount(wrapper));
    }

    private long countInspections(AssetQuery query, List<Long> scopedVenueIds) {
        QueryWrapper<AssetInspectionRecord> wrapper = new QueryWrapper<AssetInspectionRecord>().eq("deleted", 0);
        applyScopedVenue(wrapper, query, scopedVenueIds);
        if (query.spaceId != null) {
            wrapper.eq("space_id", query.spaceId);
        }
        if (query.assetId != null) {
            wrapper.eq("asset_id", query.assetId);
        }
        return nullToZero(inspectionMapper.selectCount(wrapper));
    }

    private long countRepairs(AssetQuery query, List<Long> scopedVenueIds, List<String> statuses) {
        QueryWrapper<AssetRepairRecord> wrapper = new QueryWrapper<AssetRepairRecord>().eq("deleted", 0);
        applyScopedVenue(wrapper, query, scopedVenueIds);
        if (query.spaceId != null) {
            wrapper.eq("space_id", query.spaceId);
        }
        if (query.assetId != null) {
            wrapper.eq("asset_id", query.assetId);
        }
        wrapper.in("status", statuses);
        return nullToZero(repairMapper.selectCount(wrapper));
    }

    private <T> void applyScopedVenue(QueryWrapper<T> wrapper, AssetQuery query, List<Long> scopedVenueIds) {
        if (query.venueId != null) {
            wrapper.eq("venue_id", query.venueId);
        } else if (scopedVenueIds != null) {
            wrapper.in("venue_id", scopedVenueIds);
        }
    }

    private Map<String, Long> zeroStatusMap() {
        Map<String, Long> statuses = new LinkedHashMap<>();
        for (String status : List.of(STATUS_STOCKED, STATUS_IN_USE, STATUS_REPAIRING, STATUS_INSPECTION_DUE,
            STATUS_OUT_OF_SERVICE, STATUS_RETIRED)) {
            statuses.put(status, 0L);
        }
        return statuses;
    }

    private Map<String, Object> assetContextResult(long totalAssets,
                                                   Map<String, Long> assetsByStatus,
                                                   long inspectionCount,
                                                   long openRepairCount,
                                                   long completedRepairCount) {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("totalAssets", totalAssets);
        context.put("assetsByStatus", assetsByStatus);
        context.put("inspectionCount", inspectionCount);
        context.put("openRepairCount", openRepairCount);
        context.put("completedRepairCount", completedRepairCount);
        context.put("dataGaps", List.of("NO_IOT_PROVIDER", "NO_SUPPLIER_BILL_PROVIDER",
            "NO_REAL_EXTERNAL_OBSERVATION", "NO_B9_COMPLAINT_RESULT"));
        return context;
    }

    private long nullToZero(Long value) {
        return value == null ? 0L : value;
    }

    private AssetLifecycleEvent insertLifecycleEvent(AssetItem asset,
                                                     String eventType,
                                                     String fromStatus,
                                                     String toStatus,
                                                     String reasonCode,
                                                     String reasonText,
                                                     BigDecimal costAmount,
                                                     LocalDateTime downtimeStart,
                                                     LocalDateTime downtimeEnd,
                                                     Long siteTaskId,
                                                     Long repairRecordId,
                                                     Long inspectionRecordId,
                                                     Long unavailableBlockId,
                                                     String metadataJson) {
        AssetLifecycleEvent event = baseLifecycleEvent(eventType, asset.id, asset.venueId, asset.spaceId, fromStatus,
            toStatus, reasonCode, reasonText, costAmount, downtimeStart, downtimeEnd, siteTaskId, repairRecordId,
            inspectionRecordId, unavailableBlockId, metadataJson);
        lifecycleEventMapper.insert(event);
        return event;
    }

    private AssetLifecycleEvent insertLifecycleEvent(AssetRepairRecord repair,
                                                     String eventType,
                                                     String fromStatus,
                                                     String toStatus,
                                                     String reasonCode,
                                                     String reasonText,
                                                     BigDecimal costAmount,
                                                     LocalDateTime downtimeStart,
                                                     LocalDateTime downtimeEnd,
                                                     Long siteTaskId,
                                                     Long repairRecordId,
                                                     Long inspectionRecordId,
                                                     Long unavailableBlockId,
                                                     String metadataJson) {
        AssetLifecycleEvent event = baseLifecycleEvent(eventType, repair.assetId, repair.venueId, repair.spaceId,
            fromStatus, toStatus, reasonCode, reasonText, costAmount, downtimeStart, downtimeEnd, siteTaskId,
            repairRecordId, inspectionRecordId, unavailableBlockId, metadataJson);
        lifecycleEventMapper.insert(event);
        return event;
    }

    private AssetLifecycleEvent baseLifecycleEvent(String eventType,
                                                   Long assetId,
                                                   Long venueId,
                                                   Long spaceId,
                                                   String fromStatus,
                                                   String toStatus,
                                                   String reasonCode,
                                                   String reasonText,
                                                   BigDecimal costAmount,
                                                   LocalDateTime downtimeStart,
                                                   LocalDateTime downtimeEnd,
                                                   Long siteTaskId,
                                                   Long repairRecordId,
                                                   Long inspectionRecordId,
                                                   Long unavailableBlockId,
                                                   String metadataJson) {
        LocalDateTime now = LocalDateTime.now();
        AssetLifecycleEvent event = new AssetLifecycleEvent();
        event.eventNo = nextNo("ASSET_EVENT");
        event.assetId = assetId;
        event.venueId = venueId;
        event.spaceId = spaceId;
        event.eventType = eventType;
        event.fromStatus = blankToNull(fromStatus);
        event.toStatus = blankToNull(toStatus);
        event.operatorUserId = SecurityContext.currentUserId();
        event.sourceType = SOURCE_ADMIN;
        event.reasonCode = blankToNull(reasonCode);
        event.reasonText = blankToNull(reasonText);
        event.costAmount = amountOrZero(costAmount);
        event.downtimeStart = downtimeStart;
        event.downtimeEnd = downtimeEnd;
        event.siteTaskId = siteTaskId;
        event.repairRecordId = repairRecordId;
        event.inspectionRecordId = inspectionRecordId;
        event.unavailableBlockId = unavailableBlockId;
        event.metadataJson = blankToNull(metadataJson);
        event.occurredAt = now;
        event.createTime = now;
        return event;
    }

    private BusinessEventRecordRequest assetBusinessEvent(BusinessEventType eventType,
                                                          String entityType,
                                                          Long entityId,
                                                          String idempotencyKey,
                                                          String fromStatus,
                                                          String toStatus,
                                                          String reasonCode,
                                                          String reasonText) {
        return BusinessEventRecordRequest.of(
                eventType,
                BusinessDomain.ASSET,
                entityType,
                entityId,
                BusinessSourceType.ADMIN,
                "SUCCESS")
            .idempotencyKey(eventType.name() + ":" + idempotencyKey)
            .fromStatus(fromStatus)
            .toStatus(toStatus)
            .reasonCode(reasonCode)
            .reasonText(reasonText);
    }

    private String assetCreateOperationKey(Long assetId) {
        return "asset/create/" + assetId;
    }

    private String assetStatusOperationKey(Long assetId, String fromStatus, String toStatus,
                                           AssetLifecycleEvent lifecycleEvent) {
        String lifecycleEventKey = lifecycleEvent.id == null ? lifecycleEvent.eventNo : lifecycleEvent.id.toString();
        return "asset/status/" + assetId + "/" + fromStatus + "/" + toStatus
            + "/lifecycle-event/" + lifecycleEventKey;
    }

    private String assetInspectionOperationKey(Long inspectionId) {
        return "asset/inspection/" + inspectionId;
    }

    private String assetRepairOperationKey(Long repairId, String transition) {
        return "asset/repair/" + repairId + "/" + transition;
    }

    private String nextNo(String prefix) {
        return prefix + LocalDateTime.now().format(NO_TIME)
            + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
    }

    private void validateDowntime(LocalDateTime start, LocalDateTime end) {
        if (start != null || end != null) {
            requireTimeRange(start, end, "停用开始时间必须早于结束时间");
        }
    }

    private boolean hasBlockWindow(AssetRepairRequest request) {
        if (request.blockStartTime == null && request.blockEndTime == null) {
            return false;
        }
        if (request.blockStartTime == null || request.blockEndTime == null) {
            throw BusinessException.badRequest("不可售开始和结束时间不能为空");
        }
        return true;
    }

    private boolean hasBlockWindow(AssetStatusRequest request) {
        if (request.blockStartTime == null && request.blockEndTime == null) {
            return false;
        }
        if (request.blockStartTime == null || request.blockEndTime == null) {
            throw BusinessException.badRequest("不可售开始和结束时间不能为空");
        }
        return true;
    }

    private String defaultBlockReasonCode(String reasonCode) {
        return isBlank(reasonCode) ? "ASSET_REPAIR" : reasonCode.trim().toUpperCase(Locale.ROOT);
    }

    private void requireTimeRange(LocalDateTime start, LocalDateTime end, String message) {
        if (start == null || end == null || !start.isBefore(end)) {
            throw BusinessException.badRequest(message);
        }
    }

    private void requireVenueId(Long venueId) {
        if (venueId == null) {
            throw BusinessException.badRequest("场馆不能为空");
        }
    }

    private BigDecimal amountOrZero(BigDecimal amount) {
        return amount == null ? BigDecimal.ZERO : amount;
    }

    private String normalize(String value) {
        return value == null ? null : value.trim().toUpperCase(Locale.ROOT);
    }

    private String blankToDefault(String value, String defaultValue) {
        String text = blankToNull(value);
        return text == null ? defaultValue : text;
    }

    private String blankToNull(String value) {
        return isBlank(value) ? null : value.trim();
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
