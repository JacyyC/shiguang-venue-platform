package com.example.venue.inventory.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.example.venue.business.dto.BusinessEventRecordRequest;
import com.example.venue.business.enums.BusinessDomain;
import com.example.venue.business.enums.BusinessEventType;
import com.example.venue.business.enums.BusinessSourceType;
import com.example.venue.business.service.BusinessEventService;
import com.example.venue.common.BusinessException;
import com.example.venue.common.EntityExistenceService;
import com.example.venue.inventory.dto.BookabilityDecision;
import com.example.venue.inventory.dto.InventorySlot;
import com.example.venue.inventory.dto.SaveBusinessCalendarRequest;
import com.example.venue.inventory.dto.SaveUnavailableBlockRequest;
import com.example.venue.inventory.entity.VenueBusinessCalendar;
import com.example.venue.inventory.entity.VenueInventoryAudit;
import com.example.venue.inventory.entity.VenueUnavailableBlock;
import com.example.venue.inventory.mapper.VenueBusinessCalendarMapper;
import com.example.venue.inventory.mapper.VenueInventoryAuditMapper;
import com.example.venue.inventory.mapper.VenueUnavailableBlockMapper;
import com.example.venue.reservation.entity.Reservation;
import com.example.venue.reservation.enums.ReservationStatus;
import com.example.venue.reservation.mapper.ReservationMapper;
import com.example.venue.resource.dto.ResourceAvailabilityDecision;
import com.example.venue.resource.enums.AllocationConflictCode;
import com.example.venue.resource.service.ResourceAllocationService;
import com.example.venue.security.SecurityContext;
import com.example.venue.system.service.SysConfigService;
import com.example.venue.venue.entity.Venue;
import com.example.venue.venue.entity.VenueFacilityMaintenance;
import com.example.venue.venue.entity.VenueSpace;
import com.example.venue.venue.enums.BusinessCalendarStatus;
import com.example.venue.venue.mapper.VenueFacilityMaintenanceMapper;
import com.example.venue.venue.mapper.VenueMapper;
import com.example.venue.venue.mapper.VenueSpaceMapper;
import com.example.venue.venue.service.VenueAccessService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Service
public class VenueInventoryService {
    /** 默认营业开始时间，可被营业日历覆盖 */
    private static final LocalTime DEFAULT_OPEN_TIME = LocalTime.of(8, 0);
    /** 默认营业结束时间，可被营业日历覆盖 */
    private static final LocalTime DEFAULT_CLOSE_TIME = LocalTime.of(22, 0);

    private final VenueMapper venueMapper;
    private final VenueSpaceMapper spaceMapper;
    private final EntityExistenceService entityExistenceService;
    private final VenueBusinessCalendarMapper calendarMapper;
    private final VenueUnavailableBlockMapper blockMapper;
    private final VenueInventoryAuditMapper auditMapper;
    private final VenueFacilityMaintenanceMapper maintenanceMapper;
    private final ReservationMapper reservationMapper;
    private final VenueAccessService accessService;
    private final SysConfigService sysConfigService;
    private final BusinessEventService businessEventService;
    private final InventoryConflictEventRecorder conflictEventRecorder;
    private final ObjectMapper objectMapper;
    private ResourceAllocationService resourceAllocationService;

    public VenueInventoryService(VenueMapper venueMapper,
                                 VenueSpaceMapper spaceMapper,
                                 EntityExistenceService entityExistenceService,
                                 VenueBusinessCalendarMapper calendarMapper,
                                 VenueUnavailableBlockMapper blockMapper,
                                 VenueInventoryAuditMapper auditMapper,
                                 VenueFacilityMaintenanceMapper maintenanceMapper,
                                 ReservationMapper reservationMapper,
                                 VenueAccessService accessService,
                                 SysConfigService sysConfigService,
                                 BusinessEventService businessEventService,
                                 InventoryConflictEventRecorder conflictEventRecorder,
                                 ObjectMapper objectMapper) {
        this.venueMapper = venueMapper;
        this.spaceMapper = spaceMapper;
        this.entityExistenceService = entityExistenceService;
        this.calendarMapper = calendarMapper;
        this.blockMapper = blockMapper;
        this.auditMapper = auditMapper;
        this.maintenanceMapper = maintenanceMapper;
        this.reservationMapper = reservationMapper;
        this.accessService = accessService;
        this.sysConfigService = sysConfigService;
        this.businessEventService = businessEventService;
        this.conflictEventRecorder = conflictEventRecorder;
        this.objectMapper = objectMapper;
    }

    @Autowired
    public void setResourceAllocationService(ResourceAllocationService resourceAllocationService) {
        this.resourceAllocationService = resourceAllocationService;
    }

    /**
     * 日库存生成逻辑。
     * <p>执行顺序：查询场馆日历窗口 → 排除不可售时段块 → 排除维护期 →
     * 排除已有预约占用 → 按小时粒度生成 slot → 计算每个 slot 的营业状态。</p>
     * <p>关键决策点：营业状态由 BusinessCalendarStatus 枚举决定（OPEN/CLOSED/SPECIAL_HOURS/INTERNAL_ONLY）；
     * 只有 OPEN 和 SPECIAL_HOURS 状态的 slot 可预约；维护期和已有预约的 slot 标记为不可用。</p>
     * <p>副作用：纯查询方法，无写入操作。结果用于可用性展示和预约校验。</p>
     */
    public List<InventorySlot> dailyInventory(Long venueId, Long spaceId, LocalDate date, boolean adminView) {
        return dailyInventory(venueId, spaceId, date, adminView, null);
    }

