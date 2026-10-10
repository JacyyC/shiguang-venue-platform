package com.example.venue.checkin.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.venue.business.dto.BusinessEventRecordRequest;
import com.example.venue.business.enums.BusinessDomain;
import com.example.venue.business.enums.BusinessEventType;
import com.example.venue.business.enums.BusinessSourceType;
import com.example.venue.business.service.BusinessEventService;
import com.example.venue.checkin.dto.CheckinAdminQuery;
import com.example.venue.checkin.dto.RescheduleRequest;
import com.example.venue.checkin.dto.CheckinVerifyRequest;
import com.example.venue.checkin.dto.CompletionDecision;
import com.example.venue.checkin.dto.LifecycleEventResponse;
import com.example.venue.checkin.dto.ManualAttendanceRequest;
import com.example.venue.checkin.dto.NoShowDecisionRequest;
import com.example.venue.checkin.dto.ReservationLifecycleResponse;
import com.example.venue.checkin.entity.ReservationLifecycle;
import com.example.venue.checkin.entity.ReservationLifecycleEvent;
import com.example.venue.checkin.entity.ReservationRescheduleRequest;
import com.example.venue.checkin.mapper.ReservationLifecycleEventMapper;
import com.example.venue.checkin.mapper.ReservationLifecycleMapper;
import com.example.venue.checkin.mapper.ReservationRescheduleRequestMapper;
import com.example.venue.common.BusinessException;
import com.example.venue.common.PageQuery;
import com.example.venue.common.PageResult;
import com.example.venue.order.entity.BizOrder;
import com.example.venue.order.mapper.BizOrderMapper;
import com.example.venue.reservation.entity.Reservation;
import com.example.venue.reservation.enums.ReservationStatus;
import com.example.venue.reservation.mapper.ReservationMapper;
import com.example.venue.security.SecurityContext;
import com.example.venue.system.service.SysConfigService;
import com.example.venue.venue.service.VenueAccessService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Service
public class ReservationLifecycleService {
    private static final Logger log = LoggerFactory.getLogger(ReservationLifecycleService.class);
    private static final String CREDENTIAL_ISSUED = "ISSUED";
    private static final String CREDENTIAL_INVALIDATED = "INVALIDATED";
    private static final String CHECKIN_NOT_CHECKED_IN = "NOT_CHECKED_IN";
    private static final String CHECKIN_CHECKED_IN = "CHECKED_IN";
    private static final String CHECKIN_CANCELED = "CANCELED";
    private static final String ATTENDANCE_PENDING = "PENDING";
    private static final String ATTENDANCE_PRESENT = "PRESENT";
    private static final String ATTENDANCE_LATE = "LATE";
    private static final String ATTENDANCE_MANUAL_PRESENT = "MANUAL_PRESENT";
    private static final String ATTENDANCE_ABSENT = "ABSENT";
    private static final String ATTENDANCE_CANCELED = "CANCELED";
    private static final String NO_SHOW_NOT_EVALUATED = "NOT_EVALUATED";
    private static final String NO_SHOW_CANDIDATE = "CANDIDATE";
    private static final String NO_SHOW_CONFIRMED = "CONFIRMED";
    private static final String NO_SHOW_REVOKED = "REVOKED";
    private static final String RESCHEDULE_PENDING_REVIEW = "PENDING_REVIEW";
    private static final String RESCHEDULE_APPROVED = "APPROVED";
    private static final String RESCHEDULE_REJECTED = "REJECTED";
    private static final String SOURCE_ADMIN = "ADMIN";
    private static final String SOURCE_SYSTEM = "SYSTEM";
    private static final String SOURCE_USER = "USER";
    private static final String EVENT_CREDENTIAL_ISSUED = "CHECKIN_CREDENTIAL_ISSUED";
    private static final String EVENT_CREDENTIAL_REISSUED = "CHECKIN_CREDENTIAL_REISSUED";
    private static final String EVENT_CREDENTIAL_INVALIDATED = "CHECKIN_CREDENTIAL_INVALIDATED";
    private static final String EVENT_CHECKIN_VERIFIED = "CHECKIN_VERIFIED";
    private static final String EVENT_EXCEPTION_RECORDED = "CHECKIN_EXCEPTION_RECORDED";
    private static final String EVENT_NO_SHOW_CANDIDATE_MARKED = "NO_SHOW_CANDIDATE_MARKED";
    private static final String EVENT_NO_SHOW_CONFIRMED = "NO_SHOW_CONFIRMED";
    private static final String EVENT_NO_SHOW_REVOKED = "NO_SHOW_REVOKED";
    private static final String EVENT_RESERVATION_CANCELED = "RESERVATION_CANCELED";
    private static final int RESPONSE_EVENT_LIMIT = 5;
    private static final String NO_SHOW_CONFIRM_AFTER_MINUTES_KEY = "reservation.no_show.confirm_after_minutes";
    private static final int DEFAULT_NO_SHOW_CONFIRM_AFTER_MINUTES = 60;

    private final ReservationLifecycleMapper lifecycleMapper;
    private final ReservationLifecycleEventMapper eventMapper;
    private final ReservationRescheduleRequestMapper rescheduleMapper;
    private final ReservationMapper reservationMapper;
    @SuppressWarnings("unused")
    private final BizOrderMapper orderMapper;
    private final SysConfigService sysConfigService;
    private final BusinessEventService businessEventService;
    private final VenueAccessService venueAccessService;
    private final SecureRandom secureRandom = new SecureRandom();

    public ReservationLifecycleService(ReservationLifecycleMapper lifecycleMapper,
                                       ReservationLifecycleEventMapper eventMapper,
                                       ReservationRescheduleRequestMapper rescheduleMapper,
                                       ReservationMapper reservationMapper,
                                       BizOrderMapper orderMapper,
                                       SysConfigService sysConfigService,
                                       BusinessEventService businessEventService,
                                       VenueAccessService venueAccessService) {
        this.lifecycleMapper = lifecycleMapper;
        this.eventMapper = eventMapper;
        this.rescheduleMapper = rescheduleMapper;
        this.reservationMapper = reservationMapper;
        this.orderMapper = orderMapper;
        this.sysConfigService = sysConfigService;
        this.businessEventService = businessEventService;
        this.venueAccessService = venueAccessService;
    }

