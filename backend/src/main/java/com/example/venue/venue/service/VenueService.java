package com.example.venue.venue.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.venue.common.BusinessException;
import com.example.venue.common.Constants;
import com.example.venue.common.PageResult;
import com.example.venue.feedback.dto.VenueRatingAggregate;
import com.example.venue.feedback.mapper.VenueReviewMapper;
import com.example.venue.inventory.dto.SaveUnavailableBlockRequest;
import com.example.venue.inventory.entity.VenueUnavailableBlock;
import com.example.venue.inventory.service.VenueInventoryService;
import com.example.venue.reservation.entity.Reservation;
import com.example.venue.reservation.enums.ReservationStatus;
import com.example.venue.reservation.mapper.ReservationMapper;
import com.example.venue.reservation.service.ReservationService;
import com.example.venue.resource.dto.ResourceRuleChangeRequest;
import com.example.venue.resource.dto.ResourceRuleResponse;
import com.example.venue.resource.service.VenueResourceRuleService;
import com.example.venue.security.SecurityContext;
import com.example.venue.venue.dto.SaveMaintenanceRequest;
import com.example.venue.venue.dto.SaveSpaceRequest;
import com.example.venue.venue.dto.SaveVenueRequest;
import com.example.venue.venue.dto.VenueFacetsResponse;
import com.example.venue.venue.dto.VenueQuery;
import com.example.venue.venue.dto.VenueListItemResponse;
import com.example.venue.venue.dto.VenueSpaceAggregate;
import com.example.venue.venue.dto.VenueSpaceResponse;
import com.example.venue.venue.entity.Venue;
import com.example.venue.venue.entity.VenueFacilityMaintenance;
import com.example.venue.venue.entity.VenueSpace;
import com.example.venue.venue.enums.VenueResourceStatus;
import com.example.venue.venue.mapper.VenueFacilityMaintenanceMapper;
import com.example.venue.venue.mapper.VenueMapper;
import com.example.venue.venue.mapper.VenueSpaceMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class VenueService {
    private static final List<String> FULFILLABLE_RESERVATION_STATUSES = List.of(
        ReservationStatus.PENDING_REVIEW.name(),
        ReservationStatus.APPROVED.name(),
        ReservationStatus.PENDING_PAYMENT.name(),
        ReservationStatus.PAID.name()
    );

    private final VenueMapper venueMapper;
    private final VenueSpaceMapper spaceMapper;
    private final VenueFacilityMaintenanceMapper maintenanceMapper;
    private final ReservationMapper reservationMapper;
    private final ReservationService reservationService;
    private final VenueAccessService accessService;
    private final VenueInventoryService inventoryService;
    private final VenueResourceRuleService resourceRuleService;
    private final VenueReviewMapper reviewMapper;

    public VenueService(VenueMapper venueMapper, VenueSpaceMapper spaceMapper,
                        VenueFacilityMaintenanceMapper maintenanceMapper, ReservationMapper reservationMapper,
                        ReservationService reservationService, VenueAccessService accessService,
                        VenueInventoryService inventoryService,
                        VenueResourceRuleService resourceRuleService,
                        VenueReviewMapper reviewMapper) {
        this.venueMapper = venueMapper;
        this.spaceMapper = spaceMapper;
        this.maintenanceMapper = maintenanceMapper;
        this.reservationMapper = reservationMapper;
        this.reservationService = reservationService;
        this.accessService = accessService;
        this.inventoryService = inventoryService;
        this.resourceRuleService = resourceRuleService;
        this.reviewMapper = reviewMapper;
    }

    public PageResult<VenueListItemResponse> list(VenueQuery query) {
        QueryWrapper<Venue> wrapper = new QueryWrapper<Venue>().eq("deleted", 0);
        if (query.keyword != null && !query.keyword.isBlank()) {
            wrapper.and(w -> w.like("name", query.keyword).or().like("address", query.keyword));
        }
        if (query.area != null && !query.area.isBlank()) {
            wrapper.eq("area", query.area);
        }
        applySpaceTypeFilter(wrapper, query.type);
        applyVenueStatusFilter(wrapper, query.status);
        Page<Venue> page = venueMapper.selectPage(new Page<>(query.getPageNum(), query.getPageSize()), wrapper.orderByDesc("id"));
        if (page.getRecords().isEmpty()) {
            return PageResult.of(List.of(), page.getTotal(), query.getPageNum(), query.getPageSize());
        }
        List<Long> venueIds = page.getRecords().stream().map(venue -> venue.id).toList();
        Map<Long, VenueRatingAggregate> ratings = reviewMapper.selectAggregatesByVenueIds(venueIds).stream()
            .collect(Collectors.toMap(VenueRatingAggregate::venueId, Function.identity()));
        Map<Long, VenueSpaceAggregate> spaces = spaceMapper.selectAggregatesByVenueIds(venueIds).stream()
            .collect(Collectors.toMap(VenueSpaceAggregate::venueId, Function.identity()));
        List<VenueListItemResponse> records = page.getRecords().stream()
            .map(venue -> VenueListItemResponse.of(venue, ratings.get(venue.id), spaces.get(venue.id)))
            .toList();
        return PageResult.of(records, page.getTotal(), query.getPageNum(), query.getPageSize());
    }

    /**
     * 场馆列表筛选区选项：全量 distinct 区域与场地类型，替代前端从当前页结果反推。
     * 区域来自全部未删除场馆；场地类型仅取 ACTIVE 场地，与卡片聚合口径一致。
     */
    public VenueFacetsResponse facets() {
        return new VenueFacetsResponse(venueMapper.selectDistinctAreas(), spaceMapper.selectDistinctSpaceTypes());
    }

    public Venue detail(Long id) {
        Venue venue = venueMapper.selectById(id);
        if (venue == null || venue.deleted != null && venue.deleted == 1) {
            throw BusinessException.notFound("场馆不存在");
        }
        return venue;
    }

    public List<VenueSpaceResponse> spaces(Long venueId) {
        List<VenueSpace> spaces = spaceMapper.selectList(new QueryWrapper<VenueSpace>()
            .eq("venue_id", venueId)
            .eq("deleted", 0)
            .orderByAsc("type", "code", "id"));
        Map<Long, ResourceRuleResponse> rules = resourceRuleService.currentRuleSummaries(
            spaces.stream().map(space -> space.id).toList());
        return spaces.stream()
            .map(space -> VenueSpaceResponse.of(space, rules.get(space.id)))
            .toList();
    }

    public ResourceRuleResponse manageableResourceRule(Long spaceId) {
        return resourceRuleService.manageableRuleResponse(spaceId);
    }

    public ResourceRuleResponse configureResourceRule(Long spaceId, ResourceRuleChangeRequest request) {
        return resourceRuleService.configureResponse(spaceId, request);
    }

    public List<Venue> manageableVenues() {
        var user = SecurityContext.currentUser();
        if (user == null) {
            throw BusinessException.forbidden("未登录");
        }
        QueryWrapper<Venue> wrapper = new QueryWrapper<Venue>().eq("deleted", 0);
        if (!user.hasRole(Constants.ROLE_SYSTEM_ADMIN)) {
            List<Long> venueIds = accessService.manageableVenueIds();
            if (venueIds.isEmpty()) {
                return List.of();
            }
            wrapper.in("id", venueIds);
        }
        return venueMapper.selectList(wrapper.orderByAsc("id"));
    }

    @Transactional
    public Venue saveVenue(SaveVenueRequest request) {
        requireValidBusinessHours(request);
        Venue venue = new Venue();
        copyVenue(request, venue, false);
        venue.createTime = LocalDateTime.now();
        venue.updateTime = venue.createTime;
        venue.deleted = 0;
        venueMapper.insert(venue);
        return venue;
    }

    @Transactional
    public Venue updateVenue(Long id, SaveVenueRequest request) {
        accessService.requireVenueManageAccess(id);
        requireValidBusinessHours(request);
        // A26-P2-10：停用路径需加行锁，避免 detail(id) 普通读在 RR 下提前建立读视图，
        // 导致后续 cancelFutureReservationsIfUnavailable 的取消扫描看不到加锁等待期间
        // 并发提交的新预约（PAID 预约漏取消→退款链路未启动→用户到场扑空）。
        // 对照同文件 updateSpace 用 selectByIdForUpdate。
        Venue venue = venueMapper.selectByIdForUpdate(id);
        if (venue == null || venue.deleted != null && venue.deleted == 1) {
            throw BusinessException.notFound("场馆不存在");
        }
        // A26-P3-14：修改默认营业时间窗口收缩时，落在窗外既有有效预约既不能审核通过
        // （必抛"非营业时间"）也不会被系统取消；同一收缩若走营业日历会被明确拒绝。
        // 参照 saveCalendar 的 rejectCalendarReservationConflicts 收缩拒绝逻辑。
        rejectBusinessHoursShrinkConflict(id, venue, request.openTime, request.closeTime);
        String previousStatus = venue.status;
        copyVenue(request, venue, true);
        venue.updateTime = LocalDateTime.now();
        venueMapper.updateById(venue);
        cancelFutureReservationsIfUnavailable("场馆", id, null, previousStatus, venue.status);
        return venue;
    }

    @Transactional
    public VenueSpace saveSpace(SaveSpaceRequest request) {
        requireExistingVenue(request.venueId);
        accessService.requireVenueManageAccess(request.venueId);
        VenueSpace space = new VenueSpace();
        copySpace(request, space, false);
        space.createTime = LocalDateTime.now();
        space.updateTime = space.createTime;
        space.deleted = 0;
        spaceMapper.insert(space);
        return space;
    }

    @Transactional
    public VenueSpace updateSpace(Long id, SaveSpaceRequest request) {
        VenueSpace space = spaceMapper.selectByIdForUpdate(id);
        if (space == null || space.deleted != null && space.deleted == 1) {
            throw BusinessException.notFound("场地不存在");
        }
        accessService.requireVenueManageAccess(space.venueId);
        accessService.requireVenueManageAccess(request.venueId);
        requireExistingVenue(request.venueId);
        Long previousVenueId = space.venueId;
        if (!Objects.equals(previousVenueId, request.venueId)) {
            if (reservationMapper.existsBySpaceId(space.id)) {
                throw BusinessException.conflict("存在历史预约的场地不能变更所属场馆");
            }
            // A26-P3-15：变更场馆时若该场地下存在 ACTIVE 不可售块，块 venue_id 会变陈旧，
            // 导致人工保留/不可售时段在旧馆查不到、新馆也查不到，可被预订或展示与决策分叉。
            if (inventoryService.hasActiveUnavailableBlocksBySpace(space.id)) {
                throw BusinessException.conflict("存在有效不可售时段的场地不能变更所属场馆，请先取消或调整");
            }
        }
        String previousStatus = space.status;
        copySpace(request, space, true);
        space.updateTime = LocalDateTime.now();
        spaceMapper.updateById(space);
        cancelFutureReservationsIfUnavailable("场地", previousVenueId, space.id, previousStatus, space.status);
        return space;
    }

    @Transactional
    public void updateSpaceStatus(Long id, String status) {
        // A26-P2-10：停用路径需加行锁，避免 selectById 普通读在 RR 下提前建立读视图，
        // 导致后续 cancelFutureReservationsIfUnavailable 的取消扫描看不到加锁等待期间
        // 并发提交的新预约（PAID 预约漏取消→退款链路未启动→用户到场扑空）。
        // 对照同文件 updateSpace 用 selectByIdForUpdate。
        VenueSpace space = spaceMapper.selectByIdForUpdate(id);
        if (space == null || space.deleted != null && space.deleted == 1) {
            throw BusinessException.notFound("场地不存在");
        }
        accessService.requireVenueManageAccess(space.venueId);
        String previousStatus = space.status;
        space.status = VenueResourceStatus.normalizeOrDefault(status, "场地状态", VenueResourceStatus.ACTIVE);
        space.updateTime = LocalDateTime.now();
        spaceMapper.updateById(space);
        cancelFutureReservationsIfUnavailable("场地", space.venueId, space.id, previousStatus, space.status);
    }

    public List<VenueFacilityMaintenance> maintenances(Long spaceId) {
        VenueSpace space = requireSpace(spaceId);
        accessService.requireVenueManageAccess(space.venueId);
        return maintenanceMapper.selectList(new QueryWrapper<VenueFacilityMaintenance>()
            .eq("space_id", spaceId)
            .orderByDesc("start_time"));
    }

    @Transactional
    public VenueFacilityMaintenance createMaintenance(SaveMaintenanceRequest request) {
        VenueSpace space = requireSpace(request.spaceId);
        accessService.requireVenueManageAccess(space.venueId);
        if (request.startTime == null || request.endTime == null || !request.startTime.isBefore(request.endTime)) {
            throw BusinessException.badRequest("维护开始时间必须早于结束时间");
        }
        if (request.reason == null || request.reason.isBlank()) {
            throw BusinessException.badRequest("维护原因不能为空");
        }
        VenueUnavailableBlock block = inventoryService.createBlock(maintenanceBlockRequest(space, request));
        VenueFacilityMaintenance maintenance = new VenueFacilityMaintenance();
        maintenance.spaceId = request.spaceId;
        maintenance.startTime = request.startTime;
        maintenance.endTime = request.endTime;
        maintenance.reason = request.reason.trim();
        maintenance.status = "ACTIVE";
        maintenance.unavailableBlockId = block.id;
        maintenance.sourceType = "ADMIN";
        maintenanceMapper.insert(maintenance);
        return maintenance;
    }

    @Transactional
    public void cancelMaintenance(Long id) {
        VenueFacilityMaintenance maintenance = maintenanceMapper.selectById(id);
        if (maintenance == null) {
            throw BusinessException.notFound("维护记录不存在");
        }
        VenueSpace space = requireSpace(maintenance.spaceId);
        accessService.requireVenueManageAccess(space.venueId);
        if (maintenance.unavailableBlockId != null) {
            try {
                inventoryService.cancelBlock(maintenance.unavailableBlockId, "维护时段取消");
            } catch (BusinessException ex) {
                if (!"不可售时段已取消".equals(ex.getMessage())) {
                    throw ex;
                }
            }
        }
        maintenance.status = "CANCELED";
        maintenanceMapper.updateById(maintenance);
    }

    private SaveUnavailableBlockRequest maintenanceBlockRequest(VenueSpace space, SaveMaintenanceRequest request) {
        SaveUnavailableBlockRequest blockRequest = new SaveUnavailableBlockRequest();
        blockRequest.venueId = space.venueId;
        blockRequest.spaceId = space.id;
        blockRequest.blockType = "MAINTENANCE";
        blockRequest.reasonCode = "MAINTENANCE";
        blockRequest.reasonText = request.reason.trim();
        blockRequest.startTime = request.startTime;
        blockRequest.endTime = request.endTime;
        blockRequest.sourceType = "ADMIN";
        return blockRequest;
    }

    private void copyVenue(SaveVenueRequest request, Venue venue, boolean isUpdate) {
        venue.name = request.name;
        venue.address = request.address;
        venue.area = request.area;
        venue.phone = request.phone;
        venue.openTime = request.openTime;
        venue.closeTime = request.closeTime;
        venue.description = request.description;
        venue.image = request.image;
        if (isUpdate && (request.status == null || request.status.isBlank())) {
            // A26-P3-16：updateVenue 省略 status 时保持 DB 原值，避免无意重新开放已停业场馆。
            // 其余省略字段因 NOT_NULL 策略都保持原值，只有 status 因 normalizeOrDefault 默认 ACTIVE 而例外。
            // venue.status 已是 selectByIdForUpdate 读出的当前值，此处不覆盖。
        } else {
            venue.status = VenueResourceStatus.normalizeOrDefault(request.status, "场馆状态", VenueResourceStatus.ACTIVE);
        }
    }

    private void requireValidBusinessHours(SaveVenueRequest request) {
        if (request != null && request.openTime != null && request.closeTime != null
            && !request.openTime.isBefore(request.closeTime)) {
            throw BusinessException.badRequest("场馆开馆时间必须早于闭馆时间");
        }
    }

    /**
     * A26-P3-14：updateVenue 修改默认营业时间窗口收缩时校验既有有效预约。
     * <p>窗口收缩后落在窗外的有效预约（PENDING_REVIEW/APPROVED/PENDING_PAYMENT/PAID）
     * 既不能审核通过（必抛"非营业时间"）也不会被系统取消；同一收缩若走营业日历会被
     * saveCalendar 的 rejectCalendarReservationConflicts 明确拒绝。此处对默认营业时间
     * 收缩做对称校验，避免 updateVenue 形成绕过营业日历守门的捷径。</p>
     * <p>仅校验窗口收缩；扩张不影响既有预约。仅当 openTime 后移或 closeTime 前移时触发。
     * 当前实现不区分该日是否有日历覆盖，保守拒绝；用户若需精确控制请走营业日历。</p>
     */
    private void rejectBusinessHoursShrinkConflict(Long venueId, Venue venue,
                                                   LocalTime requestedOpenTime, LocalTime requestedCloseTime) {
        if (requestedOpenTime == null && requestedCloseTime == null) {
            return;
        }
        LocalTime currentOpen = venue.openTime;
        LocalTime currentClose = venue.closeTime;
        if (currentOpen == null || currentClose == null) {
            return;
        }
        LocalTime effectiveOpen = requestedOpenTime != null ? requestedOpenTime : currentOpen;
        LocalTime effectiveClose = requestedCloseTime != null ? requestedCloseTime : currentClose;
        boolean shrunk = (requestedOpenTime != null && requestedOpenTime.isAfter(currentOpen))
            || (requestedCloseTime != null && requestedCloseTime.isBefore(currentClose));
        if (!shrunk) {
            return;
        }
        QueryWrapper<Reservation> wrapper = new QueryWrapper<Reservation>()
            .select("id", "start_time", "end_time")
            .eq("deleted", 0)
            .eq("venue_id", venueId)
            .in("status", FULFILLABLE_RESERVATION_STATUSES)
            .gt("start_time", LocalDateTime.now());
        for (Reservation reservation : reservationMapper.selectList(wrapper)) {
            if (reservation.startTime.toLocalTime().isBefore(effectiveOpen)
                || reservation.endTime.toLocalTime().isAfter(effectiveClose)) {
                throw BusinessException.conflict("场馆默认营业时间收缩后存在有效预约落在窗外，请先处理或改走营业日历");
            }
        }
    }

    private void requireExistingVenue(Long venueId) {
        if (venueId == null) {
            throw BusinessException.badRequest("场馆不能为空");
        }
        Venue venue = venueMapper.selectById(venueId);
        if (venue == null || Integer.valueOf(1).equals(venue.deleted)) {
            throw BusinessException.notFound("场馆不存在");
        }
    }

    private void copySpace(SaveSpaceRequest request, VenueSpace space, boolean isUpdate) {
        space.venueId = request.venueId;
        space.name = request.name;
        space.type = request.type;
        space.code = request.code;
        space.capacity = request.capacity;
        space.pricePerHour = request.pricePerHour;
        space.facilityText = request.facilityText;
        space.description = request.description;
        if (isUpdate && (request.status == null || request.status.isBlank())) {
            // A26-P3-16：updateSpace 省略 status 时保持 DB 原值，避免无意重新开放已停用场地。
            // 对照 copyVenue 同模式。
            // space.status 已是 selectByIdForUpdate 读出的当前值，此处不覆盖。
        } else {
            space.status = VenueResourceStatus.normalizeOrDefault(request.status, "场地状态", VenueResourceStatus.ACTIVE);
        }
    }

    private void applySpaceTypeFilter(QueryWrapper<Venue> wrapper, String type) {
        if (type == null || type.isBlank()) {
            return;
        }
        // 一馆多类：只要存在一个 ACTIVE 且类型匹配的场地，场馆即入选该类型分区。
        wrapper.and(w -> w.exists(
            "select 1 from venue_space s where s.venue_id = venue.id"
                + " and s.deleted = 0 and s.status = 'ACTIVE' and s.type = {0}", type.trim()));
    }

    private void applyVenueStatusFilter(QueryWrapper<Venue> wrapper, String status) {
        if (status == null || status.isBlank()) {
            return;
        }
        if (VenueResourceStatus.isDisabledAlias(status)) {
            wrapper.ne("status", VenueResourceStatus.ACTIVE.name());
            return;
        }
        wrapper.eq("status", VenueResourceStatus.parse(status, "场馆状态").name());
    }

    private void cancelFutureReservationsIfUnavailable(String resourceName, Long venueId, Long spaceId,
                                                       String previousStatus, String currentStatus) {
        if (VenueResourceStatus.isActive(currentStatus) || Objects.equals(previousStatus, currentStatus)) {
            return;
        }
        QueryWrapper<Reservation> wrapper = new QueryWrapper<Reservation>()
            .select("id")
            .eq("deleted", 0)
            .eq("venue_id", venueId)
            .in("status", FULFILLABLE_RESERVATION_STATUSES)
            .gt("start_time", LocalDateTime.now());
        if (spaceId != null) {
            wrapper.eq("space_id", spaceId);
        }
        String reason = resourceName + "当前状态为" + VenueResourceStatus.parse(currentStatus, resourceName + "状态").label()
            + "，预约无法履约，已由系统取消。";
        for (Reservation reservation : reservationMapper.selectList(wrapper)) {
            reservationService.cancel(reservation.id, reason, true);
        }
    }

    private VenueSpace requireSpace(Long spaceId) {
        VenueSpace space = spaceMapper.selectById(spaceId);
        if (space == null || space.deleted != null && space.deleted == 1) {
            throw BusinessException.notFound("场地不存在");
        }
        return space;
    }
}