    public List<InventorySlot> dailyInventory(Long venueId,
                                              Long spaceId,
                                              LocalDate date,
                                              boolean adminView,
                                              Long excludedReservationId) {
        if (date == null) {
            throw BusinessException.badRequest("库存日期不能为空");
        }
        VenueSpace space = requireSpace(spaceId);
        requireVenueMatch(venueId, space);
        Venue venue = requireVenue(space.venueId);
        if (adminView) {
            accessService.requireVenueManageAccess(venue.id);
        }
        VenueBusinessCalendar calendar = calendarMapper.findByVenueAndDate(venue.id, date);
        BusinessWindow window = businessWindow(venue, calendar);
        LocalDateTime dayStart = date.atStartOfDay();
        LocalDateTime dayEnd = date.plusDays(1).atStartOfDay();
        List<VenueUnavailableBlock> blocks = blockMapper.findActiveOverlaps(venue.id, space.id, dayStart, dayEnd);
        List<VenueFacilityMaintenance> maintenances = legacyMaintenancesForInventory(space.id, dayStart, dayEnd);
        List<Reservation> reservations = reservationMapper.findOverlapsByStatuses(
                space.id, dayStart, dayEnd, ReservationStatus.occupyingStatusNames()).stream()
            .filter(reservation -> !Objects.equals(reservation.id, excludedReservationId))
            .toList();

        SlotCoverage coverage = slotCoverage(date, window, blocks, maintenances, reservations);
        List<InventorySlot> slots = new ArrayList<>();
        LocalDateTime cursor = coverage.start();
        LocalDateTime generationEnd = coverage.end();
        while (!cursor.plusHours(1).isAfter(generationEnd)) {
            LocalDateTime slotStart = cursor;
            LocalDateTime slotEnd = cursor.plusHours(1);
            InventorySlot slot = baseSlot(venue, space, slotStart, slotEnd);
            applySlotStatus(slot, venue, space, window, blocks, maintenances, reservations, adminView,
                excludedReservationId);
            slots.add(slot);
            cursor = slotEnd;
        }
        return slots;
    }

    public BookabilityDecision checkBookable(Long spaceId, LocalDateTime startTime, LocalDateTime endTime) {
        return checkBookableInternal(spaceId, startTime, endTime, 1, List.of(), null, false);
    }

    public BookabilityDecision checkBookable(Long spaceId,
                                             LocalDateTime startTime,
                                             LocalDateTime endTime,
                                             Integer participantCount,
                                             List<Long> seatIds) {
        return checkBookableInternal(spaceId, startTime, endTime, participantCount, seatIds, null, true);
    }

    public BookabilityDecision checkBookableExcludingReservation(Long spaceId, LocalDateTime startTime,
                                                                 LocalDateTime endTime, Long excludedReservationId) {
        return checkBookableInternal(spaceId, startTime, endTime, 1, List.of(), excludedReservationId, false);
    }

    public BookabilityDecision checkBookableExcludingReservation(Long spaceId,
                                                                 LocalDateTime startTime,
                                                                 LocalDateTime endTime,
                                                                 Long excludedReservationId,
                                                                 Integer participantCount,
                                                                 List<Long> seatIds) {
        return checkBookableInternal(spaceId, startTime, endTime, participantCount, seatIds,
            excludedReservationId, true);
    }

    private BookabilityDecision checkBookableInternal(Long spaceId,
                                                       LocalDateTime startTime,
                                                       LocalDateTime endTime,
                                                       Integer participantCount,
                                                       List<Long> seatIds,
                                                       Long excludedReservationId,
                                                       boolean strictResourceRequest) {
        validateTimeRange(startTime, endTime);
        if (!startTime.toLocalDate().equals(endTime.toLocalDate())) {
            return BookabilityDecision.rejected("CLOSED", "CROSS_DAY_UNSUPPORTED", "暂不支持跨日预约");
        }
        VenueSpace space = requireSpace(spaceId);
        Venue venue = requireVenue(space.venueId);
        VenueBusinessCalendar calendar = calendarMapper.findByVenueAndDate(venue.id, startTime.toLocalDate());
        BookabilityDecision closed = closedDecisionForRange(venue, space, calendar, startTime, endTime);
        if (closed != null) {
            return closed;
        }

        if (resourceAllocationService != null) {
            ResourceAvailabilityDecision allocation = strictResourceRequest
                ? resourceAllocationService.checkRequestedAvailability(space.id, startTime, endTime,
                    participantCount, seatIds, excludedReservationId)
                : resourceAllocationService.checkAvailability(space.id, startTime, endTime,
                    participantCount, seatIds, excludedReservationId);
            if (!allocation.bookable()) {
                return rejectedByResourceAllocation(allocation);
            }
        } else {
            List<Reservation> reservations = reservationMapper.findOverlapsByStatuses(
                space.id, startTime, endTime, ReservationStatus.occupyingStatusNames());
            Reservation reservationConflict = reservations.stream()
                .filter(reservation -> !Objects.equals(reservation.id, excludedReservationId))
                .findFirst()
                .orElse(null);
            if (reservationConflict != null) {
                return BookabilityDecision.rejected("RESERVED", "RESERVED", "该时段已被预约")
                    .conflictReservation(reservationConflict.id);
            }
        }

        List<VenueUnavailableBlock> blocks = blockMapper.findActiveOverlaps(venue.id, space.id, startTime, endTime);
        BlockHit blockHit = firstOverlappingBlock(blocks, startTime, endTime);
        if (blockHit != null) {
            VenueUnavailableBlock block = blockHit.block();
            String status = blockHit.bufferOnly() ? "BUFFER" : statusForBlock(block, true);
            return BookabilityDecision.rejected(status, block.reasonCode, block.reasonText)
                .conflictBlock(block.id);
        }

        List<VenueFacilityMaintenance> maintenances = maintenanceMapper.findActiveOverlaps(space.id, startTime, endTime);
        if (!maintenances.isEmpty()) {
            VenueFacilityMaintenance maintenance = maintenances.get(0);
            return BookabilityDecision.rejected("UNAVAILABLE", "MAINTENANCE",
                blankToDefault(maintenance.reason, "该时段场地维护中"));
        }

        return BookabilityDecision.ok();
    }

