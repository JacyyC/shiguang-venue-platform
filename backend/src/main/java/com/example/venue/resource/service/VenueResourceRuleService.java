package com.example.venue.resource.service;

import com.example.venue.common.BusinessException;
import com.example.venue.common.EntityExistenceService;
import com.example.venue.inventory.entity.VenueInventoryAudit;
import com.example.venue.inventory.mapper.VenueInventoryAuditMapper;
import com.example.venue.reservation.mapper.ReservationOccupancyMapper;
import com.example.venue.resource.dto.ResourceRuleChangeRequest;
import com.example.venue.resource.dto.ResourceRuleResponse;
import com.example.venue.resource.dto.ResourceSeatDefinition;
import com.example.venue.resource.dto.ResourceSeatResponse;
import com.example.venue.resource.entity.VenueResourceRule;
import com.example.venue.resource.entity.VenueResourceSeat;
import com.example.venue.resource.enums.BookingMode;
import com.example.venue.resource.enums.ResourceCategory;
import com.example.venue.resource.mapper.ReservationOccupancySeatMapper;
import com.example.venue.resource.mapper.VenueResourceRuleMapper;
import com.example.venue.resource.mapper.VenueResourceSeatMapper;
import com.example.venue.security.SecurityContext;
import com.example.venue.venue.entity.Venue;
import com.example.venue.venue.entity.VenueSpace;
import com.example.venue.venue.mapper.VenueSpaceMapper;
import com.example.venue.venue.service.VenueAccessService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Service
public class VenueResourceRuleService {
    private final VenueResourceRuleMapper ruleMapper;
    private final VenueResourceSeatMapper seatMapper;
    private final ReservationOccupancyMapper occupancyMapper;
    private final ReservationOccupancySeatMapper occupancySeatMapper;
    private final EntityExistenceService entityExistenceService;
    private final VenueSpaceMapper spaceMapper;
    private final VenueAccessService venueAccessService;
    private final VenueInventoryAuditMapper auditMapper;
    private final ObjectMapper objectMapper;

    public VenueResourceRuleService(VenueResourceRuleMapper ruleMapper,
                                    VenueResourceSeatMapper seatMapper,
                                    ReservationOccupancyMapper occupancyMapper,
                                    ReservationOccupancySeatMapper occupancySeatMapper,
                                    EntityExistenceService entityExistenceService,
                                    VenueSpaceMapper spaceMapper,
                                    VenueAccessService venueAccessService,
                                    VenueInventoryAuditMapper auditMapper,
                                    ObjectMapper objectMapper) {
        this.ruleMapper = ruleMapper;
        this.seatMapper = seatMapper;
        this.occupancyMapper = occupancyMapper;
        this.occupancySeatMapper = occupancySeatMapper;
        this.entityExistenceService = entityExistenceService;
        this.spaceMapper = spaceMapper;
        this.venueAccessService = venueAccessService;
        this.auditMapper = auditMapper;
        this.objectMapper = objectMapper;
    }

    public VenueResourceRule currentRule(Long spaceId) {
        VenueResourceRule rule = ruleMapper.selectActiveBySpaceId(spaceId);
        return rule == null ? legacyDefault(spaceId) : rule;
    }

    public List<VenueResourceSeat> activeSeats(Long ruleId) {
        if (ruleId == null) {
            return List.of();
        }
        return seatMapper.findActiveByRuleId(ruleId);
    }

    public ResourceRuleResponse currentRuleResponse(Long spaceId) {
        VenueSpace space = spaceMapper.selectById(spaceId);
        if (space == null || Integer.valueOf(1).equals(space.deleted)) {
            throw BusinessException.notFound("场地不存在");
        }
        return toResponse(currentRule(spaceId));
    }

