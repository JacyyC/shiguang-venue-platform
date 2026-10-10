package com.example.venue.feedback.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.venue.business.dto.BusinessEventRecordRequest;
import com.example.venue.business.enums.BusinessDomain;
import com.example.venue.business.enums.BusinessEventType;
import com.example.venue.business.enums.BusinessSourceType;
import com.example.venue.business.service.BusinessEventService;
import com.example.venue.common.BusinessException;
import com.example.venue.common.CurrentUser;
import com.example.venue.common.EntityExistenceService;
import com.example.venue.common.PageResult;
import com.example.venue.feedback.dto.ComplaintSubmitRequest;
import com.example.venue.feedback.dto.CustomerServiceCaseActionRequest;
import com.example.venue.feedback.dto.CustomerServiceQuery;
import com.example.venue.feedback.dto.FeedbackQuery;
import com.example.venue.feedback.dto.PublicReviewResponse;
import com.example.venue.feedback.dto.ReviewModerationRequest;
import com.example.venue.feedback.dto.ReviewSubmitRequest;
import com.example.venue.feedback.entity.CustomerComplaint;
import com.example.venue.feedback.entity.CustomerServiceCase;
import com.example.venue.feedback.entity.CustomerServiceCaseEvent;
import com.example.venue.feedback.entity.ReviewModerationRecord;
import com.example.venue.feedback.entity.VenueReview;
import com.example.venue.feedback.mapper.CustomerComplaintMapper;
import com.example.venue.feedback.mapper.CustomerServiceCaseEventMapper;
import com.example.venue.feedback.mapper.CustomerServiceCaseMapper;
import com.example.venue.feedback.mapper.ReviewModerationRecordMapper;
import com.example.venue.feedback.mapper.VenueReviewMapper;
import com.example.venue.checkin.entity.ReservationLifecycle;
import com.example.venue.checkin.mapper.ReservationLifecycleMapper;
import com.example.venue.notice.entity.Notice;
import com.example.venue.notice.mapper.NoticeMapper;
import com.example.venue.notice.routing.NotificationEvent;
import com.example.venue.notice.routing.NotificationRecipientKind;
import com.example.venue.notice.service.NoticeService;
import com.example.venue.order.entity.BizOrder;
import com.example.venue.order.mapper.BizOrderMapper;
import com.example.venue.reservation.entity.Reservation;
import com.example.venue.reservation.enums.ReservationStatus;
import com.example.venue.reservation.mapper.ReservationMapper;
import com.example.venue.security.SecurityContext;
import com.example.venue.siteops.entity.AssetItem;
import com.example.venue.siteops.entity.SiteIncidentLog;
import com.example.venue.siteops.mapper.AssetItemMapper;
import com.example.venue.siteops.mapper.SiteIncidentLogMapper;
import com.example.venue.venue.entity.VenueSpace;
import com.example.venue.venue.mapper.VenueAdminRelationMapper;
import com.example.venue.venue.mapper.VenueSpaceMapper;
import com.example.venue.venue.service.VenueAccessService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jsoup.Jsoup;
import org.jsoup.safety.Safelist;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@Service
public class CustomerFeedbackService {
    private static final Set<String> REVIEW_STATUS = Set.of("PUBLISHED", "FLAGGED", "HIDDEN");
    private static final Set<String> COMPLAINT_STATUS = Set.of("SUBMITTED", "TRIAGED", "IN_PROGRESS", "WAITING_USER", "RESOLVED", "CLOSED", "CANCELED");
    // A26-P1-05 G1e：TRIAGED/WAITING_USER 是投诉状态不是 case 状态。此前 changeCaseStatus
    // 用本集合校验 case 状态变更请求，导致投诉状态被写进 case 事件 to_status，污染
    // 未解决工单状态口径（既不计入 unresolved_case_count 也不计入 case_history_gap_count）。
    // 集合只保留 IN_PROGRESS——它是 case 状态也是投诉状态，
    // 是 changeCaseStatus 唯一合法的目标状态。TRIAGED/WAITING_USER 的投诉状态变更应由
    // 专门的投诉状态变更路径处理，不进 case 事件流；本子组不建立新入口，仅修复污染。
    private static final Set<String> CHANGE_COMPLAINT_STATUS = Set.of("IN_PROGRESS");
    private static final Set<String> DIRECT_CASE_ACTION_STATUS = Set.of("RESOLVED", "CLOSED", "REOPENED");
    private static final Set<String> CASE_STATUS = Set.of("OPEN", "IN_PROGRESS", "RESOLVED", "CLOSED", "REOPENED");
    private static final Set<String> COMPLAINT_TYPES = Set.of(
        "EQUIPMENT", "SERVICE_ATTITUDE", "ORDER_PAYMENT", "NOTICE_INFO", "CLEANLINESS", "SITE", "OTHER");
    private static final Set<String> COMPLAINT_SEVERITIES = Set.of("LOW", "MEDIUM", "HIGH");
    private static final Set<String> ROOT_CAUSES = Set.of("EQUIPMENT_MAINTENANCE", "SERVICE_EXECUTION", "ORDER_POLICY");
    private static final Set<String> RESOLUTION_TYPES = Set.of("EXPLAINED", "MANUAL_FOLLOW_UP", "REFUND_REVIEW");
    private static final DateTimeFormatter NO_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final String SOURCE_USER = "USER";
    private static final String SOURCE_ADMIN = "ADMIN";
    private static final String SOURCE_SYSTEM = "SYSTEM";

    private final VenueReviewMapper reviewMapper;
    private final CustomerComplaintMapper complaintMapper;
    private final CustomerServiceCaseMapper caseMapper;
    private final CustomerServiceCaseEventMapper eventMapper;
    private final ReviewModerationRecordMapper moderationMapper;
    private final BusinessEventService businessEventService;
    private final VenueAccessService venueAccessService;
    private final ReservationMapper reservationMapper;
    private final BizOrderMapper orderMapper;
    private final ReservationLifecycleMapper lifecycleMapper;
    private final NoticeMapper noticeMapper;
    private final NoticeService noticeService;
    private final AssetItemMapper assetItemMapper;
    private final SiteIncidentLogMapper siteIncidentLogMapper;
    private final VenueSpaceMapper spaceMapper;
    private final FeedbackTextSanitizer sanitizer;
    private final ObjectMapper objectMapper;
    private final EntityExistenceService entityExistenceService; // AUD-P2-08/09：注入实体存在性 helper
    // SHIGUANG-WAVE-03-REMEDIATION-20260917 S02 / F-15：分派需校验被指派人与工单场馆的归属关系，
    // 复用 SiteOpsService/AssetLifecycleService 同型的 venue_admin_relation 判定语义。
    private final VenueAdminRelationMapper venueAdminRelationMapper;

    public CustomerFeedbackService(VenueReviewMapper reviewMapper,
                                   CustomerComplaintMapper complaintMapper,
                                   CustomerServiceCaseMapper caseMapper,
                                   CustomerServiceCaseEventMapper eventMapper,
                                   ReviewModerationRecordMapper moderationMapper,
                                   BusinessEventService businessEventService,
                                   VenueAccessService venueAccessService,
                                   ReservationMapper reservationMapper,
                                   BizOrderMapper orderMapper,
                                   ReservationLifecycleMapper lifecycleMapper,
                                   NoticeMapper noticeMapper,
                                   NoticeService noticeService,
                                   AssetItemMapper assetItemMapper,
                                   SiteIncidentLogMapper siteIncidentLogMapper,
                                   VenueSpaceMapper spaceMapper,
                                   FeedbackTextSanitizer sanitizer,
                                   ObjectMapper objectMapper,
                                   EntityExistenceService entityExistenceService,
                                   VenueAdminRelationMapper venueAdminRelationMapper) {
        this.reviewMapper = reviewMapper;
        this.complaintMapper = complaintMapper;
        this.caseMapper = caseMapper;
        this.eventMapper = eventMapper;
        this.moderationMapper = moderationMapper;
        this.businessEventService = businessEventService;
        this.venueAccessService = venueAccessService;
        this.reservationMapper = reservationMapper;
        this.orderMapper = orderMapper;
        this.lifecycleMapper = lifecycleMapper;
        this.noticeMapper = noticeMapper;
        this.noticeService = noticeService;
        this.assetItemMapper = assetItemMapper;
        this.siteIncidentLogMapper = siteIncidentLogMapper;
        this.spaceMapper = spaceMapper;
        this.sanitizer = sanitizer;
        this.objectMapper = objectMapper;
        this.entityExistenceService = entityExistenceService;
        this.venueAdminRelationMapper = venueAdminRelationMapper;
    }

    @Transactional
    public VenueReview submitReview(ReviewSubmitRequest request) {
        CurrentUser user = requireCurrentUser();
        if (request == null) {
            throw BusinessException.badRequest("评价参数不能为空");
        }
        requireVenueId(request.venueId);
        requireRating(request.overallRating, "整体评分");
        requireOptionalRating(request.venueRating, "场馆评分");
        requireOptionalRating(request.spaceRating, "场地评分");
        requireOptionalRating(request.serviceRating, "服务评分");
        rejectDuplicateReservationReview(request.reservationId, user.id());
        Reservation reservation = validateCompletedReviewReservation(request.reservationId, user.id(), request.venueId, request.spaceId);
        validateLifecycleAssociation(request.lifecycleId, user.id(), request.venueId, request.spaceId,
            request.reservationId, request.orderId);
        validateOrderAssociation(request.orderId, user.id(), request.venueId, request.reservationId);

        LocalDateTime now = LocalDateTime.now();
        FeedbackTextSanitizer.SanitizedText sanitized = sanitizer.sanitize(request.contentText);
        VenueReview review = new VenueReview();
        review.reviewNo = nextNo("REVIEW");
        review.userId = user.id();
        review.venueId = request.venueId;
        review.spaceId = request.spaceId;
        review.reservationId = request.reservationId;
        review.orderId = resolveOrderId(request.orderId, reservation.id, user.id());
        review.lifecycleId = request.lifecycleId;
        review.overallRating = request.overallRating;
        review.venueRating = request.venueRating;
        review.spaceRating = request.spaceRating;
        review.serviceRating = request.serviceRating;
        review.tagsJson = request.tags == null ? null : sanitizer.sanitize(toJson(request.tags)).text();
        // A26-P0-04：原文落 content_raw（仅审计可见），脱敏后文本落 content_text/content_sanitized（向后兼容）。
        // sanitized.text()==null 表示用户输入为 null/blank，此时 content_raw 也保持 null 不落库。
        review.contentRaw = sanitized.text() == null ? null : request.contentText;
        review.contentText = sanitized.text();
        review.contentSanitized = sanitized.text();
        review.sensitiveFlag = sanitized.sensitive() ? 1 : 0;
        review.status = "PUBLISHED";
        review.submittedAt = now;
        review.publishedAt = now;
        review.sourceType = SOURCE_USER;
        review.createTime = now;
        review.updateTime = now;
        review.deleted = 0;
        reviewMapper.insert(review);

        businessEventService.record(customerServiceEvent(BusinessEventType.REVIEW_SUBMITTED,
                "VENUE_REVIEW", review.id, BusinessSourceType.USER, "REVIEW_SUBMITTED:" + review.reviewNo)
            .actor(user.id(), user.username())
            .toStatus(review.status)
            .metadata("venueId", review.venueId)
            .metadata("spaceId", review.spaceId)
            .metadata("reservationId", review.reservationId)
            .metadata("orderId", review.orderId)
            .metadata("overallRating", review.overallRating)
            .metadata("sensitiveFlag", review.sensitiveFlag));
        return review;
    }

