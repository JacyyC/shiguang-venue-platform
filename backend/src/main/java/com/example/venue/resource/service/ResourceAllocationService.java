package com.example.venue.resource.service;

import com.example.venue.common.BusinessException;
import com.example.venue.common.EntityExistenceService;
import com.example.venue.reservation.entity.Reservation;
import com.example.venue.reservation.entity.ReservationOccupancy;
import com.example.venue.reservation.mapper.ReservationMapper;
import com.example.venue.reservation.mapper.ReservationOccupancyMapper;
import com.example.venue.reservation.service.ReservationCommitmentChangePublisher;
import com.example.venue.resource.dto.ResourceAvailabilityDecision;
import com.example.venue.resource.dto.ResourceAvailabilityResponse;
import com.example.venue.resource.dto.ResourceRuleResponse;
import com.example.venue.resource.dto.ResourceSeatResponse;
import com.example.venue.resource.entity.ReservationOccupancySeat;
import com.example.venue.resource.entity.VenueResourceRule;
import com.example.venue.resource.entity.VenueResourceSeat;
import com.example.venue.resource.enums.AllocationConflictCode;
import com.example.venue.resource.enums.BookingMode;
import com.example.venue.resource.exception.ResourceAllocationConflictException;
import com.example.venue.resource.mapper.ReservationOccupancySeatMapper;
import com.example.venue.resource.mapper.VenueResourceSeatMapper;
import com.example.venue.venue.entity.Venue;
import com.example.venue.venue.entity.VenueSpace;
import com.example.venue.venue.enums.VenueResourceStatus;
import com.example.venue.venue.mapper.VenueSpaceMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Service
public class ResourceAllocationService {
    private static final Logger log = LoggerFactory.getLogger(ResourceAllocationService.class);

    private final VenueResourceRuleService ruleService;
    private final VenueResourceSeatMapper seatMapper;
    private final ReservationOccupancyMapper occupancyMapper;
    private final ReservationOccupancySeatMapper occupancySeatMapper;
    private final ReservationMapper reservationMapper;
    private final EntityExistenceService entityExistenceService;
    private final VenueSpaceMapper spaceMapper;
    private final ResourceAllocationConflictEventRecorder conflictEventRecorder;
    private final ObjectMapper objectMapper;
    private ReservationCommitmentChangePublisher commitmentChangePublisher;

    public ResourceAllocationService(VenueResourceRuleService ruleService,
                                     VenueResourceSeatMapper seatMapper,
                                     ReservationOccupancyMapper occupancyMapper,
                                     ReservationOccupancySeatMapper occupancySeatMapper,
                                     ReservationMapper reservationMapper,
                                     EntityExistenceService entityExistenceService,
                                     VenueSpaceMapper spaceMapper,
                                     ResourceAllocationConflictEventRecorder conflictEventRecorder,
                                     ObjectMapper objectMapper) {
        this.ruleService = ruleService;
        this.seatMapper = seatMapper;
        this.occupancyMapper = occupancyMapper;
        this.occupancySeatMapper = occupancySeatMapper;
        this.reservationMapper = reservationMapper;
        this.entityExistenceService = entityExistenceService;
        this.spaceMapper = spaceMapper;
        this.conflictEventRecorder = conflictEventRecorder;
        this.objectMapper = objectMapper;
    }

    @Autowired
    public void setCommitmentChangePublisher(ReservationCommitmentChangePublisher commitmentChangePublisher) {
        this.commitmentChangePublisher = commitmentChangePublisher;
    }