    @Transactional
    public VenueUnavailableBlock createBlock(SaveUnavailableBlockRequest request) {
        if (request == null) {
            throw BusinessException.badRequest("不可售参数不能为空");
        }
        validateTimeRange(request.startTime, request.endTime);
        if (request.venueId == null) {
            throw BusinessException.badRequest("场馆不能为空");
        }
        if (isBlank(request.blockType) || isBlank(request.reasonCode) || isBlank(request.reasonText)) {
            throw BusinessException.badRequest("不可售类型和原因不能为空");
        }
        // 统一锁顺序 venue→space：先锁场馆行再锁场地行，与 ReservationService.create/reviewReschedule
        // 和 saveCalendar 形成共同栅栏，防止不可售块与并发预约/改期/闭馆日历修改产生冲突遗漏。
        Venue venue = entityExistenceService.requireVenueForUpdate(request.venueId);
        VenueSpace space = null;
        if (request.spaceId != null) {
            space = entityExistenceService.requireSpaceForUpdate(request.spaceId);
            requireVenueMatch(request.venueId, space);
        }
        accessService.requireVenueManageAccess(venue.id);
        String sourceType = normalizedManualSourceType(request.sourceType);

        int bufferBefore = nonNegative(request.bufferBeforeMinutes);
        int bufferAfter = nonNegative(request.bufferAfterMinutes);
        LocalDateTime effectiveStart = request.startTime.minusMinutes(bufferBefore);
        LocalDateTime effectiveEnd = request.endTime.plusMinutes(bufferAfter);
        rejectBlockConflicts(venue, space, request.startTime, request.endTime, effectiveStart, effectiveEnd);

        LocalDateTime now = LocalDateTime.now();
        VenueUnavailableBlock block = new VenueUnavailableBlock();
        block.venueId = venue.id;
        block.spaceId = space == null ? null : space.id;
        block.blockType = request.blockType;
        block.reasonCode = request.reasonCode;
        block.reasonText = request.reasonText;
        block.startTime = request.startTime;
        block.endTime = request.endTime;
        block.bufferBeforeMinutes = bufferBefore;
        block.bufferAfterMinutes = bufferAfter;
        block.sourceType = sourceType;
        block.providerEnv = providerFieldForManualBlock(request);
        block.providerName = providerFieldForManualBlockName(request);
        block.externalRef = blankToNull(request.externalRef);
        block.idempotencyKey = blankToNull(request.idempotencyKey);
        block.relatedEntityType = blankToNull(request.relatedEntityType);
        block.relatedEntityId = request.relatedEntityId;
        block.metadataJson = blankToNull(request.metadataJson);
        block.status = "ACTIVE";
        block.createdBy = SecurityContext.currentUserId();
        block.updatedBy = block.createdBy;
        block.createTime = now;
        block.updateTime = now;
        block.deleted = 0;
        blockMapper.insert(block);

        writeAudit("VENUE_UNAVAILABLE_BLOCK", block.id, "UNAVAILABLE_BLOCK_CREATED", null, block, request.reasonText);
        writeBlockEvent(BusinessEventType.UNAVAILABLE_BLOCK_CREATED, block, "UNAVAILABLE_BLOCK_CREATED:" + block.id,
            request.reasonText);
        return block;
    }

    @Transactional
    public VenueBusinessCalendar saveCalendar(SaveBusinessCalendarRequest request) {
        if (request == null) {
            throw BusinessException.badRequest("营业日历参数不能为空");
        }
        if (request.venueId == null) {
            throw BusinessException.badRequest("场馆不能为空");
        }
        if (request.calendarDate == null) {
            throw BusinessException.badRequest("营业日期不能为空");
        }
        if (isBlank(request.businessStatus)) {
            throw BusinessException.badRequest("营业状态不能为空");
        }
        String businessStatus = request.businessStatus.trim().toUpperCase();
        if (!Arrays.stream(BusinessCalendarStatus.values()).map(BusinessCalendarStatus::name).toList().contains(businessStatus)) {
            throw BusinessException.badRequest("营业状态不支持");
        }
        if ((BusinessCalendarStatus.SPECIAL_HOURS.name().equals(businessStatus)
            || BusinessCalendarStatus.OPEN.name().equals(businessStatus))
            && (request.openTime == null || request.closeTime == null || !request.openTime.isBefore(request.closeTime))) {
            throw BusinessException.badRequest("营业时间必须包含有效开闭时间");
        }
        // 统一锁顺序 venue→space：闭馆日历影响整馆可售性，锁场馆行与 create/createBlock/reviewReschedule
        // 形成共同栅栏，防止闭馆与并发预约产生冲突遗漏。
        Venue venue = entityExistenceService.requireVenueForUpdate(request.venueId);
        accessService.requireVenueManageAccess(venue.id);
        rejectCalendarReservationConflicts(venue, request.calendarDate, businessStatus, request.openTime, request.closeTime);

        VenueBusinessCalendar calendar = calendarMapper.findByVenueAndDate(venue.id, request.calendarDate);
        String beforeJson = calendar == null ? null : toJson(calendar);
        LocalDateTime now = LocalDateTime.now();
        boolean created = calendar == null;
        if (created) {
            calendar = new VenueBusinessCalendar();
            calendar.venueId = venue.id;
            calendar.calendarDate = request.calendarDate;
            calendar.createdBy = SecurityContext.currentUserId();
            calendar.createTime = now;
            calendar.deleted = 0;
        }
        calendar.openTime = request.openTime;
        calendar.closeTime = request.closeTime;
        calendar.businessStatus = businessStatus;
        calendar.reasonCode = blankToDefault(request.reasonCode, businessStatus);
        calendar.reasonText = blankToNull(request.reasonText);
        calendar.sourceType = normalizedManualSourceType(request.sourceType);
        calendar.providerEnv = null;
        calendar.providerName = null;
        calendar.externalRef = blankToNull(request.externalRef);
        calendar.idempotencyKey = blankToNull(request.idempotencyKey);
        calendar.metadataJson = blankToNull(request.metadataJson);
        calendar.updatedBy = SecurityContext.currentUserId();
        calendar.updateTime = now;
        calendar.deleted = 0;
        if (created) {
            calendarMapper.insert(calendar);
        } else {
            // A26-P3-13：NOT_NULL 更新策略下，openTime/closeTime/reasonCode/reasonText 等可空字段
            // 在 SPECIAL_HOURS 改存 CLOSED 时若本轮为 null，updateById 会静默丢弃 null 不落库，
            // 库内残留上一轮旧值，而审计 afterJson 记的是内存对象的 null，审计与实际背离。
            // 改用 UpdateWrapper 显式 set 这些字段，保证本轮 null 能覆盖上一轮旧值。
            // 对照 CustomerFeedbackService.resolveCase 同模式（A26-P3-27）。
            calendarMapper.update(calendar, new UpdateWrapper<VenueBusinessCalendar>()
                .eq("id", calendar.id)
                .eq("deleted", 0)
                .set("open_time", calendar.openTime)
                .set("close_time", calendar.closeTime)
                .set("business_status", calendar.businessStatus)
                .set("reason_code", calendar.reasonCode)
                .set("reason_text", calendar.reasonText)
                .set("source_type", calendar.sourceType)
                .set("provider_env", calendar.providerEnv)
                .set("provider_name", calendar.providerName)
                .set("external_ref", calendar.externalRef)
                .set("idempotency_key", calendar.idempotencyKey)
                .set("metadata_json", calendar.metadataJson)
                .set("updated_by", calendar.updatedBy)
                .set("update_time", calendar.updateTime)
                .set("deleted", calendar.deleted));
        }

        writeAudit("VENUE_BUSINESS_CALENDAR", calendar.id, "VENUE_CALENDAR_CHANGED", beforeJson, toJson(calendar),
            calendar.reasonText);
        writeCalendarEvent(calendar);
        return calendar;
    }