    @Transactional
    public ReservationLifecycleResponse issueCredentialForPaidReservation(BizOrder order, Reservation reservation) {
        if (reservation == null || reservation.id == null) {
            throw BusinessException.badRequest("预约不能为空");
        }
        ReservationLifecycle existing = lifecycleMapper.findByReservationId(reservation.id);
        if (existing != null) {
            // 已签发过的凭证不再重新生成短码：明文在首次签发时一次性交付，此后无法恢复。
            // 返回不含 checkinCode/qrPayload 的查询态响应（与 myLifecycle 等查询接口一致）。
            return toResponse(existing);
        }
        if (!ReservationStatus.PAID.name().equals(reservation.status)) {
            throw BusinessException.badRequest("当前预约状态不可发放核销凭证");
        }

        LocalDateTime now = LocalDateTime.now();
        int openBeforeMinutes = sysConfigService.getInt("reservation.checkin.open_before_minutes", 30);
        int closeAfterMinutes = sysConfigService.getInt("reservation.checkin.close_after_minutes", 30);
        ReservationLifecycle lifecycle = new ReservationLifecycle();
        lifecycle.reservationId = reservation.id;
        lifecycle.orderId = order == null ? null : order.id;
        lifecycle.userId = reservation.userId;
        lifecycle.venueId = reservation.venueId;
        lifecycle.spaceId = reservation.spaceId;
        lifecycle.credentialStatus = CREDENTIAL_ISSUED;
        lifecycle.credentialIssuedAt = now;
        lifecycle.credentialExpiresAt = reservation.endTime.plusMinutes(closeAfterMinutes);
        lifecycle.checkinWindowStart = reservation.startTime.minusMinutes(openBeforeMinutes);
        lifecycle.checkinWindowEnd = reservation.endTime.plusMinutes(closeAfterMinutes);
        lifecycle.attendanceStatus = ATTENDANCE_PENDING;
        lifecycle.checkinStatus = CHECKIN_NOT_CHECKED_IN;
        lifecycle.noShowStatus = NO_SHOW_NOT_EVALUATED;
        lifecycle.manualOverride = 0;
        lifecycle.lateMinutes = 0;
        lifecycle.createTime = now;
        lifecycle.updateTime = now;
        lifecycle.deleted = 0;
        String code = insertLifecycleWithUniqueCredential(lifecycle);

        writeLifecycleEvent(lifecycle, EVENT_CREDENTIAL_ISSUED, null, CREDENTIAL_ISSUED, null, null, SOURCE_SYSTEM,
            EVENT_CREDENTIAL_ISSUED + ":" + lifecycle.reservationId);
        // A26-P1-01：明文 code 只在签发方法的局部变量中，签发后通过 response 一次性交付给调用方
        // （Controller → 前端）。不持久化明文，仅存 checkinCodeHash + checkinCodeLast4。
        // toResponse 不填充 checkinCode/qrPayload，确保 myLifecycle 等查询接口不返回明文。
        ReservationLifecycleResponse response = toResponse(lifecycle);
        response.checkinCode = code;
        response.qrPayload = "CHECKIN:" + reservation.id + ":" + code;
        return response;
    }

    @Transactional(readOnly = true)
    public ReservationLifecycleResponse myLifecycle(Long reservationId, Long userId) {
        ReservationLifecycle lifecycle = requireLifecycle(reservationId);
        if (!Objects.equals(lifecycle.userId, userId)) {
            throw BusinessException.forbidden("无权查看该核销凭证");
        }
        return toResponse(lifecycle);
    }

    /**
     * Rotates and returns a check-in credential to its owner without persisting plaintext.
     * This is the delivery path for asynchronous Provider callbacks, whose server-to-server
     * response cannot carry the credential to the user's browser.
     */
    @Transactional
    public ReservationLifecycleResponse reissueMyCredential(Long reservationId, Long userId) {
        if (userId == null) {
            throw BusinessException.forbidden("未登录");
        }
        Reservation reservation = reservationMapper.selectByIdForUpdate(reservationId);
        if (reservation == null || Integer.valueOf(1).equals(reservation.deleted)
            || !Objects.equals(reservation.userId, userId)) {
            throw BusinessException.notFound("预约不存在");
        }
        if (!ReservationStatus.PAID.name().equals(reservation.status)) {
            throw BusinessException.badRequest("当前预约状态不可领取核销凭证");
        }

        ReservationLifecycle lifecycle = lifecycleMapper.findByReservationIdForUpdate(reservationId);
        if (lifecycle == null) {
            throw BusinessException.conflict("核销凭证尚未签发，请稍后重试");
        }
        if (!CHECKIN_NOT_CHECKED_IN.equals(lifecycle.checkinStatus)) {
            throw BusinessException.badRequest("当前预约已完成核销");
        }
        LocalDateTime now = LocalDateTime.now();
        if (lifecycle.credentialExpiresAt != null && lifecycle.credentialExpiresAt.isBefore(now)) {
            throw BusinessException.badRequest("核销凭证已过期");
        }

        String fromStatus = lifecycle.credentialStatus;
        lifecycle.credentialStatus = CREDENTIAL_ISSUED;
        lifecycle.credentialIssuedAt = now;
        lifecycle.updateTime = now;
        String code = updateLifecycleWithUniqueCredential(lifecycle);

        writeLifecycleEvent(lifecycle, EVENT_CREDENTIAL_REISSUED, fromStatus, CREDENTIAL_ISSUED,
            "USER_CREDENTIAL_REQUESTED", "用户领取并轮换核销凭证", SOURCE_USER,
            EVENT_CREDENTIAL_REISSUED + ":" + reservationId + ":" + UUID.randomUUID());
        ReservationLifecycleResponse response = toResponse(lifecycle);
        response.checkinCode = code;
        response.qrPayload = "CHECKIN:" + reservationId + ":" + code;
        return response;
    }

    @Transactional(readOnly = true)
    public List<ReservationLifecycleResponse> myCheckins() {
        Long userId = SecurityContext.currentUserId();
        if (userId == null) {
            throw BusinessException.forbidden("未登录");
        }
        return lifecycleMapper.selectList(new QueryWrapper<ReservationLifecycle>()
                .eq("deleted", 0)
                .eq("user_id", userId)
                .orderByDesc("id"))
            .stream()
            .map(this::toResponse)
            .toList();
    }

    @Transactional(readOnly = true)
    public PageResult<ReservationLifecycleResponse> adminCheckins(CheckinAdminQuery query) {
        CheckinAdminQuery safeQuery = query == null ? new CheckinAdminQuery() : query;
        QueryWrapper<ReservationLifecycle> wrapper = new QueryWrapper<ReservationLifecycle>().eq("deleted", 0);
        if (safeQuery.venueId != null) {
            venueAccessService.requireVenueManageAccess(safeQuery.venueId);
            wrapper.eq("venue_id", safeQuery.venueId);
        } else {
            List<Long> manageableVenueIds = venueAccessService.manageableVenueIds();
            if (manageableVenueIds != null) {
                if (manageableVenueIds.isEmpty()) {
                    return PageResult.of(List.of(), 0, safeQuery.getPageNum(), safeQuery.getPageSize());
                }
                wrapper.in("venue_id", manageableVenueIds);
            }
        }
        applyCheckinAdminQuery(wrapper, safeQuery);
        Page<ReservationLifecycle> page = lifecycleMapper.selectPage(
            new Page<>(safeQuery.getPageNum(), safeQuery.getPageSize()), wrapper.orderByDesc("id"));
        return PageResult.of(page.getRecords().stream().map(this::toResponse).toList(), page.getTotal(),
            safeQuery.getPageNum(), safeQuery.getPageSize());
    }