    @Transactional
    public ReservationOccupancy allocate(Reservation reservation, Integer participantCount, List<Long> seatIds) {
        if (reservation == null || reservation.id == null) {
            throw BusinessException.badRequest("预约分配必须关联已保存的预约");
        }
        validateRange(reservation.startTime, reservation.endTime);
        LockedResource resource = lockResource(reservation.spaceId);
        if (!Objects.equals(reservation.venueId, resource.venue().id)) {
            throw BusinessException.badRequest("预约场地与场馆不匹配");
        }
        BookingMode mode = BookingMode.parse(resource.rule().bookingMode);
        List<Long> normalizedSeatIds = normalizeSeatIds(seatIds);
        int normalizedParticipants = normalizeParticipantCount(mode, participantCount, normalizedSeatIds, true);
        // F-01：创建与改期都经由此处，先按场地物理容量拒绝越界人数，再写任何占用或预约副作用。
        validatePhysicalParticipantCapacity(resource.space(), normalizedParticipants);
        List<VenueResourceSeat> selectedSeats = selectedSeats(resource.rule(), mode, normalizedSeatIds, true);
        String snapshotJson = allocationSnapshot(resource.rule(), normalizedParticipants, selectedSeats);

        // Insert the transaction-local candidate before the range lock. Empty resource ranges otherwise
        // share the same supremum gap and independent first allocations can deadlock while inserting.
        ReservationOccupancy occupancy = new ReservationOccupancy();
        occupancy.reservationId = reservation.id;
        occupancy.spaceId = reservation.spaceId;
        occupancy.slotDate = reservation.startTime.toLocalDate();
        occupancy.startTime = reservation.startTime.toLocalTime();
        occupancy.endTime = reservation.endTime.toLocalTime();
        occupancy.activeFlag = 1;
        occupancy.ruleId = resource.rule().id;
        occupancy.resourceCategorySnapshot = resource.rule().resourceCategory;
        occupancy.bookingMode = mode.name();
        occupancy.ruleVersionSnapshot = resource.rule().versionNo;
        occupancy.quantity = allocationQuantity(mode, normalizedParticipants);
        occupancy.allocationSnapshotJson = snapshotJson;
        occupancyMapper.insert(occupancy);

        ResourceAvailabilityDecision decision = evaluate(resource.rule(), resource.space(),
            reservation.startTime, reservation.endTime,
            normalizedParticipants, normalizedSeatIds, reservation.id, true, true);
        if (!decision.bookable()) {
            throw recordedConflict(resource, reservation.startTime, reservation.endTime,
                allocationQuantity(mode, normalizedParticipants), decision);
        }

        reservation.resourceCategorySnapshot = resource.rule().resourceCategory;
        reservation.bookingModeSnapshot = mode.name();
        reservation.ruleIdSnapshot = resource.rule().id;
        reservation.ruleVersionSnapshot = resource.rule().versionNo;
        reservation.participantCount = normalizedParticipants;
        reservation.allocationSnapshotJson = snapshotJson;
        reservation.updateTime = LocalDateTime.now();
        reservationMapper.updateById(reservation);
        insertSeatAllocations(occupancy, selectedSeats);
        return occupancy;
    }

    @Transactional
    public ReservationOccupancy replace(Reservation reservation,
                                        LocalDateTime newStartTime,
                                        LocalDateTime newEndTime,
                                        Integer participantCount,
                                        List<Long> seatIds) {
        if (reservation == null || reservation.id == null) {
            throw BusinessException.badRequest("改期分配必须关联预约");
        }
        validateRange(newStartTime, newEndTime);
        lockResource(reservation.spaceId);
        Integer replacementParticipantCount = participantCount == null
            ? reservation.participantCount
            : participantCount;
        List<Long> replacementSeatIds = seatIds == null
            ? activeSeatIds(reservation.id)
            : seatIds;
        occupancyMapper.releaseByReservationIdWithReason(reservation.id, "RESCHEDULE_REPLACED");
        reservation.startTime = newStartTime;
        reservation.endTime = newEndTime;
        ReservationOccupancy replacement = allocate(
            reservation, replacementParticipantCount, replacementSeatIds);
        publishCommitmentChange(reservation.id, "RESCHEDULED", "RESCHEDULE_REPLACED");
        return replacement;
    }