    @Transactional
    public void cancelBlock(Long blockId, String reason) {
        VenueUnavailableBlock block = requireBlock(blockId);
        accessService.requireVenueManageAccess(block.venueId);
        if ("CANCELED".equals(block.status)) {
            cancelBoundLegacyMaintenance(block.id);
            return;
        }
        String beforeJson = toJson(block);
        block.status = "CANCELED";
        block.updatedBy = SecurityContext.currentUserId();
        block.updateTime = LocalDateTime.now();
        blockMapper.updateById(block);
        writeAudit("VENUE_UNAVAILABLE_BLOCK", block.id, "UNAVAILABLE_BLOCK_CANCELED", beforeJson, toJson(block), reason);
        writeBlockEvent(BusinessEventType.UNAVAILABLE_BLOCK_CANCELED, block,
            "UNAVAILABLE_BLOCK_CANCELED:" + block.id, reason);
        cancelBoundLegacyMaintenance(block.id);
    }

    public VenueBusinessCalendar businessCalendar(Long venueId, LocalDate date) {
        if (date == null) {
            throw BusinessException.badRequest("营业日期不能为空");
        }
        Venue venue = requireVenue(venueId);
        accessService.requireVenueManageAccess(venue.id);
        return calendarMapper.findByVenueAndDate(venue.id, date);
    }

    public List<VenueUnavailableBlock> blocks(Long venueId, Long spaceId, LocalDate date) {
        if (date == null) {
            throw BusinessException.badRequest("库存日期不能为空");
        }
        Venue venue = requireVenue(venueId);
        accessService.requireVenueManageAccess(venue.id);
        // SHIGUANG-BATCH-02 S04：记录列表含 CANCELED（资源面"已取消 · 保留记录"展示），
        // 读侧扩展；库存槽位推导仍只消费 ACTIVE（dailyInventory/checkBookable 不变）。
        return blockMapper.findListedOverlaps(venue.id, spaceId, date.atStartOfDay(), date.plusDays(1).atStartOfDay());
    }

    public List<VenueFacilityMaintenance> legacyMaintenancesForInventory(Long spaceId,
                                                                         LocalDateTime startTime,
                                                                         LocalDateTime endTime) {
        return maintenanceMapper.findActiveOverlaps(spaceId, startTime, endTime).stream()
            .filter(maintenance -> maintenance.unavailableBlockId == null)
            .toList();
    }

    /**
     * 判断指定场地是否存在未取消的不可售时段块。
     * <p>A26-P3-15：updateSpace 变更场地所属场馆前调用，避免块 venue_id 与 space.venue_id
     * 错位后人工保留/不可售时段在新旧场馆下都查不到，被当作可预订时段。</p>
     */
    public boolean hasActiveUnavailableBlocksBySpace(Long spaceId) {
        if (spaceId == null) {
            return false;
        }
        Long count = blockMapper.selectCount(new QueryWrapper<VenueUnavailableBlock>()
            .eq("space_id", spaceId)
            .eq("deleted", 0)
            .eq("status", "ACTIVE"));
        return count != null && count > 0;
    }

    private void applySlotStatus(InventorySlot slot,
                                 Venue venue,
                                 VenueSpace space,
                                 BusinessWindow window,
                                 List<VenueUnavailableBlock> blocks,
                                 List<VenueFacilityMaintenance> maintenances,
                                 List<Reservation> reservations,
                                 boolean adminView,
                                 Long excludedReservationId) {
        ClosedReason closed = closedReasonForSlot(venue, space, window, slot.startTime, slot.endTime);
        if (closed != null && !"NON_BUSINESS_HOURS".equals(closed.reasonCode())) {
            markClosed(slot, closed.reasonCode(), closed.reasonText());
            return;
        }

        Reservation reservation = firstOverlappingReservation(reservations, slot.startTime, slot.endTime);
        if (resourceAllocationService != null) {
            ResourceAvailabilityDecision allocation = resourceAllocationService.checkAvailability(
                space.id, slot.startTime, slot.endTime, 1, List.of(), excludedReservationId);
            if (!allocation.bookable()) {
                applyResourceAllocationDecision(slot, allocation);
                if (adminView && reservation != null) {
                    slot.reservationId = reservation.id;
                    slot.reservationStatus = reservation.status;
                }
                return;
            }
        } else if (reservation != null) {
            slot.status = "RESERVED";
            slot.label = "已预约";
            if (adminView) {
                slot.reservationId = reservation.id;
                slot.reservationStatus = reservation.status;
            }
            return;
        }

        BlockHit blockHit = firstOverlappingBlock(blocks, slot.startTime, slot.endTime);
        if (blockHit != null) {
            applyBlockHit(slot, blockHit, adminView);
            return;
        }

        VenueFacilityMaintenance maintenance = firstOverlappingMaintenance(maintenances, slot.startTime, slot.endTime);
        if (maintenance != null) {
            slot.status = "UNAVAILABLE";
            slot.label = "维护中";
            slot.reasonCode = "MAINTENANCE";
            slot.reasonText = blankToDefault(maintenance.reason, "维护");
            return;
        }

        if (closed != null) {
            markClosed(slot, closed.reasonCode(), closed.reasonText());
            return;
        }

        slot.status = "AVAILABLE";
        slot.label = "可预约";
    }