    @Transactional(readOnly = true)
    public ReservationLifecycleResponse adminLifecycle(Long reservationId) {
        ReservationLifecycle lifecycle = requireLifecycle(reservationId);
        venueAccessService.requireVenueManageAccess(lifecycle.venueId);
        return toResponse(lifecycle);
    }

    @Transactional
    public ReservationRescheduleRequest createRescheduleRequest(Reservation reservation, RescheduleRequest request) {
        if (reservation == null || reservation.id == null) {
            throw BusinessException.badRequest("预约不能为空");
        }
        if (request == null) {
            throw BusinessException.badRequest("改期申请不能为空");
        }
        Reservation lockedReservation = reservationMapper.selectByIdForUpdate(reservation.id);
        if (lockedReservation == null || Integer.valueOf(1).equals(lockedReservation.deleted)) {
            throw BusinessException.notFound("预约不存在");
        }
        Long pendingCount = rescheduleMapper.selectCount(new QueryWrapper<ReservationRescheduleRequest>()
            .eq("reservation_id", lockedReservation.id)
            .eq("status", RESCHEDULE_PENDING_REVIEW)
            .eq("deleted", 0));
        if (pendingCount != null && pendingCount > 0) {
            throw BusinessException.conflict("预约已有待审核改期申请");
        }

        LocalDateTime now = LocalDateTime.now();
        ReservationRescheduleRequest rescheduleRequest = new ReservationRescheduleRequest();
        rescheduleRequest.reservationId = lockedReservation.id;
        rescheduleRequest.userId = lockedReservation.userId;
        rescheduleRequest.venueId = lockedReservation.venueId;
        rescheduleRequest.spaceId = lockedReservation.spaceId;
        rescheduleRequest.fromStartTime = lockedReservation.startTime;
        rescheduleRequest.fromEndTime = lockedReservation.endTime;
        rescheduleRequest.toStartTime = request.startTime;
        rescheduleRequest.toEndTime = request.endTime;
        rescheduleRequest.status = RESCHEDULE_PENDING_REVIEW;
        rescheduleRequest.reasonCode = blankToNull(request.reasonCode);
        rescheduleRequest.reasonText = blankToNull(request.reasonText);
        rescheduleRequest.createTime = now;
        rescheduleRequest.updateTime = now;
        rescheduleRequest.deleted = 0;
        rescheduleMapper.insert(rescheduleRequest);
        writeRescheduleBusinessEvent(BusinessEventType.RESCHEDULE_REQUESTED, rescheduleRequest,
            BusinessSourceType.USER, null, RESCHEDULE_PENDING_REVIEW, rescheduleRequest.reasonCode,
            rescheduleRequest.reasonText, "RESCHEDULE_REQUESTED:" + rescheduleRequest.id);
        return rescheduleRequest;
    }

    @Transactional(readOnly = true)
    public ReservationRescheduleRequest requireRescheduleRequest(Long requestId) {
        ReservationRescheduleRequest request = rescheduleMapper.selectById(requestId);
        if (request == null || Integer.valueOf(1).equals(request.deleted)) {
            throw BusinessException.notFound("改期申请不存在");
        }
        return request;
    }

    // A26-P3-03：改期申请审核入口用行锁版本，串行化并发"通过+拒绝"。
    // 锁后重读的 request 传给 approveRescheduleRequest/rejectRescheduleRequest，
    // private reviewRescheduleRequest 的 PENDING_REVIEW 守卫也基于锁后快照，
    // 后到的审核请求会看到前一个审核写入的 APPROVED/REJECTED 状态而被守卫拒绝。
    @Transactional
    public ReservationRescheduleRequest requireRescheduleRequestForUpdate(Long requestId) {
        ReservationRescheduleRequest request = rescheduleMapper.selectByIdForUpdate(requestId);
        if (request == null || Integer.valueOf(1).equals(request.deleted)) {
            throw BusinessException.notFound("改期申请不存在");
        }
        return request;
    }

    @Transactional(readOnly = true)
    public PageResult<ReservationRescheduleRequest> adminRescheduleRequests(PageQuery query, List<Long> venueIds) {
        PageQuery safeQuery = query == null ? new PageQuery() : query;
        QueryWrapper<ReservationRescheduleRequest> wrapper =
            new QueryWrapper<ReservationRescheduleRequest>().eq("deleted", 0);
        if (venueIds != null) {
            if (venueIds.isEmpty()) {
                return PageResult.of(List.of(), 0, safeQuery.getPageNum(), safeQuery.getPageSize());
            }
            wrapper.in("venue_id", venueIds);
        }
        Page<ReservationRescheduleRequest> page = rescheduleMapper.selectPage(
            new Page<>(safeQuery.getPageNum(), safeQuery.getPageSize()), wrapper.orderByDesc("id"));
        return PageResult.of(page.getRecords(), page.getTotal(), safeQuery.getPageNum(), safeQuery.getPageSize());
    }

    @Transactional(readOnly = true)
    public PageResult<ReservationRescheduleRequest> myRescheduleRequests(PageQuery query, Long userId) {
        if (userId == null) {
            throw BusinessException.forbidden("未登录");
        }
        PageQuery safeQuery = query == null ? new PageQuery() : query;
        QueryWrapper<ReservationRescheduleRequest> wrapper = new QueryWrapper<ReservationRescheduleRequest>()
            .eq("user_id", userId)
            .eq("deleted", 0);
        Page<ReservationRescheduleRequest> page = rescheduleMapper.selectPage(
            new Page<>(safeQuery.getPageNum(), safeQuery.getPageSize()), wrapper.orderByDesc("id"));
        return PageResult.of(page.getRecords(), page.getTotal(), safeQuery.getPageNum(), safeQuery.getPageSize());
    }

    @Transactional
    public void approveRescheduleRequest(ReservationRescheduleRequest request, String reviewReason) {
        reviewRescheduleRequest(request, RESCHEDULE_APPROVED, reviewReason, BusinessEventType.RESCHEDULE_APPROVED);
    }

    @Transactional
    public void rejectRescheduleRequest(ReservationRescheduleRequest request, String reviewReason) {
        reviewRescheduleRequest(request, RESCHEDULE_REJECTED, reviewReason, BusinessEventType.RESCHEDULE_REJECTED);
    }

    @Transactional
    public void recordCancellation(Reservation reservation, String fromStatus, String reasonCode,
                                   String reasonText, String sourceType) {
        if (reservation == null || reservation.id == null) {
            throw BusinessException.badRequest("预约不能为空");
        }
        BusinessSourceType businessSource = businessSourceType(sourceType);
        String eventSource = businessSource.name();
        String normalizedReasonCode = blankToNull(reasonCode);
        String normalizedReasonText = blankToNull(reasonText);
        ReservationLifecycle lifecycle = lifecycleMapper.findByReservationId(reservation.id);
        Long lifecycleId = null;
        if (lifecycle != null) {
            lifecycleId = lifecycle.id;
            lifecycle.attendanceStatus = ATTENDANCE_CANCELED;
            lifecycle.checkinStatus = CHECKIN_CANCELED;
            lifecycle.reasonCode = normalizedReasonCode;
            lifecycle.reasonText = normalizedReasonText;
            lifecycle.updateTime = LocalDateTime.now();
            lifecycleMapper.updateById(lifecycle);
        }
        writeLifecycleEvent(reservation.id, lifecycleId, EVENT_RESERVATION_CANCELED, fromStatus,
            ReservationStatus.CANCELED.name(), normalizedReasonCode, normalizedReasonText, eventSource,
            EVENT_RESERVATION_CANCELED + ":" + reservation.id);
        writeReservationCanceledBusinessEvent(reservation, fromStatus, normalizedReasonCode, normalizedReasonText,
            businessSource);
    }