    @Transactional
    public int release(Long reservationId, String reason) {
        if (reservationId == null) {
            return 0;
        }
        String normalizedReason = normalizedReleaseReason(reason);
        int changed = occupancyMapper.releaseByReservationIdWithReason(reservationId, normalizedReason);
        if (changed > 0) {
            publishCommitmentChange(reservationId, "RELEASED", normalizedReason);
        }
        return changed;
    }

    @Transactional(readOnly = true)
    public ResourceAvailabilityDecision checkAvailability(Long spaceId,
                                                          LocalDateTime startTime,
                                                          LocalDateTime endTime,
                                                          Integer participantCount,
                                                          List<Long> seatIds,
                                                          Long excludedReservationId) {
        return checkAvailabilityInternal(spaceId, startTime, endTime, participantCount, seatIds,
            excludedReservationId, false);
    }

    @Transactional(readOnly = true)
    public ResourceAvailabilityDecision checkRequestedAvailability(Long spaceId,
                                                                   LocalDateTime startTime,
                                                                   LocalDateTime endTime,
                                                                   Integer participantCount,
                                                                   List<Long> seatIds,
                                                                   Long excludedReservationId) {
        return checkAvailabilityInternal(spaceId, startTime, endTime, participantCount, seatIds,
            excludedReservationId, true);
    }

    @Transactional(readOnly = true)
    public List<Long> activeSeatIds(Long reservationId) {
        if (reservationId == null) {
            return List.of();
        }
        return List.copyOf(occupancySeatMapper.findActiveSeatIdsByReservationId(reservationId));
    }

    @Transactional(readOnly = true)
    public ResourceAvailabilityResponse availability(Long spaceId,
                                                     LocalDateTime startTime,
                                                     LocalDateTime endTime,
                                                     Integer participantCount,
                                                     List<Long> seatIds,
                                                     Long excludedReservationId) {
        validateRange(startTime, endTime);
        VenueResourceRule rule = ruleService.currentRule(spaceId);
        BookingMode mode = BookingMode.parse(rule.bookingMode);
        List<Long> normalizedSeatIds = normalizeSeatIds(seatIds);
        int normalizedParticipants = normalizeParticipantCount(
            mode,
            participantCount == null ? 1 : participantCount,
            normalizedSeatIds,
            !normalizedSeatIds.isEmpty()
        );
        VenueSpace allocationSpace = spaceMapper.selectById(spaceId);
        ResourceAvailabilityDecision decision = evaluate(
            rule,
            allocationSpace,
            startTime,
            endTime,
            normalizedParticipants,
            normalizedSeatIds,
            excludedReservationId,
            false,
            !normalizedSeatIds.isEmpty()
        );

        List<ResourceSeatResponse> seats = List.of();
        if (mode == BookingMode.ASSIGNED_SEAT && rule.id != null) {
            List<VenueResourceSeat> activeSeats = seatMapper.findActiveByRuleId(rule.id);
            List<Long> activeSeatIds = activeSeats.stream().map(seat -> seat.id).toList();
            Set<Long> allocated = activeSeatIds.isEmpty()
                ? Set.of()
                : Set.copyOf(occupancySeatMapper.findAllocatedSeatIds(
                    rule.spaceId,
                    startTime.toLocalDate(),
                    startTime.toLocalTime(),
                    endTime.toLocalTime(),
                    excludedReservationId,
                    activeSeatIds
                ));
            seats = activeSeats.stream()
                .map(seat -> ResourceSeatResponse.availability(seat, !allocated.contains(seat.id)))
                .toList();
        }

        int remainingCapacity = currentRemainingCapacity(
            mode,
            decision,
            normalizedParticipants,
            normalizedSeatIds.size()
        );
        ResourceRuleResponse ruleResponse = ResourceRuleResponse.of(rule, seats, allocationSpace);
        return new ResourceAvailabilityResponse(
            spaceId,
            ruleResponse,
            normalizedParticipants,
            remainingCapacity,
            decision.bookable(),
            decision.conflictCode() == null ? null : decision.conflictCode().name(),
            decision.reasonText()
        );
    }