    private BookabilityDecision rejectedByResourceAllocation(ResourceAvailabilityDecision allocation) {
        String status = allocation.conflictCode() == AllocationConflictCode.RESOURCE_RULE_UNAVAILABLE
            ? "UNAVAILABLE"
            : "RESERVED";
        return BookabilityDecision.rejected(status, allocation.conflictCode().name(), allocation.reasonText());
    }

    private void applyResourceAllocationDecision(InventorySlot slot, ResourceAvailabilityDecision allocation) {
        BookabilityDecision decision = rejectedByResourceAllocation(allocation);
        slot.status = decision.status;
        slot.label = "UNAVAILABLE".equals(decision.status) ? "不可预约" : "已约满";
        slot.reasonCode = decision.reasonCode;
        slot.reasonText = decision.reasonText;
    }

    private BookabilityDecision closedDecisionForRange(Venue venue,
                                                       VenueSpace space,
                                                       VenueBusinessCalendar calendar,
                                                       LocalDateTime startTime,
                                                       LocalDateTime endTime) {
        BusinessWindow window = businessWindow(venue, calendar);
        ClosedReason closed = closedReasonForSlot(venue, space, window, startTime, endTime);
        if (closed == null) {
            return null;
        }
        return BookabilityDecision.rejected("CLOSED", closed.reasonCode(), closed.reasonText());
    }

    private ClosedReason closedReasonForSlot(Venue venue,
                                             VenueSpace space,
                                             BusinessWindow window,
                                             LocalDateTime startTime,
                                             LocalDateTime endTime) {
        LocalDateTime now = LocalDateTime.now();
        if (!startTime.isAfter(now)) {
            return new ClosedReason("PAST_TIME", "已过期");
        }
        if (startTime.toLocalDate().isAfter(now.toLocalDate().plusDays(reservationAdvanceDays()))) {
            return new ClosedReason("ADVANCE_DAYS_EXCEEDED", "超出可预约日期");
        }
        if (!"ACTIVE".equals(venue.status)) {
            return new ClosedReason("VENUE_DISABLED", "场馆停用");
        }
        if (!"ACTIVE".equals(space.status)) {
            return new ClosedReason("SPACE_DISABLED", "场地不可用");
        }
        if (window.closed()) {
            return new ClosedReason(blankToDefault(window.reasonCode(), "VENUE_CLOSED"),
                blankToDefault(window.reasonText(), "闭馆"));
        }
        if (startTime.toLocalTime().isBefore(window.openTime())
            || endTime.toLocalTime().isAfter(window.closeTime())) {
            return new ClosedReason("NON_BUSINESS_HOURS", "非营业时间");
        }
        return null;
    }

    private void rejectBlockConflicts(Venue venue,
                                      VenueSpace space,
                                      LocalDateTime actualStart,
                                      LocalDateTime actualEnd,
                                      LocalDateTime effectiveStart,
                                      LocalDateTime effectiveEnd) {
        List<VenueUnavailableBlock> blockConflicts = space == null
            ? blockMapper.findActiveVenueOverlaps(venue.id, effectiveStart, effectiveEnd)
            : blockMapper.findActiveOverlaps(venue.id, space.id, effectiveStart, effectiveEnd);
        if (!blockConflicts.isEmpty()) {
            VenueUnavailableBlock conflict = blockConflicts.get(0);
            String message = bufferConflictMessage(actualStart, actualEnd, conflict.startTime, conflict.endTime,
                "不可售时段冲突");
            recordConflictRejected(venue.id, spaceId(space), actualStart, actualEnd, effectiveStart, effectiveEnd,
                "UNAVAILABLE_BLOCK", conflict.id, "UNAVAILABLE_BLOCK_CONFLICT", message);
            throw BusinessException.conflict(message);
        }

        List<VenueFacilityMaintenance> maintenanceConflicts = maintenanceConflicts(space, venue, effectiveStart, effectiveEnd);
        if (!maintenanceConflicts.isEmpty()) {
            VenueFacilityMaintenance conflict = maintenanceConflicts.get(0);
            String message = bufferConflictMessage(actualStart, actualEnd, conflict.startTime, conflict.endTime,
                "维护时段冲突");
            recordConflictRejected(venue.id, spaceId(space), actualStart, actualEnd, effectiveStart, effectiveEnd,
                "MAINTENANCE", conflict.id, "MAINTENANCE_CONFLICT", message);
            throw BusinessException.conflict(message);
        }

        List<Reservation> reservationConflicts = space == null
            ? reservationMapper.findVenueOverlapsByStatuses(venue.id, effectiveStart, effectiveEnd,
                ReservationStatus.occupyingStatusNames())
            : reservationMapper.findOverlapsByStatuses(space.id, effectiveStart, effectiveEnd,
                ReservationStatus.occupyingStatusNames());
        if (!reservationConflicts.isEmpty()) {
            Reservation conflict = reservationConflicts.get(0);
            String message = bufferConflictMessage(actualStart, actualEnd, conflict.startTime, conflict.endTime,
                "预约时段冲突");
            recordConflictRejected(venue.id, spaceId(space), actualStart, actualEnd, effectiveStart, effectiveEnd,
                "RESERVATION", conflict.id, "RESERVATION_CONFLICT", message);
            throw BusinessException.conflict(message);
        }
    }