    @Transactional
    public CustomerComplaint submitComplaint(ComplaintSubmitRequest request) {
        CurrentUser user = requireCurrentUser();
        if (request == null) {
            throw BusinessException.badRequest("投诉参数不能为空");
        }
        requireVenueId(request.venueId);
        // AUD-P2-09：投诉时校验场馆存在，避免插入指向不存在场馆的投诉
        entityExistenceService.requireVenueExists(request.venueId);
        if (isBlank(request.title) || isBlank(request.complaintType) || isBlank(request.severity)) {
            throw BusinessException.badRequest("投诉信息不完整");
        }
        String complaintType = canonicalRequiredCode(request.complaintType, COMPLAINT_TYPES, "投诉类型");
        String severity = canonicalRequiredCode(request.severity, COMPLAINT_SEVERITIES, "严重程度");
        validateComplaintAssociations(request, user.id());

        LocalDateTime now = LocalDateTime.now();
        FeedbackTextSanitizer.SanitizedText sanitized = sanitizer.sanitize(request.contentText);
        FeedbackTextSanitizer.SanitizedText title = sanitizer.sanitize(request.title);
        FeedbackTextSanitizer.SanitizedText requestedResolution = sanitizer.sanitize(request.requestedResolution);
        CustomerComplaint complaint = new CustomerComplaint();
        complaint.complaintNo = nextNo("COMP");
        complaint.userId = user.id();
        complaint.venueId = request.venueId;
        complaint.spaceId = request.spaceId;
        complaint.reservationId = request.reservationId;
        complaint.orderId = request.orderId;
        complaint.lifecycleId = request.lifecycleId;
        complaint.noticeId = request.noticeId;
        complaint.assetId = request.assetId;
        complaint.siteIncidentId = request.siteIncidentId;
        complaint.complaintType = complaintType;
        complaint.severity = severity;
        complaint.status = "SUBMITTED";
        complaint.title = title.text();
        // A26-P0-04：原文落 content_raw（仅审计可见），脱敏后文本落 content_text/content_sanitized（向后兼容）。
        // title 与 requestedResolution 是单独字段，本轮按 evidence 点名范围只处理 contentText 的原文落库。
        complaint.contentRaw = sanitized.text() == null ? null : request.contentText;
        complaint.contentText = sanitized.text();
        complaint.contentSanitized = sanitized.text();
        complaint.sensitiveFlag = sanitized.sensitive() || title.sensitive() || requestedResolution.sensitive() ? 1 : 0;
        complaint.requestedResolution = requestedResolution.text();
        complaint.sourceType = SOURCE_USER;
        complaint.submittedAt = now;
        complaint.createTime = now;
        complaint.updateTime = now;
        complaint.deleted = 0;
        complaintMapper.insert(complaint);

        CustomerServiceCase serviceCase = new CustomerServiceCase();
        serviceCase.caseNo = nextNo("CASE");
        serviceCase.complaintId = complaint.id;
        serviceCase.venueId = complaint.venueId;
        serviceCase.priority = complaint.severity;
        serviceCase.status = "OPEN";
        serviceCase.requiresRefundReview = 0;
        serviceCase.requiresCouponReview = 0;
        serviceCase.requiresSiteFollowUp = 0;
        serviceCase.openedAt = now;
        serviceCase.createTime = now;
        serviceCase.updateTime = now;
        serviceCase.deleted = 0;
        caseMapper.insert(serviceCase);

        writeCaseEvent(serviceCase, complaint, "CREATED", null, "OPEN", user,
            complaint.title, null, SOURCE_USER, now);
        notifyVenueManagersOfNewCase(serviceCase);
        businessEventService.record(customerServiceEvent(BusinessEventType.COMPLAINT_SUBMITTED,
                "CUSTOMER_COMPLAINT", complaint.id, BusinessSourceType.USER,
                "COMPLAINT_SUBMITTED:" + complaint.complaintNo)
            .actor(user.id(), user.username())
            .toStatus(complaint.status)
            .metadata("venueId", complaint.venueId)
            .metadata("spaceId", complaint.spaceId)
            .metadata("reservationId", complaint.reservationId)
            .metadata("severity", complaint.severity)
            .metadata("complaintType", complaint.complaintType)
            .metadata("sensitiveFlag", complaint.sensitiveFlag));
        businessEventService.record(customerServiceEvent(BusinessEventType.CUSTOMER_SERVICE_CASE_CREATED,
                "CUSTOMER_SERVICE_CASE", serviceCase.id, BusinessSourceType.SYSTEM,
                "CUSTOMER_SERVICE_CASE_CREATED:" + serviceCase.caseNo)
            .relatedEntity("CUSTOMER_COMPLAINT", complaint.id)
            .actor(user.id(), user.username())
            .toStatus(serviceCase.status)
            .metadata("venueId", serviceCase.venueId)
            .metadata("complaintNo", complaint.complaintNo)
            .metadata("priority", serviceCase.priority));
        return complaint;
    }

    @Transactional(readOnly = true)
    public PageResult<VenueReview> myReviews(FeedbackQuery query) {
        CurrentUser user = requireCurrentUser();
        FeedbackQuery safeQuery = safeQuery(query);
        QueryWrapper<VenueReview> wrapper = new QueryWrapper<VenueReview>()
            .eq("user_id", user.id())
            .eq("deleted", 0);
        applyReviewFilters(wrapper, safeQuery);
        Page<VenueReview> page = reviewMapper.selectPage(
            new Page<>(safeQuery.getPageNum(), safeQuery.getPageSize()),
            wrapper.orderByDesc("submitted_at").orderByDesc("id"));
        return PageResult.of(page.getRecords(), page.getTotal(), safeQuery.getPageNum(), safeQuery.getPageSize());
    }

    @Transactional(readOnly = true)
    public PageResult<CustomerComplaint> myComplaints(FeedbackQuery query) {
        CurrentUser user = requireCurrentUser();
        FeedbackQuery safeQuery = safeQuery(query);
        QueryWrapper<CustomerComplaint> wrapper = new QueryWrapper<CustomerComplaint>()
            .eq("user_id", user.id())
            .eq("deleted", 0);
        applyComplaintFilters(wrapper, safeQuery);
        Page<CustomerComplaint> page = complaintMapper.selectPage(
            new Page<>(safeQuery.getPageNum(), safeQuery.getPageSize()),
            wrapper.orderByDesc("submitted_at").orderByDesc("id"));
        return PageResult.of(page.getRecords(), page.getTotal(), safeQuery.getPageNum(), safeQuery.getPageSize());
    }

    @Transactional(readOnly = true)
    public PageResult<CustomerServiceCase> myCases(FeedbackQuery query) {
        CurrentUser user = requireCurrentUser();
        FeedbackQuery safeQuery = safeQuery(query);
        QueryWrapper<CustomerComplaint> complaintWrapper = new QueryWrapper<CustomerComplaint>()
            .select("id")
            .eq("user_id", user.id())
            .eq("deleted", 0);
        applyComplaintFilters(complaintWrapper, safeQuery, false);
        List<Long> complaintIds = complaintMapper.selectList(complaintWrapper).stream()
            .map(item -> item.id)
            .filter(id -> id != null)
            .toList();
        if (complaintIds.isEmpty()) {
            return PageResult.of(List.of(), 0, safeQuery.getPageNum(), safeQuery.getPageSize());
        }
        QueryWrapper<CustomerServiceCase> wrapper = new QueryWrapper<CustomerServiceCase>()
            .eq("deleted", 0)
            .in("complaint_id", complaintIds);
        if (safeQuery.venueId != null) {
            wrapper.eq("venue_id", safeQuery.venueId);
        }
        String status = blankToNull(safeQuery.status);
        if (status != null) {
            wrapper.eq("status", status);
        }
        String severity = blankToNull(safeQuery.severity);
        if (severity != null) {
            wrapper.eq("priority", severity);
        }
        Page<CustomerServiceCase> page = caseMapper.selectPage(
            new Page<>(safeQuery.getPageNum(), safeQuery.getPageSize()),
            wrapper.orderByDesc("id"));
        return PageResult.of(page.getRecords(), page.getTotal(), safeQuery.getPageNum(), safeQuery.getPageSize());
    }