    @Transactional
    public ReservationLifecycle verify(CheckinVerifyRequest request) {
        if (request == null || isBlank(request.credential)) {
            throw BusinessException.badRequest("核销凭证不能为空");
        }
        ReservationLifecycle lifecycle = findByCredential(request.credential);
        if (lifecycle == null) {
            throw BusinessException.badRequest("核销凭证无效");
        }
        venueAccessService.requireVenueManageAccess(lifecycle.venueId);
        // A26-P3-02：行锁 lifecycle 行后再校验与更新，避免基于过期快照整行回写丢失并发更新
        // （如 manualPass 的 manual_override=1 被 verify 写回 0）。参照 G2a 已修的 manualPass/
        // processCompletionCandidate 路径模式：findByCredential 仅用于定位行与场馆权限校验，
        // 状态校验与 updateById 必须基于锁后重读的最新行。
        ReservationLifecycle locked = lifecycleMapper.selectByIdForUpdate(lifecycle.id);
        if (locked == null || Integer.valueOf(1).equals(locked.deleted)) {
            throw BusinessException.notFound("预约履约记录不存在");
        }
        lifecycle = locked;
        if (CREDENTIAL_INVALIDATED.equals(lifecycle.credentialStatus)) {
            throw BusinessException.badRequest("核销凭证已失效");
        }
        if (CHECKIN_CHECKED_IN.equals(lifecycle.checkinStatus)) {
            return lifecycle;
        }

        Reservation reservation = requireReservation(lifecycle.reservationId);
        // A26-P2-01：核销前校验预约状态必须为 PAID，防止 REFUNDING/REFUNDED/EXPIRED/CANCELED 等终态或
        // 退款中状态被核销（用户既用场地又拿回全款的资金风险）。对照 requireManualPassAllowed 的 PAID 守卫。
        if (!ReservationStatus.PAID.name().equals(reservation.status)) {
            throw BusinessException.badRequest("当前预约状态不可核销");
        }
        LocalDateTime now = LocalDateTime.now();
        if (lifecycle.checkinWindowStart != null && now.isBefore(lifecycle.checkinWindowStart)) {
            throw BusinessException.badRequest("核销窗口尚未开启");
        }
        if (lifecycle.checkinWindowEnd != null && now.isAfter(lifecycle.checkinWindowEnd)) {
            throw BusinessException.badRequest("核销窗口已关闭");
        }

        String fromStatus = lifecycle.checkinStatus;
        int lateGraceMinutes = sysConfigService.getInt("reservation.checkin.late_grace_minutes", 10);
        long lateMinutes = Math.max(0, ChronoUnit.MINUTES.between(reservation.startTime, now));
        boolean late = now.isAfter(reservation.startTime.plusMinutes(lateGraceMinutes));
        lifecycle.attendanceStatus = late ? ATTENDANCE_LATE : ATTENDANCE_PRESENT;
        lifecycle.checkinStatus = CHECKIN_CHECKED_IN;
        lifecycle.checkedInAt = now;
        lifecycle.checkedInBy = SecurityContext.currentUserId();
        lifecycle.checkinSourceType = SOURCE_ADMIN;
        lifecycle.deviceRef = blankToNull(request.deviceRef);
        lifecycle.reasonCode = blankToNull(request.reasonCode);
        lifecycle.reasonText = blankToNull(request.reasonText);
        lifecycle.lateMinutes = late ? (int) lateMinutes : 0;
        lifecycle.updateTime = now;
        lifecycleMapper.updateById(lifecycle);

        String idempotencyKey = EVENT_CHECKIN_VERIFIED + ":" + lifecycle.reservationId;
        writeLifecycleEvent(lifecycle, EVENT_CHECKIN_VERIFIED, fromStatus, CHECKIN_CHECKED_IN, request.reasonCode,
            request.reasonText, SOURCE_ADMIN, idempotencyKey);
        writeBusinessEvent(BusinessEventType.CHECKIN_VERIFIED, lifecycle, BusinessSourceType.ADMIN, fromStatus, CHECKIN_CHECKED_IN,
            request.reasonCode, request.reasonText, idempotencyKey);
        return lifecycle;
    }

    @Transactional
    public ReservationLifecycle manualPass(Long reservationId, ManualAttendanceRequest request) {
        if (request == null || isBlank(request.reasonCode) || isBlank(request.reasonText)) {
            throw BusinessException.badRequest("人工核销原因不能为空");
        }
        ReservationLifecycle lifecycle = requireLifecycle(reservationId);
        // AUD-P2-06 行锁 lifecycle 行后再校验与更新，避免与并发自动爽约候选标记互相用旧实体覆盖当前事实
        ReservationLifecycle locked = lifecycleMapper.selectByIdForUpdate(lifecycle.id);
        if (locked == null || Integer.valueOf(1).equals(locked.deleted)) {
            throw BusinessException.notFound("预约履约记录不存在");
        }
        lifecycle = locked;
        Reservation reservation = requireReservation(reservationId);
        venueAccessService.requireVenueManageAccess(lifecycle.venueId);
        requireManualPassAllowed(lifecycle, reservation);

        LocalDateTime now = LocalDateTime.now();
        String fromStatus = lifecycle.attendanceStatus;
        lifecycle.attendanceStatus = ATTENDANCE_MANUAL_PRESENT;
        lifecycle.checkinStatus = CHECKIN_CHECKED_IN;
        lifecycle.checkedInAt = lifecycle.checkedInAt == null ? now : lifecycle.checkedInAt;
        lifecycle.checkedInBy = SecurityContext.currentUserId();
        lifecycle.checkinSourceType = SOURCE_ADMIN;
        lifecycle.deviceRef = blankToNull(request.deviceRef);
        lifecycle.manualOverride = 1;
        lifecycle.reasonCode = request.reasonCode;
        lifecycle.reasonText = request.reasonText;
        lifecycle.lateMinutes = Math.max(0, (int) ChronoUnit.MINUTES.between(reservation.startTime, now));
        lifecycle.updateTime = now;
        lifecycleMapper.updateById(lifecycle);

        String idempotencyKey = EVENT_EXCEPTION_RECORDED + ":" + lifecycle.reservationId + ":" + request.reasonCode;
        writeLifecycleEvent(lifecycle, EVENT_EXCEPTION_RECORDED, fromStatus, ATTENDANCE_MANUAL_PRESENT,
            request.reasonCode, request.reasonText, SOURCE_ADMIN, idempotencyKey);
        writeBusinessEvent(BusinessEventType.CHECKIN_EXCEPTION_RECORDED, lifecycle, BusinessSourceType.ADMIN, fromStatus,
            ATTENDANCE_MANUAL_PRESENT, request.reasonCode, request.reasonText, idempotencyKey);
        return lifecycle;
    }