    private void rejectCalendarReservationConflicts(Venue venue,
                                                    LocalDate calendarDate,
                                                    String businessStatus,
                                                    LocalTime requestedOpenTime,
                                                    LocalTime requestedCloseTime) {
        LocalDateTime dayStart = calendarDate.atStartOfDay();
        LocalDateTime dayEnd = calendarDate.plusDays(1).atStartOfDay();
        List<Reservation> activeReservations = reservationMapper.findVenueOverlapsByStatuses(
            venue.id, dayStart, dayEnd, ReservationStatus.occupyingStatusNames());
        if (activeReservations == null || activeReservations.isEmpty()) {
            return;
        }

        boolean closed = BusinessCalendarStatus.CLOSED.name().equals(businessStatus)
            || BusinessCalendarStatus.INTERNAL_ONLY.name().equals(businessStatus);
        LocalTime openTime = requestedOpenTime == null
            ? (venue.openTime == null ? DEFAULT_OPEN_TIME : venue.openTime)
            : requestedOpenTime;
        LocalTime closeTime = requestedCloseTime == null
            ? (venue.closeTime == null ? DEFAULT_CLOSE_TIME : venue.closeTime)
            : requestedCloseTime;

        for (Reservation reservation : activeReservations) {
            if (closed || reservationOutsideBusinessWindow(reservation, calendarDate, openTime, closeTime)) {
                throw BusinessException.conflict("营业日历与有效预约冲突，无法保存");
            }
        }
    }

    private boolean reservationOutsideBusinessWindow(Reservation reservation,
                                                     LocalDate calendarDate,
                                                     LocalTime openTime,
                                                     LocalTime closeTime) {
        return !calendarDate.equals(reservation.startTime.toLocalDate())
            || !calendarDate.equals(reservation.endTime.toLocalDate())
            || reservation.startTime.toLocalTime().isBefore(openTime)
            || reservation.endTime.toLocalTime().isAfter(closeTime);
    }

    private Long spaceId(VenueSpace space) {
        return space == null ? null : space.id;
    }

    private void recordConflictRejected(Long venueId,
                                        Long spaceId,
                                        LocalDateTime requestedStartTime,
                                        LocalDateTime requestedEndTime,
                                        LocalDateTime effectiveStartTime,
                                        LocalDateTime effectiveEndTime,
                                        String conflictType,
                                        Long conflictEntityId,
                                        String reasonCode,
                                        String reasonText) {
        conflictEventRecorder.recordBlockConflictRejection(
            BusinessEventType.INVENTORY_CONFLICT_REJECTED,
            venueId,
            spaceId,
            requestedStartTime,
            requestedEndTime,
            effectiveStartTime,
            effectiveEndTime,
            conflictType,
            conflictEntityId,
            reasonCode,
            reasonText
        );
    }

    private void cancelBoundLegacyMaintenance(Long blockId) {
        VenueFacilityMaintenance maintenance = maintenanceMapper.findByUnavailableBlockId(blockId);
        if (maintenance == null || "CANCELED".equals(maintenance.status)) {
            return;
        }
        maintenance.status = "CANCELED";
        maintenanceMapper.updateById(maintenance);
    }

    private List<VenueFacilityMaintenance> maintenanceConflicts(VenueSpace space,
                                                                Venue venue,
                                                                LocalDateTime effectiveStart,
                                                                LocalDateTime effectiveEnd) {
        if (space != null) {
            return maintenanceMapper.findActiveOverlaps(space.id, effectiveStart, effectiveEnd);
        }
        List<VenueFacilityMaintenance> conflicts = new ArrayList<>();
        List<VenueSpace> spaces = spaceMapper.selectList(new QueryWrapper<VenueSpace>()
            .eq("venue_id", venue.id)
            .eq("deleted", 0));
        for (VenueSpace candidate : spaces) {
            conflicts.addAll(maintenanceMapper.findActiveOverlaps(candidate.id, effectiveStart, effectiveEnd));
            if (!conflicts.isEmpty()) {
                break;
            }
        }
        return conflicts;
    }

    private BusinessWindow businessWindow(Venue venue, VenueBusinessCalendar calendar) {
        LocalTime fallbackOpen = venue.openTime == null ? DEFAULT_OPEN_TIME : venue.openTime;
        LocalTime fallbackClose = venue.closeTime == null ? DEFAULT_CLOSE_TIME : venue.closeTime;
        if (calendar == null) {
            return new BusinessWindow(fallbackOpen, fallbackClose, fallbackOpen, fallbackClose, false, null, null);
        }
        boolean closed = BusinessCalendarStatus.CLOSED.name().equals(calendar.businessStatus)
            || BusinessCalendarStatus.INTERNAL_ONLY.name().equals(calendar.businessStatus);
        LocalTime open = calendar.openTime == null ? fallbackOpen : calendar.openTime;
        LocalTime close = calendar.closeTime == null ? fallbackClose : calendar.closeTime;
        LocalTime slotStart = closed ? fallbackOpen : open;
        LocalTime slotEnd = closed ? fallbackClose : close;
        return new BusinessWindow(slotStart, slotEnd, open, close, closed, calendar.reasonCode, calendar.reasonText);
    }

    private InventorySlot baseSlot(Venue venue, VenueSpace space, LocalDateTime startTime, LocalDateTime endTime) {
        InventorySlot slot = new InventorySlot();
        slot.venueId = venue.id;
        slot.spaceId = space.id;
        slot.startTime = startTime;
        slot.endTime = endTime;
        slot.price = space.pricePerHour;
        return slot;
    }