    @Transactional(readOnly = true)
    public List<CustomerServiceCaseEvent> myCaseEvents(Long caseId) {
        CurrentUser user = requireCurrentUser();
        CustomerServiceCase serviceCase = requireCase(caseId);
        CustomerComplaint complaint = requireComplaint(serviceCase.complaintId);
        if (!user.id().equals(complaint.userId)) {
            throw BusinessException.notFound("客服工单不存在");
        }
        return listCaseEvents(serviceCase.id);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> publicReviewSummary(Long venueId) {
        requireVenueId(venueId);
        List<VenueReview> reviews = reviewMapper.selectList(new QueryWrapper<VenueReview>()
            .eq("venue_id", venueId)
            .eq("status", "PUBLISHED")
            .eq("deleted", 0));
        long reviewCount = reviews.size();
        int ratingSum = reviews.stream()
            .filter(item -> item.overallRating != null)
            .mapToInt(item -> item.overallRating)
            .sum();
        BigDecimal averageRating = reviewCount == 0
            ? new BigDecimal("0.00")
            : BigDecimal.valueOf(ratingSum).divide(BigDecimal.valueOf(reviewCount), 2, RoundingMode.HALF_UP);
        Map<Integer, Long> ratingCounts = new LinkedHashMap<>();
        for (int rating = 5; rating >= 1; rating--) {
            int currentRating = rating;
            ratingCounts.put(rating, reviews.stream()
                .filter(item -> item.overallRating != null && item.overallRating == currentRating)
                .count());
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("venueId", venueId);
        summary.put("reviewCount", reviewCount);
        summary.put("averageRating", averageRating);
        summary.put("ratingCounts", ratingCounts);
        return summary;
    }

    @Transactional(readOnly = true)
    public PageResult<PublicReviewResponse> publicReviews(FeedbackQuery query) {
        FeedbackQuery safeQuery = safeQuery(query);
        requireVenueId(safeQuery.venueId);
        QueryWrapper<VenueReview> wrapper = new QueryWrapper<VenueReview>()
            .eq("venue_id", safeQuery.venueId)
            .eq("status", "PUBLISHED")
            .eq("deleted", 0);
        if (safeQuery.spaceId != null) {
            wrapper.eq("space_id", safeQuery.spaceId);
        }
        String keyword = blankToNull(safeQuery.keyword);
        if (keyword != null) {
            wrapper.and(item -> item.like("content_sanitized", keyword).or().like("tags_json", keyword));
        }
        Page<VenueReview> page = reviewMapper.selectPage(
            new Page<>(safeQuery.getPageNum(), safeQuery.getPageSize()),
            wrapper.orderByDesc("published_at").orderByDesc("id"));
        return PageResult.of(page.getRecords().stream()
                .map(this::toPublicReviewResponse)
                .toList(),
            page.getTotal(),
            safeQuery.getPageNum(),
            safeQuery.getPageSize());
    }

    @Transactional(readOnly = true)
    public PageResult<CustomerComplaint> adminComplaints(CustomerServiceQuery query) {
        CustomerServiceQuery safeQuery = safeQuery(query);
        QueryWrapper<CustomerComplaint> wrapper = new QueryWrapper<CustomerComplaint>()
            .eq("deleted", 0);
        List<Long> scopedVenueIds = applyAdminVenueScope(wrapper, safeQuery);
        if (scopedVenueIds != null && scopedVenueIds.isEmpty()) {
            return PageResult.of(List.of(), 0, safeQuery.getPageNum(), safeQuery.getPageSize());
        }
        applyAdminComplaintFilters(wrapper, safeQuery);
        Page<CustomerComplaint> page = complaintMapper.selectPage(
            new Page<>(safeQuery.getPageNum(), safeQuery.getPageSize()),
            wrapper.orderByDesc("submitted_at").orderByDesc("id"));
        return PageResult.of(page.getRecords(), page.getTotal(), safeQuery.getPageNum(), safeQuery.getPageSize());
    }

    @Transactional(readOnly = true)
    public PageResult<CustomerServiceCase> adminCases(CustomerServiceQuery query) {
        CustomerServiceQuery safeQuery = safeQuery(query);
        QueryWrapper<CustomerServiceCase> wrapper = new QueryWrapper<CustomerServiceCase>()
            .eq("deleted", 0);
        List<Long> scopedVenueIds = applyAdminVenueScope(wrapper, safeQuery);
        if (scopedVenueIds != null && scopedVenueIds.isEmpty()) {
            return PageResult.of(List.of(), 0, safeQuery.getPageNum(), safeQuery.getPageSize());
        }
        applyAdminCaseFilters(wrapper, safeQuery);
        Page<CustomerServiceCase> page = caseMapper.selectPage(
            new Page<>(safeQuery.getPageNum(), safeQuery.getPageSize()),
            wrapper.orderByDesc("opened_at").orderByDesc("id"));
        return PageResult.of(page.getRecords(), page.getTotal(), safeQuery.getPageNum(), safeQuery.getPageSize());
    }

    @Transactional(readOnly = true)
    public List<CustomerServiceCaseEvent> adminCaseEvents(Long caseId) {
        CustomerServiceCase serviceCase = requireCase(caseId);
        venueAccessService.requireVenueManageAccess(serviceCase.venueId);
        return listCaseEvents(serviceCase.id);
    }

    @Transactional(readOnly = true)
    public PageResult<VenueReview> adminReviews(CustomerServiceQuery query) {
        CustomerServiceQuery safeQuery = safeQuery(query);
        QueryWrapper<VenueReview> wrapper = new QueryWrapper<VenueReview>()
            .eq("deleted", 0);
        List<Long> scopedVenueIds = applyAdminVenueScope(wrapper, safeQuery);
        if (scopedVenueIds != null && scopedVenueIds.isEmpty()) {
            return PageResult.of(List.of(), 0, safeQuery.getPageNum(), safeQuery.getPageSize());
        }
        applyAdminReviewFilters(wrapper, safeQuery);
        Page<VenueReview> page = reviewMapper.selectPage(
            new Page<>(safeQuery.getPageNum(), safeQuery.getPageSize()),
            wrapper.orderByDesc("submitted_at").orderByDesc("id"));
        return PageResult.of(page.getRecords(), page.getTotal(), safeQuery.getPageNum(), safeQuery.getPageSize());
    }

    @Transactional(readOnly = true)
    public Map<String, Object> adminContext(CustomerServiceQuery query) {
        CustomerServiceQuery safeQuery = safeQuery(query);
        List<Long> scopedVenueIds = resolveAdminVenueScope(safeQuery);
        if (scopedVenueIds != null && scopedVenueIds.isEmpty()) {
            return adminContextResult(scopedVenueIds, 0, 0, 0, List.of(), List.of(), List.of());
        }

        long complaintCount = safeCount(complaintMapper.selectCount(adminComplaintContextWrapper(safeQuery, scopedVenueIds)));
        long caseCount = safeCount(caseMapper.selectCount(adminCaseContextWrapper(safeQuery, scopedVenueIds)));
        long reviewCount = safeCount(reviewMapper.selectCount(adminReviewContextWrapper(safeQuery, scopedVenueIds)));

        List<Map<String, Object>> complaints = complaintMapper
            .selectList(adminComplaintContextWrapper(safeQuery, scopedVenueIds)
                .orderByDesc("submitted_at")
                .last("limit 20"))
            .stream()
            .map(this::toAdminComplaintPreview)
            .toList();
        List<Map<String, Object>> cases = caseMapper
            .selectList(adminCaseContextWrapper(safeQuery, scopedVenueIds)
                .orderByDesc("opened_at")
                .last("limit 20"))
            .stream()
            .map(this::toAdminCasePreview)
            .toList();
        List<Map<String, Object>> reviews = reviewMapper
            .selectList(adminReviewContextWrapper(safeQuery, scopedVenueIds)
                .orderByDesc("submitted_at")
                .last("limit 20"))
            .stream()
            .map(this::toAdminReviewPreview)
            .toList();
        return adminContextResult(scopedVenueIds, complaintCount, caseCount, reviewCount, complaints, cases, reviews);
    }

    @Transactional
    public CustomerServiceCase assignCase(Long caseId, CustomerServiceCaseActionRequest request) {
        CustomerServiceCase serviceCase = requireCase(caseId);
        venueAccessService.requireVenueManageAccess(serviceCase.venueId);
        if (request == null || request.assignedUserId == null) {
            throw BusinessException.badRequest("分派人员不能为空");
        }
        // AUD-P2-08：分派时校验目标用户存在且启用，避免指向不存在/禁用/普通用户
        entityExistenceService.requireActiveUser(request.assignedUserId);
        // A26-P2-20：写操作前用 FOR UPDATE 锁定 case 行，重新读取最新状态以避免
        // 并发 assign/reopen/resolve/close 之间的 TOCTOU（对照 changeCaseStatus/resolveCase/addReply 已用此模式）。
        // DC-02：锁查询 SQL 已加 deleted = 0，已删行返回 null；此处再校验 deleted 作双保险，避免沿用 requireCase 旧实体
        CustomerServiceCase lockedCase = caseMapper.selectByIdForUpdate(caseId);
        if (lockedCase == null || Integer.valueOf(1).equals(lockedCase.deleted)) {
            throw BusinessException.notFound("客服工单不存在");
        }
        serviceCase = lockedCase;
        // A26-P3-26：assignCase 加状态守卫，拒绝 RESOLVED/CLOSED 终态工单分派
        // （对照 changeCaseStatus 已拒绝这两个状态）。此前无守卫导致终态工单仍可被分派并追加事件，
        // 与 closeCase 的"仅 RESOLVED 可关闭"、resolveCase 的"仅 IN_PROGRESS/REOPENED 可解决"语义冲突。
        // 与 G2f 已加的 selectByIdForUpdate 行锁是叠加关系：锁后重读看到终态则拒绝，避免并发 resolve 后再分派。
        if (Set.of("RESOLVED", "CLOSED").contains(serviceCase.status)) {
            throw BusinessException.conflict("当前客服工单状态不可分派");
        }
        // SHIGUANG-WAVE-03-REMEDIATION-20260917 S02 / F-15：被指派人除存在且启用外，
        // 还必须与锁定工单的 venueId 存在有效 venue_admin_relation（对照 SiteOpsService/
        // AssetLifecycleService.requireAssignedUserForVenue 同型合同）。否则分派者可制造
        // 处理人看不到的悬空工单并留下与责任范围冲突的审计。失败为稳定 400，工单不落任何变更。
        // 这里校验的是被指派人（不是当前登录主体），不能改用 VenueAccessService。
        requireAssignedUserForVenue(request.assignedUserId, serviceCase.venueId);
        CurrentUser user = SecurityContext.currentUser();
        String fromStatus = serviceCase.status;
        LocalDateTime now = LocalDateTime.now();
        serviceCase.assignedUserId = request.assignedUserId;
        serviceCase.assignedAt = now;
        if ("OPEN".equals(serviceCase.status) || "REOPENED".equals(serviceCase.status)) {
            serviceCase.status = "IN_PROGRESS";
            updateComplaintStatus(serviceCase.complaintId, "IN_PROGRESS", now);
        }
        serviceCase.updateTime = now;
        caseMapper.updateById(serviceCase);
        CustomerComplaint complaint = complaintMapper.selectById(serviceCase.complaintId);
        CustomerServiceCaseEvent event = writeCaseEvent(serviceCase, complaint, "ASSIGNED", fromStatus, serviceCase.status, user,
            request.replyText, request.internalNote, SOURCE_ADMIN, now);
        // A26-P3-28：业务事件幂等键加唯一 eventNo（参照 resolveCase/addReply/closeCase/reopenCase 已带 eventNo 的模式）。
        // 原键只到秒级 (now.format(NO_TIME))，同一秒两次不同操作（assign + 状态变更）会丢失第二条审计事件。
        recordCaseBusinessEvent(BusinessEventType.CUSTOMER_SERVICE_CASE_ASSIGNED, serviceCase,
            complaint, fromStatus, serviceCase.status, "CUSTOMER_SERVICE_CASE_ASSIGNED:" + serviceCase.id + ":" + event.eventNo);
        return serviceCase;
    }

    @Transactional
    public CustomerServiceCase changeCaseStatus(Long caseId, CustomerServiceCaseActionRequest request) {
        CustomerServiceCase serviceCase = requireCase(caseId);
        venueAccessService.requireVenueManageAccess(serviceCase.venueId);
        if (request == null || isBlank(request.status)) {
            throw BusinessException.badRequest("目标状态不能为空");
        }
        String targetStatus = request.status.trim();
        if (DIRECT_CASE_ACTION_STATUS.contains(targetStatus)) {
            throw BusinessException.conflict("请使用专用客服状态动作处理该状态");
        }
        if (!"IN_PROGRESS".equals(targetStatus) && !CHANGE_COMPLAINT_STATUS.contains(targetStatus)) {
            throw BusinessException.badRequest("客服工单状态不支持");
        }
        // AUD-P2-07：写操作前用 FOR UPDATE 锁定 case 行，重新读取最新状态以避免并发解决导致状态错乱
        // DC-02：锁查询 SQL 已加 deleted = 0，已删行返回 null；此处再校验 deleted 作双保险，避免沿用 requireCase 旧实体
        CustomerServiceCase lockedCase = caseMapper.selectByIdForUpdate(caseId);
        if (lockedCase == null || Integer.valueOf(1).equals(lockedCase.deleted)) {
            throw BusinessException.notFound("客服工单不存在");
        }
        serviceCase = lockedCase;
        CurrentUser user = SecurityContext.currentUser();
        String fromStatus = serviceCase.status;
        if (!CASE_STATUS.contains(fromStatus)) {
            throw BusinessException.conflict("当前客服工单状态不可变更");
        }
        if (Set.of("RESOLVED", "CLOSED").contains(fromStatus)) {
            throw BusinessException.conflict("当前客服工单状态不可变更投诉状态");
        }
        // AUD-P2-07：同样锁定 complaint 行，避免 case/complaint 状态错乱
        CustomerComplaint complaint = complaintMapper.selectByIdForUpdate(serviceCase.complaintId);
        if (complaint == null || Integer.valueOf(1).equals(complaint.deleted)) {
            throw BusinessException.notFound("投诉不存在");
        }
        String complaintFromStatus = complaint.status;
        LocalDateTime now = LocalDateTime.now();
        boolean caseStatusChanged = false;
        if ("IN_PROGRESS".equals(targetStatus)) {
            if ("OPEN".equals(serviceCase.status) || "REOPENED".equals(serviceCase.status)) {
                serviceCase.status = "IN_PROGRESS";
                serviceCase.updateTime = now;
                caseMapper.updateById(serviceCase);
                caseStatusChanged = true;
            } else if (!"IN_PROGRESS".equals(serviceCase.status)) {
                throw BusinessException.conflict("当前客服工单状态不可转为处理中");
            }
        } else if ("WAITING_USER".equals(targetStatus) && ("OPEN".equals(serviceCase.status) || "REOPENED".equals(serviceCase.status))) {
            serviceCase.status = "IN_PROGRESS";
            serviceCase.updateTime = now;
            caseMapper.updateById(serviceCase);
            caseStatusChanged = true;
        }
        updateComplaintOperationalStatus(complaint, targetStatus, user, now);
        String eventFromStatus = caseStatusChanged ? fromStatus : complaintFromStatus;
        CustomerServiceCaseEvent event = writeCaseEvent(serviceCase, complaint, "STATUS_CHANGED", eventFromStatus, targetStatus, user,
            request.replyText, request.internalNote, SOURCE_ADMIN, now);
        // A26-P3-28：业务事件幂等键加唯一 eventNo（参照 resolveCase/addReply/closeCase/reopenCase 已带 eventNo 的模式）。
        // 原键只到秒级 (now.format(NO_TIME))，同一秒两次不同操作会丢失第二条审计事件。
        recordCaseBusinessEvent(BusinessEventType.CUSTOMER_SERVICE_CASE_STATUS_CHANGED, serviceCase,
            complaint, eventFromStatus, targetStatus, "CUSTOMER_SERVICE_CASE_STATUS_CHANGED:" + serviceCase.id + ":" + event.eventNo);
        return serviceCase;
    }

    @Transactional
    public CustomerServiceCaseEvent addReply(Long caseId, CustomerServiceCaseActionRequest request) {
        CustomerServiceCase serviceCase = requireCase(caseId);
        venueAccessService.requireVenueManageAccess(serviceCase.venueId);
        // DC-06：与 resolveCase 一致，写操作前 FOR UPDATE 锁定 case 行，避免并发回复基于已删/已变更实体；
        // 锁查询 SQL 已加 deleted = 0，此处 deleted 校验作双保险（同 resolveCase/changeCaseStatus）
        CustomerServiceCase lockedCase = caseMapper.selectByIdForUpdate(caseId);
        if (lockedCase == null || Integer.valueOf(1).equals(lockedCase.deleted)) {
            throw BusinessException.notFound("客服工单不存在");
        }
        serviceCase = lockedCase;
        String customerVisibleReply = sanitizeCustomerVisibleReply(request == null ? null : request.replyText);
        if (isBlank(customerVisibleReply)) {
            throw BusinessException.badRequest("回复内容不能为空");
        }
        LocalDateTime now = LocalDateTime.now();
        serviceCase.customerVisibleReply = customerVisibleReply;
        serviceCase.updateTime = now;
        caseMapper.updateById(serviceCase);
        CustomerComplaint complaint = complaintMapper.selectById(serviceCase.complaintId);
        CustomerServiceCaseEvent event = writeCaseEvent(serviceCase, complaint, "REPLY_ADDED", serviceCase.status, serviceCase.status,
            SecurityContext.currentUser(), request.replyText, request.internalNote, SOURCE_ADMIN, now);
        // DC-06：幂等键含 eventNo 而非纯 caseId——同一工单多次回复各自生成独立 eventNo，
        // 保证每次回复都能投递独立通知（而非被幂等消费方按 caseId 去重吞掉）；
        // 并发回复的串行化由上方 FOR UPDATE 行锁保证，不依赖幂等键去重。
        notifyCustomer(serviceCase, complaint, event, "CUSTOMER_SERVICE_REPLY_ADDED",
            "客服工单有新回复", "客服工单 " + serviceCase.id + " 有新回复：" + serviceCase.customerVisibleReply,
            "CUSTOMER_SERVICE_REPLY_ADDED:" + serviceCase.id + ":" + event.eventNo);
        return event;
    }

    @Transactional
    public CustomerServiceCase resolveCase(Long caseId, CustomerServiceCaseActionRequest request) {
        CustomerServiceCase serviceCase = requireCase(caseId);
        venueAccessService.requireVenueManageAccess(serviceCase.venueId);
        // AUD-P2-07：写操作前用 FOR UPDATE 锁定 case 行，重新读取最新状态以避免并发解决导致状态错乱
        // DC-02：锁查询 SQL 已加 deleted = 0，已删行返回 null；此处再校验 deleted 作双保险，避免沿用 requireCase 旧实体
        CustomerServiceCase lockedCase = caseMapper.selectByIdForUpdate(caseId);
        if (lockedCase == null || Integer.valueOf(1).equals(lockedCase.deleted)) {
            throw BusinessException.notFound("客服工单不存在");
        }
        serviceCase = lockedCase;
        if (!Set.of("IN_PROGRESS", "REOPENED").contains(serviceCase.status)) {
            throw BusinessException.conflict("当前客服工单状态不可解决");
        }
        // AUD-P2-07：同样锁定 complaint 行，避免出现 case=RESOLVED+complaint=WAITING_USER 的不一致
        CustomerComplaint complaint = complaintMapper.selectByIdForUpdate(serviceCase.complaintId);
        if (complaint == null || Integer.valueOf(1).equals(complaint.deleted)) {
            throw BusinessException.notFound("投诉不存在");
        }
        if (!Set.of("IN_PROGRESS", "WAITING_USER").contains(complaint.status)) {
            throw BusinessException.conflict("当前投诉状态不可解决");
        }
        String rootCauseCode = canonicalOptionalCode(request == null ? null : request.rootCauseCode, ROOT_CAUSES, "根因");
        String resolutionType = canonicalOptionalCode(request == null ? null : request.resolutionType, RESOLUTION_TYPES, "处理结果");
        String customerVisibleReply = sanitizeCustomerVisibleReply(request == null ? null : request.replyText);
        String fromStatus = serviceCase.status;
        LocalDateTime now = LocalDateTime.now();
        serviceCase.status = "RESOLVED";
        serviceCase.rootCauseCode = rootCauseCode;
        serviceCase.resolutionType = resolutionType;
        serviceCase.resolutionSummary = sanitizer.sanitize(request == null ? null : request.replyText).text();
        serviceCase.customerVisibleReply = customerVisibleReply;
        serviceCase.requiresRefundReview = flag(request == null ? null : request.requiresRefundReview);
        serviceCase.requiresCouponReview = flag(request == null ? null : request.requiresCouponReview);
        serviceCase.requiresSiteFollowUp = flag(request == null ? null : request.requiresSiteFollowUp);
        serviceCase.resolvedAt = now;
        serviceCase.updateTime = now;
        // A26-P3-27：NOT_NULL 更新策略下，rootCauseCode/resolutionType/resolutionSummary/customerVisibleReply
        // 这 4 个可空字段在重开后二次 resolve 时若本轮传 null，updateById 会静默丢弃 null 不落库，
        // 库内残留上一轮根因与回复，用户看到"RESOLVED + 上一轮旧回复"当作本轮结论。
        // 改用 UpdateWrapper 显式 set 这 4 个字段，保证本轮 null 能覆盖上一轮旧值。
        // 对照 SiteOpsService 同模式（A26-P2-16 metadata_json/space_id 显式 set）。
        caseMapper.update(serviceCase, new UpdateWrapper<CustomerServiceCase>()
            .eq("id", serviceCase.id)
            .eq("deleted", 0)
            .set("status", serviceCase.status)
            .set("root_cause_code", serviceCase.rootCauseCode)
            .set("resolution_type", serviceCase.resolutionType)
            .set("resolution_summary", serviceCase.resolutionSummary)
            .set("customer_visible_reply", serviceCase.customerVisibleReply)
            .set("requires_refund_review", serviceCase.requiresRefundReview)
            .set("requires_coupon_review", serviceCase.requiresCouponReview)
            .set("requires_site_follow_up", serviceCase.requiresSiteFollowUp)
            .set("resolved_at", serviceCase.resolvedAt)
            .set("update_time", serviceCase.updateTime));

        complaint.status = "RESOLVED";
        complaint.resolvedAt = now;
        complaint.updateTime = now;
        complaintMapper.updateById(complaint);
        CustomerServiceCaseEvent event = writeCaseEvent(serviceCase, complaint, "RESOLVED", fromStatus, "RESOLVED", SecurityContext.currentUser(),
            request == null ? null : request.replyText, request == null ? null : request.internalNote, SOURCE_ADMIN, now);
        // A26-P2-19：通知幂等键含 eventNo（对照 addReply 620 行与同函数下方 recordCaseBusinessEvent 的键都带 eventNo）。
        // resolve → reopen → 再次 resolve 是合法路径：已加行锁且 RESOLVED 不可再 resolve，并发重复已不可能，
        // 稳定键只会吞掉第二轮"已解决"通知（含新回复）。键加 eventNo 后每轮 resolve 各自生成独立 eventNo，
        // 保证每次解决都能投递独立通知。
        notifyCustomer(serviceCase, complaint, event, "CUSTOMER_SERVICE_CASE_RESOLVED",
            "客服工单已解决", "客服工单 " + serviceCase.id + " 已解决。回复：" + serviceCase.customerVisibleReply,
            "CUSTOMER_SERVICE_CASE_RESOLVED:" + serviceCase.id + ":" + event.eventNo);
        recordCaseBusinessEvent(BusinessEventType.CUSTOMER_SERVICE_CASE_RESOLVED, serviceCase, complaint,
            fromStatus, "RESOLVED", "CUSTOMER_SERVICE_CASE_RESOLVED:" + serviceCase.id + ":" + event.eventNo);
        return serviceCase;
    }

    @Transactional
    public CustomerServiceCase closeCase(Long caseId, CustomerServiceCaseActionRequest request) {
        CustomerServiceCase serviceCase = requireCase(caseId);
        venueAccessService.requireVenueManageAccess(serviceCase.venueId);
        // A26-P2-20：写操作前用 FOR UPDATE 锁定 case 行，重新读取最新状态以避免
        // 并发 reopen/close 绕过"仅 RESOLVED 可关闭"守卫（对照 changeCaseStatus/resolveCase/addReply 已用此模式）。
        // DC-02：锁查询 SQL 已加 deleted = 0，已删行返回 null；此处再校验 deleted 作双保险，避免沿用 requireCase 旧实体
        CustomerServiceCase lockedCase = caseMapper.selectByIdForUpdate(caseId);
        if (lockedCase == null || Integer.valueOf(1).equals(lockedCase.deleted)) {
            throw BusinessException.notFound("客服工单不存在");
        }
        serviceCase = lockedCase;
        if (!"RESOLVED".equals(serviceCase.status)) {
            throw BusinessException.conflict("当前客服工单状态不可关闭");
        }
        // A26-P2-20：同样锁定 complaint 行，避免 case/complaint 状态错乱（对照 resolveCase/changeCaseStatus）
        CustomerComplaint complaint = complaintMapper.selectByIdForUpdate(serviceCase.complaintId);
        if (complaint == null || Integer.valueOf(1).equals(complaint.deleted)) {
            throw BusinessException.notFound("投诉不存在");
        }
        String fromStatus = serviceCase.status;
        LocalDateTime now = LocalDateTime.now();
        serviceCase.status = "CLOSED";
        serviceCase.closedAt = now;
        serviceCase.updateTime = now;
        caseMapper.updateById(serviceCase);

        complaint.status = "CLOSED";
        complaint.closedAt = now;
        complaint.updateTime = now;
        complaintMapper.updateById(complaint);
        CustomerServiceCaseEvent event = writeCaseEvent(serviceCase, complaint, "CLOSED", fromStatus, "CLOSED", SecurityContext.currentUser(),
            request == null ? null : request.closeReason, request == null ? null : request.internalNote, SOURCE_ADMIN, now);
        recordCaseBusinessEvent(BusinessEventType.CUSTOMER_SERVICE_CASE_CLOSED, serviceCase, complaint,
            fromStatus, "CLOSED", "CUSTOMER_SERVICE_CASE_CLOSED:" + serviceCase.id + ":" + event.eventNo);
        return serviceCase;
    }

    @Transactional
    public CustomerServiceCase reopenCase(Long caseId, CustomerServiceCaseActionRequest request) {
        CustomerServiceCase serviceCase = requireCase(caseId);
        venueAccessService.requireVenueManageAccess(serviceCase.venueId);
        // A26-P2-20：写操作前用 FOR UPDATE 锁定 case 行，重新读取最新状态以避免
        // 并发 reopen/resolve 状态错乱（对照 changeCaseStatus/resolveCase/addReply 已用此模式）。
        // DC-02：锁查询 SQL 已加 deleted = 0，已删行返回 null；此处再校验 deleted 作双保险，避免沿用 requireCase 旧实体
        CustomerServiceCase lockedCase = caseMapper.selectByIdForUpdate(caseId);
        if (lockedCase == null || Integer.valueOf(1).equals(lockedCase.deleted)) {
            throw BusinessException.notFound("客服工单不存在");
        }
        serviceCase = lockedCase;
        if (!Set.of("CLOSED", "RESOLVED").contains(serviceCase.status)) {
            throw BusinessException.conflict("当前客服工单状态不可重开");
        }
        // A26-P2-20：同样锁定 complaint 行，避免 case/complaint 状态错乱（对照 resolveCase/changeCaseStatus）
        CustomerComplaint complaint = complaintMapper.selectByIdForUpdate(serviceCase.complaintId);
        if (complaint == null || Integer.valueOf(1).equals(complaint.deleted)) {
            throw BusinessException.notFound("投诉不存在");
        }
        String fromStatus = serviceCase.status;
        LocalDateTime now = LocalDateTime.now();
        serviceCase.status = "REOPENED";
        serviceCase.reopenedAt = now;
        serviceCase.updateTime = now;
        caseMapper.updateById(serviceCase);

        complaint.status = "IN_PROGRESS";
        complaint.updateTime = now;
        complaintMapper.updateById(complaint);
        CustomerServiceCaseEvent event = writeCaseEvent(serviceCase, complaint, "REOPENED", fromStatus, "REOPENED", SecurityContext.currentUser(),
            request == null ? null : request.reopenReason, request == null ? null : request.internalNote, SOURCE_ADMIN, now);
        recordCaseBusinessEvent(BusinessEventType.CUSTOMER_SERVICE_CASE_STATUS_CHANGED, serviceCase,
            complaint, fromStatus, "REOPENED", "CUSTOMER_SERVICE_CASE_REOPENED:" + serviceCase.id + ":" + event.eventNo);
        return serviceCase;
    }

    @Transactional
    public VenueReview moderateReview(Long reviewId, ReviewModerationRequest request) {
        VenueReview review = reviewMapper.selectById(reviewId);
        if (review == null || Integer.valueOf(1).equals(review.deleted)) {
            throw BusinessException.notFound("评价不存在");
        }
        venueAccessService.requireVenueManageAccess(review.venueId);
        if (request == null || isBlank(request.action)) {
            throw BusinessException.badRequest("审核动作不能为空");
        }
        String action = request.action.trim().toUpperCase();
        String fromStatus = review.status;
        String toStatus = switch (action) {
            case "FLAG" -> "FLAGGED";
            case "HIDE" -> "HIDDEN";
            case "RESTORE" -> "PUBLISHED";
            default -> throw BusinessException.badRequest("审核动作不支持");
        };
        if (!REVIEW_STATUS.contains(toStatus)) {
            throw BusinessException.badRequest("评价状态不支持");
        }
        LocalDateTime now = LocalDateTime.now();
        review.status = toStatus;
        review.hiddenReason = "HIDDEN".equals(toStatus) || "FLAGGED".equals(toStatus)
            ? blankToNull(request.reasonText)
            : null;
        if ("PUBLISHED".equals(toStatus) && review.publishedAt == null) {
            review.publishedAt = now;
        }
        review.updateTime = now;
        // A26-P3-27：RESTORE 时 hiddenReason 应清空，但 NOT_NULL 策略下 updateById 会丢弃 null，
        // 导致 RESTORE 后 hidden_reason 仍残留 HIDE/FLAG 阶段的理由。显式 set 覆盖。
        reviewMapper.update(review, new UpdateWrapper<VenueReview>()
            .eq("id", review.id)
            .eq("deleted", 0)
            .set("status", review.status)
            .set("hidden_reason", review.hiddenReason)
            .set("published_at", review.publishedAt)
            .set("update_time", review.updateTime));

        ReviewModerationRecord record = new ReviewModerationRecord();
        record.moderationNo = nextNo("MOD");
        record.reviewId = review.id;
        record.action = action;
        record.fromStatus = fromStatus;
        record.toStatus = toStatus;
        record.reasonCode = blankToNull(request.reasonCode);
        record.reasonText = blankToNull(request.reasonText);
        CurrentUser user = SecurityContext.currentUser();
        record.operatorUserId = user == null ? null : user.id();
        record.occurredAt = now;
        record.createTime = now;
        moderationMapper.insert(record);

        businessEventService.record(customerServiceEvent(BusinessEventType.REVIEW_MODERATED,
                "VENUE_REVIEW", review.id, BusinessSourceType.ADMIN, "REVIEW_MODERATED:" + record.moderationNo)
            .actor(record.operatorUserId, user == null ? null : user.username())
            .fromStatus(fromStatus)
            .toStatus(toStatus)
            .reasonCode(record.reasonCode)
            .reasonText(record.reasonText)
            .metadata("venueId", review.venueId)
            .metadata("moderationAction", action));
        return review;
    }

    private List<CustomerServiceCaseEvent> listCaseEvents(Long caseId) {
        return eventMapper.selectList(new QueryWrapper<CustomerServiceCaseEvent>()
            .eq("case_id", caseId)
            .orderByAsc("occurred_at")
            .orderByAsc("id"));
    }

    private CustomerServiceCase requireCase(Long caseId) {
        if (caseId == null) {
            throw BusinessException.badRequest("客服工单不能为空");
        }
        CustomerServiceCase serviceCase = caseMapper.selectById(caseId);
        if (serviceCase == null || Integer.valueOf(1).equals(serviceCase.deleted)) {
            throw BusinessException.notFound("客服工单不存在");
        }
        return serviceCase;
    }

    // SHIGUANG-WAVE-03-REMEDIATION-20260917 S02 / F-15：被指派人必须是存在+启用且与目标场馆
    // 有关联的有效用户。这里校验的是被指派人（不是当前登录用户），不能用
    // VenueAccessService.requireVenueManageAccess（它依赖 SecurityContext），与
    // SiteOpsService/AssetLifecycleService 同名合同一致，改用
    // VenueAdminRelationMapper.countRelation 直接判定归属。
    private void requireAssignedUserForVenue(Long assignedUserId, Long venueId) {
        entityExistenceService.requireActiveUser(assignedUserId);
        int relationCount = venueAdminRelationMapper.countRelation(assignedUserId, venueId);
        if (relationCount <= 0) {
            throw BusinessException.badRequest("指派人员不属于该场馆");
        }
    }

    private CustomerComplaint requireComplaint(Long complaintId) {
        CustomerComplaint complaint = complaintMapper.selectById(complaintId);
        if (complaint == null || Integer.valueOf(1).equals(complaint.deleted)) {
            throw BusinessException.notFound("投诉不存在");
        }
        return complaint;
    }

    private void rejectDuplicateReservationReview(Long reservationId, Long userId) {
        if (reservationId == null) {
            return;
        }
        Long count = reviewMapper.selectCount(new QueryWrapper<VenueReview>()
            .eq("reservation_id", reservationId)
            .eq("user_id", userId)
            .eq("deleted", 0));
        if (count != null && count > 0) {
            throw BusinessException.conflict("该预约已评价");
        }
    }

    private Reservation validateCompletedReviewReservation(Long reservationId, Long userId, Long venueId, Long spaceId) {
        if (reservationId == null) {
            throw BusinessException.badRequest("评价必须关联已完成预约");
        }
        Reservation reservation = validateReservationAssociation(reservationId, userId, venueId, spaceId);
        if (!ReservationStatus.COMPLETED.name().equals(reservation.status)) {
            throw BusinessException.conflict("预约完成后才能评价");
        }
        return reservation;
    }

    private Reservation validateReservationAssociation(Long reservationId, Long userId, Long venueId, Long spaceId) {
        if (reservationId == null) {
            return null;
        }
        Reservation reservation = reservationMapper.selectById(reservationId);
        if (reservation == null || Integer.valueOf(1).equals(reservation.deleted)) {
            throw BusinessException.notFound("预约不存在");
        }
        if (!userId.equals(reservation.userId)) {
            throw BusinessException.forbidden("无权关联该预约");
        }
        if (!venueId.equals(reservation.venueId)) {
            throw BusinessException.badRequest("预约与场馆不匹配");
        }
        if (spaceId != null && reservation.spaceId != null && !spaceId.equals(reservation.spaceId)) {
            throw BusinessException.badRequest("预约与场地不匹配");
        }
        return reservation;
    }

    private BizOrder validateOrderAssociation(Long orderId, Long userId, Long venueId, Long reservationId) {
        if (orderId == null) {
            return null;
        }
        BizOrder order = orderMapper.selectById(orderId);
        if (order == null) {
            throw BusinessException.notFound("订单不存在");
        }
        if (!userId.equals(order.userId)) {
            throw BusinessException.forbidden("无权关联该订单");
        }
        if (venueId != null && order.venueId != null && !venueId.equals(order.venueId)) {
            throw BusinessException.badRequest("订单与场馆不匹配");
        }
        if (reservationId != null && order.reservationId != null && !reservationId.equals(order.reservationId)) {
            throw BusinessException.badRequest("订单与预约不匹配");
        }
        return order;
    }

    private ReservationLifecycle validateLifecycleAssociation(Long lifecycleId,
                                                              Long userId,
                                                              Long venueId,
                                                              Long spaceId,
                                                              Long reservationId,
                                                              Long orderId) {
        if (lifecycleId == null) {
            return null;
        }
        ReservationLifecycle lifecycle = lifecycleMapper.selectById(lifecycleId);
        if (lifecycle == null || Integer.valueOf(1).equals(lifecycle.deleted)) {
            throw BusinessException.notFound("履约记录不存在");
        }
        if (!userId.equals(lifecycle.userId)) {
            throw BusinessException.forbidden("无权关联该履约记录");
        }
        if (venueId != null && lifecycle.venueId != null && !venueId.equals(lifecycle.venueId)) {
            throw BusinessException.badRequest("履约记录与场馆不匹配");
        }
        if (spaceId != null && lifecycle.spaceId != null && !spaceId.equals(lifecycle.spaceId)) {
            throw BusinessException.badRequest("履约记录与场地不匹配");
        }
        if (reservationId != null && lifecycle.reservationId != null && !reservationId.equals(lifecycle.reservationId)) {
            throw BusinessException.badRequest("履约记录与预约不匹配");
        }
        if (orderId != null && lifecycle.orderId != null && !orderId.equals(lifecycle.orderId)) {
            throw BusinessException.badRequest("履约记录与订单不匹配");
        }
        return lifecycle;
    }

    private void validateComplaintAssociations(ComplaintSubmitRequest request, Long userId) {
        // A26-P3-29：spaceId 与同请求其余六种关联（reservation/order/lifecycle/notice/asset/siteIncident）
        // 一样逐一校验存在性与场馆归属，避免写入指向不存在或他馆场地的投诉。
        validateSpaceAssociation(request.spaceId, request.venueId);
        validateReservationAssociation(request.reservationId, userId, request.venueId, request.spaceId);
        validateOrderAssociation(request.orderId, userId, request.venueId, request.reservationId);
        validateLifecycleAssociation(request.lifecycleId, userId, request.venueId, request.spaceId,
            request.reservationId, request.orderId);
        validateNoticeAssociation(request.noticeId, request.venueId);
        validateAssetAssociation(request.assetId, request.venueId, request.spaceId);
        validateSiteIncidentAssociation(request.siteIncidentId, request.assetId, request.venueId, request.spaceId);
    }

    private VenueSpace validateSpaceAssociation(Long spaceId, Long venueId) {
        if (spaceId == null) {
            return null;
        }
        VenueSpace space = spaceMapper.selectById(spaceId);
        if (space == null || Integer.valueOf(1).equals(space.deleted)) {
            throw BusinessException.notFound("场地不存在");
        }
        if (venueId != null && space.venueId != null && !venueId.equals(space.venueId)) {
            throw BusinessException.badRequest("场地与场馆不匹配");
        }
        return space;
    }

    private Notice validateNoticeAssociation(Long noticeId, Long venueId) {
        if (noticeId == null) {
            return null;
        }
        Notice notice = noticeMapper.selectById(noticeId);
        if (notice == null || Integer.valueOf(1).equals(notice.deleted)) {
            throw BusinessException.notFound("公告不存在");
        }
        if (notice.targetVenueId != null && venueId != null && !venueId.equals(notice.targetVenueId)) {
            throw BusinessException.badRequest("公告与场馆不匹配");
        }
        return notice;
    }

    private AssetItem validateAssetAssociation(Long assetId, Long venueId, Long spaceId) {
        if (assetId == null) {
            return null;
        }
        AssetItem asset = assetItemMapper.selectById(assetId);
        if (asset == null || Integer.valueOf(1).equals(asset.deleted)) {
            throw BusinessException.notFound("资产不存在");
        }
        if (venueId != null && asset.venueId != null && !venueId.equals(asset.venueId)) {
            throw BusinessException.badRequest("资产与场馆不匹配");
        }
        if (spaceId != null && asset.spaceId != null && !spaceId.equals(asset.spaceId)) {
            throw BusinessException.badRequest("资产与场地不匹配");
        }
        return asset;
    }

    private SiteIncidentLog validateSiteIncidentAssociation(Long siteIncidentId,
                                                            Long assetId,
                                                            Long venueId,
                                                            Long spaceId) {
        if (siteIncidentId == null) {
            return null;
        }
        SiteIncidentLog incident = siteIncidentLogMapper.selectById(siteIncidentId);
        if (incident == null || Integer.valueOf(1).equals(incident.deleted)) {
            throw BusinessException.notFound("现场事件不存在");
        }
        if (venueId != null && incident.venueId != null && !venueId.equals(incident.venueId)) {
            throw BusinessException.badRequest("现场事件与场馆不匹配");
        }
        if (spaceId != null && incident.spaceId != null && !spaceId.equals(incident.spaceId)) {
            throw BusinessException.badRequest("现场事件与场地不匹配");
        }
        if (assetId != null && incident.assetId != null && !assetId.equals(incident.assetId)) {
            throw BusinessException.badRequest("现场事件与资产不匹配");
        }
        return incident;
    }

    private String canonicalRequiredCode(String value, Set<String> allowed, String label) {
        String code = canonicalOptionalCode(value, allowed, label);
        if (code == null) {
            throw BusinessException.badRequest(label + "不能为空");
        }
        return code;
    }

    private String canonicalOptionalCode(String value, Set<String> allowed, String label) {
        String code = blankToNull(value);
        if (code == null) {
            return null;
        }
        String canonical = code.toUpperCase(Locale.ROOT);
        if (!allowed.contains(canonical)) {
            throw BusinessException.badRequest(label + "不支持");
        }
        return canonical;
    }

    private Long resolveOrderId(Long requestOrderId, Long reservationId, Long userId) {
        if (requestOrderId != null || reservationId == null) {
            return requestOrderId;
        }
        BizOrder order = orderMapper.findByReservationId(reservationId);
        if (order == null) {
            return null;
        }
        return userId.equals(order.userId) ? order.id : null;
    }

    private CustomerComplaint updateComplaintForCaseStatus(Long complaintId, String caseStatus, LocalDateTime now) {
        CustomerComplaint complaint = complaintMapper.selectById(complaintId);
        if (complaint == null) {
            return null;
        }
        String complaintStatus = switch (caseStatus) {
            case "RESOLVED" -> "RESOLVED";
            case "CLOSED" -> "CLOSED";
            case "OPEN" -> "SUBMITTED";
            case "IN_PROGRESS", "REOPENED" -> "IN_PROGRESS";
            default -> complaint.status;
        };
        if (COMPLAINT_STATUS.contains(complaintStatus) && !complaintStatus.equals(complaint.status)) {
            complaint.status = complaintStatus;
            if ("RESOLVED".equals(complaintStatus)) {
                complaint.resolvedAt = now;
            }
            if ("CLOSED".equals(complaintStatus)) {
                complaint.closedAt = now;
            }
            complaint.updateTime = now;
            complaintMapper.updateById(complaint);
        }
        return complaint;
    }

    private void updateComplaintStatus(Long complaintId, String status, LocalDateTime now) {
        if (!COMPLAINT_STATUS.contains(status)) {
            return;
        }
        CustomerComplaint complaint = complaintMapper.selectById(complaintId);
        if (complaint == null || status.equals(complaint.status)) {
            return;
        }
        complaint.status = status;
        complaint.updateTime = now;
        complaintMapper.updateById(complaint);
    }

    private void updateComplaintOperationalStatus(CustomerComplaint complaint, String status, CurrentUser user, LocalDateTime now) {
        if (!CHANGE_COMPLAINT_STATUS.contains(status)) {
            return;
        }
        if (status.equals(complaint.status)) {
            return;
        }
        complaint.status = status;
        if ("TRIAGED".equals(status)) {
            complaint.triagedBy = user == null ? null : user.id();
            complaint.triagedAt = now;
        }
        complaint.updateTime = now;
        complaintMapper.updateById(complaint);
    }

    private CustomerServiceCaseEvent writeCaseEvent(CustomerServiceCase serviceCase,
                                                    CustomerComplaint complaint,
                                                    String eventType,
                                                    String fromStatus,
                                                    String toStatus,
                                                    CurrentUser user,
                                                    String noteText,
                                                    String internalNote,
                                                    String sourceType,
                                                    LocalDateTime now) {
        // A26-P3-30：note 经 Jsoup 去 HTML 后再脱敏，与 customerVisibleReply 走同一清洗路径
        // （sanitizeCustomerVisibleReply = Jsoup.clean + sanitizer.sanitize），保证两个用户可见出口
        // （REPLY_ADDED/RESOLVED 事件 note 与 case.customerVisibleReply）内容一致。
        // 此前 note 只过 sanitizer.sanitize（正则脱敏）不去 HTML，导致同一段回复在事件流与 case 字段中内容不同。
        String noteForSanitize = noteText == null ? null : Jsoup.clean(noteText, Safelist.none()).trim();
        FeedbackTextSanitizer.SanitizedText note = sanitizer.sanitize(noteForSanitize);
        CustomerServiceCaseEvent event = new CustomerServiceCaseEvent();
        event.eventNo = nextNo("CASE_EVT");
        event.caseId = serviceCase.id;
        event.complaintId = serviceCase.complaintId;
        event.eventType = eventType;
        event.fromStatus = blankToNull(fromStatus);
        event.toStatus = blankToNull(toStatus);
        event.actorUserId = user == null ? null : user.id();
        event.actorUsername = user == null ? null : user.username();
        // A26-P0-04：回复原文落 note_raw（仅审计可见），脱敏后文本落 note_text/note_sanitized（向后兼容）。
        // note.text()==null 表示入参 noteText 为 null/blank（或经 Jsoup 清洗后为空），此时 note_raw 也保持 null 不落库。
        // 注意：noteRaw 保存的是入参 noteText 的原始值，覆盖 assignCase/changeCaseStatus/addReply/resolveCase/
        // closeCase/reopenCase 全部 writeCaseEvent 调用点，追加式证据可恢复每次回复/状态变更的原文。
        event.noteRaw = note.text() == null ? null : noteText;
        event.noteText = note.text();
        event.noteSanitized = note.text();
        event.internalNote = sanitizer.sanitize(internalNote).text();
        event.sourceType = sourceType;
        event.metadataJson = toJson(Map.of(
            "venueId", serviceCase.venueId,
            "caseNo", serviceCase.caseNo,
            "complaintNo", complaint == null ? "" : complaint.complaintNo
        ));
        event.occurredAt = now;
        event.createTime = now;
        eventMapper.insert(event);
        return event;
    }

    private void notifyVenueManagersOfNewCase(CustomerServiceCase serviceCase) {
        for (Long managerUserId : venueAccessService.managerUserIds(serviceCase.venueId)) {
            noticeService.createBusinessNotification(new NotificationEvent(
                "CUSTOMER_SERVICE_CASE_REVIEW_REQUIRED",
                "CUSTOMER_SERVICE_CASE_REVIEW_REQUIRED:" + serviceCase.id,
                managerUserId,
                NotificationRecipientKind.VENUE_ADMIN,
                "客服工单待处理",
                "有新的客服工单需要场馆管理员处理。",
                "CUSTOMER_SERVICE_CASE",
                serviceCase.id,
                Map.of("caseId", String.valueOf(serviceCase.id))
            ));
        }
    }

    private void notifyCustomer(CustomerServiceCase serviceCase,
                                CustomerComplaint complaint,
                                CustomerServiceCaseEvent event,
                                String eventType,
                                String title,
                                String content,
                                String idempotencyKey) {
        if (isBlank(serviceCase.customerVisibleReply)) {
            return;
        }
        // AUD-P2-07：通知幂等键由调用方提供，解决场景使用基于 caseId+操作类型的稳定键以避免重复通知
        noticeService.createBusinessNotification(new NotificationEvent(
            eventType,
            idempotencyKey,
            complaint.userId,
            NotificationRecipientKind.USER,
            title,
            content,
            "CUSTOMER_SERVICE_CASE",
            serviceCase.id,
            Map.of(
                "caseId", String.valueOf(serviceCase.id),
                "reply", serviceCase.customerVisibleReply
            )
        ));
    }

    private void recordCaseBusinessEvent(BusinessEventType eventType,
                                         CustomerServiceCase serviceCase,
                                         CustomerComplaint complaint,
                                         String fromStatus,
                                         String toStatus,
                                         String idempotencyKey) {
        CurrentUser user = SecurityContext.currentUser();
        businessEventService.record(customerServiceEvent(eventType, "CUSTOMER_SERVICE_CASE", serviceCase.id,
                BusinessSourceType.ADMIN, idempotencyKey)
            .relatedEntity("CUSTOMER_COMPLAINT", serviceCase.complaintId)
            .actor(user == null ? null : user.id(), user == null ? null : user.username())
            .fromStatus(fromStatus)
            .toStatus(toStatus)
            .metadata("venueId", serviceCase.venueId)
            .metadata("complaintNo", complaint == null ? null : complaint.complaintNo)
            .metadata("assignedUserId", serviceCase.assignedUserId)
            .metadata("requiresRefundReview", serviceCase.requiresRefundReview)
            .metadata("requiresCouponReview", serviceCase.requiresCouponReview)
            .metadata("requiresSiteFollowUp", serviceCase.requiresSiteFollowUp));
    }

    private BusinessEventRecordRequest customerServiceEvent(BusinessEventType eventType,
                                                            String entityType,
                                                            Long entityId,
                                                            BusinessSourceType sourceType,
                                                            String idempotencyKey) {
        return BusinessEventRecordRequest.of(
                eventType,
                BusinessDomain.CUSTOMER_SERVICE,
                entityType,
                entityId,
                sourceType,
                "SUCCESS")
            .idempotencyKey(idempotencyKey);
    }

    private CurrentUser requireCurrentUser() {
        CurrentUser user = SecurityContext.currentUser();
        if (user == null || user.id() == null) {
            throw BusinessException.forbidden("未登录");
        }
        return user;
    }

    private FeedbackQuery safeQuery(FeedbackQuery query) {
        return query == null ? new FeedbackQuery() : query;
    }

    private CustomerServiceQuery safeQuery(CustomerServiceQuery query) {
        return query == null ? new CustomerServiceQuery() : query;
    }

    private PublicReviewResponse toPublicReviewResponse(VenueReview review) {
        PublicReviewResponse response = new PublicReviewResponse();
        response.id = review.id;
        response.reviewNo = review.reviewNo;
        response.venueId = review.venueId;
        response.spaceId = review.spaceId;
        response.overallRating = review.overallRating;
        response.venueRating = review.venueRating;
        response.spaceRating = review.spaceRating;
        response.serviceRating = review.serviceRating;
        response.tagsJson = sanitizer.sanitize(review.tagsJson).text();
        response.contentSanitized = sanitizeForOutput(review.contentSanitized, review.contentText);
        response.sensitiveFlag = review.sensitiveFlag;
        response.submittedAt = review.submittedAt;
        return response;
    }

    private void applyReviewFilters(QueryWrapper<VenueReview> wrapper, FeedbackQuery query) {
        if (query.venueId != null) {
            wrapper.eq("venue_id", query.venueId);
        }
        if (query.spaceId != null) {
            wrapper.eq("space_id", query.spaceId);
        }
        String status = blankToNull(query.status);
        if (status != null) {
            wrapper.eq("status", status);
        }
        String keyword = blankToNull(query.keyword);
        if (keyword != null) {
            wrapper.and(item -> item.like("content_sanitized", keyword).or().like("tags_json", keyword));
        }
    }

    private void applyComplaintFilters(QueryWrapper<CustomerComplaint> wrapper, FeedbackQuery query) {
        applyComplaintFilters(wrapper, query, true);
    }

    private void applyComplaintFilters(QueryWrapper<CustomerComplaint> wrapper,
                                       FeedbackQuery query,
                                       boolean includeStatus) {
        if (query.venueId != null) {
            wrapper.eq("venue_id", query.venueId);
        }
        if (query.spaceId != null) {
            wrapper.eq("space_id", query.spaceId);
        }
        if (includeStatus) {
            String status = blankToNull(query.status);
            if (status != null) {
                wrapper.eq("status", status);
            }
        }
        String severity = blankToNull(query.severity);
        if (severity != null) {
            wrapper.eq("severity", severity);
        }
        String complaintType = blankToNull(query.complaintType);
        if (complaintType != null) {
            wrapper.eq("complaint_type", complaintType);
        }
        String keyword = blankToNull(query.keyword);
        if (keyword != null) {
            wrapper.and(item -> item.like("title", keyword)
                .or().like("content_sanitized", keyword));
        }
    }

    private void applyAdminComplaintFilters(QueryWrapper<CustomerComplaint> wrapper, CustomerServiceQuery query) {
        if (query.spaceId != null) {
            wrapper.eq("space_id", query.spaceId);
        }
        String status = blankToNull(query.status);
        if (status != null) {
            wrapper.eq("status", status);
        }
        String severity = blankToNull(query.severity);
        if (severity != null) {
            wrapper.eq("severity", severity);
        }
        String complaintType = blankToNull(query.complaintType);
        if (complaintType != null) {
            wrapper.eq("complaint_type", complaintType);
        }
        String keyword = blankToNull(query.keyword);
        if (keyword != null) {
            wrapper.and(item -> item.like("title", keyword)
                .or().like("content_sanitized", keyword));
        }
    }

    private void applyAdminCaseFilters(QueryWrapper<CustomerServiceCase> wrapper, CustomerServiceQuery query) {
        String status = blankToNull(query.status);
        if (status != null) {
            wrapper.eq("status", status);
        }
        String severity = blankToNull(query.severity);
        if (severity != null) {
            wrapper.eq("priority", severity);
        }
        String keyword = blankToNull(query.keyword);
        if (keyword != null) {
            wrapper.and(item -> item.like("case_no", keyword)
                .or().like("customer_visible_reply", keyword));
        }
    }

    private void applyAdminReviewFilters(QueryWrapper<VenueReview> wrapper, CustomerServiceQuery query) {
        if (query.spaceId != null) {
            wrapper.eq("space_id", query.spaceId);
        }
        String status = blankToNull(query.status);
        if (status != null) {
            wrapper.eq("status", status);
        }
        String keyword = blankToNull(query.keyword);
        if (keyword != null) {
            wrapper.and(item -> item.like("content_sanitized", keyword)
                .or().like("tags_json", keyword));
        }
    }

    private <T> List<Long> applyAdminVenueScope(QueryWrapper<T> wrapper, CustomerServiceQuery query) {
        if (query.venueId != null) {
            venueAccessService.requireVenueManageAccess(query.venueId);
            wrapper.eq("venue_id", query.venueId);
            return List.of(query.venueId);
        }
        List<Long> manageableVenueIds = venueAccessService.manageableVenueIds();
        if (manageableVenueIds != null) {
            if (manageableVenueIds.isEmpty()) {
                return List.of();
            }
            wrapper.in("venue_id", manageableVenueIds);
        }
        return manageableVenueIds;
    }

    private List<Long> resolveAdminVenueScope(CustomerServiceQuery query) {
        if (query.venueId != null) {
            venueAccessService.requireVenueManageAccess(query.venueId);
            return List.of(query.venueId);
        }
        return venueAccessService.manageableVenueIds();
    }

    private <T> void applyResolvedAdminVenueScope(QueryWrapper<T> wrapper,
                                                  List<Long> scopedVenueIds,
                                                  Long explicitVenueId) {
        if (explicitVenueId != null) {
            wrapper.eq("venue_id", explicitVenueId);
            return;
        }
        if (scopedVenueIds != null && !scopedVenueIds.isEmpty()) {
            wrapper.in("venue_id", scopedVenueIds);
        }
    }

    private QueryWrapper<CustomerComplaint> adminComplaintContextWrapper(CustomerServiceQuery query,
                                                                         List<Long> scopedVenueIds) {
        QueryWrapper<CustomerComplaint> wrapper = new QueryWrapper<CustomerComplaint>()
            .eq("deleted", 0);
        applyResolvedAdminVenueScope(wrapper, scopedVenueIds, query.venueId);
        applyAdminComplaintFilters(wrapper, query);
        return wrapper;
    }

    private QueryWrapper<CustomerServiceCase> adminCaseContextWrapper(CustomerServiceQuery query,
                                                                      List<Long> scopedVenueIds) {
        QueryWrapper<CustomerServiceCase> wrapper = new QueryWrapper<CustomerServiceCase>()
            .eq("deleted", 0);
        applyResolvedAdminVenueScope(wrapper, scopedVenueIds, query.venueId);
        applyAdminCaseFilters(wrapper, query);
        return wrapper;
    }

    private QueryWrapper<VenueReview> adminReviewContextWrapper(CustomerServiceQuery query,
                                                                List<Long> scopedVenueIds) {
        QueryWrapper<VenueReview> wrapper = new QueryWrapper<VenueReview>()
            .eq("deleted", 0);
        applyResolvedAdminVenueScope(wrapper, scopedVenueIds, query.venueId);
        applyAdminReviewFilters(wrapper, query);
        return wrapper;
    }

    private Map<String, Object> adminContextResult(List<Long> venueIds,
                                                   long complaintCount,
                                                   long caseCount,
                                                   long reviewCount,
                                                   List<Map<String, Object>> complaints,
                                                   List<Map<String, Object>> cases,
                                                   List<Map<String, Object>> reviews) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("venueIds", venueIds);
        result.put("complaintCount", complaintCount);
        result.put("caseCount", caseCount);
        result.put("reviewCount", reviewCount);
        result.put("complaints", complaints);
        result.put("cases", cases);
        result.put("reviews", reviews);
        return result;
    }

    private Map<String, Object> toAdminComplaintPreview(CustomerComplaint complaint) {
        Map<String, Object> preview = new LinkedHashMap<>();
        preview.put("id", complaint.id);
        preview.put("complaintNo", complaint.complaintNo);
        preview.put("venueId", complaint.venueId);
        preview.put("spaceId", complaint.spaceId);
        preview.put("complaintType", complaint.complaintType);
        preview.put("severity", complaint.severity);
        preview.put("status", complaint.status);
        preview.put("title", sanitizeForOutput(null, complaint.title));
        preview.put("contentSanitized", sanitizeForOutput(complaint.contentSanitized, complaint.contentText));
        preview.put("sensitiveFlag", complaint.sensitiveFlag);
        preview.put("submittedAt", complaint.submittedAt);
        preview.put("triagedAt", complaint.triagedAt);
        preview.put("resolvedAt", complaint.resolvedAt);
        preview.put("closedAt", complaint.closedAt);
        return preview;
    }

    private Map<String, Object> toAdminCasePreview(CustomerServiceCase serviceCase) {
        Map<String, Object> preview = new LinkedHashMap<>();
        preview.put("id", serviceCase.id);
        preview.put("caseNo", serviceCase.caseNo);
        preview.put("complaintId", serviceCase.complaintId);
        preview.put("venueId", serviceCase.venueId);
        preview.put("priority", serviceCase.priority);
        preview.put("status", serviceCase.status);
        preview.put("rootCauseCode", serviceCase.rootCauseCode);
        preview.put("resolutionType", serviceCase.resolutionType);
        preview.put("requiresRefundReview", serviceCase.requiresRefundReview);
        preview.put("requiresCouponReview", serviceCase.requiresCouponReview);
        preview.put("requiresSiteFollowUp", serviceCase.requiresSiteFollowUp);
        preview.put("openedAt", serviceCase.openedAt);
        preview.put("assignedAt", serviceCase.assignedAt);
        preview.put("resolvedAt", serviceCase.resolvedAt);
        preview.put("closedAt", serviceCase.closedAt);
        preview.put("reopenedAt", serviceCase.reopenedAt);
        return preview;
    }

    private Map<String, Object> toAdminReviewPreview(VenueReview review) {
        Map<String, Object> preview = new LinkedHashMap<>();
        preview.put("id", review.id);
        preview.put("reviewNo", review.reviewNo);
        preview.put("venueId", review.venueId);
        preview.put("spaceId", review.spaceId);
        preview.put("overallRating", review.overallRating);
        preview.put("venueRating", review.venueRating);
        preview.put("spaceRating", review.spaceRating);
        preview.put("serviceRating", review.serviceRating);
        preview.put("tagsJson", review.tagsJson);
        preview.put("contentSanitized", sanitizeForOutput(review.contentSanitized, review.contentText));
        preview.put("sensitiveFlag", review.sensitiveFlag);
        preview.put("status", review.status);
        preview.put("submittedAt", review.submittedAt);
        preview.put("publishedAt", review.publishedAt);
        return preview;
    }

    private long safeCount(Long count) {
        return count == null ? 0 : count;
    }

    private String sanitizeForOutput(String storedSanitized, String rawText) {
        String chosen = blankToNull(storedSanitized);
        return sanitizer.sanitize(chosen == null ? rawText : chosen).text();
    }

    private String sanitizeCustomerVisibleReply(String rawText) {
        String htmlStripped = rawText == null ? null : Jsoup.clean(rawText, Safelist.none()).trim();
        return sanitizer.sanitize(htmlStripped).text();
    }

    private void requireVenueId(Long venueId) {
        if (venueId == null) {
            throw BusinessException.badRequest("场馆不能为空");
        }
    }

    private void requireRating(Integer rating, String name) {
        if (rating == null || rating < 1 || rating > 5) {
            throw BusinessException.badRequest(name + "必须为1-5分");
        }
    }

    private void requireOptionalRating(Integer rating, String name) {
        if (rating != null) {
            requireRating(rating, name);
        }
    }

    private int flag(Boolean value) {
        return Boolean.TRUE.equals(value) ? 1 : 0;
    }

    private String nextNo(String prefix) {
        return prefix + LocalDateTime.now().format(NO_TIME)
            + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw BusinessException.badRequest("反馈数据无法序列化");
        }
    }

    private String blankToNull(String value) {
        return isBlank(value) ? null : value.trim();
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