    @Transactional
    public void freezeCredentialForReservation(Long reservationId, String reason) {
        ReservationLifecycle lifecycle = lifecycleMapper.findByReservationId(reservationId);
        if (lifecycle == null) {
            return;
        }
        if (CREDENTIAL_INVALIDATED.equals(lifecycle.credentialStatus)) {
            return;
        }
        String fromStatus = lifecycle.credentialStatus;
        lifecycle.credentialStatus = CREDENTIAL_INVALIDATED;
        lifecycle.reasonText = blankToNull(reason);
        lifecycle.updateTime = LocalDateTime.now();
        lifecycleMapper.updateById(lifecycle);
        writeLifecycleEvent(lifecycle, EVENT_CREDENTIAL_INVALIDATED, fromStatus, CREDENTIAL_INVALIDATED, null, reason,
            SOURCE_SYSTEM, EVENT_CREDENTIAL_INVALIDATED + ":" + lifecycle.reservationId);
    }

    @Transactional
    public CompletionDecision processCompletionCandidate(Reservation reservation, LocalDateTime now) {
        if (reservation == null || reservation.id == null) {
            return CompletionDecision.noAction();
        }
        ReservationLifecycle lifecycle = lifecycleMapper.findByReservationId(reservation.id);
        if (lifecycle == null) {
            return CompletionDecision.noAction();
        }
        // AUD-P2-06 行锁 lifecycle 行后再做状态判断与更新，避免与并发人工核销互相用旧实体覆盖当前事实
        ReservationLifecycle locked = lifecycleMapper.selectByIdForUpdate(lifecycle.id);
        if (locked == null) {
            return CompletionDecision.noAction();
        }
        lifecycle = locked;
        if (NO_SHOW_CONFIRMED.equals(lifecycle.noShowStatus)
            && ATTENDANCE_ABSENT.equals(lifecycle.attendanceStatus)) {
            return CompletionDecision.complete("爽约已确认，可完成");
        }
        if (CHECKIN_CHECKED_IN.equals(lifecycle.checkinStatus)
            && (ATTENDANCE_PRESENT.equals(lifecycle.attendanceStatus)
            || ATTENDANCE_LATE.equals(lifecycle.attendanceStatus)
            || ATTENDANCE_MANUAL_PRESENT.equals(lifecycle.attendanceStatus))) {
            return CompletionDecision.complete("预约已核销，可完成");
        }
        LocalDateTime evaluatedAt = now == null ? LocalDateTime.now() : now;
        if (!CHECKIN_NOT_CHECKED_IN.equals(lifecycle.checkinStatus)
            || !ATTENDANCE_PENDING.equals(lifecycle.attendanceStatus)
            || lifecycle.checkinWindowEnd == null
            || NO_SHOW_CANDIDATE.equals(lifecycle.noShowStatus)
            || NO_SHOW_CONFIRMED.equals(lifecycle.noShowStatus)
            // A26-P2-02：爽约撤销后 noShowStatus=REVOKED，attendanceStatus 仍为 PENDING、checkinStatus 仍为
            // NOT_CHECKED_IN，若不排除会重新进入候选标记流程，幂等键撞 uk_lifecycle_event_idempotency
            // 导致子事务回滚、外层 catch 记 error，每 5 分钟循环一次。REVOKED 表示管理员已撤销爽约判定，
            // 不应再次自动标记候选。
            || NO_SHOW_REVOKED.equals(lifecycle.noShowStatus)) {
            return CompletionDecision.noAction();
        }
        int confirmAfterMinutes = Math.max(0,
            sysConfigService.getInt(NO_SHOW_CONFIRM_AFTER_MINUTES_KEY, DEFAULT_NO_SHOW_CONFIRM_AFTER_MINUTES));
        LocalDateTime candidateAt = lifecycle.checkinWindowEnd.plusMinutes(confirmAfterMinutes);
        if (evaluatedAt.isBefore(candidateAt)) {
            return CompletionDecision.noAction();
        }

        String fromStatus = lifecycle.noShowStatus;
        lifecycle.noShowStatus = NO_SHOW_CANDIDATE;
        lifecycle.updateTime = evaluatedAt;
        lifecycleMapper.updateById(lifecycle);
        String idempotencyKey = EVENT_NO_SHOW_CANDIDATE_MARKED + ":" + lifecycle.reservationId;
        writeLifecycleEvent(lifecycle, EVENT_NO_SHOW_CANDIDATE_MARKED, fromStatus, NO_SHOW_CANDIDATE, null,
            "超过核销窗口仍未到场", SOURCE_SYSTEM, idempotencyKey);
        writeBusinessEvent(BusinessEventType.NO_SHOW_CANDIDATE_MARKED, lifecycle, BusinessSourceType.SYSTEM, fromStatus,
            NO_SHOW_CANDIDATE, null, "超过核销窗口仍未到场", idempotencyKey);
        return CompletionDecision.noAction();
    }

    @Transactional
    public ReservationLifecycle confirmNoShow(Long reservationId, NoShowDecisionRequest request) {
        if (request == null || isBlank(request.reasonCode) || isBlank(request.reasonText)) {
            throw BusinessException.badRequest("爽约确认原因不能为空");
        }
        ReservationLifecycle lifecycle = requireLifecycle(reservationId);
        venueAccessService.requireVenueManageAccess(lifecycle.venueId);
        // A26-P3-02：行锁 lifecycle 行后再校验与更新，避免基于过期快照整行回写丢失并发更新
        // （如并发 verify/manualPass 把 checkinStatus 改为 CHECKED_IN 后，confirmNoShow 仍用旧
        // NOT_CHECKED_IN 快照通过 isUnverifiedNoShowCandidate 守卫并整行回写覆盖当前事实）。
        ReservationLifecycle locked = lifecycleMapper.selectByIdForUpdate(lifecycle.id);
        if (locked == null || Integer.valueOf(1).equals(locked.deleted)) {
            throw BusinessException.notFound("预约履约记录不存在");
        }
        lifecycle = locked;
        if (!isUnverifiedNoShowCandidate(lifecycle)) {
            throw BusinessException.badRequest("当前预约不可确认爽约");
        }

        String fromStatus = lifecycle.noShowStatus;
        lifecycle.attendanceStatus = ATTENDANCE_ABSENT;
        lifecycle.noShowStatus = NO_SHOW_CONFIRMED;
        lifecycle.reasonCode = request.reasonCode;
        lifecycle.reasonText = request.reasonText;
        lifecycle.updateTime = LocalDateTime.now();
        lifecycleMapper.updateById(lifecycle);

        String idempotencyKey = EVENT_NO_SHOW_CONFIRMED + ":" + lifecycle.reservationId;
        writeLifecycleEvent(lifecycle, EVENT_NO_SHOW_CONFIRMED, fromStatus, NO_SHOW_CONFIRMED, request.reasonCode,
            request.reasonText, SOURCE_ADMIN, idempotencyKey);
        writeBusinessEvent(BusinessEventType.NO_SHOW_CONFIRMED, lifecycle, BusinessSourceType.ADMIN, fromStatus, NO_SHOW_CONFIRMED,
            request.reasonCode, request.reasonText, idempotencyKey);
        return lifecycle;
    }