    private SlotCoverage slotCoverage(LocalDate date,
                                      BusinessWindow window,
                                      List<VenueUnavailableBlock> blocks,
                                      List<VenueFacilityMaintenance> maintenances,
                                      List<Reservation> reservations) {
        LocalDateTime dayStart = date.atStartOfDay();
        LocalDateTime dayEnd = date.plusDays(1).atStartOfDay();
        LocalDateTime start = LocalDateTime.of(date, window.slotStart());
        LocalDateTime end = LocalDateTime.of(date, window.slotEnd());

        for (VenueUnavailableBlock block : blocks) {
            LocalDateTime effectiveStart = block.startTime.minusMinutes(nonNegative(block.bufferBeforeMinutes));
            LocalDateTime effectiveEnd = block.endTime.plusMinutes(nonNegative(block.bufferAfterMinutes));
            LocalDateTime clippedStart = max(effectiveStart, dayStart);
            LocalDateTime clippedEnd = min(effectiveEnd, dayEnd);
            if (clippedStart.isBefore(clippedEnd)) {
                start = min(start, floorToHour(clippedStart));
                end = max(end, ceilToHour(clippedEnd));
            }
        }
        for (VenueFacilityMaintenance maintenance : maintenances) {
            LocalDateTime clippedStart = max(maintenance.startTime, dayStart);
            LocalDateTime clippedEnd = min(maintenance.endTime, dayEnd);
            if (clippedStart.isBefore(clippedEnd)) {
                start = min(start, floorToHour(clippedStart));
                end = max(end, ceilToHour(clippedEnd));
            }
        }
        for (Reservation reservation : reservations) {
            LocalDateTime clippedStart = max(reservation.startTime, dayStart);
            LocalDateTime clippedEnd = min(reservation.endTime, dayEnd);
            if (clippedStart.isBefore(clippedEnd)) {
                start = min(start, floorToHour(clippedStart));
                end = max(end, ceilToHour(clippedEnd));
            }
        }

        return new SlotCoverage(start, end);
    }

    private Reservation firstOverlappingReservation(List<Reservation> reservations,
                                                    LocalDateTime startTime,
                                                    LocalDateTime endTime) {
        return reservations.stream()
            .filter(reservation -> overlaps(startTime, endTime, reservation.startTime, reservation.endTime))
            .findFirst()
            .orElse(null);
    }

    private BlockHit firstOverlappingBlock(List<VenueUnavailableBlock> blocks,
                                           LocalDateTime startTime,
                                           LocalDateTime endTime) {
        BlockHit firstBufferHit = null;
        for (VenueUnavailableBlock block : blocks) {
            if (overlaps(startTime, endTime, block.startTime, block.endTime)) {
                return new BlockHit(block, false);
            }
            LocalDateTime effectiveStart = block.startTime.minusMinutes(nonNegative(block.bufferBeforeMinutes));
            LocalDateTime effectiveEnd = block.endTime.plusMinutes(nonNegative(block.bufferAfterMinutes));
            if (firstBufferHit == null && overlaps(startTime, endTime, effectiveStart, effectiveEnd)) {
                firstBufferHit = new BlockHit(block, true);
            }
        }
        return firstBufferHit;
    }

    private VenueFacilityMaintenance firstOverlappingMaintenance(List<VenueFacilityMaintenance> maintenances,
                                                                 LocalDateTime startTime,
                                                                 LocalDateTime endTime) {
        return maintenances.stream()
            .filter(maintenance -> overlaps(startTime, endTime, maintenance.startTime, maintenance.endTime))
            .findFirst()
            .orElse(null);
    }

    private void applyBlockHit(InventorySlot slot, BlockHit hit, boolean adminView) {
        VenueUnavailableBlock block = hit.block();
        slot.status = hit.bufferOnly() ? "BUFFER" : statusForBlock(block, true);
        slot.label = labelForStatus(slot.status);
        slot.reasonCode = block.reasonCode;
        slot.reasonText = block.reasonText;
        if (adminView) {
            slot.blockId = block.id;
            slot.blockType = block.blockType;
            slot.sourceType = block.sourceType;
            slot.relatedEntityType = block.relatedEntityType;
            slot.relatedEntityId = block.relatedEntityId;
        }
    }

    private String statusForBlock(VenueUnavailableBlock block, boolean actualOverlap) {
        if (!actualOverlap) {
            return "BUFFER";
        }
        return "MANUAL_HOLD".equals(block.blockType) ? "MANUAL_HOLD" : "UNAVAILABLE";
    }

    private String labelForStatus(String status) {
        return switch (status) {
            case "MANUAL_HOLD" -> "人工保留";
            case "BUFFER" -> "缓冲";
            case "UNAVAILABLE" -> "不可售";
            default -> status;
        };
    }

    private void markClosed(InventorySlot slot, String reasonCode, String reasonText) {
        slot.status = "CLOSED";
        slot.label = reasonText;
        slot.reasonCode = reasonCode;
        slot.reasonText = reasonText;
    }

    private boolean overlaps(LocalDateTime leftStart, LocalDateTime leftEnd,
                             LocalDateTime rightStart, LocalDateTime rightEnd) {
        return leftStart != null && leftEnd != null && rightStart != null && rightEnd != null
            && leftStart.isBefore(rightEnd)
            && leftEnd.isAfter(rightStart);
    }

    private LocalDateTime floorToHour(LocalDateTime value) {
        return value.withMinute(0).withSecond(0).withNano(0);
    }

    private LocalDateTime ceilToHour(LocalDateTime value) {
        LocalDateTime floored = floorToHour(value);
        return floored.equals(value) ? floored : floored.plusHours(1);
    }

    private LocalDateTime min(LocalDateTime left, LocalDateTime right) {
        return left.isBefore(right) ? left : right;
    }

    private LocalDateTime max(LocalDateTime left, LocalDateTime right) {
        return left.isAfter(right) ? left : right;
    }

    private String bufferConflictMessage(LocalDateTime actualStart,
                                         LocalDateTime actualEnd,
                                         LocalDateTime conflictStart,
                                         LocalDateTime conflictEnd,
                                         String directMessage) {
        if (overlaps(actualStart, actualEnd, conflictStart, conflictEnd)) {
            return directMessage;
        }
        return "缓冲时间冲突：" + directMessage;
    }

    private void validateTimeRange(LocalDateTime startTime, LocalDateTime endTime) {
        if (startTime == null || endTime == null) {
            throw BusinessException.badRequest("开始和结束时间不能为空");
        }
        if (!startTime.isBefore(endTime)) {
            throw BusinessException.badRequest("开始时间必须早于结束时间");
        }
    }

    private VenueSpace requireSpace(Long spaceId) {
        if (spaceId == null) {
            throw BusinessException.badRequest("场地不能为空");
        }
        VenueSpace space = spaceMapper.selectById(spaceId);
        if (space == null || Objects.equals(space.deleted, 1)) {
            throw BusinessException.notFound("场地不存在");
        }
        return space;
    }

