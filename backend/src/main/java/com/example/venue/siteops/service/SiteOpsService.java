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
import com.example.venue.common.PageResult;
import com.example.venue.security.SecurityContext;
import com.example.venue.siteops.dto.SiteCancelRequest;
import com.example.venue.siteops.dto.SiteHandoverRequest;
import com.example.venue.siteops.dto.SiteIncidentResolveRequest;
import com.example.venue.siteops.dto.SiteIncidentRequest;
import com.example.venue.siteops.dto.SiteOpsQuery;
import com.example.venue.siteops.dto.SiteShiftRequest;
import com.example.venue.siteops.dto.SiteTaskCompleteRequest;
import com.example.venue.siteops.dto.SiteTaskRequest;
import com.example.venue.siteops.entity.SiteIncidentLog;
import com.example.venue.siteops.entity.SiteShift;
import com.example.venue.siteops.entity.SiteShiftHandover;
import com.example.venue.siteops.entity.SiteTask;
import com.example.venue.siteops.mapper.SiteIncidentLogMapper;
import com.example.venue.siteops.mapper.SiteShiftHandoverMapper;
import com.example.venue.siteops.mapper.SiteShiftMapper;
import com.example.venue.siteops.mapper.SiteTaskMapper;
import com.example.venue.venue.mapper.VenueAdminRelationMapper;
import com.example.venue.venue.service.VenueAccessService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

@Service
public class SiteOpsService {
    private static final DateTimeFormatter NO_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final String SOURCE_ADMIN = "ADMIN";
    private static final String STATUS_PLANNED = "PLANNED";
    private static final String STATUS_IN_PROGRESS = "IN_PROGRESS";
    private static final String STATUS_COMPLETED = "COMPLETED";
    private static final String STATUS_TODO = "TODO";
    private static final String STATUS_DONE = "DONE";
    private static final String STATUS_EXCEPTION = "EXCEPTION";
    private static final String STATUS_PENDING = "PENDING";
    private static final String STATUS_SUBMITTED = "SUBMITTED";
    private static final String STATUS_ACCEPTED = "ACCEPTED";
    private static final String STATUS_OPEN = "OPEN";
    private static final String STATUS_RESOLVED = "RESOLVED";
    private static final String STATUS_CANCELED = "CANCELED";

    private final SiteShiftMapper shiftMapper;
    private final SiteTaskMapper taskMapper;
    private final SiteShiftHandoverMapper handoverMapper;
    private final SiteIncidentLogMapper incidentMapper;
    private final VenueAccessService venueAccessService;
    private final BusinessEventService businessEventService;
    private final EntityExistenceService entityExistenceService;
    private final VenueAdminRelationMapper venueAdminRelationMapper;

    public SiteOpsService(SiteShiftMapper shiftMapper,
                          SiteTaskMapper taskMapper,
                          SiteShiftHandoverMapper handoverMapper,
                          SiteIncidentLogMapper incidentMapper,
                          VenueAccessService venueAccessService,
                          BusinessEventService businessEventService,
                          EntityExistenceService entityExistenceService,
                          VenueAdminRelationMapper venueAdminRelationMapper) {
        this.shiftMapper = shiftMapper;
        this.taskMapper = taskMapper;
        this.handoverMapper = handoverMapper;
        this.incidentMapper = incidentMapper;
        this.venueAccessService = venueAccessService;
        this.businessEventService = businessEventService;
        this.entityExistenceService = entityExistenceService;
        this.venueAdminRelationMapper = venueAdminRelationMapper;
    }

    @Transactional(readOnly = true)
    public PageResult<SiteShift> shifts(SiteOpsQuery query) {
        SiteOpsQuery safeQuery = safeQuery(query);
        QueryWrapper<SiteShift> wrapper = new QueryWrapper<SiteShift>().eq("deleted", 0);
        if (!applyVenueScope(wrapper, safeQuery)) {
            return emptyPage(safeQuery);
        }
        applyCommonSiteQuery(wrapper, safeQuery);
        if (safeQuery.date != null) {
            wrapper.eq("shift_date", safeQuery.date);
        }
        if (safeQuery.status != null && !safeQuery.status.isBlank()) {
            wrapper.eq("status", safeQuery.status.trim());
        }
        Page<SiteShift> page = shiftMapper.selectPage(new Page<>(safeQuery.getPageNum(), safeQuery.getPageSize()),
            wrapper.orderByDesc("id"));
        return PageResult.of(page.getRecords(), page.getTotal(), safeQuery.getPageNum(), safeQuery.getPageSize());
    }