    public Map<Long, ResourceRuleResponse> currentRuleSummaries(List<Long> spaceIds) {
        if (spaceIds == null || spaceIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, VenueResourceRule> rulesBySpace = new LinkedHashMap<>();
        List<VenueResourceRule> rules = ruleMapper.selectActiveBySpaceIds(spaceIds);
        for (VenueResourceRule rule : rules) {
            rulesBySpace.put(rule.spaceId, rule);
        }
        // SHIGUANG-BATCH-02 S04（B2-T8 落实）：资源面"资源定义"需要座位编码目录与座位数副标，
        // 摘要改为批量水合 ACTIVE 座位；无规则的 legacyDefault 保持空座位。
        List<Long> ruleIds = rules.stream().map(rule -> rule.id).toList();
        Map<Long, List<ResourceSeatResponse>> seatsByRule = new LinkedHashMap<>();
        if (!ruleIds.isEmpty()) {
            for (VenueResourceSeat seat : seatMapper.findActiveByRuleIds(ruleIds)) {
                seatsByRule.computeIfAbsent(seat.ruleId, key -> new ArrayList<>())
                    .add(ResourceSeatResponse.configuration(seat));
            }
        }
        Map<Long, ResourceRuleResponse> responses = new LinkedHashMap<>();
        for (Long spaceId : spaceIds) {
            VenueResourceRule rule = rulesBySpace.getOrDefault(spaceId, legacyDefault(spaceId));
            responses.put(spaceId, ResourceRuleResponse.of(rule, seatsByRule.getOrDefault(rule.id, List.of())));
        }
        return Map.copyOf(responses);
    }

    public ResourceRuleResponse manageableRuleResponse(Long spaceId) {
        VenueSpace space = spaceMapper.selectById(spaceId);
        if (space == null || Integer.valueOf(1).equals(space.deleted)) {
            throw BusinessException.notFound("场地不存在");
        }
        venueAccessService.requireVenueManageAccess(space.venueId);
        return toResponse(currentRule(spaceId));
    }

    @Transactional
    public ResourceRuleResponse configureResponse(Long spaceId, ResourceRuleChangeRequest request) {
        return toResponse(configure(spaceId, request));
    }

    public ResourceRuleResponse toResponse(VenueResourceRule rule) {
        List<ResourceSeatResponse> seats = activeSeats(rule.id).stream()
            .map(ResourceSeatResponse::configuration)
            .toList();
        return ResourceRuleResponse.of(rule, seats);
    }

    @Transactional
    public VenueResourceRule configure(Long spaceId, ResourceRuleChangeRequest request) {
        if (request == null) {
            throw BusinessException.badRequest("资源规则不能为空");
        }
        ResourceCategory category = ResourceCategory.parse(request.resourceCategory());
        BookingMode mode = BookingMode.parse(request.bookingMode());
        List<ResourceSeatDefinition> seats = normalizeSeats(mode, request.seats());
        validateCapacity(mode, request.capacityLimit());
        String reasonText = normalizedReason(request.reasonText());

        Venue venue = spaceMapper.findVenueBySpaceIdForUpdate(spaceId);
        if (venue == null) {
            throw BusinessException.notFound("场地不存在");
        }
        VenueSpace space = entityExistenceService.requireSpaceForUpdate(spaceId);
        if (!Objects.equals(space.venueId, venue.id)) {
            throw BusinessException.conflict("场地归属已变化，请刷新后重试");
        }
        venueAccessService.requireVenueManageAccess(space.venueId);

        VenueResourceRule current = ensureCurrentRuleForUpdate(space);
        List<VenueResourceSeat> currentSeats = activeSeats(current.id);
        if (sameConfiguration(current, currentSeats, category, mode, request.capacityLimit(), seats)) {
            return current;
        }
        if (occupancyMapper.countFutureActiveBySpace(space.id, LocalDateTime.now()) > 0
            && isDangerousChange(current, category, mode, request.capacityLimit(), currentSeats, seats)) {
            throw BusinessException.conflict("存在未来有效预约，不能切换模式、缩减容量或变更座位拓扑");
        }

        LocalDateTime now = LocalDateTime.now();
        ruleMapper.retireActive(space.id, now);
        VenueResourceRule next = new VenueResourceRule();
        next.spaceId = space.id;
        next.resourceCategory = category.name();
        next.bookingMode = mode.name();
        next.capacityLimit = normalizedCapacity(mode, request.capacityLimit());
        next.versionNo = ruleMapper.selectMaxVersion(space.id) + 1;
        next.status = "ACTIVE";
        next.activeFlag = 1;
        next.effectiveFrom = now;
        next.createdBy = SecurityContext.currentUserId();
        next.reasonText = reasonText;
        next.createTime = now;
        next.updateTime = now;
        ruleMapper.insert(next);
        insertSeats(space.id, next.id, seats, now);
        writeAudit(next.id, current, next, reasonText);
        return next;
    }

    public VenueResourceRule ensureCurrentRuleForUpdate(VenueSpace space) {
        // The caller already holds the space row. Do not lock an absent rule:
        // different new spaces can share the same index gap and deadlock on insert.
        if (ruleMapper.selectActiveBySpaceId(space.id) != null) {
            VenueResourceRule current = ruleMapper.selectActiveBySpaceIdForUpdate(space.id);
            if (current != null) return current;
            throw BusinessException.conflict("资源规则已变化，请刷新后重试");
        }
        LocalDateTime now = LocalDateTime.now();
        VenueResourceRule defaultRule = legacyDefault(space.id);
        defaultRule.versionNo = Math.max(1, ruleMapper.selectMaxVersion(space.id) + 1);
        defaultRule.effectiveFrom = now;
        defaultRule.createdBy = SecurityContext.currentUserId();
        defaultRule.reasonText = "新空间默认整空间排他规则";
        defaultRule.createTime = now;
        defaultRule.updateTime = now;
        try {
            ruleMapper.insert(defaultRule);
            return defaultRule;
        } catch (DuplicateKeyException exception) {
            VenueResourceRule concurrent = ruleMapper.selectActiveBySpaceIdForUpdate(space.id);
            if (concurrent == null) {
                throw exception;
            }
            return concurrent;
        }
    }

    private VenueResourceRule legacyDefault(Long spaceId) {
        VenueResourceRule rule = new VenueResourceRule();
        rule.spaceId = spaceId;
        rule.resourceCategory = ResourceCategory.SPORTS_FIELD.name();
        rule.bookingMode = BookingMode.EXCLUSIVE.name();
        rule.capacityLimit = 1;
        rule.versionNo = 1;
        rule.status = "ACTIVE";
        rule.activeFlag = 1;
        return rule;
    }

    private List<ResourceSeatDefinition> normalizeSeats(BookingMode mode, List<ResourceSeatDefinition> requested) {
        List<ResourceSeatDefinition> source = requested == null ? List.of() : requested;
        if (mode != BookingMode.ASSIGNED_SEAT) {
            if (!source.isEmpty()) {
                throw BusinessException.badRequest("只有 ASSIGNED_SEAT 模式可以配置座位");
            }
            return List.of();
        }
        if (source.isEmpty()) {
            throw BusinessException.badRequest("ASSIGNED_SEAT 模式至少需要一个座位");
        }
        Set<String> codes = new HashSet<>();
        List<ResourceSeatDefinition> normalized = new ArrayList<>();
        for (ResourceSeatDefinition seat : source) {
            String code = seat == null || seat.seatCode() == null ? "" : seat.seatCode().trim();
            String label = seat == null || seat.seatLabel() == null ? "" : seat.seatLabel().trim();
            if (code.isEmpty() || label.isEmpty()) {
                throw BusinessException.badRequest("座位编码和名称不能为空");
            }
            if (code.length() > 64 || label.length() > 128) {
                throw BusinessException.badRequest("座位编码或名称过长");
            }
            if (!codes.add(code)) {
                throw BusinessException.badRequest("座位编码不能重复");
            }
            normalized.add(new ResourceSeatDefinition(code, label));
        }
        return normalized;
    }

    private void validateCapacity(BookingMode mode, Integer capacityLimit) {
        if (mode == BookingMode.CAPACITY && (capacityLimit == null || capacityLimit < 1)) {
            throw BusinessException.badRequest("CAPACITY 模式容量必须为正整数");
        }
        if (mode != BookingMode.CAPACITY && capacityLimit != null
            && !(mode == BookingMode.EXCLUSIVE && capacityLimit == 1)) {
            throw BusinessException.badRequest("当前预订模式不接受容量参数");
        }
    }

    private Integer normalizedCapacity(BookingMode mode, Integer capacityLimit) {
        return mode == BookingMode.EXCLUSIVE ? Integer.valueOf(1) : capacityLimit;
    }

    private String normalizedReason(String reasonText) {
        if (reasonText == null || reasonText.isBlank()) {
            throw BusinessException.badRequest("资源规则变更原因不能为空");
        }
        String normalized = reasonText.trim();
        if (normalized.length() > 255) {
            throw BusinessException.badRequest("资源规则变更原因不能超过255字");
        }
        return normalized;
    }

    private boolean sameConfiguration(VenueResourceRule current,
                                      List<VenueResourceSeat> currentSeats,
                                      ResourceCategory category,
                                      BookingMode mode,
                                      Integer capacityLimit,
                                      List<ResourceSeatDefinition> seats) {
        if (!category.name().equals(current.resourceCategory)
            || !mode.name().equals(current.bookingMode)
            || !Objects.equals(normalizedCapacity(mode, capacityLimit), current.capacityLimit)) {
            return false;
        }
        if (mode != BookingMode.ASSIGNED_SEAT) {
            return true;
        }
        List<String> existing = currentSeats.stream()
            .map(seat -> seat.seatCode + "\u0000" + seat.seatLabel)
            .sorted()
            .toList();
        List<String> requested = seats.stream()
            .map(seat -> seat.seatCode() + "\u0000" + seat.seatLabel())
            .sorted()
            .toList();
        return existing.equals(requested);
    }

    private boolean isDangerousChange(VenueResourceRule current,
                                      ResourceCategory category,
                                      BookingMode mode,
                                      Integer capacityLimit,
                                      List<VenueResourceSeat> currentSeats,
                                      List<ResourceSeatDefinition> requestedSeats) {
        BookingMode currentMode = BookingMode.parse(current.bookingMode);
        if (!category.name().equals(current.resourceCategory) || currentMode != mode) {
            return true;
        }
        if (mode == BookingMode.CAPACITY && capacityLimit < current.capacityLimit) {
            return true;
        }
        if (mode == BookingMode.ASSIGNED_SEAT) {
            return !sameConfiguration(current, currentSeats, category, mode, capacityLimit, requestedSeats);
        }
        return false;
    }

    private void insertSeats(Long spaceId, Long ruleId, List<ResourceSeatDefinition> seats, LocalDateTime now) {
        int sortOrder = 0;
        for (ResourceSeatDefinition definition : seats) {
            VenueResourceSeat seat = new VenueResourceSeat();
            seat.spaceId = spaceId;
            seat.ruleId = ruleId;
            seat.seatCode = definition.seatCode();
            seat.seatLabel = definition.seatLabel();
            seat.sortOrder = ++sortOrder;
            seat.status = "ACTIVE";
            seat.createTime = now;
            seat.updateTime = now;
            seatMapper.insert(seat);
        }
    }

    private void writeAudit(Long ruleId,
                            VenueResourceRule before,
                            VenueResourceRule after,
                            String reasonText) {
        VenueInventoryAudit audit = new VenueInventoryAudit();
        audit.entityType = "VENUE_RESOURCE_RULE";
        audit.entityId = ruleId;
        audit.action = "RESOURCE_RULE_VERSION_CREATED";
        audit.operatorId = SecurityContext.currentUserId();
        audit.sourceType = "ADMIN";
        audit.beforeJson = toJson(before);
        audit.afterJson = toJson(after);
        audit.reasonText = reasonText;
        audit.createTime = LocalDateTime.now();
        auditMapper.insert(audit);
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw BusinessException.badRequest("资源规则审计数据无法序列化");
        }
    }
}