    @Transactional
    public ReservationLifecycle revokeNoShow(Long reservationId, NoShowDecisionRequest request) {
        if (request == null || isBlank(request.reasonCode) || isBlank(request.reasonText)) {
            throw BusinessException.badRequest("爽约撤销原因不能为空");
        }
        ReservationLifecycle lifecycle = requireLifecycle(reservationId);
        venueAccessService.requireVenueManageAccess(lifecycle.venueId);
        // A26-P3-02：行锁 lifecycle 行后再校验与更新，避免基于过期快照整行回写丢失并发更新
        // （如并发 confirmNoShow/revokeNoShow 互相覆盖 noShowStatus）。
        ReservationLifecycle locked = lifecycleMapper.selectByIdForUpdate(lifecycle.id);
        if (locked == null || Integer.valueOf(1).equals(locked.deleted)) {
            throw BusinessException.notFound("预约履约记录不存在");
        }
        lifecycle = locked;
        if (!NO_SHOW_CONFIRMED.equals(lifecycle.noShowStatus)) {
            throw BusinessException.badRequest("当前预约不可撤销爽约");
        }
        // A26-P3-02：COMPLETED 守卫基于锁后 reservation 快照，避免并发完成把 reservation 改为
        // COMPLETED 后 revokeNoShow 仍用旧非 COMPLETED 快照通过守卫并撤销爽约（导致 COMPLETED
        // 预约的 attendanceStatus 被回退为 PENDING）。锁顺序 lifecycle → reservation：revokeNoShow
        // 是管理员手动操作，与 create/reviewReschedule 的 reservation→（lifecycle 后续独立事务）
        // 路径不在同一事务链路，无死锁风险。
        Reservation reservation = requireReservationForUpdate(reservationId);
        boolean manualPresent = ATTENDANCE_MANUAL_PRESENT.equals(request.reasonCode);
        if (ReservationStatus.COMPLETED.name().equals(reservation.status) && !manualPresent) {
            throw BusinessException.badRequest("已完成预约只能撤销为人工到场");
        }

        String fromStatus = lifecycle.noShowStatus;
        lifecycle.attendanceStatus = manualPresent ? ATTENDANCE_MANUAL_PRESENT : ATTENDANCE_PENDING;
        lifecycle.checkinStatus = manualPresent ? CHECKIN_CHECKED_IN : CHECKIN_NOT_CHECKED_IN;
        lifecycle.noShowStatus = NO_SHOW_REVOKED;
        lifecycle.manualOverride = manualPresent ? 1 : lifecycle.manualOverride;
        lifecycle.checkedInAt = manualPresent && lifecycle.checkedInAt == null ? LocalDateTime.now() : lifecycle.checkedInAt;
        lifecycle.checkedInBy = manualPresent ? SecurityContext.currentUserId() : lifecycle.checkedInBy;
        lifecycle.checkinSourceType = manualPresent ? SOURCE_ADMIN : lifecycle.checkinSourceType;
        lifecycle.reasonCode = request.reasonCode;
        lifecycle.reasonText = request.reasonText;
        lifecycle.updateTime = LocalDateTime.now();
        lifecycleMapper.updateById(lifecycle);

        String idempotencyKey = EVENT_NO_SHOW_REVOKED + ":" + lifecycle.reservationId;
        writeLifecycleEvent(lifecycle, EVENT_NO_SHOW_REVOKED, fromStatus, NO_SHOW_REVOKED, request.reasonCode,
            request.reasonText, SOURCE_ADMIN, idempotencyKey);
        writeBusinessEvent(BusinessEventType.NO_SHOW_REVOKED, lifecycle, BusinessSourceType.ADMIN, fromStatus, NO_SHOW_REVOKED,
            request.reasonCode, request.reasonText, idempotencyKey);
        return lifecycle;
    }

    private void reviewRescheduleRequest(ReservationRescheduleRequest request, String toStatus, String reviewReason,
                                         BusinessEventType eventType) {
        if (request == null || request.id == null) {
            throw BusinessException.badRequest("改期申请不能为空");
        }
        if (!RESCHEDULE_PENDING_REVIEW.equals(request.status)) {
            throw BusinessException.badRequest("当前改期申请不可审核");
        }
        String fromStatus = request.status;
        request.status = toStatus;
        request.reviewedBy = SecurityContext.currentUserId();
        request.reviewedAt = LocalDateTime.now();
        request.reviewReason = blankToNull(reviewReason);
        request.updateTime = request.reviewedAt;
        rescheduleMapper.updateById(request);
        writeRescheduleBusinessEvent(eventType, request, BusinessSourceType.ADMIN, fromStatus, toStatus,
            request.reasonCode, request.reviewReason, eventType.name() + ":" + request.id);
    }

    private ReservationLifecycle requireLifecycle(Long reservationId) {
        ReservationLifecycle lifecycle = lifecycleMapper.findByReservationId(reservationId);
        if (lifecycle == null) {
            throw BusinessException.notFound("预约履约记录不存在");
        }
        return lifecycle;
    }

    private Reservation requireReservation(Long reservationId) {
        Reservation reservation = reservationMapper.selectById(reservationId);
        if (reservation == null || Integer.valueOf(1).equals(reservation.deleted)) {
            throw BusinessException.notFound("预约不存在");
        }
        return reservation;
    }

    // A26-P3-02：revokeNoShow 的 COMPLETED 守卫需要锁后重读 reservation 行，避免基于无锁快照。
    private Reservation requireReservationForUpdate(Long reservationId) {
        Reservation reservation = reservationMapper.selectByIdForUpdate(reservationId);
        if (reservation == null || Integer.valueOf(1).equals(reservation.deleted)) {
            throw BusinessException.notFound("预约不存在");
        }
        return reservation;
    }

    private ReservationLifecycle findByCredential(String credential) {
        String normalized = credential.trim();
        if (normalized.startsWith("CHECKIN:")) {
            return findByQrCredential(normalized);
        }
        return lifecycleMapper.findByCheckinCodeHash(hashCredential(normalized));
    }

    private ReservationLifecycle findByQrCredential(String normalized) {
        String[] parts = normalized.split(":");
        if (parts.length != 3 || isBlank(parts[1]) || isBlank(parts[2])) {
            return null;
        }
        Long reservationId = parseReservationId(parts[1]);
        if (reservationId == null) {
            return null;
        }

        ReservationLifecycle byHash = lifecycleMapper.findByCheckinCodeHash(hashCredential(parts[2]));
        return matchesReservation(byHash, reservationId) ? byHash : null;
    }