    @Transactional
    public SiteShift createShift(SiteShiftRequest request) {
        if (request == null) {
            throw BusinessException.badRequest("班次参数不能为空");
        }
        requireVenueId(request.venueId);
        requireTimeRange(request.plannedStartTime, request.plannedEndTime);
        if (request.shiftDate == null) {
            throw BusinessException.badRequest("班次日期不能为空");
        }
        if (isBlank(request.shiftName) || isBlank(request.roleCode) || request.assignedUserId == null) {
            throw BusinessException.badRequest("班次信息不完整");
        }
        venueAccessService.requireVenueManageAccess(request.venueId);
        // AUD-P2-03：assignedUserId 必须是存在+启用且与场馆关联的有效用户，避免写入幻影 ID。
        requireAssignedUserForVenue(request.assignedUserId, request.venueId);
        lockShiftAssignment(request);
        rejectShiftOverlap(request);

        LocalDateTime now = LocalDateTime.now();
        SiteShift shift = new SiteShift();
        shift.shiftNo = nextNo("SITE_SHIFT");
        shift.venueId = request.venueId;
        shift.spaceId = request.spaceId;
        shift.shiftDate = request.shiftDate;
        shift.shiftName = request.shiftName;
        shift.roleCode = request.roleCode;
        shift.assignedUserId = request.assignedUserId;
        shift.plannedStartTime = request.plannedStartTime;
        shift.plannedEndTime = request.plannedEndTime;
        shift.status = STATUS_PLANNED;
        shift.sourceType = SOURCE_ADMIN;
        shift.handoverStatus = "NONE";
        shift.metadataJson = blankToNull(request.metadataJson);
        shift.createTime = now;
        shift.updateTime = now;
        shift.deleted = 0;
        shiftMapper.insert(shift);

        BusinessEventRecordRequest event = siteEvent(BusinessEventType.SITE_SHIFT_CREATED, "SITE_SHIFT", shift.id,
            "SITE_SHIFT_CREATED:" + shift.shiftNo, null, STATUS_PLANNED, null, null)
            .metadata("shiftNo", shift.shiftNo)
            .metadata("venueId", shift.venueId)
            .metadata("spaceId", shift.spaceId)
            .metadata("assignedUserId", shift.assignedUserId)
            .metadata("plannedStartTime", shift.plannedStartTime)
            .metadata("plannedEndTime", shift.plannedEndTime);
        businessEventService.record(event);
        return shift;
    }

    @Transactional
    public SiteShift updateShift(Long shiftId, SiteShiftRequest request) {
        SiteShift shift = requireShift(shiftId);
        venueAccessService.requireVenueManageAccess(shift.venueId);
        if (request == null) {
            throw BusinessException.badRequest("班次参数不能为空");
        }
        requireVenueId(request.venueId);
        requireTimeRange(request.plannedStartTime, request.plannedEndTime);
        if (request.shiftDate == null) {
            throw BusinessException.badRequest("班次日期不能为空");
        }
        if (isBlank(request.shiftName) || isBlank(request.roleCode) || request.assignedUserId == null) {
            throw BusinessException.badRequest("班次信息不完整");
        }
        if (STATUS_COMPLETED.equals(shift.status)) {
            if (!sameCompletedShiftOwnership(shift, request)) {
                throw BusinessException.conflict("已完成班次不可改写核心归属");
            }
            shift.shiftName = request.shiftName;
            shift.metadataJson = blankToNull(request.metadataJson);
            shift.updateTime = LocalDateTime.now();
            // A26-P2-16：metadata_json 显式 set，允许置空落库（避免 NOT_NULL 策略静默丢弃）。
            shiftMapper.update(shift, new UpdateWrapper<SiteShift>()
                .eq("id", shiftId)
                .eq("deleted", 0)
                .set("metadata_json", shift.metadataJson));
            return shift;
        }
        if (!request.venueId.equals(shift.venueId)) {
            venueAccessService.requireVenueManageAccess(request.venueId);
        }
        // A26-P2-12：更新路径同样要校验被指派人存在+启用+场馆归属，避免写入幻影 ID。
        requireAssignedUserForVenue(request.assignedUserId, request.venueId);
        lockShiftAssignment(request);
        rejectShiftOverlap(request, shift.id);

        shift.venueId = request.venueId;
        shift.spaceId = request.spaceId;
        shift.shiftDate = request.shiftDate;
        shift.shiftName = request.shiftName;
        shift.roleCode = request.roleCode;
        shift.assignedUserId = request.assignedUserId;
        shift.plannedStartTime = request.plannedStartTime;
        shift.plannedEndTime = request.plannedEndTime;
        shift.metadataJson = blankToNull(request.metadataJson);
        shift.updateTime = LocalDateTime.now();
        // A26-P2-16：可空字段显式 set，允许 null 落库（避免 NOT_NULL 策略静默丢弃 spaceId/metadataJson）。
        shiftMapper.update(shift, new UpdateWrapper<SiteShift>()
            .eq("id", shiftId)
            .eq("deleted", 0)
            .set("space_id", shift.spaceId)
            .set("metadata_json", shift.metadataJson));
        return shift;
    }

    @Transactional
    public SiteShift clockIn(Long shiftId) {
        SiteShift shift = requireShift(shiftId);
        venueAccessService.requireVenueManageAccess(shift.venueId);
        if (!STATUS_PLANNED.equals(shift.status)) {
            throw BusinessException.conflict("当前班次状态不可签到");
        }
        String fromStatus = shift.status;
        LocalDateTime now = LocalDateTime.now();
        shift.status = STATUS_IN_PROGRESS;
        shift.actualClockInTime = now;
        shift.updateTime = now;
        shiftMapper.updateById(shift);

        BusinessEventRecordRequest event = siteEvent(BusinessEventType.SITE_SHIFT_CLOCKED_IN, "SITE_SHIFT", shift.id,
            "SITE_SHIFT_CLOCKED_IN:" + shift.id, fromStatus, STATUS_IN_PROGRESS, null, null)
            .metadata("shiftNo", shift.shiftNo)
            .metadata("venueId", shift.venueId)
            .metadata("clockInTime", shift.actualClockInTime);
        businessEventService.record(event);
        return shift;
    }

    @Transactional
    public SiteShift clockOut(Long shiftId) {
        SiteShift shift = requireShift(shiftId);
        venueAccessService.requireVenueManageAccess(shift.venueId);
        if (!STATUS_IN_PROGRESS.equals(shift.status)) {
            throw BusinessException.conflict("当前班次状态不可签退");
        }
        if (hasUnfinishedTasks(shift) && !hasValidHandover(shift)) {
            throw BusinessException.conflict("存在未完成任务，请先完成交接确认");
        }
        String fromStatus = shift.status;
        LocalDateTime now = LocalDateTime.now();
        shift.status = STATUS_COMPLETED;
        shift.actualClockOutTime = now;
        shift.updateTime = now;
        shiftMapper.updateById(shift);

        BusinessEventRecordRequest event = siteEvent(BusinessEventType.SITE_SHIFT_COMPLETED, "SITE_SHIFT", shift.id,
            "SITE_SHIFT_COMPLETED:" + shift.id, fromStatus, STATUS_COMPLETED, null, null)
            .metadata("shiftNo", shift.shiftNo)
            .metadata("venueId", shift.venueId)
            .metadata("clockOutTime", shift.actualClockOutTime);
        businessEventService.record(event);
        return shift;
    }