    private Venue requireVenue(Long venueId) {
        if (venueId == null) {
            throw BusinessException.badRequest("场馆不能为空");
        }
        Venue venue = venueMapper.selectById(venueId);
        if (venue == null || Objects.equals(venue.deleted, 1)) {
            throw BusinessException.notFound("场馆不存在");
        }
        return venue;
    }

    private VenueUnavailableBlock requireBlock(Long blockId) {
        if (blockId == null) {
            throw BusinessException.badRequest("不可售时段不能为空");
        }
        VenueUnavailableBlock block = blockMapper.selectById(blockId);
        if (block == null || Objects.equals(block.deleted, 1)) {
            throw BusinessException.notFound("不可售时段不存在");
        }
        return block;
    }

    private void requireVenueMatch(Long venueId, VenueSpace space) {
        if (venueId == null) {
            throw BusinessException.badRequest("场馆不能为空");
        }
        if (!Objects.equals(venueId, space.venueId)) {
            throw BusinessException.badRequest("场地与场馆不匹配");
        }
    }

    private void writeAudit(String entityType, Long entityId, String action, Object before, Object after, String reasonText) {
        writeAudit(entityType, entityId, action, before == null ? null : toJson(before), toJson(after), reasonText);
    }

    private void writeAudit(String entityType, Long entityId, String action, String beforeJson, String afterJson,
                            String reasonText) {
        VenueInventoryAudit audit = new VenueInventoryAudit();
        audit.entityType = entityType;
        audit.entityId = entityId;
        audit.action = action;
        audit.operatorId = SecurityContext.currentUserId();
        audit.sourceType = "ADMIN";
        audit.beforeJson = beforeJson;
        audit.afterJson = afterJson;
        audit.reasonText = reasonText;
        audit.createTime = LocalDateTime.now();
        auditMapper.insert(audit);
    }

    private void writeBlockEvent(BusinessEventType eventType,
                                 VenueUnavailableBlock block,
                                 String idempotencyKey,
                                 String reasonText) {
        businessEventService.record(BusinessEventRecordRequest.of(
                eventType,
                BusinessDomain.VENUE_AVAILABILITY,
                "VENUE_UNAVAILABLE_BLOCK",
                block.id,
                BusinessSourceType.ADMIN,
                "SUCCESS")
            .reasonCode(block.reasonCode)
            .reasonText(blankToDefault(reasonText, block.reasonText))
            .idempotencyKey(idempotencyKey)
            .metadata("venueId", block.venueId)
            .metadata("spaceId", block.spaceId)
            .metadata("blockType", block.blockType)
            .metadata("startTime", block.startTime)
            .metadata("endTime", block.endTime)
            .metadata("bufferBeforeMinutes", nonNegative(block.bufferBeforeMinutes))
            .metadata("bufferAfterMinutes", nonNegative(block.bufferAfterMinutes))
            .metadata("relatedEntityType", block.relatedEntityType)
            .metadata("relatedEntityId", block.relatedEntityId));
    }

    private void writeCalendarEvent(VenueBusinessCalendar calendar) {
        businessEventService.record(BusinessEventRecordRequest.of(
                BusinessEventType.VENUE_CALENDAR_CHANGED,
                BusinessDomain.VENUE_AVAILABILITY,
                "VENUE_BUSINESS_CALENDAR",
                calendar.id,
                BusinessSourceType.ADMIN,
                "SUCCESS")
            .reasonCode(calendar.reasonCode)
            .reasonText(calendar.reasonText)
            .idempotencyKey("VENUE_CALENDAR_CHANGED:" + calendar.venueId + ":" + calendar.calendarDate + ":"
                + calendar.id + ":" + UUID.randomUUID())
            .metadata("venueId", calendar.venueId)
            .metadata("calendarDate", calendar.calendarDate)
            .metadata("businessStatus", calendar.businessStatus)
            .metadata("openTime", calendar.openTime)
            .metadata("closeTime", calendar.closeTime));
    }

    private String toJson(Object value) {
        if (value instanceof String text) {
            return text;
        }
        try {
            return objectMapper.writeValueAsString(value == null ? Map.of() : value);
        } catch (JsonProcessingException ex) {
            throw BusinessException.badRequest("库存审计数据无法序列化");
        }
    }

    private int reservationAdvanceDays() {
        return Math.max(0, sysConfigService.getInt("reservation.advance_days", 7));
    }

    private int nonNegative(Integer value) {
        return Math.max(0, value == null ? 0 : value);
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String blankToNull(String value) {
        return isBlank(value) ? null : value;
    }

    private String blankToDefault(String value, String fallback) {
        return isBlank(value) ? fallback : value;
    }

    private String normalizedManualSourceType(String sourceType) {
        String normalized = blankToDefault(sourceType, "ADMIN").trim().toUpperCase();
        if (!"ADMIN".equals(normalized) && !"SYSTEM".equals(normalized)) {
            throw BusinessException.badRequest("B1 暂不支持外部 provider 创建不可售");
        }
        return normalized;
    }

    private String providerFieldForManualBlock(SaveUnavailableBlockRequest request) {
        return isManualWeatherHold(request) ? null : blankToNull(request.providerEnv);
    }

    private String providerFieldForManualBlockName(SaveUnavailableBlockRequest request) {
        return isManualWeatherHold(request) ? null : blankToNull(request.providerName);
    }

    private boolean isManualWeatherHold(SaveUnavailableBlockRequest request) {
        return "WEATHER_HOLD".equalsIgnoreCase(blankToDefault(request.reasonCode, ""))
            || "WEATHER".equalsIgnoreCase(blankToDefault(request.blockType, ""));
    }

    private record BusinessWindow(LocalTime slotStart, LocalTime slotEnd, LocalTime openTime, LocalTime closeTime,
                                  boolean closed, String reasonCode, String reasonText) {
    }

    private record ClosedReason(String reasonCode, String reasonText) {
    }

    private record SlotCoverage(LocalDateTime start, LocalDateTime end) {
    }

    private record BlockHit(VenueUnavailableBlock block, boolean bufferOnly) {
    }
}