    private ResourceAvailabilityDecision checkAvailabilityInternal(Long spaceId,
                                                                   LocalDateTime startTime,
                                                                   LocalDateTime endTime,
                                                                   Integer participantCount,
                                                                   List<Long> seatIds,
                                                                   Long excludedReservationId,
                                                                   boolean strictSelection) {
        validateRange(startTime, endTime);
        VenueResourceRule rule = ruleService.currentRule(spaceId);
        BookingMode mode = BookingMode.parse(rule.bookingMode);
        List<Long> normalizedSeatIds = normalizeSeatIds(seatIds);
        int normalizedParticipants = normalizeParticipantCount(mode, participantCount, normalizedSeatIds,
            strictSelection);
        return evaluate(rule, spaceMapper.selectById(spaceId), startTime, endTime, normalizedParticipants,
            normalizedSeatIds, excludedReservationId, false, strictSelection);
    }

    @Transactional(readOnly = true)
    public boolean hasActiveOverlap(Long spaceId, LocalDateTime startTime, LocalDateTime endTime) {
        validateRange(startTime, endTime);
        return !occupancyMapper.findActiveOverlaps(spaceId, startTime.toLocalDate(), startTime.toLocalTime(),
            endTime.toLocalTime(), null).isEmpty();
    }

    private ResourceAvailabilityDecision evaluate(VenueResourceRule rule,
                                                  VenueSpace space,
                                                  LocalDateTime startTime,
                                                  LocalDateTime endTime,
                                                  int participantCount,
                                                  List<Long> requestedSeatIds,
                                                  Long excludedReservationId,
                                                  boolean forUpdate,
                                                  boolean strictSelection) {
        BookingMode mode = BookingMode.parse(rule.bookingMode);
        List<ReservationOccupancy> overlaps = forUpdate
            ? occupancyMapper.findActiveOverlapsForUpdate(rule.spaceId, startTime.toLocalDate(),
                startTime.toLocalTime(), endTime.toLocalTime(), excludedReservationId)
            : occupancyMapper.findActiveOverlaps(rule.spaceId, startTime.toLocalDate(),
                startTime.toLocalTime(), endTime.toLocalTime(), excludedReservationId);

        // F-01：物理承载人数与预订模式无关，先判它，再判模式各自的库存/座位/整场冲突。
        // 这样「人数本身就超过场地」在任何模式下都得到同一个稳定 reasonCode。
        ResourceAvailabilityDecision physical = physicalCapacityRejection(mode, space, participantCount, null);
        if (physical != null) {
            return physical;
        }

        if (mode == BookingMode.EXCLUSIVE) {
            // 整场占用量恒为 1，与到场人数（受物理容量约束）是两件事。
            return overlaps.isEmpty()
                ? ResourceAvailabilityDecision.available(mode, 1)
                : ResourceAvailabilityDecision.rejected(mode, AllocationConflictCode.EXCLUSIVE_OVERLAP,
                    "该时段已被整空间预约", 0);
        }

        if (mode == BookingMode.CAPACITY) {
            int capacity = rule.capacityLimit == null ? 0 : rule.capacityLimit;
            int allocated = overlaps.stream().mapToInt(value -> value.quantity == null ? 1 : value.quantity).sum();
            int remaining = Math.max(0, capacity - allocated);
            return participantCount <= remaining
                ? ResourceAvailabilityDecision.available(mode, remaining - participantCount)
                : ResourceAvailabilityDecision.rejected(mode, AllocationConflictCode.CAPACITY_EXCEEDED,
                    "该时段剩余容量不足", remaining);
        }

        List<VenueResourceSeat> activeSeats = rule.id == null ? List.of() : seatMapper.findActiveByRuleId(rule.id);
        if (activeSeats.isEmpty()) {
            return ResourceAvailabilityDecision.rejected(mode, AllocationConflictCode.RESOURCE_RULE_UNAVAILABLE,
                "当前座位规则没有可用座位", 0);
        }
        if (strictSelection) {
            selectedSeats(rule, mode, requestedSeatIds, true);
        }
        List<Long> activeSeatIds = activeSeats.stream().map(seat -> seat.id).toList();
        List<Long> allocatedSeatIds;
        if (forUpdate) {
            List<Long> overlapIds = overlaps.stream().map(value -> value.id).toList();
            // Avoid locking the empty global seat index. The overlap rows are already current-read locked;
            // locking their concrete seat rows keeps the same-seat check current without cross-space gap locks.
            allocatedSeatIds = overlapIds.isEmpty()
                ? List.of()
                : occupancySeatMapper.findSeatIdsByOccupancyIdsForUpdate(overlapIds);
        } else {
            allocatedSeatIds = occupancySeatMapper.findAllocatedSeatIds(rule.spaceId,
                startTime.toLocalDate(), startTime.toLocalTime(), endTime.toLocalTime(),
                excludedReservationId, activeSeatIds);
        }
        Set<Long> allocatedSeatSet = new LinkedHashSet<>(allocatedSeatIds);
        allocatedSeatSet.retainAll(Set.copyOf(activeSeatIds));
        int remaining = Math.max(0, activeSeats.size() - allocatedSeatSet.size());
        if (!requestedSeatIds.isEmpty() && requestedSeatIds.stream().anyMatch(allocatedSeatSet::contains)) {
            return ResourceAvailabilityDecision.rejected(mode, AllocationConflictCode.SEAT_ALREADY_ALLOCATED,
                "所选座位在该时段已被预约", remaining);
        }
        if (requestedSeatIds.isEmpty() && remaining < participantCount) {
            return ResourceAvailabilityDecision.rejected(mode, AllocationConflictCode.SEAT_ALREADY_ALLOCATED,
                "该时段可用座位不足", remaining);
        }
        return ResourceAvailabilityDecision.available(mode, requestedSeatIds.isEmpty()
            ? remaining - participantCount
            : remaining - requestedSeatIds.size());
    }