    @Transactional
    public SiteTask createTask(SiteTaskRequest request) {
        if (request == null) {
            throw BusinessException.badRequest("任务参数不能为空");
        }
        requireVenueId(request.venueId);
        if (isBlank(request.taskType) || isBlank(request.title)) {
            throw BusinessException.badRequest("任务信息不完整");
        }
        venueAccessService.requireVenueManageAccess(request.venueId);
        requireAssociatedShiftVenue(request.shiftId, request.venueId);
        // AUD-P2-03：assignedUserId 非空时必须是存在+启用且与场馆关联的有效用户，
        // 任务允许不指派（assignedUserId 可为 null），但一旦指派必须校验，避免写入幻影 ID。
        if (request.assignedUserId != null) {
            requireAssignedUserForVenue(request.assignedUserId, request.venueId);
        }

        LocalDateTime now = LocalDateTime.now();
        SiteTask task = new SiteTask();
        task.taskNo = nextNo("SITE_TASK");
        task.venueId = request.venueId;
        task.spaceId = request.spaceId;
        task.shiftId = request.shiftId;
        task.taskType = request.taskType;
        task.title = request.title;
        task.priority = blankToDefault(request.priority, "NORMAL");
        task.status = STATUS_TODO;
        task.assignedUserId = request.assignedUserId;
        task.reservationId = request.reservationId;
        task.orderId = request.orderId;
        task.lifecycleId = request.lifecycleId;
        task.assetId = request.assetId;
        task.unavailableBlockId = request.unavailableBlockId;
        task.complaintId = request.complaintId;
        task.dueTime = request.dueTime;
        task.sourceType = SOURCE_ADMIN;
        task.metadataJson = blankToNull(request.metadataJson);
        task.createTime = now;
        task.updateTime = now;
        task.deleted = 0;
        taskMapper.insert(task);

        BusinessEventRecordRequest event = siteEvent(BusinessEventType.SITE_TASK_CREATED, "SITE_TASK", task.id,
            "SITE_TASK_CREATED:" + task.taskNo, null, STATUS_TODO, null, null)
            .metadata("taskNo", task.taskNo)
            .metadata("venueId", task.venueId)
            .metadata("spaceId", task.spaceId)
            .metadata("shiftId", task.shiftId)
            .metadata("taskType", task.taskType)
            .metadata("priority", task.priority);
        businessEventService.record(event);
        return task;
    }

    @Transactional(readOnly = true)
    public PageResult<SiteTask> tasks(SiteOpsQuery query) {
        SiteOpsQuery safeQuery = safeQuery(query);
        QueryWrapper<SiteTask> wrapper = new QueryWrapper<SiteTask>().eq("deleted", 0);
        if (!applyVenueScope(wrapper, safeQuery)) {
            return emptyPage(safeQuery);
        }
        applyCommonSiteQuery(wrapper, safeQuery);
        if (safeQuery.shiftId != null) {
            wrapper.eq("shift_id", safeQuery.shiftId);
        }
        if (safeQuery.assetId != null) {
            wrapper.eq("asset_id", safeQuery.assetId);
        }
        if (safeQuery.status != null && !safeQuery.status.isBlank()) {
            wrapper.eq("status", safeQuery.status.trim());
        }
        Page<SiteTask> page = taskMapper.selectPage(new Page<>(safeQuery.getPageNum(), safeQuery.getPageSize()),
            wrapper.orderByDesc("id"));
        return PageResult.of(page.getRecords(), page.getTotal(), safeQuery.getPageNum(), safeQuery.getPageSize());
    }