    private Long parseReservationId(String value) {
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private boolean matchesReservation(ReservationLifecycle lifecycle, Long reservationId) {
        return lifecycle != null && Objects.equals(lifecycle.reservationId, reservationId);
    }

    private boolean isUnverifiedNoShowCandidate(ReservationLifecycle lifecycle) {
        return NO_SHOW_CANDIDATE.equals(lifecycle.noShowStatus)
            && CHECKIN_NOT_CHECKED_IN.equals(lifecycle.checkinStatus)
            && ATTENDANCE_PENDING.equals(lifecycle.attendanceStatus);
    }

    private void requireManualPassAllowed(ReservationLifecycle lifecycle, Reservation reservation) {
        if (!ReservationStatus.PAID.name().equals(reservation.status)) {
            throw BusinessException.badRequest("当前预约状态不可人工核销");
        }
        if (!CREDENTIAL_ISSUED.equals(lifecycle.credentialStatus)) {
            throw BusinessException.badRequest("当前核销凭证不可人工核销");
        }
        if (!CHECKIN_NOT_CHECKED_IN.equals(lifecycle.checkinStatus)
            || !ATTENDANCE_PENDING.equals(lifecycle.attendanceStatus)
            || NO_SHOW_CONFIRMED.equals(lifecycle.noShowStatus)) {
            throw BusinessException.badRequest("当前预约不可人工核销");
        }
    }

    public ReservationLifecycleResponse toResponse(ReservationLifecycle lifecycle) {
        ReservationLifecycleResponse response = new ReservationLifecycleResponse();
        response.reservationId = lifecycle.reservationId;
        response.orderId = lifecycle.orderId;
        response.venueId = lifecycle.venueId;
        response.spaceId = lifecycle.spaceId;
        response.credentialStatus = lifecycle.credentialStatus;
        response.checkinCodeLast4 = lifecycle.checkinCodeLast4;
        response.checkinWindowStart = lifecycle.checkinWindowStart;
        response.checkinWindowEnd = lifecycle.checkinWindowEnd;
        response.attendanceStatus = lifecycle.attendanceStatus;
        response.checkinStatus = lifecycle.checkinStatus;
        response.noShowStatus = lifecycle.noShowStatus;
        response.checkedInAt = lifecycle.checkedInAt;
        response.lateMinutes = lifecycle.lateMinutes;
        response.reasonCode = lifecycle.reasonCode;
        response.reasonText = lifecycle.reasonText;
        response.events = recentLifecycleEvents(lifecycle.reservationId);
        return response;
    }

    private List<LifecycleEventResponse> recentLifecycleEvents(Long reservationId) {
        List<ReservationLifecycleEvent> events = eventMapper.findRecentByReservationId(reservationId, RESPONSE_EVENT_LIMIT);
        if (events == null || events.isEmpty()) {
            return List.of();
        }
        return events.stream().map(this::toEventResponse).toList();
    }

    private LifecycleEventResponse toEventResponse(ReservationLifecycleEvent event) {
        LifecycleEventResponse response = new LifecycleEventResponse();
        response.eventType = event.eventType;
        response.fromStatus = event.fromStatus;
        response.toStatus = event.toStatus;
        response.sourceType = event.sourceType;
        response.reasonCode = event.reasonCode;
        response.reasonText = event.reasonText;
        response.createTime = event.createTime;
        return response;
    }

    private void applyCheckinAdminQuery(QueryWrapper<ReservationLifecycle> wrapper, CheckinAdminQuery query) {
        if (query.reservationId != null) {
            wrapper.eq("reservation_id", query.reservationId);
        }
        if (query.userId != null) {
            wrapper.eq("user_id", query.userId);
        }
        if (query.spaceId != null) {
            wrapper.eq("space_id", query.spaceId);
        }
        if (!isBlank(query.credentialStatus)) {
            wrapper.eq("credential_status", query.credentialStatus.trim());
        }
        if (!isBlank(query.attendanceStatus)) {
            wrapper.eq("attendance_status", query.attendanceStatus.trim());
        }
        if (!isBlank(query.checkinStatus)) {
            wrapper.eq("checkin_status", query.checkinStatus.trim());
        }
        if (!isBlank(query.noShowStatus)) {
            wrapper.eq("no_show_status", query.noShowStatus.trim());
        }
    }

    private void writeLifecycleEvent(ReservationLifecycle lifecycle, String eventType, String fromStatus,
                                     String toStatus, String reasonCode, String reasonText, String sourceType,
                                     String idempotencyKey) {
        writeLifecycleEvent(lifecycle.reservationId, lifecycle.id, eventType, fromStatus, toStatus, reasonCode,
            reasonText, sourceType, idempotencyKey);
    }

    private void writeLifecycleEvent(Long reservationId, Long lifecycleId, String eventType, String fromStatus,
                                     String toStatus, String reasonCode, String reasonText, String sourceType,
                                     String idempotencyKey) {
        ReservationLifecycleEvent event = new ReservationLifecycleEvent();
        event.reservationId = reservationId;
        event.lifecycleId = lifecycleId;
        event.eventType = eventType;
        event.fromStatus = blankToNull(fromStatus);
        event.toStatus = blankToNull(toStatus);
        event.actorUserId = SecurityContext.currentUserId();
        event.sourceType = sourceType;
        event.reasonCode = blankToNull(reasonCode);
        event.reasonText = blankToNull(reasonText);
        event.relatedEntityType = "RESERVATION";
        event.relatedEntityId = reservationId;
        event.idempotencyKey = idempotencyKey;
        event.createTime = LocalDateTime.now();
        try {
            eventMapper.insert(event);
        } catch (DuplicateKeyException ex) {
            // A26-P2-02：幂等键撞 uk_lifecycle_event_idempotency，说明同一事件已被记录过
            // （如爽约撤销后调度器误判重新标记候选，或重试/重放触发同一幂等键）。
            // 对照 BusinessEventService.record 的 DuplicateKey 兜底：事件已存在视为成功，记 WARN 不阻断业务。
            // 此处 catch 在同事务内 private 方法调用，不影响外层 @Transactional 的 AOP rollback 判定，
            // 不会因日志写入失败而回滚业务状态变更（lifecycle.updateById 已先于此执行）。
            log.warn("AUD-P2-02 lifecycle_event 幂等键已存在，跳过重复写入 reservationId={} lifecycleId={} "
                    + "eventType={} idempotencyKey={}", reservationId, lifecycleId, eventType, idempotencyKey);
        }
    }

    private void writeBusinessEvent(BusinessEventType eventType, ReservationLifecycle lifecycle,
                                    BusinessSourceType sourceType, String fromStatus, String toStatus,
                                    String reasonCode, String reasonText, String idempotencyKey) {
        businessEventService.record(BusinessEventRecordRequest.of(
                eventType,
                BusinessDomain.CHECKIN,
                "RESERVATION",
                lifecycle.reservationId,
                sourceType,
                "SUCCESS")
            .relatedEntity("RESERVATION_LIFECYCLE", lifecycle.id)
            .fromStatus(fromStatus)
            .toStatus(toStatus)
            .reasonCode(reasonCode)
            .reasonText(reasonText)
            .idempotencyKey(idempotencyKey)
            .metadata("venueId", lifecycle.venueId)
            .metadata("spaceId", lifecycle.spaceId)
            .metadata("orderId", lifecycle.orderId)
            .metadata("attendanceStatus", lifecycle.attendanceStatus)
            .metadata("checkinStatus", lifecycle.checkinStatus)
            .metadata("noShowStatus", lifecycle.noShowStatus));
    }

    private void writeReservationCanceledBusinessEvent(Reservation reservation, String fromStatus, String reasonCode,
                                                       String reasonText, BusinessSourceType sourceType) {
        businessEventService.record(BusinessEventRecordRequest.of(
                BusinessEventType.RESERVATION_CANCELED,
                BusinessDomain.RESERVATION,
                "RESERVATION",
                reservation.id,
                sourceType,
                "SUCCESS")
            .fromStatus(fromStatus)
            .toStatus(ReservationStatus.CANCELED.name())
            .reasonCode(reasonCode)
            .reasonText(reasonText)
            .idempotencyKey("RESERVATION_CANCELED:" + reservation.id)
            .metadata("venueId", reservation.venueId)
            .metadata("spaceId", reservation.spaceId)
            .metadata("startTime", reservation.startTime)
            .metadata("endTime", reservation.endTime));
    }

    private void writeRescheduleBusinessEvent(BusinessEventType eventType, ReservationRescheduleRequest request,
                                              BusinessSourceType sourceType, String fromStatus, String toStatus,
                                              String reasonCode, String reasonText, String idempotencyKey) {
        businessEventService.record(BusinessEventRecordRequest.of(
                eventType,
                BusinessDomain.RESERVATION,
                "RESERVATION_RESCHEDULE_REQUEST",
                request.id,
                sourceType,
                "SUCCESS")
            .relatedEntity("RESERVATION", request.reservationId)
            .fromStatus(fromStatus)
            .toStatus(toStatus)
            .reasonCode(reasonCode)
            .reasonText(reasonText)
            .idempotencyKey(idempotencyKey)
            .metadata("reservationId", request.reservationId)
            .metadata("venueId", request.venueId)
            .metadata("spaceId", request.spaceId)
            .metadata("fromStartTime", request.fromStartTime)
            .metadata("fromEndTime", request.fromEndTime)
            .metadata("toStartTime", request.toStartTime)
            .metadata("toEndTime", request.toEndTime)
            .metadata("reviewedBy", request.reviewedBy)
            .metadata("reviewedAt", request.reviewedAt));
    }

    private BusinessSourceType businessSourceType(String sourceType) {
        if (isBlank(sourceType)) {
            return BusinessSourceType.SYSTEM;
        }
        try {
            return BusinessSourceType.valueOf(sourceType.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return BusinessSourceType.SYSTEM;
        }
    }

    // A26-P2-03：6 位核销短码全局查重重试。
    // evidence 根因：原 generateCode 不查重不重试，schema 上 checkin_code_hash 是普通索引非唯一键，
    // 历史行永久保留，存量 1 万行时每次签发碰撞概率约 1.1% 且随规模上升。碰撞后 findByCheckinCodeHash
    // limit 1 无 ORDER BY，可能核销到他人预约，或合法用户被"窗口已关闭"拒绝。
    // 修复策略：代码层面预查并重试，V12 唯一索引关闭并发“先查后写”竞态。
    // 6 位数字空间 100000~999999 共 90 万种组合，重试 5 次后仍碰撞的概率为 (1/900000)^5 量级，
    // 实际几乎不可能；若极端情况下 5 次仍碰撞，抛业务异常由上层重试或人工介入。
    // 数据库冲突仍由下方插入/轮换路径捕获 DuplicateKeyException 后重试。
    private static final int CODE_GENERATION_MAX_ATTEMPTS = 5;
    private static final int CODE_SPACE_SIZE = 900000;

    private String insertLifecycleWithUniqueCredential(ReservationLifecycle lifecycle) {
        for (int attempt = 0; attempt < CODE_GENERATION_MAX_ATTEMPTS; attempt++) {
            String code = assignNewCredential(lifecycle);
            try {
                lifecycleMapper.insert(lifecycle);
                return code;
            } catch (DuplicateKeyException ex) {
                ReservationLifecycle concurrentlyIssued = lifecycleMapper.findByReservationId(lifecycle.reservationId);
                if (concurrentlyIssued != null) {
                    throw BusinessException.conflict("核销凭证已由并发请求签发，请刷新后领取");
                }
                lifecycle.id = null;
                log.warn("核销短码唯一键并发冲突，重试签发 reservationId={} attempt={}",
                    lifecycle.reservationId, attempt + 1);
            }
        }
        throw BusinessException.conflict("核销短码生成冲突，请重试");
    }

    private String updateLifecycleWithUniqueCredential(ReservationLifecycle lifecycle) {
        for (int attempt = 0; attempt < CODE_GENERATION_MAX_ATTEMPTS; attempt++) {
            String code = assignNewCredential(lifecycle);
            try {
                int updated = lifecycleMapper.update(lifecycle, new UpdateWrapper<ReservationLifecycle>()
                    .eq("id", lifecycle.id)
                    .eq("deleted", 0)
                    .set("checkin_code_hash", lifecycle.checkinCodeHash)
                    .set("checkin_code_last4", lifecycle.checkinCodeLast4)
                    .set("credential_status", lifecycle.credentialStatus)
                    .set("credential_issued_at", lifecycle.credentialIssuedAt)
                    .set("update_time", lifecycle.updateTime));
                if (updated == 0) {
                    throw BusinessException.conflict("核销凭证状态已变更，请重试");
                }
                return code;
            } catch (DuplicateKeyException ex) {
                log.warn("核销短码唯一键并发冲突，重试轮换 reservationId={} attempt={}",
                    lifecycle.reservationId, attempt + 1);
            }
        }
        throw BusinessException.conflict("核销短码生成冲突，请重试");
    }

    private String assignNewCredential(ReservationLifecycle lifecycle) {
        String code = generateCode();
        lifecycle.checkinCodeHash = hashCredential(code);
        lifecycle.checkinCodeLast4 = code.substring(code.length() - 4);
        return code;
    }

    private String generateCode() {
        for (int attempt = 0; attempt < CODE_GENERATION_MAX_ATTEMPTS; attempt++) {
            int value = secureRandom.nextInt(CODE_SPACE_SIZE) + 100000;
            String code = String.valueOf(value);
            String hash = hashCredential(code);
            if (lifecycleMapper.findByCheckinCodeHash(hash) == null) {
                return code;
            }
            log.warn("核销短码哈希碰撞，重试生成 attempt={}（未持久化，无副作用）", attempt + 1);
        }
        throw BusinessException.badRequest("核销短码生成失败，请重试");
    }

    private String hashCredential(String credential) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(credential.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw BusinessException.badRequest("核销凭证生成失败");
        }
    }



    private String blankToNull(String value) {
        return isBlank(value) ? null : value.trim();
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