    private LockedResource lockResource(Long spaceId) {
        Venue venue = spaceMapper.findVenueBySpaceIdForShare(spaceId);
        if (venue == null) {
            throw BusinessException.notFound("场地不存在");
        }
        VenueSpace space = entityExistenceService.requireSpaceForUpdate(spaceId);
        if (!Objects.equals(space.venueId, venue.id)) {
            throw BusinessException.conflict("场地归属已变化，请刷新后重试");
        }
        if (!VenueResourceStatus.isActive(venue.status) || !VenueResourceStatus.isActive(space.status)) {
            throw new ResourceAllocationConflictException(AllocationConflictCode.RESOURCE_RULE_UNAVAILABLE,
                "场馆或场地当前不可预约");
        }
        VenueResourceRule rule = ruleService.ensureCurrentRuleForUpdate(space);
        return new LockedResource(venue, space, rule);
    }

    private List<Long> normalizeSeatIds(List<Long> seatIds) {
        if (seatIds == null || seatIds.isEmpty()) {
            return List.of();
        }
        if (seatIds.stream().anyMatch(Objects::isNull)) {
            throw BusinessException.badRequest("座位ID不能为空");
        }
        Set<Long> unique = new LinkedHashSet<>(seatIds);
        if (unique.size() != seatIds.size()) {
            throw BusinessException.badRequest("座位不能重复选择");
        }
        return List.copyOf(unique);
    }