    @Transactional
    public SiteTask updateTask(Long taskId, SiteTaskRequest request) {
        SiteTask task = requireTask(taskId);
        venueAccessService.requireVenueManageAccess(task.venueId);
        if (request == null) {
            throw BusinessException.badRequest("任务参数不能为空");
        }
        requireVenueId(request.venueId);
        if (isBlank(request.taskType) || isBlank(request.title)) {
            throw BusinessException.badRequest("任务信息不完整");
        }
        if (isTerminalTaskStatus(task.status)) {
            if (!sameTerminalTaskOwnership(task, request)) {
                throw BusinessException.conflict("终态任务不可改写核心归属");
            }
            task.title = request.title;
            task.priority = blankToDefault(request.priority, "NORMAL");
            task.dueTime = request.dueTime;
            task.metadataJson = blankToNull(request.metadataJson);
            task.updateTime = LocalDateTime.now();
            // A26-P2-16：可空字段显式 set，允许 null 落库（避免 NOT_NULL 策略静默丢弃 dueTime/metadataJson）。
            taskMapper.update(task, new UpdateWrapper<SiteTask>()
                .eq("id", taskId)
                .eq("deleted", 0)
                .set("due_time", task.dueTime)
                .set("metadata_json", task.metadataJson));
            return task;
        }
        if (!request.venueId.equals(task.venueId)) {
            venueAccessService.requireVenueManageAccess(request.venueId);
        }
        requireAssociatedShiftVenue(request.shiftId, request.venueId);
        // A26-P2-12：assignedUserId 非空时校验存在+启用+场馆归属；任务允许不指派（null 不校验）。
        if (request.assignedUserId != null) {
            requireAssignedUserForVenue(request.assignedUserId, request.venueId);
        }

        task.venueId = request.venueId;
        task.spaceId = request.spaceId;
        task.shiftId = request.shiftId;
        task.taskType = request.taskType;
        task.title = request.title;
        task.priority = blankToDefault(request.priority, "NORMAL");
        task.assignedUserId = request.assignedUserId;
        task.reservationId = request.reservationId;
        task.orderId = request.orderId;
        task.lifecycleId = request.lifecycleId;
        task.assetId = request.assetId;
        task.unavailableBlockId = request.unavailableBlockId;
        task.complaintId = request.complaintId;
        task.dueTime = request.dueTime;
        task.metadataJson = blankToNull(request.metadataJson);
        task.updateTime = LocalDateTime.now();
        // A26-P2-16：可空字段显式 set，允许 null 落库（避免 NOT_NULL 策略静默丢弃指派人/关联 ID/dueTime/metadataJson）。
        taskMapper.update(task, new UpdateWrapper<SiteTask>()
            .eq("id", taskId)
            .eq("deleted", 0)
            .set("space_id", task.spaceId)
            .set("shift_id", task.shiftId)
            .set("assigned_user_id", task.assignedUserId)
            .set("reservation_id", task.reservationId)
            .set("order_id", task.orderId)
            .set("lifecycle_id", task.lifecycleId)
            .set("asset_id", task.assetId)
            .set("unavailable_block_id", task.unavailableBlockId)
            .set("complaint_id", task.complaintId)
            .set("due_time", task.dueTime)
            .set("metadata_json", task.metadataJson));
        return task;
    }

    @Transactional
    public SiteTask startTask(Long taskId) {
        SiteTask task = requireTask(taskId);
        venueAccessService.requireVenueManageAccess(task.venueId);
        if (!isStartableTaskStatus(task.status)) {
            throw BusinessException.conflict("当前任务状态不可开始");
        }
        LocalDateTime now = LocalDateTime.now();
        task.status = STATUS_IN_PROGRESS;
        task.startedAt = now;
        task.updateTime = now;
        taskMapper.updateById(task);
        return task;
    }

    @Transactional
    public SiteTask completeTask(Long taskId, SiteTaskCompleteRequest request) {
        // AUD-P2-01：先 FOR UPDATE 锁行，避免后写者覆盖先写终态导致完成说明/异常码/证据丢失。
        SiteTask task = requireTaskForUpdate(taskId);
        venueAccessService.requireVenueManageAccess(task.venueId);
        if (request == null || isBlank(request.completionNote)) {
            throw BusinessException.badRequest("完成说明不能为空");
        }
        if (!isOpenTaskStatus(task.status)) {
            throw BusinessException.conflict("当前任务状态不可完成");
        }
        String fromStatus = task.status;
        String exceptionCode = blankToNull(request.exceptionCode);
        String toStatus = exceptionCode == null ? STATUS_DONE : STATUS_EXCEPTION;
        LocalDateTime now = LocalDateTime.now();
        task.status = toStatus;
        task.completedAt = now;
        task.exceptionCode = exceptionCode;
        task.completionNote = request.completionNote;
        task.evidenceJson = blankToNull(request.evidence);
        task.updateTime = now;
        // AUD-P2-01：CAS 更新，仅当 status 仍为原开放状态时成功；返回 0 行说明已被并发改终态，拒绝覆盖。
        int affected = taskMapper.update(task, new UpdateWrapper<SiteTask>()
            .eq("id", taskId)
            .eq("status", fromStatus)
            .eq("deleted", 0));
        if (affected == 0) {
            throw BusinessException.conflict("任务状态已变更");
        }

        BusinessEventRecordRequest event = siteEvent(BusinessEventType.SITE_TASK_COMPLETED, "SITE_TASK", task.id,
            "SITE_TASK_COMPLETED:" + task.id, fromStatus, toStatus, exceptionCode, request.completionNote)
            .metadata("taskNo", task.taskNo)
            .metadata("venueId", task.venueId)
            .metadata("shiftId", task.shiftId)
            .metadata("taskType", task.taskType)
            .metadata("assignedUserId", task.assignedUserId);
        businessEventService.record(event);
        return task;
    }

    @Transactional
    public SiteTask cancelTask(Long taskId, SiteCancelRequest request) {
        // AUD-P2-01：先 FOR UPDATE 锁行，避免后写者覆盖先写终态导致取消原因/时间丢失。
        SiteTask task = requireTaskForUpdate(taskId);
        venueAccessService.requireVenueManageAccess(task.venueId);
        if (!isOpenTaskStatus(task.status)) {
            throw BusinessException.conflict("当前任务状态不可取消");
        }
        String fromStatus = task.status;
        LocalDateTime now = LocalDateTime.now();
        task.status = STATUS_CANCELED;
        task.completedAt = now;
        task.completionNote = request == null ? null : blankToNull(request.reason);
        task.updateTime = now;
        // AUD-P2-01：CAS 更新，仅当 status 仍为原开放状态时成功；返回 0 行说明已被并发改终态，拒绝覆盖。
        int affected = taskMapper.update(task, new UpdateWrapper<SiteTask>()
            .eq("id", taskId)
            .eq("status", fromStatus)
            .eq("deleted", 0));
        if (affected == 0) {
            throw BusinessException.conflict("任务状态已变更");
        }
        return task;
    }

    @Transactional(readOnly = true)
    public PageResult<SiteShiftHandover> handovers(SiteOpsQuery query) {
        SiteOpsQuery safeQuery = safeQuery(query);
        QueryWrapper<SiteShiftHandover> wrapper = new QueryWrapper<SiteShiftHandover>().eq("deleted", 0);
        if (!applyVenueScope(wrapper, safeQuery)) {
            return emptyPage(safeQuery);
        }
        if (safeQuery.shiftId != null) {
            wrapper.and(item -> item.eq("from_shift_id", safeQuery.shiftId).or().eq("to_shift_id", safeQuery.shiftId));
        }
        if (safeQuery.status != null && !safeQuery.status.isBlank()) {
            wrapper.eq("status", safeQuery.status.trim());
        }
        Page<SiteShiftHandover> page = handoverMapper.selectPage(
            new Page<>(safeQuery.getPageNum(), safeQuery.getPageSize()), wrapper.orderByDesc("id"));
        return PageResult.of(page.getRecords(), page.getTotal(), safeQuery.getPageNum(), safeQuery.getPageSize());
    }

    @Transactional
    public SiteShiftHandover submitHandover(SiteHandoverRequest request) {
        if (request == null) {
            throw BusinessException.badRequest("交接参数不能为空");
        }
        requireVenueId(request.venueId);
        // AUD-P1-04：接收班次与接收人必须显式提供，原先仅校验源班次/交班人，
        // 配合 requireAssociatedShiftVenue 的 null 放行会导致接收方可被伪造。
        if (request.fromShiftId == null || request.fromUserId == null
            || request.toShiftId == null || request.toUserId == null) {
            throw BusinessException.badRequest("交接信息不完整");
        }
        venueAccessService.requireVenueManageAccess(request.venueId);
        SiteShift fromShift = requireAssociatedShiftVenue(request.fromShiftId, request.venueId);
        SiteShift toShift = requireAssociatedShiftVenue(request.toShiftId, request.venueId);
        // AUD-P1-04：禁止自交接，避免同一班次或同一人交接给自己绕过接收确认。
        if (java.util.Objects.equals(request.fromShiftId, request.toShiftId)) {
            throw BusinessException.badRequest("交接班次不可与源班次相同");
        }
        if (java.util.Objects.equals(request.fromUserId, request.toUserId)) {
            throw BusinessException.badRequest("接收人不可与交班人相同");
        }
        // AUD-P1-04：交班人/接收人必须是各自班次的实际指派人，避免伪造接收方。
        if (!java.util.Objects.equals(fromShift.assignedUserId, request.fromUserId)) {
            throw BusinessException.badRequest("交班人不是源班次的指派人");
        }
        if (!java.util.Objects.equals(toShift.assignedUserId, request.toUserId)) {
            throw BusinessException.badRequest("接收人不是接收班次的指派人");
        }
        // AUD-P1-04：同一源班次已有待接收或已接收交接时拒绝重复提交，避免覆盖式交接抹去过程。
        Long existingHandover = handoverMapper.selectCount(new QueryWrapper<SiteShiftHandover>()
            .eq("venue_id", request.venueId)
            .eq("from_shift_id", request.fromShiftId)
            .eq("deleted", 0)
            .in("status", List.of(STATUS_SUBMITTED, STATUS_ACCEPTED)));
        if (existingHandover != null && existingHandover > 0) {
            throw BusinessException.conflict("源班次已存在待接收或已接收交接");
        }
        int openTaskCount = countOpenTasks(request).intValue();

        LocalDateTime now = LocalDateTime.now();
        SiteShiftHandover handover = new SiteShiftHandover();
        handover.handoverNo = nextNo("SITE_HANDOVER");
        handover.fromShiftId = request.fromShiftId;
        handover.toShiftId = request.toShiftId;
        handover.venueId = request.venueId;
        handover.fromUserId = request.fromUserId;
        handover.toUserId = request.toUserId;
        handover.status = STATUS_SUBMITTED;
        handover.unresolvedTaskCount = openTaskCount;
        handover.incidentSummary = blankToNull(request.incidentSummary);
        handover.assetIssueSummary = blankToNull(request.assetIssueSummary);
        handover.note = blankToNull(request.note);
        handover.createTime = now;
        handover.updateTime = now;
        handover.deleted = 0;
        handoverMapper.insert(handover);
        syncHandoverShiftStatuses(fromShift, STATUS_SUBMITTED, toShift, STATUS_PENDING, now);

        BusinessEventRecordRequest event = siteEvent(BusinessEventType.SITE_HANDOVER_SUBMITTED, "SITE_HANDOVER", handover.id,
            "SITE_HANDOVER_SUBMITTED:" + handover.handoverNo, null, STATUS_SUBMITTED, null, handover.note)
            .metadata("handoverNo", handover.handoverNo)
            .metadata("venueId", handover.venueId)
            .metadata("fromShiftId", handover.fromShiftId)
            .metadata("toShiftId", handover.toShiftId)
            .metadata("unresolvedTaskCount", handover.unresolvedTaskCount);
        businessEventService.record(event);
        return handover;
    }

    @Transactional
    public SiteShiftHandover acceptHandover(Long handoverId) {
        SiteShiftHandover handover = requireHandover(handoverId);
        venueAccessService.requireVenueManageAccess(handover.venueId);
        if (!STATUS_SUBMITTED.equals(handover.status)) {
            throw BusinessException.conflict("当前交接状态不可接收");
        }
        SiteShift fromShift = requireAssociatedShiftVenue(handover.fromShiftId, handover.venueId);
        SiteShift toShift = requireAssociatedShiftVenue(handover.toShiftId, handover.venueId);
        LocalDateTime now = LocalDateTime.now();
        handover.status = STATUS_ACCEPTED;
        handover.acceptedAt = now;
        handover.updateTime = now;
        handoverMapper.updateById(handover);
        syncHandoverShiftStatuses(fromShift, STATUS_ACCEPTED, toShift, STATUS_ACCEPTED, now);
        return handover;
    }