    /**
     * F-01（SHIGUANG-WAVE-01-REMEDIATION-20260916 S04）：到场人数不得超过场地物理承载人数。
     *
     * <p>{@code venue_space.capacity} 是场地物理容量；{@code venue_resource_rule.capacity_limit}
     * 是资源规则的可售单位，两者职责不同、不自动同步（V14 与既有 Spec 的明确分层）。
     * 该上限与资源规则无关：EXCLUSIVE 下 {@code capacity_limit=1} 只表示「一笔整场占用」，
     * 不代表只能 1 人到场，因此这里读的是场地物理容量，而不是 `capacity_limit`；
     * {@code reservation_occupancy.quantity} 仍由 {@link #allocationQuantity} 固定为 1。
     *
     * <p>物理容量同样与预订模式无关：CAPACITY / ASSIGNED_SEAT 也把真实的人带进同一个场地，
     * 因此三种模式共用这一条上限，不因模式不同而放宽。CAPACITY 的
     * {@code capacityLimit} 与座位数只决定「可售单位」，不改变物理承载人数。
     *
     * <p>容量为空视为未设置物理上限（历史数据兼容），不做拒绝。
     *
     * @return 物理容量，未设置时为 {@code null}
     */
    private Integer validatePhysicalParticipantCapacity(VenueSpace space, int participantCount) {
        if (space == null) {
            return null;
        }
        Integer capacity = space.capacity;
        if (capacity != null && participantCount > capacity) {
            throw BusinessException.badRequest("本次同行人数超过场地可容纳人数（最多 " + capacity + " 人）");
        }
        return capacity;
    }

    /** 只读可用性判断：把物理超容表达为不可预订与稳定的 reasonCode，而不是抛异常。 */
    private ResourceAvailabilityDecision physicalCapacityRejection(BookingMode mode, VenueSpace space,
                                                                   int participantCount,
                                                                   Integer remainingCapacity) {
        if (space == null || space.capacity == null || participantCount <= space.capacity) {
            return null;
        }
        return ResourceAvailabilityDecision.rejected(mode, AllocationConflictCode.PHYSICAL_CAPACITY_EXCEEDED,
            "本次同行人数超过场地可容纳人数（最多 " + space.capacity + " 人）", remainingCapacity);
    }

    private int normalizeParticipantCount(BookingMode mode,
                                          Integer participantCount,
                                          List<Long> seatIds,
                                          boolean strictSelection) {
        if (mode == BookingMode.CAPACITY && participantCount == null) {
            throw BusinessException.badRequest("CAPACITY 模式必须填写预约人数");
        }
        int normalized = participantCount == null
            ? mode == BookingMode.ASSIGNED_SEAT && !seatIds.isEmpty() ? seatIds.size() : 1
            : participantCount;
        if (normalized < 1) {
            throw BusinessException.badRequest("预约人数必须为正整数");
        }
        if (mode != BookingMode.ASSIGNED_SEAT && !seatIds.isEmpty()) {
            throw BusinessException.badRequest("当前预订模式不能选择座位");
        }
        if (mode == BookingMode.ASSIGNED_SEAT && strictSelection && seatIds.isEmpty()) {
            throw BusinessException.badRequest("ASSIGNED_SEAT 模式必须选择座位");
        }
        if (mode == BookingMode.ASSIGNED_SEAT && !seatIds.isEmpty() && normalized != seatIds.size()) {
            throw BusinessException.badRequest("预约人数必须与所选座位数一致");
        }
        return normalized;
    }

    private List<VenueResourceSeat> selectedSeats(VenueResourceRule rule,
                                                  BookingMode mode,
                                                  List<Long> seatIds,
                                                  boolean strictSelection) {
        if (mode != BookingMode.ASSIGNED_SEAT) {
            return List.of();
        }
        if (seatIds.isEmpty()) {
            if (strictSelection) {
                throw BusinessException.badRequest("ASSIGNED_SEAT 模式必须选择座位");
            }
            return List.of();
        }
        List<VenueResourceSeat> seats = seatMapper.findActiveByRuleIdAndIds(rule.id, seatIds);
        if (seats.size() != seatIds.size()) {
            throw BusinessException.badRequest("所选座位不存在、已停用或不属于当前规则版本");
        }
        return seats;
    }

    private int allocationQuantity(BookingMode mode, int participantCount) {
        return mode == BookingMode.EXCLUSIVE ? 1 : participantCount;
    }