    @Transactional(readOnly = true)
    public PageResult<SiteIncidentLog> incidents(SiteOpsQuery query) {
        SiteOpsQuery safeQuery = safeQuery(query);
        QueryWrapper<SiteIncidentLog> wrapper = new QueryWrapper<SiteIncidentLog>().eq("deleted", 0);
        if (!applyVenueScope(wrapper, safeQuery)) {
            return emptyPage(safeQuery);
        }
        applyCommonSiteQuery(wrapper, safeQuery);
        if (safeQuery.shiftId != null) {
            wrapper.eq("shift_id", safeQuery.shiftId);
        }
        if (safeQuery.assetId != null) {
            wrapper.eq("asset_id", safeQuery.assetId);
        }
        if (safeQuery.status != null && !safeQuery.status.isBlank()) {
            wrapper.eq("status", safeQuery.status.trim());
        }
        Page<SiteIncidentLog> page = incidentMapper.selectPage(
            new Page<>(safeQuery.getPageNum(), safeQuery.getPageSize()), wrapper.orderByDesc("id"));
        return PageResult.of(page.getRecords(), page.getTotal(), safeQuery.getPageNum(), safeQuery.getPageSize());
    }

    @Transactional
    public SiteIncidentLog recordIncident(SiteIncidentRequest request) {
        if (request == null) {
            throw BusinessException.badRequest("异常参数不能为空");
        }
        requireVenueId(request.venueId);
        if (isBlank(request.incidentType) || isBlank(request.severity) || isBlank(request.description)) {
            throw BusinessException.badRequest("异常信息不完整");
        }
        venueAccessService.requireVenueManageAccess(request.venueId);

        LocalDateTime now = LocalDateTime.now();
        SiteIncidentLog incident = new SiteIncidentLog();
        incident.incidentNo = nextNo("SITE_INCIDENT");
        incident.venueId = request.venueId;
        incident.spaceId = request.spaceId;
        incident.shiftId = request.shiftId;
        incident.taskId = request.taskId;
        incident.reservationId = request.reservationId;
        incident.orderId = request.orderId;
        incident.assetId = request.assetId;
        incident.complaintId = request.complaintId;
        incident.incidentType = request.incidentType;
        incident.severity = request.severity;
        incident.status = STATUS_OPEN;
        incident.occurredAt = request.occurredAt == null ? now : request.occurredAt;
        incident.reportedBy = SecurityContext.currentUserId();
        incident.description = request.description;
        incident.sourceType = SOURCE_ADMIN;
        incident.createTime = now;
        incident.updateTime = now;
        incident.deleted = 0;
        incidentMapper.insert(incident);

        BusinessEventRecordRequest event = siteEvent(BusinessEventType.SITE_INCIDENT_RECORDED, "SITE_INCIDENT", incident.id,
            "SITE_INCIDENT_RECORDED:" + incident.incidentNo, null, STATUS_OPEN, incident.incidentType,
            incident.description)
            .metadata("incidentNo", incident.incidentNo)
            .metadata("venueId", incident.venueId)
            .metadata("spaceId", incident.spaceId)
            .metadata("shiftId", incident.shiftId)
            .metadata("taskId", incident.taskId)
            .metadata("severity", incident.severity);
        businessEventService.record(event);
        return incident;
    }

    @Transactional
    public SiteIncidentLog resolveIncident(Long incidentId, SiteIncidentResolveRequest request) {
        // A26-P3-19：先 FOR UPDATE 锁行，避免并发解决时先写的处理结论被整行覆盖且无业务事件可追溯。
        SiteIncidentLog incident = requireIncidentForUpdate(incidentId);
        venueAccessService.requireVenueManageAccess(incident.venueId);
        if (request == null || isBlank(request.resolution)) {
            throw BusinessException.badRequest("处理结果不能为空");
        }
        if (!STATUS_OPEN.equals(incident.status) && !STATUS_IN_PROGRESS.equals(incident.status)) {
            throw BusinessException.conflict("当前异常状态不可解决");
        }
        String fromStatus = incident.status;
        incident.status = STATUS_RESOLVED;
        incident.resolution = request.resolution;
        incident.updateTime = LocalDateTime.now();
        // A26-P3-19：CAS 更新，仅当 status 仍为 fromStatus 时成功；返回 0 行说明已被并发改终态，拒绝覆盖。
        int affected = incidentMapper.update(incident, new UpdateWrapper<SiteIncidentLog>()
            .eq("id", incidentId)
            .eq("status", fromStatus)
            .eq("deleted", 0));
        if (affected == 0) {
            throw BusinessException.conflict("异常状态已变更");
        }
        return incident;
    }

    private void rejectShiftOverlap(SiteShiftRequest request) {
        rejectShiftOverlap(request, null);
    }

    private void rejectShiftOverlap(SiteShiftRequest request, Long excludeShiftId) {
        // A26-P2-11：恢复人级语义——同一人在重叠时段只能被排一个班次，不再按 role_code 收窄。
        // 历史 8df41f6 只按 assigned_user_id 校验；153aa77 加并发守卫时同批加入 .eq("role_code", ...)
        // 把校验收窄为"同人+同岗位"，导致同一人可被排进两个时间重叠的不同岗位班次。
        // 错误文案"人员已存在重叠班次"本就是人级语义，行为对齐文案。
        Long overlaps = shiftMapper.selectCount(new QueryWrapper<SiteShift>()
            .eq("assigned_user_id", request.assignedUserId)
            .eq("deleted", 0)
            .ne(excludeShiftId != null, "id", excludeShiftId)
            .in("status", List.of(STATUS_PLANNED, STATUS_IN_PROGRESS))
            .lt("planned_start_time", request.plannedEndTime)
            .gt("planned_end_time", request.plannedStartTime));
        if (overlaps != null && overlaps > 0) {
            throw BusinessException.conflict("人员已存在重叠班次");
        }
    }

    private void lockShiftAssignment(SiteShiftRequest request) {
        // A26-P3-21：INSERT IGNORE + SELECT FOR UPDATE 在守卫行已存在时构成 S→X 锁升级死锁，
        // 并发排班一方以 500 而非干净的 409 失败（数据侧仍正确）。catch DeadlockLoserDataAccessException
        // 把死锁翻译为业务 conflict 409，调用方可重试；不改变锁顺序，不动 schema，行为对齐 evidence 描述。
        try {
            // AC-13 / A26-P2-11：V13 后守卫键只有 assigned_user_id。不同岗位必须竞争同一人员行锁，
            // 才能让后续人级时间重叠查询在并发创建/更新时保持串行。
            shiftMapper.insertAssignmentGuard(request.assignedUserId);
            Long lockedUserId = shiftMapper.lockAssignmentGuard(request.assignedUserId);
            if (lockedUserId == null) {
                throw BusinessException.conflict("排班并发控制获取失败");
            }
        } catch (org.springframework.dao.DeadlockLoserDataAccessException ex) {
            throw BusinessException.conflict("排班并发冲突，请重试");
        }
    }

    private boolean sameCompletedShiftOwnership(SiteShift shift, SiteShiftRequest request) {
        return java.util.Objects.equals(shift.venueId, request.venueId)
            && java.util.Objects.equals(shift.spaceId, request.spaceId)
            && java.util.Objects.equals(shift.shiftDate, request.shiftDate)
            && java.util.Objects.equals(shift.roleCode, request.roleCode)
            && java.util.Objects.equals(shift.assignedUserId, request.assignedUserId)
            && java.util.Objects.equals(shift.plannedStartTime, request.plannedStartTime)
            && java.util.Objects.equals(shift.plannedEndTime, request.plannedEndTime);
    }

    private boolean sameTerminalTaskOwnership(SiteTask task, SiteTaskRequest request) {
        return java.util.Objects.equals(task.venueId, request.venueId)
            && java.util.Objects.equals(task.spaceId, request.spaceId)
            && java.util.Objects.equals(task.shiftId, request.shiftId)
            && java.util.Objects.equals(task.taskType, request.taskType)
            && java.util.Objects.equals(task.assignedUserId, request.assignedUserId)
            && java.util.Objects.equals(task.reservationId, request.reservationId)
            && java.util.Objects.equals(task.orderId, request.orderId)
            && java.util.Objects.equals(task.lifecycleId, request.lifecycleId)
            && java.util.Objects.equals(task.assetId, request.assetId)
            && java.util.Objects.equals(task.unavailableBlockId, request.unavailableBlockId)
            && java.util.Objects.equals(task.complaintId, request.complaintId);
    }

    private Long countOpenTasks(SiteHandoverRequest request) {
        // A26-P3-18：shift_id 已全局唯一，venue_id 谓词纯属有害——班次跨场馆移动后
        // 旧 venue_id 下的未完成任务会被漏计，架空签退门槛（AUD-P1-04）。
        Long count = taskMapper.selectCount(new QueryWrapper<SiteTask>()
            .eq("shift_id", request.fromShiftId)
            .eq("deleted", 0)
            .in("status", unfinishedTaskStatuses()));
        return count == null ? 0L : count;
    }

    private boolean hasUnfinishedTasks(SiteShift shift) {
        // A26-P3-18：shift_id 已全局唯一，移除 venue_id 谓词，避免班次跨场馆移动后漏计未完成任务。
        Long count = taskMapper.selectCount(new QueryWrapper<SiteTask>()
            .eq("shift_id", shift.id)
            .eq("deleted", 0)
            .in("status", unfinishedTaskStatuses()));
        return count != null && count > 0;
    }

    private boolean hasValidHandover(SiteShift shift) {
        // AUD-P1-04：签退前置收紧为已接收（ACCEPTED）交接，提交（SUBMITTED）不再放行，
        // 确保交班内容经接收人确认后才允许源班次签退。
        Long count = handoverMapper.selectCount(new QueryWrapper<SiteShiftHandover>()
            .eq("venue_id", shift.venueId)
            .eq("from_shift_id", shift.id)
            .eq("deleted", 0)
            .eq("status", STATUS_ACCEPTED));
        return count != null && count > 0;
    }

    private SiteShift requireAssociatedShiftVenue(Long shiftId, Long venueId) {
        if (shiftId == null) {
            return null;
        }
        SiteShift shift = requireShift(shiftId);
        if (!venueId.equals(shift.venueId)) {
            throw BusinessException.badRequest("关联班次不属于请求场馆");
        }
        return shift;
    }

    private void syncHandoverShiftStatuses(SiteShift fromShift,
                                            String fromStatus,
                                            SiteShift toShift,
                                            String toStatus,
                                            LocalDateTime now) {
        updateHandoverStatus(fromShift, fromStatus, now);
        if (toShift != null && !java.util.Objects.equals(fromShift.id, toShift.id)) {
            updateHandoverStatus(toShift, toStatus, now);
        }
    }

    private void updateHandoverStatus(SiteShift shift, String handoverStatus, LocalDateTime now) {
        shift.handoverStatus = handoverStatus;
        shift.updateTime = now;
        shiftMapper.updateById(shift);
    }

    private boolean isStartableTaskStatus(String status) {
        return STATUS_TODO.equals(status) || STATUS_PENDING.equals(status);
    }