    private int currentRemainingCapacity(BookingMode mode,
                                         ResourceAvailabilityDecision decision,
                                         int participantCount,
                                         int selectedSeatCount) {
        if (mode == BookingMode.EXCLUSIVE) {
            return decision.bookable() ? 1 : 0;
        }
        int remaining = decision.remainingCapacity() == null ? 0 : decision.remainingCapacity();
        if (!decision.bookable()) {
            return remaining;
        }
        int requested = mode == BookingMode.ASSIGNED_SEAT && selectedSeatCount > 0
            ? selectedSeatCount
            : participantCount;
        return remaining + requested;
    }

    private void insertSeatAllocations(ReservationOccupancy occupancy, List<VenueResourceSeat> seats) {
        for (VenueResourceSeat seat : seats) {
            ReservationOccupancySeat allocation = new ReservationOccupancySeat();
            allocation.occupancyId = occupancy.id;
            allocation.reservationId = occupancy.reservationId;
            allocation.seatId = seat.id;
            allocation.seatCodeSnapshot = seat.seatCode;
            allocation.seatLabelSnapshot = seat.seatLabel;
            allocation.createTime = LocalDateTime.now();
            occupancySeatMapper.insert(allocation);
        }
    }

    private String allocationSnapshot(VenueResourceRule rule,
                                      int participantCount,
                                      List<VenueResourceSeat> seats) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("resourceCategory", rule.resourceCategory);
        snapshot.put("bookingMode", rule.bookingMode);
        snapshot.put("ruleId", rule.id);
        snapshot.put("ruleVersion", rule.versionNo);
        snapshot.put("participantCount", participantCount);
        if (!seats.isEmpty()) {
            List<Map<String, Object>> seatSnapshots = new ArrayList<>();
            for (VenueResourceSeat seat : seats) {
                seatSnapshots.add(Map.of("seatId", seat.id, "seatCode", seat.seatCode, "seatLabel", seat.seatLabel));
            }
            snapshot.put("seats", seatSnapshots);
        }
        try {
            return objectMapper.writeValueAsString(snapshot);
        } catch (JsonProcessingException exception) {
            throw BusinessException.badRequest("资源分配快照无法序列化");
        }
    }

    private ResourceAllocationConflictException recordedConflict(LockedResource resource,
                                                                  LocalDateTime startTime,
                                                                  LocalDateTime endTime,
                                                                  int quantity,
                                                                  ResourceAvailabilityDecision decision) {
        try {
            conflictEventRecorder.record(resource.venue().id, resource.space().id, decision.bookingMode(),
                startTime, endTime, quantity, decision.conflictCode(), decision.reasonText());
        } catch (RuntimeException exception) {
            log.warn("资源分配冲突证据写入失败 spaceId={} conflictCode={}", resource.space().id,
                decision.conflictCode(), exception);
        }
        return new ResourceAllocationConflictException(decision.conflictCode(), decision.reasonText());
    }

    private String normalizedReleaseReason(String reason) {
        if (reason == null || reason.isBlank()) {
            return "RESOURCE_RELEASED";
        }
        String normalized = reason.trim();
        return normalized.length() <= 255 ? normalized : normalized.substring(0, 255);
    }

    private void publishCommitmentChange(Long reservationId, String changeType, String reasonCode) {
        if (commitmentChangePublisher != null) {
            commitmentChangePublisher.publish(reservationId, changeType, reasonCode);
        }
    }

    private void validateRange(LocalDateTime startTime, LocalDateTime endTime) {
        if (startTime == null || endTime == null || !startTime.isBefore(endTime)) {
            throw BusinessException.badRequest("预约开始时间必须早于结束时间");
        }
        if (!startTime.toLocalDate().equals(endTime.toLocalDate())) {
            throw BusinessException.badRequest("暂不支持跨日预约");
        }
    }

    private record LockedResource(Venue venue, VenueSpace space, VenueResourceRule rule) {
    }

}