    private boolean isTerminalTaskStatus(String status) {
        return STATUS_DONE.equals(status) || STATUS_EXCEPTION.equals(status) || STATUS_CANCELED.equals(status);
    }

    private boolean isOpenTaskStatus(String status) {
        return unfinishedTaskStatuses().contains(status);
    }

    private List<String> unfinishedTaskStatuses() {
        return List.of(STATUS_TODO, STATUS_IN_PROGRESS, STATUS_PENDING);
    }

    private SiteShift requireShift(Long shiftId) {
        if (shiftId == null) {
            throw BusinessException.badRequest("班次ID不能为空");
        }
        SiteShift shift = shiftMapper.selectById(shiftId);
        if (shift == null || Integer.valueOf(1).equals(shift.deleted)) {
            throw BusinessException.notFound("班次不存在");
        }
        return shift;
    }

    private SiteTask requireTask(Long taskId) {
        if (taskId == null) {
            throw BusinessException.badRequest("任务ID不能为空");
        }
        SiteTask task = taskMapper.selectById(taskId);
        if (task == null || Integer.valueOf(1).equals(task.deleted)) {
            throw BusinessException.notFound("任务不存在");
        }
        return task;
    }

    // AUD-P2-01：completeTask/cancelTask 专用，先 FOR UPDATE 锁行再判定状态，
    // 与 CAS 更新配合避免后写者覆盖先写终态导致完成说明/异常码/证据丢失。
    private SiteTask requireTaskForUpdate(Long taskId) {
        if (taskId == null) {
            throw BusinessException.badRequest("任务ID不能为空");
        }
        SiteTask task = taskMapper.selectByIdForUpdate(taskId);
        if (task == null || Integer.valueOf(1).equals(task.deleted)) {
            throw BusinessException.notFound("任务不存在");
        }
        return task;
    }

    private SiteShiftHandover requireHandover(Long handoverId) {
        if (handoverId == null) {
            throw BusinessException.badRequest("交接ID不能为空");
        }
        SiteShiftHandover handover = handoverMapper.selectById(handoverId);
        if (handover == null || Integer.valueOf(1).equals(handover.deleted)) {
            throw BusinessException.notFound("交接记录不存在");
        }
        return handover;
    }

    private SiteIncidentLog requireIncident(Long incidentId) {
        if (incidentId == null) {
            throw BusinessException.badRequest("异常ID不能为空");
        }
        SiteIncidentLog incident = incidentMapper.selectById(incidentId);
        if (incident == null || Integer.valueOf(1).equals(incident.deleted)) {
            throw BusinessException.notFound("异常记录不存在");
        }
        return incident;
    }

    // A26-P3-19：resolveIncident 专用，先 FOR UPDATE 锁行再判定状态，
    // 与 CAS 更新配合避免并发解决时先写的处理结论被整行覆盖且无业务事件可追溯。
    private SiteIncidentLog requireIncidentForUpdate(Long incidentId) {
        if (incidentId == null) {
            throw BusinessException.badRequest("异常ID不能为空");
        }
        SiteIncidentLog incident = incidentMapper.selectByIdForUpdate(incidentId);
        if (incident == null || Integer.valueOf(1).equals(incident.deleted)) {
            throw BusinessException.notFound("异常记录不存在");
        }
        return incident;
    }

    private SiteOpsQuery safeQuery(SiteOpsQuery query) {
        return query == null ? new SiteOpsQuery() : query;
    }

    private <T> boolean applyVenueScope(QueryWrapper<T> wrapper, SiteOpsQuery query) {
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

    private <T> void applyCommonSiteQuery(QueryWrapper<T> wrapper, SiteOpsQuery query) {
        if (query.spaceId != null) {
            wrapper.eq("space_id", query.spaceId);
        }
    }

    private <T> PageResult<T> emptyPage(SiteOpsQuery query) {
        return PageResult.of(List.of(), 0, query.getPageNum(), query.getPageSize());
    }

    private BusinessEventRecordRequest siteEvent(BusinessEventType eventType,
                                                 String entityType,
                                                 Long entityId,
                                                 String idempotencyKey,
                                                 String fromStatus,
                                                 String toStatus,
                                                 String reasonCode,
                                                 String reasonText) {
        return BusinessEventRecordRequest.of(
                eventType,
                BusinessDomain.SITE_OPERATIONS,
                entityType,
                entityId,
                BusinessSourceType.ADMIN,
                "SUCCESS")
            .idempotencyKey(idempotencyKey)
            .fromStatus(fromStatus)
            .toStatus(toStatus)
            .reasonCode(reasonCode)
            .reasonText(reasonText);
    }

    private String nextNo(String prefix) {
        return prefix + LocalDateTime.now().format(NO_TIME)
            + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
    }

    private void requireTimeRange(LocalDateTime start, LocalDateTime end) {
        if (start == null || end == null || !start.isBefore(end)) {
            throw BusinessException.badRequest("开始时间必须早于结束时间");
        }
    }

    private void requireVenueId(Long venueId) {
        if (venueId == null) {
            throw BusinessException.badRequest("场馆不能为空");
        }
    }

    // AUD-P2-03：assignedUserId 必须是存在+启用且与目标场馆有关联的有效用户。
    // 这里校验的是被指派人（不是当前登录用户），不能用 VenueAccessService.requireVenueManageAccess
    // （它依赖 SecurityContext），改用 VenueAdminRelationMapper.countRelation 直接判定归属。
    private void requireAssignedUserForVenue(Long assignedUserId, Long venueId) {
        entityExistenceService.requireActiveUser(assignedUserId);
        int relationCount = venueAdminRelationMapper.countRelation(assignedUserId, venueId);
        if (relationCount <= 0) {
            throw BusinessException.badRequest("指派人员不属于该场馆");
        }
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
