package com.example.venue.feedback;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.venue.common.Constants;
import com.example.venue.business.dto.BusinessEventRecordRequest;
import com.example.venue.business.enums.BusinessEventType;
import com.example.venue.business.service.BusinessEventService;
import com.example.venue.common.BusinessException;
import com.example.venue.common.CurrentUser;
import com.example.venue.common.EntityExistenceService;
import com.example.venue.common.PageResult;
import com.example.venue.feedback.dto.ComplaintSubmitRequest;
import com.example.venue.feedback.dto.CustomerServiceCaseActionRequest;
import com.example.venue.feedback.dto.CustomerServiceQuery;
import com.example.venue.feedback.dto.FeedbackQuery;
import com.example.venue.feedback.dto.FeedbackResponses;
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
import com.example.venue.feedback.service.CustomerFeedbackService;
import com.example.venue.feedback.service.FeedbackTextSanitizer;
import com.example.venue.checkin.mapper.ReservationLifecycleMapper;
import com.example.venue.notice.mapper.NoticeMapper;
import com.example.venue.notice.routing.NotificationEvent;
import com.example.venue.notice.routing.NotificationRecipientKind;
import com.example.venue.notice.service.NoticeService;
import com.example.venue.order.entity.BizOrder;
import com.example.venue.order.mapper.BizOrderMapper;
import com.example.venue.reservation.entity.Reservation;
import com.example.venue.reservation.enums.ReservationStatus;
import com.example.venue.reservation.mapper.ReservationMapper;
import com.example.venue.siteops.mapper.AssetItemMapper;
import com.example.venue.siteops.mapper.SiteIncidentLogMapper;
import com.example.venue.venue.entity.VenueSpace;
import com.example.venue.venue.mapper.VenueAdminRelationMapper;
import com.example.venue.venue.mapper.VenueSpaceMapper;
import com.example.venue.venue.service.VenueAccessService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.stubbing.Answer;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import com.fasterxml.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class CustomerFeedbackServiceTest {
    @Mock
    private VenueReviewMapper reviewMapper;
    @Mock
    private CustomerComplaintMapper complaintMapper;
    @Mock
    private CustomerServiceCaseMapper caseMapper;
    @Mock
    private CustomerServiceCaseEventMapper eventMapper;
    @Mock
    private ReviewModerationRecordMapper moderationMapper;
    @Mock
    private BusinessEventService businessEventService;
    @Mock
    private VenueAccessService venueAccessService;
    @Mock
    private ReservationMapper reservationMapper;
    @Mock
    private BizOrderMapper orderMapper;
    @Mock
    private ReservationLifecycleMapper lifecycleMapper;
    @Mock
    private NoticeMapper noticeMapper;
    @Mock
    private NoticeService noticeService;
    @Mock
    private AssetItemMapper assetItemMapper;
    @Mock
    private SiteIncidentLogMapper siteIncidentLogMapper;
    @Mock
    private VenueSpaceMapper spaceMapper;
    @Mock
    private EntityExistenceService entityExistenceService; // AUD-P2-08/09：实体存在性 helper
    @Mock
    private VenueAdminRelationMapper venueAdminRelationMapper; // F-15：分派场馆归属判定

    private CustomerFeedbackService service;

    @BeforeEach
    void setUp() {
        service = new CustomerFeedbackService(reviewMapper, complaintMapper, caseMapper, eventMapper,
            moderationMapper, businessEventService, venueAccessService, reservationMapper, orderMapper,
            lifecycleMapper, noticeMapper, noticeService, assetItemMapper, siteIncidentLogMapper,
            spaceMapper,
            new FeedbackTextSanitizer(), new ObjectMapper().findAndRegisterModules(), entityExistenceService,
            venueAdminRelationMapper);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
            new CurrentUser(7L, "customer", Set.of("USER"), Set.of()), null, List.of()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void submitReviewRejectsDuplicateReservationReviewAndSanitizesContent() {
        ReviewSubmitRequest request = new ReviewSubmitRequest();
        request.venueId = 1L;
        request.spaceId = 2L;
        request.reservationId = 3L;
        request.overallRating = 5;
        request.venueRating = 5;
        request.spaceRating = 4;
        request.serviceRating = 5;
        request.tags = List.of("CLEAN_SPACE", "FRIENDLY_SERVICE");
        request.contentText = "体验不错，电话 13812345678，订单号 OD202607060001";

        when(reviewMapper.selectCount(any(QueryWrapper.class))).thenReturn(1L);

        assertThatThrownBy(() -> service.submitReview(request))
            .hasMessage("该预约已评价");
        verify(reviewMapper, never()).insert(any(VenueReview.class));
    }

    @Test
    void sanitizerMasksRealGeneratedOrderRefundAndPaymentIdentifiers() {
        FeedbackTextSanitizer sanitizer = new FeedbackTextSanitizer();

        FeedbackTextSanitizer.SanitizedText sanitized = sanitizer.sanitize(
            "订单 ORD-20260706-0001，退款 REF202607060002，支付 Pay_20260706_0003，REFUND:20260706:0004");

        assertThat(sanitized.sensitive()).isTrue();
        assertThat(sanitized.text())
            .contains("[业务单号已隐藏]")
            .doesNotContain("ORD-20260706-0001")
            .doesNotContain("REF202607060002")
            .doesNotContain("Pay_20260706_0003")
            .doesNotContain("REFUND:20260706:0004");

        FeedbackTextSanitizer.SanitizedText formatted = sanitizer.sanitize(
            "联系 138-1234-5678、010-12345678 或 user@example.test");
        assertThat(formatted.sensitive()).isTrue();
        assertThat(formatted.text())
            .doesNotContain("138-1234-5678", "010-12345678", "user@example.test")
            .contains("138****5678", "010-****", "[邮箱已隐藏]");
    }

    @Test
    // A26-P0-04：原正则把空白放进重复组导致 "Ordinary / pay more / RFID" 等普通英文文本被整段替换；
    // 修复后字符类不含 \s、不含 OD/RF 短前缀，三句实测用例必须原文返回。
    void sanitizerDoesNotSwallowOrdinaryEnglishTextAfterRegexFix() {
        FeedbackTextSanitizer sanitizer = new FeedbackTextSanitizer();

        FeedbackTextSanitizer.SanitizedText ordinary = sanitizer.sanitize(
            "Ordinary people like the venue 5 stars");
        assertThat(ordinary.sensitive()).isFalse();
        assertThat(ordinary.text()).isEqualTo("Ordinary people like the venue 5 stars");

        FeedbackTextSanitizer.SanitizedText pay = sanitizer.sanitize(
            "I will pay more attention next time.");
        assertThat(pay.sensitive()).isFalse();
        assertThat(pay.text()).isEqualTo("I will pay more attention next time.");

        FeedbackTextSanitizer.SanitizedText rfid = sanitizer.sanitize(
            "RFID reader was broken yesterday");
        assertThat(rfid.sensitive()).isFalse();
        assertThat(rfid.text()).isEqualTo("RFID reader was broken yesterday");
    }

    @Test
    // A26-P0-04：submitReview 必须把原文落 content_raw、脱敏后文本落 content_text/content_sanitized；
    // sensitiveFlag 仅在脱敏实际发生时置 1。原文与脱敏后文本分别落库，损坏可恢复。
    void submitReviewStoresRawContentAndSanitizedTextSeparately() {
        ReviewSubmitRequest request = new ReviewSubmitRequest();
        request.venueId = 1L;
        request.spaceId = 2L;
        request.reservationId = 3L;
        request.overallRating = 5;
        request.contentText = "体验不错，电话 13812345678，订单号 ORD-20260706-0001";

        Reservation reservation = reservationEntity(3L, 7L, 1L, 2L, ReservationStatus.COMPLETED.name());
        when(reviewMapper.selectCount(any(QueryWrapper.class))).thenReturn(0L);
        when(reservationMapper.selectById(3L)).thenReturn(reservation);
        when(reviewMapper.insert(any(VenueReview.class))).thenAnswer(insertedId(31L));

        service.submitReview(request);

        ArgumentCaptor<VenueReview> reviewCaptor = ArgumentCaptor.forClass(VenueReview.class);
        verify(reviewMapper).insert(reviewCaptor.capture());
        VenueReview persisted = reviewCaptor.getValue();
        assertThat(persisted.contentRaw).isEqualTo("体验不错，电话 13812345678，订单号 ORD-20260706-0001");
        assertThat(persisted.contentText)
            .contains("138****5678", "[业务单号已隐藏]")
            .doesNotContain("13812345678", "ORD-20260706-0001");
        assertThat(persisted.contentSanitized).isEqualTo(persisted.contentText);
        assertThat(persisted.sensitiveFlag).isEqualTo(1);
    }

    @Test
    // A26-P0-04：submitReview 入参 contentText 为 null/blank 时，sanitizer 返回 null 文本，
    // content_raw 也保持 null 不落库（与 content_text 行为一致）。
    void submitReviewKeepsContentRawNullWhenInputIsBlank() {
        ReviewSubmitRequest request = new ReviewSubmitRequest();
        request.venueId = 1L;
        request.spaceId = 2L;
        request.reservationId = 3L;
        request.overallRating = 5;
        request.contentText = "   ";

        Reservation reservation = reservationEntity(3L, 7L, 1L, 2L, ReservationStatus.COMPLETED.name());
        when(reviewMapper.selectCount(any(QueryWrapper.class))).thenReturn(0L);
        when(reservationMapper.selectById(3L)).thenReturn(reservation);
        when(reviewMapper.insert(any(VenueReview.class))).thenAnswer(insertedId(31L));

        service.submitReview(request);

        ArgumentCaptor<VenueReview> reviewCaptor = ArgumentCaptor.forClass(VenueReview.class);
        verify(reviewMapper).insert(reviewCaptor.capture());
        VenueReview persisted = reviewCaptor.getValue();
        assertThat(persisted.contentRaw).isNull();
        assertThat(persisted.contentText).isNull();
        assertThat(persisted.contentSanitized).isNull();
        assertThat(persisted.sensitiveFlag).isEqualTo(0);
    }

    @Test
    // A26-P0-04：submitComplaint 必须把原文落 content_raw、脱敏后文本落 content_text/content_sanitized。
    void submitComplaintStoresRawContentAndSanitizedTextSeparately() {
        ComplaintSubmitRequest request = new ComplaintSubmitRequest();
        request.venueId = 1L;
        request.spaceId = 2L;
        request.reservationId = 3L;
        request.complaintType = "EQUIPMENT";
        request.severity = "HIGH";
        request.title = "灯光影响体验";
        request.contentText = "灯光故障，请联系我 13812345678";
        request.requestedResolution = "希望人工跟进";

        when(reservationMapper.selectById(3L))
            .thenReturn(reservationEntity(3L, 7L, 1L, 2L, ReservationStatus.COMPLETED.name()));
        when(spaceMapper.selectById(2L)).thenReturn(spaceEntity(2L, 1L));
        when(complaintMapper.insert(any(CustomerComplaint.class))).thenAnswer(insertedId(11L));
        when(caseMapper.insert(any(CustomerServiceCase.class))).thenAnswer(insertedId(21L));
        when(eventMapper.insert(any(CustomerServiceCaseEvent.class))).thenAnswer(insertedId(31L));

        service.submitComplaint(request);

        ArgumentCaptor<CustomerComplaint> complaintCaptor = ArgumentCaptor.forClass(CustomerComplaint.class);
        verify(complaintMapper).insert(complaintCaptor.capture());
        CustomerComplaint persisted = complaintCaptor.getValue();
        assertThat(persisted.contentRaw).isEqualTo("灯光故障，请联系我 13812345678");
        assertThat(persisted.contentText)
            .contains("138****5678")
            .doesNotContain("13812345678");
        assertThat(persisted.contentSanitized).isEqualTo(persisted.contentText);
        assertThat(persisted.sensitiveFlag).isEqualTo(1);
    }

    @Test
    // A26-P0-04：writeCaseEvent 必须把入参 noteText 原文落 note_raw、脱敏后文本落 note_text/note_sanitized。
    // 覆盖 addReply 路径，确认客服回复原文可恢复。
    void addReplyStoresRawNoteAndSanitizedNoteSeparately() {
        CustomerServiceCase existing = caseEntity(21L, 11L, 1L, "IN_PROGRESS");
        CustomerComplaint complaint = complaintEntity(11L, 1L, "IN_PROGRESS");
        complaint.userId = 88L;
        when(caseMapper.selectById(21L)).thenReturn(existing);
        when(caseMapper.selectByIdForUpdate(21L)).thenReturn(existing);
        when(complaintMapper.selectById(11L)).thenReturn(complaint);
        when(eventMapper.insert(any(CustomerServiceCaseEvent.class))).thenAnswer(insertedId(31L));
        CustomerServiceCaseActionRequest request = new CustomerServiceCaseActionRequest();
        request.replyText = "已处理，联系电话 13800000000 或 ORD-202607120001";
        request.internalNote = "内部备注";

        service.addReply(21L, request);

        ArgumentCaptor<CustomerServiceCaseEvent> eventCaptor = ArgumentCaptor.forClass(CustomerServiceCaseEvent.class);
        verify(eventMapper).insert(eventCaptor.capture());
        CustomerServiceCaseEvent persisted = eventCaptor.getValue();
        assertThat(persisted.noteRaw).isEqualTo("已处理，联系电话 13800000000 或 ORD-202607120001");
        assertThat(persisted.noteText)
            .contains("138****0000", "[业务单号已隐藏]")
            .doesNotContain("13800000000", "ORD-202607120001");
        assertThat(persisted.noteSanitized).isEqualTo(persisted.noteText);
    }

    @Test
    // A26-P0-04：所有 FeedbackResponses DTO 类字段列表必须不含 contentRaw/noteRaw/contentText/noteText 等
    // 原文或脱敏前字段，确保实体新增 contentRaw/noteRaw 后前端响应仍不泄露原文。
    void feedbackResponseDtosDoNotExposeRawContentFields() {
        assertThat(fieldNames(FeedbackResponses.UserReview.class))
            .doesNotContain("contentRaw", "contentText", "noteRaw", "noteText");
        assertThat(fieldNames(FeedbackResponses.UserComplaint.class))
            .doesNotContain("contentRaw", "contentText", "noteRaw", "noteText");
        assertThat(fieldNames(FeedbackResponses.UserCase.class))
            .doesNotContain("contentRaw", "contentText", "noteRaw", "noteText");
        assertThat(fieldNames(FeedbackResponses.UserCaseEvent.class))
            .doesNotContain("contentRaw", "contentText", "noteRaw", "noteText");
        assertThat(fieldNames(FeedbackResponses.AdminReview.class))
            .doesNotContain("contentRaw", "contentText", "noteRaw", "noteText");
        assertThat(fieldNames(FeedbackResponses.AdminComplaint.class))
            .doesNotContain("contentRaw", "contentText", "noteRaw", "noteText");
        assertThat(fieldNames(FeedbackResponses.AdminCase.class))
            .doesNotContain("contentRaw", "contentText", "noteRaw", "noteText");
        assertThat(fieldNames(FeedbackResponses.AdminCaseEvent.class))
            .doesNotContain("contentRaw", "contentText", "noteRaw", "noteText");
        assertThat(fieldNames(FeedbackResponses.CaseEvent.class))
            .doesNotContain("contentRaw", "contentText", "noteRaw", "noteText");
    }

    private static java.util.List<String> fieldNames(Class<?> dtoClass) {
        return java.util.Arrays.stream(dtoClass.getDeclaredFields())
            .map(java.lang.reflect.Field::getName)
            .toList();
    }

    @Test
    void submitReviewRequiresCompletedReservationEvidence() {
        ReviewSubmitRequest request = new ReviewSubmitRequest();
        request.venueId = 1L;
        request.overallRating = 5;
        request.contentText = "体验不错";

        assertThatThrownBy(() -> service.submitReview(request))
            .hasMessageContaining("预约");
        verify(reviewMapper, never()).insert(any(VenueReview.class));
    }

    @Test
    void submitReviewRejectsReservationThatIsNotCompleted() {
        ReviewSubmitRequest request = new ReviewSubmitRequest();
        request.venueId = 1L;
        request.spaceId = 2L;
        request.reservationId = 3L;
        request.overallRating = 5;
        request.contentText = "体验不错";

        Reservation reservation = reservationEntity(3L, 7L, 1L, 2L, ReservationStatus.PAID.name());
        when(reviewMapper.selectCount(any(QueryWrapper.class))).thenReturn(0L);
        when(reservationMapper.selectById(3L)).thenReturn(reservation);

        assertThatThrownBy(() -> service.submitReview(request))
            .hasMessageContaining("完成");
        verify(reviewMapper, never()).insert(any(VenueReview.class));
    }

    @Test
    void submitReviewAcceptsCompletedReservationOwnedByCurrentUser() {
        ReviewSubmitRequest request = new ReviewSubmitRequest();
        request.venueId = 1L;
        request.spaceId = 2L;
        request.reservationId = 3L;
        request.overallRating = 5;
        request.contentText = "体验不错";

        Reservation reservation = reservationEntity(3L, 7L, 1L, 2L, ReservationStatus.COMPLETED.name());
        when(reviewMapper.selectCount(any(QueryWrapper.class))).thenReturn(0L);
        when(reservationMapper.selectById(3L)).thenReturn(reservation);
        when(reviewMapper.insert(any(VenueReview.class))).thenAnswer(insertedId(31L));

        VenueReview review = service.submitReview(request);

        assertThat(review.id).isEqualTo(31L);
        assertThat(review.reservationId).isEqualTo(3L);
        verify(reviewMapper).insert(argThat((VenueReview item) ->
            ReservationStatus.COMPLETED.name().equals(reservation.status)
                && item.reservationId.equals(3L)));
    }

    @Test
    void submitComplaintCreatesOpenCaseEventAndBusinessEvents() {
        ComplaintSubmitRequest request = new ComplaintSubmitRequest();
        request.venueId = 1L;
        request.spaceId = 2L;
        request.reservationId = 3L;
        request.complaintType = "EQUIPMENT";
        request.severity = "HIGH";
        request.title = "灯光影响体验";
        request.contentText = "灯光故障，请联系我 13812345678";
        request.requestedResolution = "希望人工跟进";

        when(reservationMapper.selectById(3L))
            .thenReturn(reservationEntity(3L, 7L, 1L, 2L, ReservationStatus.COMPLETED.name()));
        when(spaceMapper.selectById(2L)).thenReturn(spaceEntity(2L, 1L));
        when(complaintMapper.insert(any(CustomerComplaint.class))).thenAnswer(insertedId(11L));
        when(caseMapper.insert(any(CustomerServiceCase.class))).thenAnswer(insertedId(21L));
        when(eventMapper.insert(any(CustomerServiceCaseEvent.class))).thenAnswer(insertedId(31L));

        CustomerComplaint complaint = service.submitComplaint(request);

        assertThat(complaint.id).isEqualTo(11L);
        assertThat(complaint.status).isEqualTo("SUBMITTED");
        assertThat(complaint.contentSanitized).contains("138****5678");
        assertThat(complaint.contentText).contains("138****5678").doesNotContain("13812345678");
        verify(caseMapper).insert(argThat((CustomerServiceCase item) ->
            "OPEN".equals(item.status) && item.complaintId.equals(11L)));
        verify(eventMapper).insert(argThat((CustomerServiceCaseEvent item) ->
            "CREATED".equals(item.eventType) && item.complaintId.equals(11L)));
        verify(businessEventService).record(argThat(item -> item.eventType == BusinessEventType.COMPLAINT_SUBMITTED));
        verify(businessEventService).record(argThat(item ->
            item.eventType == BusinessEventType.CUSTOMER_SERVICE_CASE_CREATED));
    }

    @Test
    void submitComplaintNotifiesEveryVenueManagerAfterCaseEventWithoutComplaintData() {
        ComplaintSubmitRequest request = complaintRequest();
        request.contentText = "原始投诉 13812345678";
        when(spaceMapper.selectById(2L)).thenReturn(spaceEntity(2L, 1L));
        when(complaintMapper.insert(any(CustomerComplaint.class))).thenAnswer(insertedId(11L));
        when(caseMapper.insert(any(CustomerServiceCase.class))).thenAnswer(insertedId(21L));
        when(eventMapper.insert(any(CustomerServiceCaseEvent.class))).thenAnswer(insertedId(31L));
        when(venueAccessService.managerUserIds(1L)).thenReturn(List.of(8L, 9L));

        service.submitComplaint(request);

        ArgumentCaptor<NotificationEvent> notificationCaptor = ArgumentCaptor.forClass(NotificationEvent.class);
        verify(noticeService, times(2)).createBusinessNotification(notificationCaptor.capture());
        assertThat(notificationCaptor.getAllValues())
            .extracting(NotificationEvent::recipientUserId)
            .containsExactly(8L, 9L);
        assertThat(notificationCaptor.getAllValues()).allSatisfy(notification -> {
            assertThat(notification.eventType()).isEqualTo("CUSTOMER_SERVICE_CASE_REVIEW_REQUIRED");
            assertThat(notification.eventKey()).isEqualTo("CUSTOMER_SERVICE_CASE_REVIEW_REQUIRED:21");
            assertThat(notification.recipientKind()).isEqualTo(NotificationRecipientKind.VENUE_ADMIN);
            assertThat(notification.businessType()).isEqualTo("CUSTOMER_SERVICE_CASE");
            assertThat(notification.businessId()).isEqualTo(21L);
            assertThat(notification.templateModel()).containsExactly(Map.entry("caseId", "21"));
            assertThat(notification.toString())
                .doesNotContain("原始投诉", "13812345678", "contentText", "internalNote",
                    "sensitiveFlag", "metadataJson");
        });
        InOrder persistedBeforeNotification = inOrder(complaintMapper, caseMapper, eventMapper, noticeService);
        persistedBeforeNotification.verify(complaintMapper).insert(any(CustomerComplaint.class));
        persistedBeforeNotification.verify(caseMapper).insert(any(CustomerServiceCase.class));
        persistedBeforeNotification.verify(eventMapper).insert(any(CustomerServiceCaseEvent.class));
        persistedBeforeNotification.verify(noticeService, times(2)).createBusinessNotification(any(NotificationEvent.class));
    }

    @Test
    void addReplyUsesStableEventNumberAndSanitizedReplyForComplaintOwnerNotification() {
        CustomerServiceCase existing = caseEntity(21L, 11L, 1L, "IN_PROGRESS");
        CustomerComplaint complaint = complaintEntity(11L, 1L, "IN_PROGRESS");
        complaint.userId = 88L;
        when(caseMapper.selectById(21L)).thenReturn(existing);
        // DC-06：addReply 现加 FOR UPDATE 行锁，需 mock 锁查询返回同一实体
        when(caseMapper.selectByIdForUpdate(21L)).thenReturn(existing);
        when(complaintMapper.selectById(11L)).thenReturn(complaint);
        when(eventMapper.insert(any(CustomerServiceCaseEvent.class))).thenAnswer(insertedId(31L));
        CustomerServiceCaseActionRequest request = new CustomerServiceCaseActionRequest();
        request.replyText = "<p>已处理，请联系 <strong>13800000000</strong> 或 user@example.test</p>";
        request.internalNote = "内部手机号 13911112222";

        CustomerServiceCaseEvent event = service.addReply(21L, request);

        ArgumentCaptor<NotificationEvent> notificationCaptor = ArgumentCaptor.forClass(NotificationEvent.class);
        verify(noticeService).createBusinessNotification(notificationCaptor.capture());
        NotificationEvent notification = notificationCaptor.getValue();
        assertThat(notification.eventType()).isEqualTo("CUSTOMER_SERVICE_REPLY_ADDED");
        assertThat(notification.eventKey()).isEqualTo("CUSTOMER_SERVICE_REPLY_ADDED:21:" + event.eventNo);
        assertThat(notification.recipientUserId()).isEqualTo(88L);
        assertThat(notification.recipientKind()).isEqualTo(NotificationRecipientKind.USER);
        assertThat(notification.templateModel()).containsExactlyInAnyOrderEntriesOf(Map.of(
            "caseId", "21",
            "reply", "已处理，请联系 138****0000 或 [邮箱已隐藏]"
        ));
        assertThat(existing.customerVisibleReply).isEqualTo("已处理，请联系 138****0000 或 [邮箱已隐藏]");
        ArgumentCaptor<CustomerServiceCaseEvent> eventCaptor = ArgumentCaptor.forClass(CustomerServiceCaseEvent.class);
        verify(eventMapper).insert(eventCaptor.capture());
        // A26-P3-30：note 经 Jsoup 去 HTML 后再脱敏，与 customerVisibleReply 走同一清洗路径，
        // 两个用户可见出口内容一致：noteText 与 customerVisibleReply 都不含 <p>/<strong> 等 HTML 标签。
        assertThat(eventCaptor.getValue().noteText)
            .isEqualTo("已处理，请联系 138****0000 或 [邮箱已隐藏]");
        assertThat(eventCaptor.getValue().noteSanitized)
            .isEqualTo("已处理，请联系 138****0000 或 [邮箱已隐藏]");
        assertThat(notification.toString())
            .doesNotContain("<p>", "<strong>", "13800000000", "user@example.test", "13911112222", "contentText", "internalNote",
                "sensitiveFlag", "metadataJson");
        InOrder persistedBeforeNotification = inOrder(eventMapper, noticeService);
        persistedBeforeNotification.verify(eventMapper).insert(any(CustomerServiceCaseEvent.class));
        persistedBeforeNotification.verify(noticeService).createBusinessNotification(any(NotificationEvent.class));
    }

    @Test
    void resolveCaseUsesStableEventNumberAndSanitizedCustomerVisibleReply() {
        CustomerServiceCase existing = caseEntity(21L, 11L, 1L, "IN_PROGRESS");
        CustomerComplaint complaint = complaintEntity(11L, 1L, "IN_PROGRESS");
        when(caseMapper.selectById(21L)).thenReturn(existing);
        // AUD-P2-07：mock FOR UPDATE 行锁返回同一实体，模拟事务内重新读取最新行
        when(caseMapper.selectByIdForUpdate(21L)).thenReturn(existing);
        when(complaintMapper.selectByIdForUpdate(11L)).thenReturn(complaint);
        when(eventMapper.insert(any(CustomerServiceCaseEvent.class))).thenAnswer(insertedId(31L));
        CustomerServiceCaseActionRequest request = resolveRequest(
            "<section>已解决 <b>13800000000</b>，邮箱 user@example.test</section>");
        request.internalNote = "内部手机号 13911112222";

        service.resolveCase(21L, request);

        ArgumentCaptor<CustomerServiceCaseEvent> eventCaptor = ArgumentCaptor.forClass(CustomerServiceCaseEvent.class);
        verify(eventMapper).insert(eventCaptor.capture());
        ArgumentCaptor<NotificationEvent> notificationCaptor = ArgumentCaptor.forClass(NotificationEvent.class);
        verify(noticeService).createBusinessNotification(notificationCaptor.capture());
        NotificationEvent notification = notificationCaptor.getValue();
        assertThat(notification.eventType()).isEqualTo("CUSTOMER_SERVICE_CASE_RESOLVED");
        // A26-P2-19：通知幂等键含 eventNo（对照 addReply 与 recordCaseBusinessEvent 的键都带 eventNo）。
        // resolve → reopen → 再次 resolve 是合法路径，键加 eventNo 后每轮 resolve 各自生成独立 eventNo，
        // 保证每次解决都能投递独立通知，不被 NoticeService 按 (eventType, eventKey, recipientUserId) 全局去重吞掉。
        CustomerServiceCaseEvent capturedEvent = eventCaptor.getValue();
        assertThat(notification.eventKey())
            .isEqualTo("CUSTOMER_SERVICE_CASE_RESOLVED:21:" + capturedEvent.eventNo);
        assertThat(notification.recipientUserId()).isEqualTo(7L);
        assertThat(notification.recipientKind()).isEqualTo(NotificationRecipientKind.USER);
        assertThat(notification.templateModel()).containsExactlyInAnyOrderEntriesOf(Map.of(
            "caseId", "21",
            "reply", "已解决 138****0000，邮箱 [邮箱已隐藏]"
        ));
        assertThat(existing.resolutionSummary)
            .isEqualTo("<section>已解决 <b>138****0000</b>，邮箱 [邮箱已隐藏]</section>");
        assertThat(existing.customerVisibleReply).isEqualTo("已解决 138****0000，邮箱 [邮箱已隐藏]");
        // A26-P3-30：note 经 Jsoup 去 HTML 后再脱敏，与 customerVisibleReply 走同一清洗路径，
        // 两个用户可见出口内容一致：noteText 与 customerVisibleReply 都不含 <section>/<b> 等 HTML 标签。
        // resolutionSummary 不变（不在 P3-30 范围，evidence 只点名 note）。
        assertThat(eventCaptor.getValue().noteText)
            .isEqualTo("已解决 138****0000，邮箱 [邮箱已隐藏]");
        assertThat(eventCaptor.getValue().noteSanitized)
            .isEqualTo("已解决 138****0000，邮箱 [邮箱已隐藏]");
        assertThat(notification.toString())
            .doesNotContain("<section>", "<b>", "13800000000", "user@example.test", "13911112222", "contentText", "internalNote",
                "sensitiveFlag", "metadataJson");
        InOrder persistedBeforeNotification = inOrder(eventMapper, noticeService);
        persistedBeforeNotification.verify(eventMapper).insert(any(CustomerServiceCaseEvent.class));
        persistedBeforeNotification.verify(noticeService).createBusinessNotification(any(NotificationEvent.class));
    }

    @Test
    void resolveWithoutCustomerVisibleReplyDoesNotCreateAnEmailCapableNotification() {
        CustomerServiceCase existing = caseEntity(21L, 11L, 1L, "IN_PROGRESS");
        CustomerComplaint complaint = complaintEntity(11L, 1L, "IN_PROGRESS");
        when(caseMapper.selectById(21L)).thenReturn(existing);
        // AUD-P2-07：mock FOR UPDATE 行锁返回同一实体
        when(caseMapper.selectByIdForUpdate(21L)).thenReturn(existing);
        when(complaintMapper.selectByIdForUpdate(11L)).thenReturn(complaint);
        CustomerServiceCaseActionRequest request = resolveRequest(null);
        request.internalNote = "internal only";

        service.resolveCase(21L, request);

        verify(eventMapper).insert(argThat((CustomerServiceCaseEvent event) ->
            "RESOLVED".equals(event.eventType) && "internal only".equals(event.internalNote)));
        verify(noticeService, never()).createBusinessNotification(any(NotificationEvent.class));
    }

    @Test
    void addReplyRejectsMarkupOnlyCustomerVisibleTextBeforePersistenceOrNotification() {
        CustomerServiceCase existing = caseEntity(21L, 11L, 1L, "IN_PROGRESS");
        when(caseMapper.selectById(21L)).thenReturn(existing);
        // DC-06：addReply 现加 FOR UPDATE 行锁，markup-only 校验在锁后，需 mock 锁查询返回实体
        when(caseMapper.selectByIdForUpdate(21L)).thenReturn(existing);
        CustomerServiceCaseActionRequest request = new CustomerServiceCaseActionRequest();
        request.replyText = "<div><br></div>";

        assertThatThrownBy(() -> service.addReply(21L, request))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("回复内容不能为空");

        verify(caseMapper, never()).updateById(any(CustomerServiceCase.class));
        verify(eventMapper, never()).insert(any(CustomerServiceCaseEvent.class));
        verify(noticeService, never()).createBusinessNotification(any(NotificationEvent.class));
    }

    @Test
    void addReplyRejectsConcurrentlyDeletedCaseAfterLock() {
        // DC-06：requireCase 通过后，FOR UPDATE 锁查询返回 deleted=1（模拟并发删除），
        // Service 层 deleted 校验应拒绝，且确认走了行锁串行化路径
        CustomerServiceCase active = caseEntity(21L, 11L, 1L, "IN_PROGRESS");
        CustomerServiceCase deleted = caseEntity(21L, 11L, 1L, "IN_PROGRESS");
        deleted.deleted = 1;
        when(caseMapper.selectById(21L)).thenReturn(active);
        when(caseMapper.selectByIdForUpdate(21L)).thenReturn(deleted);

        CustomerServiceCaseActionRequest request = new CustomerServiceCaseActionRequest();
        request.replyText = "已处理";
        assertThatThrownBy(() -> service.addReply(21L, request))
            .hasMessageContaining("客服工单不存在");

        verify(caseMapper).selectByIdForUpdate(21L);
        verify(caseMapper, never()).updateById(any(CustomerServiceCase.class));
        verify(eventMapper, never()).insert(any(CustomerServiceCaseEvent.class));
        verify(noticeService, never()).createBusinessNotification(any(NotificationEvent.class));
    }

    @Test
    void resolveWithMarkupOnlyReplyPersistsResolutionWithoutCustomerNotification() {
        CustomerServiceCase existing = caseEntity(21L, 11L, 1L, "IN_PROGRESS");
        CustomerComplaint complaint = complaintEntity(11L, 1L, "IN_PROGRESS");
        when(caseMapper.selectById(21L)).thenReturn(existing);
        // AUD-P2-07：mock FOR UPDATE 行锁返回同一实体
        when(caseMapper.selectByIdForUpdate(21L)).thenReturn(existing);
        when(complaintMapper.selectByIdForUpdate(11L)).thenReturn(complaint);
        CustomerServiceCaseActionRequest request = resolveRequest("<div><br></div>");

        CustomerServiceCase resolved = service.resolveCase(21L, request);

        assertThat(resolved.status).isEqualTo("RESOLVED");
        assertThat(resolved.resolutionSummary).isEqualTo(request.replyText);
        assertThat(resolved.customerVisibleReply).isNull();
        assertThat(complaint.status).isEqualTo("RESOLVED");
        // A26-P3-27：resolveCase 改用 UpdateWrapper 显式 set 可空字段，不再走 updateById
        verify(caseMapper).update(eq(existing), any(Wrapper.class));
        verify(complaintMapper).updateById(complaint);
        // A26-P3-30：note 经 Jsoup 去 HTML 后为空字符串，sanitizer.sanitize 返回 null，
        // 故 noteText/noteSanitized 为 null（与 customerVisibleReply=null 一致）。
        verify(eventMapper).insert(argThat((CustomerServiceCaseEvent event) ->
            "RESOLVED".equals(event.eventType)
                && event.noteText == null
                && event.noteSanitized == null));
        verify(noticeService, never()).createBusinessNotification(any(NotificationEvent.class));
    }

    @Test
    void submitComplaintRejectsForeignReservationAssociationBeforePersisting() {
        ComplaintSubmitRequest request = complaintRequest();
        request.reservationId = 3L;
        when(spaceMapper.selectById(2L)).thenReturn(spaceEntity(2L, 1L));
        when(reservationMapper.selectById(3L))
            .thenReturn(reservationEntity(3L, 99L, 1L, 2L, ReservationStatus.COMPLETED.name()));

        assertThatThrownBy(() -> service.submitComplaint(request))
            .hasMessageContaining("预约");
        verify(complaintMapper, never()).insert(any(CustomerComplaint.class));
        verify(caseMapper, never()).insert(any(CustomerServiceCase.class));
    }

    @Test
    void submitComplaintRejectsForeignOrderVenueBeforePersisting() {
        ComplaintSubmitRequest request = complaintRequest();
        request.orderId = 8L;
        when(spaceMapper.selectById(2L)).thenReturn(spaceEntity(2L, 1L));
        when(orderMapper.selectById(8L)).thenReturn(orderEntity(8L, 7L, 99L, 3L));

        assertThatThrownBy(() -> service.submitComplaint(request))
            .hasMessageContaining("订单与场馆不匹配");
        verify(complaintMapper, never()).insert(any(CustomerComplaint.class));
        verify(caseMapper, never()).insert(any(CustomerServiceCase.class));
    }

    @Test
    void submitComplaintRejectsUnvalidatedAssetAssociationBeforePersisting() {
        ComplaintSubmitRequest request = complaintRequest();
        request.assetId = 88L;
        when(spaceMapper.selectById(2L)).thenReturn(spaceEntity(2L, 1L));

        assertThatThrownBy(() -> service.submitComplaint(request))
            .hasMessageContaining("资产");
        verify(complaintMapper, never()).insert(any(CustomerComplaint.class));
        verify(caseMapper, never()).insert(any(CustomerServiceCase.class));
    }

    @Test
    void submitComplaintRejectsNonCanonicalComplaintTypeAndSeverity() {
        ComplaintSubmitRequest request = complaintRequest();
        request.complaintType = "SERVICE";

        assertThatThrownBy(() -> service.submitComplaint(request))
            .hasMessageContaining("投诉类型");
        verify(complaintMapper, never()).insert(any(CustomerComplaint.class));

        ComplaintSubmitRequest severityRequest = complaintRequest();
        severityRequest.severity = "URGENT";

        assertThatThrownBy(() -> service.submitComplaint(severityRequest))
            .hasMessageContaining("严重程度");
        verify(complaintMapper, never()).insert(any(CustomerComplaint.class));
    }

    @Test
    void resolveCaseStoresManualFollowUpFlagsWithoutExecutingRefunds() {
        CustomerServiceCase existing = caseEntity(21L, 11L, 1L, "IN_PROGRESS");
        CustomerComplaint complaint = complaintEntity(11L, 1L, "IN_PROGRESS");
        when(caseMapper.selectById(21L)).thenReturn(existing);
        // AUD-P2-07：mock FOR UPDATE 行锁返回同一实体
        when(caseMapper.selectByIdForUpdate(21L)).thenReturn(existing);
        when(complaintMapper.selectByIdForUpdate(11L)).thenReturn(complaint);

        CustomerServiceCaseActionRequest request = new CustomerServiceCaseActionRequest();
        request.resolutionType = "REFUND_REVIEW";
        request.rootCauseCode = "EQUIPMENT_MAINTENANCE";
        request.replyText = "已记录，将由人工复核";
        request.requiresRefundReview = true;

        CustomerServiceCase resolved = service.resolveCase(21L, request);

        assertThat(resolved.status).isEqualTo("RESOLVED");
        assertThat(resolved.requiresRefundReview).isEqualTo(1);
        // A26-P3-27：resolveCase 改用 UpdateWrapper 显式 set 可空字段，不再走 updateById
        verify(caseMapper).update(argThat((CustomerServiceCase item) -> "RESOLVED".equals(item.status)), any(Wrapper.class));
        verify(eventMapper).insert(argThat((CustomerServiceCaseEvent item) -> "RESOLVED".equals(item.eventType)));
        verify(businessEventService).record(argThat(item ->
            item.eventType == BusinessEventType.CUSTOMER_SERVICE_CASE_RESOLVED));
    }

    @Test
    // A26-P1-05 G1e：WAITING_USER 是投诉状态不是 case 状态，changeCaseStatus 必须拒绝，
    // 避免投诉状态被写进 case 事件 to_status 污染未解决工单状态口径。
    void changeCaseStatusRejectsWaitingUserToProtectCaseEventToStatus() {
        CustomerServiceCase existing = caseEntity(21L, 11L, 1L, "IN_PROGRESS");
        when(caseMapper.selectById(21L)).thenReturn(existing);

        CustomerServiceCaseActionRequest request = statusRequest("WAITING_USER");
        request.replyText = "等待用户补充现场照片";

        assertThatThrownBy(() -> service.changeCaseStatus(21L, request))
            .hasMessageContaining("客服工单状态不支持");

        // 校验在锁查询前拒绝，未触碰 case/complaint/event 任何写操作
        verify(caseMapper, never()).selectByIdForUpdate(any());
        verify(complaintMapper, never()).selectByIdForUpdate(any());
        verify(caseMapper, never()).updateById(any(CustomerServiceCase.class));
        verify(complaintMapper, never()).updateById(any(CustomerComplaint.class));
        verify(eventMapper, never()).insert(any(CustomerServiceCaseEvent.class));
        verify(businessEventService, never()).record(any(BusinessEventRecordRequest.class));
    }

    @Test
    // A26-P1-05 G1e：TRIAGED 同属投诉状态，changeCaseStatus 必须拒绝，避免 to_status 被污染。
    // 修复前 TRIAGED 既不计入 unresolved_case_count（不在 'OPEN','IN_PROGRESS','REOPENED'），
    // 也不计入 case_history_gap_count（不为 NULL），受影响的恰是"分诊后待指派"这类未解决状态。
    void changeCaseStatusRejectsTriagedToProtectCaseEventToStatus() {
        CustomerServiceCase existing = caseEntity(21L, 11L, 1L, "OPEN");
        when(caseMapper.selectById(21L)).thenReturn(existing);

        assertThatThrownBy(() -> service.changeCaseStatus(21L, statusRequest("TRIAGED")))
            .hasMessageContaining("客服工单状态不支持");

        verify(caseMapper, never()).selectByIdForUpdate(any());
        verify(complaintMapper, never()).selectByIdForUpdate(any());
        verify(caseMapper, never()).updateById(any(CustomerServiceCase.class));
        verify(complaintMapper, never()).updateById(any(CustomerComplaint.class));
        verify(eventMapper, never()).insert(any(CustomerServiceCaseEvent.class));
        verify(businessEventService, never()).record(any(BusinessEventRecordRequest.class));
    }

    @Test
    // A26-P1-05 G1e：IN_PROGRESS 是 case 状态也是投诉状态，是 changeCaseStatus 唯一合法目标。
    // 修复后 case 事件 to_status 只写 case 状态（IN_PROGRESS），未解决工单读取口径
    // `to_status IN ('OPEN','IN_PROGRESS','REOPENED')` 能正确计入未解决数。
    void changeCaseStatusInProgressWritesCaseStatusToEventToStatus() {
        CustomerServiceCase existing = caseEntity(21L, 11L, 1L, "OPEN");
        CustomerComplaint complaint = complaintEntity(11L, 1L, "SUBMITTED");
        when(caseMapper.selectById(21L)).thenReturn(existing);
        when(caseMapper.selectByIdForUpdate(21L)).thenReturn(existing);
        when(complaintMapper.selectByIdForUpdate(11L)).thenReturn(complaint);

        CustomerServiceCaseActionRequest request = statusRequest("IN_PROGRESS");
        request.replyText = "已开始核查";

        CustomerServiceCase updated = service.changeCaseStatus(21L, request);

        assertThat(updated.status).isEqualTo("IN_PROGRESS");
        assertThat(complaint.status).isEqualTo("IN_PROGRESS");
        verify(caseMapper).updateById(argThat((CustomerServiceCase item) ->
            "IN_PROGRESS".equals(item.status)));
        verify(complaintMapper).updateById(argThat((CustomerComplaint item) ->
            "IN_PROGRESS".equals(item.status)));
        // 关键断言：to_status 是 case 状态 IN_PROGRESS，不是投诉状态 TRIAGED/WAITING_USER，
        // 落入统计 SQL 的未解决集合 ('OPEN','IN_PROGRESS','REOPENED')
        verify(eventMapper).insert(argThat((CustomerServiceCaseEvent item) ->
            "STATUS_CHANGED".equals(item.eventType)
                && "OPEN".equals(item.fromStatus)
                && "IN_PROGRESS".equals(item.toStatus)
                && Set.of("OPEN", "IN_PROGRESS", "RESOLVED", "CLOSED", "REOPENED").contains(item.toStatus)));
        verify(businessEventService).record(argThat(item ->
            item.eventType == BusinessEventType.CUSTOMER_SERVICE_CASE_STATUS_CHANGED
                && "OPEN".equals(item.fromStatus)
                && "IN_PROGRESS".equals(item.toStatus)));
    }

    @Test
    void changeCaseStatusBlocksDirectResolvedClosedAndReopenedTransitions() {
        for (String blockedStatus : List.of("RESOLVED", "CLOSED", "REOPENED")) {
            when(caseMapper.selectById(21L)).thenReturn(caseEntity(21L, 11L, 1L, "IN_PROGRESS"));

            assertThatThrownBy(() -> service.changeCaseStatus(21L, statusRequest(blockedStatus)))
                .hasMessageContaining("专用客服状态动作");
        }
        verify(caseMapper, never()).updateById(any(CustomerServiceCase.class));
        verify(eventMapper, never()).insert(any(CustomerServiceCaseEvent.class));
        verify(businessEventService, never()).record(any(BusinessEventRecordRequest.class));
    }

    @Test
    void resolveCaseRejectsConcurrentlyDeletedCaseAfterLock() {
        // DC-02：requireCase 通过后，FOR UPDATE 锁查询返回 deleted=1（模拟 requireCase 后并发删除，
        // 或 SQL 未过滤时的双保险），Service 层 deleted 校验应拒绝而非沿用旧实体
        CustomerServiceCase active = caseEntity(21L, 11L, 1L, "IN_PROGRESS");
        CustomerServiceCase deleted = caseEntity(21L, 11L, 1L, "IN_PROGRESS");
        deleted.deleted = 1;
        when(caseMapper.selectById(21L)).thenReturn(active);
        when(caseMapper.selectByIdForUpdate(21L)).thenReturn(deleted);

        assertThatThrownBy(() -> service.resolveCase(21L, resolveRequest("已处理")))
            .hasMessageContaining("客服工单不存在");
        verify(caseMapper, never()).updateById(any(CustomerServiceCase.class));
        verify(complaintMapper, never()).updateById(any(CustomerComplaint.class));
        verify(eventMapper, never()).insert(any(CustomerServiceCaseEvent.class));
    }

    @Test
    void changeCaseStatusRejectsConcurrentlyDeletedCaseAfterLock() {
        // DC-02：同 resolveCase，changeCaseStatus 锁后 deleted=1 应拒绝，避免基于已删行变更状态
        // A26-P1-05 G1e：targetStatus 改用合法的 IN_PROGRESS（WAITING_USER 已被前置校验拒绝）
        CustomerServiceCase active = caseEntity(21L, 11L, 1L, "IN_PROGRESS");
        CustomerServiceCase deleted = caseEntity(21L, 11L, 1L, "IN_PROGRESS");
        deleted.deleted = 1;
        when(caseMapper.selectById(21L)).thenReturn(active);
        when(caseMapper.selectByIdForUpdate(21L)).thenReturn(deleted);

        assertThatThrownBy(() -> service.changeCaseStatus(21L, statusRequest("IN_PROGRESS")))
            .hasMessageContaining("客服工单不存在");
        verify(caseMapper, never()).updateById(any(CustomerServiceCase.class));
        verify(complaintMapper, never()).updateById(any(CustomerComplaint.class));
        verify(eventMapper, never()).insert(any(CustomerServiceCaseEvent.class));
    }

    @Test
    void changeCaseStatusRejectsCanceledToAvoidComplaintCaseDesync() {
        CustomerServiceCase existing = caseEntity(21L, 11L, 1L, "IN_PROGRESS");
        when(caseMapper.selectById(21L)).thenReturn(existing);

        assertThatThrownBy(() -> service.changeCaseStatus(21L, statusRequest("CANCELED")))
            .hasMessageContaining("客服工单状态不支持");
        verify(complaintMapper, never()).updateById(any(CustomerComplaint.class));
        verify(caseMapper, never()).updateById(any(CustomerServiceCase.class));
        verify(eventMapper, never()).insert(any(CustomerServiceCaseEvent.class));
    }

    @Test
    // A26-P2-19：resolveCase 客户通知幂等键含 eventNo，使 resolve → reopen → 再次 resolve 的合法路径
    // 第二次"已解决"通知（含新回复）能投递而非被 NoticeService 按 (eventType, eventKey, recipientUserId)
    // 全局去重吞掉。对照同函数下方 recordCaseBusinessEvent 的键与 addReply 的键都带 eventNo。
    void resolveCaseNotificationIdempotencyKeyIncludesEventNo() {
        CustomerServiceCase existing = caseEntity(21L, 11L, 1L, "IN_PROGRESS");
        CustomerComplaint complaint = complaintEntity(11L, 1L, "IN_PROGRESS");
        complaint.userId = 88L;
        when(caseMapper.selectById(21L)).thenReturn(existing);
        when(caseMapper.selectByIdForUpdate(21L)).thenReturn(existing);
        when(complaintMapper.selectByIdForUpdate(11L)).thenReturn(complaint);
        CustomerServiceCaseActionRequest request = resolveRequest("首次处理完成");
        request.replyText = "首次回复";

        service.resolveCase(21L, request);

        ArgumentCaptor<NotificationEvent> eventCaptor = ArgumentCaptor.forClass(NotificationEvent.class);
        verify(noticeService).createBusinessNotification(eventCaptor.capture());
        NotificationEvent notification = eventCaptor.getValue();
        // 键必须含 eventNo 后缀，不再是无轮次的稳定键 "CUSTOMER_SERVICE_CASE_RESOLVED:21"
        assertThat(notification.eventKey()).startsWith("CUSTOMER_SERVICE_CASE_RESOLVED:21:");
        assertThat(notification.eventKey()).isNotEqualTo("CUSTOMER_SERVICE_CASE_RESOLVED:21");
        assertThat(notification.eventKey()).matches("CUSTOMER_SERVICE_CASE_RESOLVED:21:CASE_EVT[A-Z0-9]+");
    }

    @Test
    // A26-P2-19：reopen → 再次 resolve 是合法路径，两次 resolve 的通知 eventKey 必须不同（含不同 eventNo），
    // 否则 NoticeService 按 (eventType, eventKey, recipientUserId) 全局去重会吞掉第二轮通知。
    void resolveCaseAfterReopenProducesDistinctNotificationIdempotencyKey() {
        CustomerServiceCase existing = caseEntity(21L, 11L, 1L, "IN_PROGRESS");
        CustomerComplaint complaint = complaintEntity(11L, 1L, "IN_PROGRESS");
        complaint.userId = 88L;
        when(caseMapper.selectById(21L)).thenReturn(existing);
        when(caseMapper.selectByIdForUpdate(21L)).thenReturn(existing);
        when(complaintMapper.selectByIdForUpdate(11L)).thenReturn(complaint);

        service.resolveCase(21L, resolveRequest("首次处理完成"));
        service.reopenCase(21L, reopenRequest("用户补充材料"));
        service.resolveCase(21L, resolveRequest("补充材料后再次处理完成"));

        ArgumentCaptor<NotificationEvent> eventCaptor = ArgumentCaptor.forClass(NotificationEvent.class);
        verify(noticeService, times(2)).createBusinessNotification(eventCaptor.capture());
        List<NotificationEvent> notifications = eventCaptor.getAllValues();
        assertThat(notifications).hasSize(2);
        // 两次 resolve 的 eventKey 都含 eventNo 后缀，且 eventNo 不同（nextNo 含 UUID）
        assertThat(notifications.get(0).eventKey()).startsWith("CUSTOMER_SERVICE_CASE_RESOLVED:21:");
        assertThat(notifications.get(1).eventKey()).startsWith("CUSTOMER_SERVICE_CASE_RESOLVED:21:");
        assertThat(notifications.get(0).eventKey())
            .isNotEqualTo(notifications.get(1).eventKey());
    }

    @Test
    // A26-P2-20：assignCase 写操作前应用 selectByIdForUpdate + 重读校验，避免并发 assign/reopen/resolve/close
    // 之间的 TOCTOU。场景：requireCase 看到旧视图 OPEN，FOR UPDATE 锁后看到 case 已被并发请求删除（deleted=1），
    // Service 层 deleted 校验应拒绝，且不写入 case/event。
    void assignCaseRejectsConcurrentlyDeletedCaseAfterLock() {
        CustomerServiceCase active = caseEntity(21L, 11L, 1L, "OPEN");
        CustomerServiceCase deleted = caseEntity(21L, 11L, 1L, "OPEN");
        deleted.deleted = 1;
        when(caseMapper.selectById(21L)).thenReturn(active);
        when(caseMapper.selectByIdForUpdate(21L)).thenReturn(deleted);

        CustomerServiceCaseActionRequest request = new CustomerServiceCaseActionRequest();
        request.assignedUserId = 88L;

        assertThatThrownBy(() -> service.assignCase(21L, request))
            .hasMessageContaining("客服工单不存在");

        verify(caseMapper).selectByIdForUpdate(21L);
        verify(caseMapper, never()).updateById(any(CustomerServiceCase.class));
        verify(eventMapper, never()).insert(any(CustomerServiceCaseEvent.class));
        verify(businessEventService, never()).record(any(BusinessEventRecordRequest.class));
    }

    @Test
    // A26-P2-20：closeCase 写操作前应用 selectByIdForUpdate + 重读校验，避免并发 reopen 绕过"仅 RESOLVED 可关闭"守卫。
    // 场景：requireCase 看到旧视图 RESOLVED，FOR UPDATE 锁后看到 case 已被并发请求 reopen 到 REOPENED，
    // Service 层 status 校验应拒绝（REOPENED 不是 RESOLVED），不写入 case/complaint/event。
    void closeCaseRejectsConcurrentReopenAfterLock() {
        CustomerServiceCase staleResolved = caseEntity(21L, 11L, 1L, "RESOLVED");
        CustomerServiceCase lockedReopened = caseEntity(21L, 11L, 1L, "REOPENED");
        when(caseMapper.selectById(21L)).thenReturn(staleResolved);
        when(caseMapper.selectByIdForUpdate(21L)).thenReturn(lockedReopened);

        assertThatThrownBy(() -> service.closeCase(21L, closeRequest("关闭")))
            .hasMessageContaining("当前客服工单状态不可关闭");

        verify(caseMapper).selectByIdForUpdate(21L);
        // 锁后 status 校验在 complaint 锁查询之前拒绝，不应触碰 complaint
        verify(complaintMapper, never()).selectByIdForUpdate(any());
        verify(caseMapper, never()).updateById(any(CustomerServiceCase.class));
        verify(complaintMapper, never()).updateById(any(CustomerComplaint.class));
        verify(eventMapper, never()).insert(any(CustomerServiceCaseEvent.class));
        verify(businessEventService, never()).record(any(BusinessEventRecordRequest.class));
    }

    @Test
    // A26-P2-20：closeCase FOR UPDATE 锁后 case 已被并发删除（deleted=1）应拒绝，不沿用 requireCase 旧实体。
    void closeCaseRejectsConcurrentlyDeletedCaseAfterLock() {
        CustomerServiceCase active = caseEntity(21L, 11L, 1L, "RESOLVED");
        CustomerServiceCase deleted = caseEntity(21L, 11L, 1L, "RESOLVED");
        deleted.deleted = 1;
        when(caseMapper.selectById(21L)).thenReturn(active);
        when(caseMapper.selectByIdForUpdate(21L)).thenReturn(deleted);

        assertThatThrownBy(() -> service.closeCase(21L, closeRequest("关闭")))
            .hasMessageContaining("客服工单不存在");

        verify(caseMapper).selectByIdForUpdate(21L);
        verify(caseMapper, never()).updateById(any(CustomerServiceCase.class));
        verify(complaintMapper, never()).selectByIdForUpdate(any());
        verify(eventMapper, never()).insert(any(CustomerServiceCaseEvent.class));
    }

    @Test
    // A26-P2-20：reopenCase 写操作前应用 selectByIdForUpdate + 重读校验，避免并发 close/resolve 状态错乱。
    // 场景：requireCase 看到旧视图 CLOSED，FOR UPDATE 锁后看到 case 已被并发请求改为 IN_PROGRESS
    // （不在 reopen 合法 fromStatus 集合 {CLOSED, RESOLVED} 内），应拒绝。
    void reopenCaseRejectsConcurrentStatusChangeAfterLock() {
        CustomerServiceCase staleClosed = caseEntity(21L, 11L, 1L, "CLOSED");
        CustomerServiceCase lockedInProgress = caseEntity(21L, 11L, 1L, "IN_PROGRESS");
        when(caseMapper.selectById(21L)).thenReturn(staleClosed);
        when(caseMapper.selectByIdForUpdate(21L)).thenReturn(lockedInProgress);

        assertThatThrownBy(() -> service.reopenCase(21L, reopenRequest("重开")))
            .hasMessageContaining("当前客服工单状态不可重开");

        verify(caseMapper).selectByIdForUpdate(21L);
        verify(complaintMapper, never()).selectByIdForUpdate(any());
        verify(caseMapper, never()).updateById(any(CustomerServiceCase.class));
        verify(complaintMapper, never()).updateById(any(CustomerComplaint.class));
        verify(eventMapper, never()).insert(any(CustomerServiceCaseEvent.class));
        verify(businessEventService, never()).record(any(BusinessEventRecordRequest.class));
    }

    @Test
    // A26-P2-20：reopenCase FOR UPDATE 锁后 case 已被并发删除（deleted=1）应拒绝。
    void reopenCaseRejectsConcurrentlyDeletedCaseAfterLock() {
        CustomerServiceCase active = caseEntity(21L, 11L, 1L, "CLOSED");
        CustomerServiceCase deleted = caseEntity(21L, 11L, 1L, "CLOSED");
        deleted.deleted = 1;
        when(caseMapper.selectById(21L)).thenReturn(active);
        when(caseMapper.selectByIdForUpdate(21L)).thenReturn(deleted);

        assertThatThrownBy(() -> service.reopenCase(21L, reopenRequest("重开")))
            .hasMessageContaining("客服工单不存在");

        verify(caseMapper).selectByIdForUpdate(21L);
        verify(caseMapper, never()).updateById(any(CustomerServiceCase.class));
        verify(complaintMapper, never()).selectByIdForUpdate(any());
        verify(eventMapper, never()).insert(any(CustomerServiceCaseEvent.class));
    }

    @Test
    void resolveCaseRejectsNonCanonicalRootCauseAndResolutionType() {
        CustomerServiceCase existing = caseEntity(21L, 11L, 1L, "IN_PROGRESS");
        CustomerComplaint complaint = complaintEntity(11L, 1L, "IN_PROGRESS");
        when(caseMapper.selectById(21L)).thenReturn(existing);
        // AUD-P2-07：mock FOR UPDATE 行锁返回同一实体，使校验通过到 rootCause/resolution 校验环节
        when(caseMapper.selectByIdForUpdate(21L)).thenReturn(existing);
        when(complaintMapper.selectByIdForUpdate(11L)).thenReturn(complaint);

        CustomerServiceCaseActionRequest invalidRootCause = resolveRequest("已处理");
        invalidRootCause.rootCauseCode = "EQUIPMENT";
        assertThatThrownBy(() -> service.resolveCase(21L, invalidRootCause))
            .hasMessageContaining("根因");

        CustomerServiceCaseActionRequest invalidResolution = resolveRequest("已处理");
        invalidResolution.resolutionType = "OFFLINE_REVIEW";
        assertThatThrownBy(() -> service.resolveCase(21L, invalidResolution))
            .hasMessageContaining("处理结果");

        verify(caseMapper, never()).updateById(any(CustomerServiceCase.class));
        verify(complaintMapper, never()).updateById(any(CustomerComplaint.class));
    }

    @Test
    void reopenThenResolveAndCloseUseDistinctBusinessEventIdempotencyKeys() {
        CustomerServiceCase existing = caseEntity(21L, 11L, 1L, "IN_PROGRESS");
        CustomerComplaint complaint = complaintEntity(11L, 1L, "IN_PROGRESS");
        when(caseMapper.selectById(21L)).thenReturn(existing);
        // AUD-P2-07：mock FOR UPDATE 行锁返回同一实体，状态变化通过引用在多次调用间保持可见
        when(caseMapper.selectByIdForUpdate(21L)).thenReturn(existing);
        when(complaintMapper.selectByIdForUpdate(11L)).thenReturn(complaint);

        service.resolveCase(21L, resolveRequest("首次处理完成"));
        service.closeCase(21L, closeRequest("首次关闭"));
        service.reopenCase(21L, reopenRequest("用户补充材料"));
        service.resolveCase(21L, resolveRequest("补充材料后再次处理完成"));
        service.closeCase(21L, closeRequest("再次关闭"));

        ArgumentCaptor<BusinessEventRecordRequest> eventCaptor =
            ArgumentCaptor.forClass(BusinessEventRecordRequest.class);
        verify(businessEventService, times(5)).record(eventCaptor.capture());
        List<BusinessEventRecordRequest> events = eventCaptor.getAllValues();

        assertThat(idempotencyKeys(events, BusinessEventType.CUSTOMER_SERVICE_CASE_RESOLVED))
            .hasSize(2)
            .doesNotHaveDuplicates();
        assertThat(idempotencyKeys(events, BusinessEventType.CUSTOMER_SERVICE_CASE_CLOSED))
            .hasSize(2)
            .doesNotHaveDuplicates();
    }

    @Test
    void moderateReviewHidesReviewAndRecordsModerationBusinessEvent() {
        VenueReview review = new VenueReview();
        review.id = 31L;
        review.venueId = 1L;
        review.status = "PUBLISHED";
        review.deleted = 0;
        when(reviewMapper.selectById(31L)).thenReturn(review);

        ReviewModerationRequest request = new ReviewModerationRequest();
        request.action = "HIDE";
        request.reasonCode = "PRIVACY";
        request.reasonText = "包含个人联系方式";

        VenueReview updated = service.moderateReview(31L, request);

        assertThat(updated.status).isEqualTo("HIDDEN");
        assertThat(updated.hiddenReason).isEqualTo("包含个人联系方式");
        verify(moderationMapper).insert(argThat((ReviewModerationRecord item) ->
            "HIDE".equals(item.action)
                && "PUBLISHED".equals(item.fromStatus)
                && "HIDDEN".equals(item.toStatus)));
        verify(businessEventService).record(argThat(item ->
            item.eventType == BusinessEventType.REVIEW_MODERATED
                && "PUBLISHED".equals(item.fromStatus)
                && "HIDDEN".equals(item.toStatus)
                && "PRIVACY".equals(item.reasonCode)));
    }

    @Test
    void myReviewsReturnsCurrentUsersPagedReviews() {
        VenueReview review = reviewEntity(31L, 1L, 7L, "PUBLISHED", 5);
        when(reviewMapper.selectPage(any(Page.class), any(Wrapper.class))).thenAnswer(invocation -> {
            Page<VenueReview> page = invocation.getArgument(0);
            page.setRecords(List.of(review));
            page.setTotal(1);
            return page;
        });
        FeedbackQuery query = new FeedbackQuery();
        query.venueId = 1L;
        query.status = "PUBLISHED";
        query.setPageNum(2);
        query.setPageSize(5);

        PageResult<VenueReview> result = service.myReviews(query);

        assertThat(result.getRecords()).containsExactly(review);
        assertThat(result.getTotal()).isEqualTo(1);
        assertThat(result.getPageNum()).isEqualTo(2);
        assertThat(result.getPageSize()).isEqualTo(5);
        ArgumentCaptor<Page<VenueReview>> pageCaptor = pageCaptor();
        ArgumentCaptor<Wrapper<VenueReview>> wrapperCaptor = wrapperCaptor();
        verify(reviewMapper).selectPage(pageCaptor.capture(), wrapperCaptor.capture());
        assertThat(pageCaptor.getValue().getCurrent()).isEqualTo(2);
        assertThat(pageCaptor.getValue().getSize()).isEqualTo(5);
        assertThat(wrapperCaptor.getValue().getSqlSegment())
            .contains("user_id", "venue_id", "status", "deleted");
    }

    @Test
    void myCasesReturnsEmptyPageWhenCurrentUserHasNoComplaints() {
        when(complaintMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of());

        PageResult<CustomerServiceCase> result = service.myCases(new FeedbackQuery());

        assertThat(result.getRecords()).isEmpty();
        assertThat(result.getTotal()).isZero();
        verify(caseMapper, never()).selectPage(any(Page.class), any(Wrapper.class));
    }

    @Test
    void myCaseEventsReturnsOwnedCaseEventsInChronologicalOrderWithoutWrites() {
        CustomerServiceCase serviceCase = caseEntity(21L, 11L, 1L, "IN_PROGRESS");
        CustomerComplaint complaint = complaintEntity(11L, 1L, "IN_PROGRESS");
        CustomerServiceCaseEvent created = caseEvent(
            31L, "CREATED", LocalDateTime.of(2026, 7, 12, 9, 0));
        CustomerServiceCaseEvent replied = caseEvent(
            32L, "REPLY_ADDED", LocalDateTime.of(2026, 7, 12, 10, 0));
        when(caseMapper.selectById(21L)).thenReturn(serviceCase);
        when(complaintMapper.selectById(11L)).thenReturn(complaint);
        when(eventMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(created, replied));

        List<CustomerServiceCaseEvent> result = service.myCaseEvents(21L);

        assertThat(result).containsExactly(created, replied);
        ArgumentCaptor<Wrapper<CustomerServiceCaseEvent>> wrapperCaptor = wrapperCaptor();
        verify(eventMapper).selectList(wrapperCaptor.capture());
        QueryWrapper<CustomerServiceCaseEvent> eventQuery =
            (QueryWrapper<CustomerServiceCaseEvent>) wrapperCaptor.getValue();
        assertThat(eventQuery.getSqlSegment())
            .contains("case_id =")
            .contains("ORDER BY occurred_at ASC,id ASC");
        assertThat(eventQuery.getParamNameValuePairs())
            .hasSize(1)
            .containsValue(21L);
        verify(caseMapper, never()).updateById(any(CustomerServiceCase.class));
        verify(eventMapper, never()).insert(any(CustomerServiceCaseEvent.class));
        verify(businessEventService, never()).record(any(BusinessEventRecordRequest.class));
    }

    @Test
    void myCaseEventsReturnsEmptyListForOwnedCaseWithoutEvents() {
        CustomerServiceCase serviceCase = caseEntity(21L, 11L, 1L, "OPEN");
        CustomerComplaint complaint = complaintEntity(11L, 1L, "SUBMITTED");
        when(caseMapper.selectById(21L)).thenReturn(serviceCase);
        when(complaintMapper.selectById(11L)).thenReturn(complaint);
        when(eventMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of());

        assertThat(service.myCaseEvents(21L)).isEmpty();

        verify(caseMapper).selectById(21L);
        verify(complaintMapper).selectById(11L);
        verify(eventMapper, times(1)).selectList(any(QueryWrapper.class));
    }

    @Test
    void myCaseEventsHidesForeignCaseAsNotFoundBeforeReadingEvents() {
        CustomerServiceCase serviceCase = caseEntity(21L, 11L, 1L, "OPEN");
        CustomerComplaint complaint = complaintEntity(11L, 1L, "SUBMITTED");
        complaint.userId = 88L;
        when(caseMapper.selectById(21L)).thenReturn(serviceCase);
        when(complaintMapper.selectById(11L)).thenReturn(complaint);

        assertThatThrownBy(() -> service.myCaseEvents(21L))
            .hasMessage("客服工单不存在");

        verify(eventMapper, never()).selectList(any(QueryWrapper.class));
    }

    @Test
    void adminCaseEventsRequiresVenueAccessAndReturnsAllCaseEvents() {
        authenticate(new CurrentUser(8L, "venue-admin", Set.of(Constants.ROLE_VENUE_ADMIN),
            Set.of("customer-service:manage")));
        CustomerServiceCase serviceCase = caseEntity(21L, 11L, 1L, "RESOLVED");
        CustomerServiceCaseEvent resolved = caseEvent(
            33L, "RESOLVED", LocalDateTime.of(2026, 7, 12, 11, 0));
        when(caseMapper.selectById(21L)).thenReturn(serviceCase);
        when(eventMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(resolved));

        assertThat(service.adminCaseEvents(21L)).containsExactly(resolved);

        verify(venueAccessService).requireVenueManageAccess(1L);
        ArgumentCaptor<Wrapper<CustomerServiceCaseEvent>> wrapperCaptor = wrapperCaptor();
        verify(eventMapper).selectList(wrapperCaptor.capture());
        assertThat(wrapperCaptor.getValue().getSqlSegment())
            .contains("case_id", "occurred_at", "id");
    }

    @Test
    void adminCaseEventsStopsBeforeQueryWhenVenueAccessDenied() {
        authenticate(new CurrentUser(8L, "venue-admin", Set.of(Constants.ROLE_VENUE_ADMIN),
            Set.of("customer-service:manage")));
        CustomerServiceCase serviceCase = caseEntity(21L, 11L, 1L, "OPEN");
        when(caseMapper.selectById(21L)).thenReturn(serviceCase);
        doThrow(BusinessException.forbidden("无权管理该场馆数据"))
            .when(venueAccessService).requireVenueManageAccess(1L);

        assertThatThrownBy(() -> service.adminCaseEvents(21L))
            .hasMessage("无权管理该场馆数据");

        verify(eventMapper, never()).selectList(any(QueryWrapper.class));
    }

    @Test
    void eventResponseMappersEnforceUserAndAdminPrivacy() {
        CustomerServiceCaseEvent event = caseEvent(
            32L, "REPLY_ADDED", LocalDateTime.of(2026, 7, 12, 10, 0));
        event.eventNo = "EVT-20260712-0032";
        event.fromStatus = "IN_PROGRESS";
        event.toStatus = "WAITING_USER";
        event.actorUserId = 8L;
        event.actorUsername = "operator 13812345678 ORD-202607120003";
        event.noteText = "raw public 13812345678 ORD-202607120001";
        event.noteSanitized = "public 13812345678 ORD-202607120001";
        event.internalNote = "internal 13912345678 PAY-202607120002";
        event.sourceType = "ADMIN";
        event.metadataJson = "{\"paymentId\":\"PAY-202607120004\"}";

        FeedbackTextSanitizer sanitizer = new FeedbackTextSanitizer();
        FeedbackResponses.UserCaseEvent userResponse = FeedbackResponses.userCaseEvent(event, sanitizer);
        FeedbackResponses.AdminCaseEvent adminResponse = FeedbackResponses.adminCaseEvent(event, sanitizer);

        assertThat(userResponse.id).isEqualTo(32L);
        assertThat(userResponse.eventType).isEqualTo("REPLY_ADDED");
        assertThat(userResponse.fromStatus).isEqualTo("IN_PROGRESS");
        assertThat(userResponse.toStatus).isEqualTo("WAITING_USER");
        assertThat(userResponse.noteSanitized)
            .isEqualTo("public 138****5678 [业务单号已隐藏]");
        assertThat(userResponse.occurredAt).isEqualTo(LocalDateTime.of(2026, 7, 12, 10, 0));

        assertThat(adminResponse.id).isEqualTo(32L);
        assertThat(adminResponse.eventType).isEqualTo("REPLY_ADDED");
        assertThat(adminResponse.fromStatus).isEqualTo("IN_PROGRESS");
        assertThat(adminResponse.toStatus).isEqualTo("WAITING_USER");
        assertThat(adminResponse.noteSanitized)
            .isEqualTo("public 138****5678 [业务单号已隐藏]");
        assertThat(adminResponse.internalNoteSanitized)
            .isEqualTo("internal 139****5678 [业务单号已隐藏]");
        assertThat(adminResponse.actorUsername)
            .isEqualTo("operator 138****5678 [业务单号已隐藏]");
        assertThat(adminResponse.sourceType).isEqualTo("ADMIN");
        assertThat(adminResponse.occurredAt).isEqualTo(LocalDateTime.of(2026, 7, 12, 10, 0));

        event.eventType = "ASSIGNED";
        assertThat(FeedbackResponses.userCaseEvent(event, sanitizer).noteSanitized).isNull();

        assertThat(FeedbackResponses.UserCaseEvent.class.getDeclaredFields())
            .extracting(Field::getName)
            .containsExactlyInAnyOrder(
                "id", "eventType", "fromStatus", "toStatus", "noteSanitized", "occurredAt");
        assertThat(FeedbackResponses.AdminCaseEvent.class.getDeclaredFields())
            .extracting(Field::getName)
            .containsExactlyInAnyOrder(
                "id", "eventType", "fromStatus", "toStatus", "noteSanitized",
                "internalNoteSanitized", "actorUsername", "sourceType", "occurredAt");
    }

    @Test
    void publicReviewSummaryCountsPublishedRatings() {
        when(reviewMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(
            reviewEntity(31L, 1L, 7L, "PUBLISHED", 5),
            reviewEntity(32L, 1L, 8L, "PUBLISHED", 3)));

        Map<String, Object> summary = service.publicReviewSummary(1L);

        assertThat(summary)
            .containsEntry("venueId", 1L)
            .containsEntry("reviewCount", 2L)
            .containsEntry("averageRating", new BigDecimal("4.00"));
        assertThat((Map<Integer, Long>) summary.get("ratingCounts"))
            .containsEntry(5, 1L)
            .containsEntry(3, 1L);
    }

    @Test
    void publicReviewsReturnsPublishedOnlySanitizedDtos() {
        VenueReview review = reviewEntity(31L, 1L, 7L, "PUBLISHED", 5);
        review.spaceId = 2L;
        review.venueRating = 4;
        review.spaceRating = 5;
        review.serviceRating = 5;
        review.tagsJson = "[\"CLEAN\"]";
        review.contentText = "原始文本 13812345678 ORD-202607060001";
        review.contentSanitized = "旧脱敏文本 138****5678 ORD-202607060001";
        review.sensitiveFlag = 1;
        review.reservationId = 99L;
        review.orderId = 199L;
        review.lifecycleId = 299L;
        review.hiddenReason = "不应公开";
        review.metadataJson = "{\"internal\":true}";
        review.deleted = 0;
        review.submittedAt = LocalDateTime.of(2026, 7, 6, 10, 0, 0);
        when(reviewMapper.selectPage(any(Page.class), any(Wrapper.class))).thenAnswer(invocation -> {
            Page<VenueReview> page = invocation.getArgument(0);
            page.setRecords(List.of(review));
            page.setTotal(1);
            return page;
        });

        FeedbackQuery query = new FeedbackQuery();
        query.venueId = 1L;
        query.spaceId = 2L;
        query.setPageNum(3);
        query.setPageSize(4);

        PageResult<PublicReviewResponse> result = service.publicReviews(query);

        assertThat(result.getTotal()).isEqualTo(1);
        assertThat(result.getPageNum()).isEqualTo(3);
        assertThat(result.getPageSize()).isEqualTo(4);
        assertThat(result.getRecords()).singleElement().satisfies(item -> {
            assertThat(item.id).isEqualTo(31L);
            assertThat(item.reviewNo).isEqualTo("REVIEW202607060001");
            assertThat(item.venueId).isEqualTo(1L);
            assertThat(item.spaceId).isEqualTo(2L);
            assertThat(item.overallRating).isEqualTo(5);
            assertThat(item.venueRating).isEqualTo(4);
            assertThat(item.spaceRating).isEqualTo(5);
            assertThat(item.serviceRating).isEqualTo(5);
            assertThat(item.tagsJson).isEqualTo("[\"CLEAN\"]");
            assertThat(item.contentSanitized).isEqualTo("旧脱敏文本 138****5678 [业务单号已隐藏]");
            assertThat(item.sensitiveFlag).isEqualTo(1);
            assertThat(item.submittedAt).isEqualTo(LocalDateTime.of(2026, 7, 6, 10, 0, 0));
        });

        ArgumentCaptor<Page<VenueReview>> pageCaptor = pageCaptor();
        ArgumentCaptor<Wrapper<VenueReview>> wrapperCaptor = wrapperCaptor();
        verify(reviewMapper).selectPage(pageCaptor.capture(), wrapperCaptor.capture());
        assertThat(pageCaptor.getValue().getCurrent()).isEqualTo(3);
        assertThat(pageCaptor.getValue().getSize()).isEqualTo(4);
        assertThat(wrapperCaptor.getValue().getSqlSegment())
            .contains("venue_id", "space_id", "status", "deleted")
            .doesNotContain("user_id");
    }

    @Test
    void publicReviewKeywordSearchUsesOnlySanitizedColumns() {
        when(reviewMapper.selectPage(any(Page.class), any(Wrapper.class))).thenAnswer(invocation -> {
            Page<VenueReview> page = invocation.getArgument(0);
            page.setRecords(List.of());
            page.setTotal(0);
            return page;
        });
        FeedbackQuery query = new FeedbackQuery();
        query.venueId = 1L;
        query.keyword = "电话";

        service.publicReviews(query);

        ArgumentCaptor<Wrapper<VenueReview>> wrapperCaptor = wrapperCaptor();
        verify(reviewMapper).selectPage(any(Page.class), wrapperCaptor.capture());
        assertThat(wrapperCaptor.getValue().getSqlSegment())
            .contains("content_sanitized", "tags_json")
            .doesNotContain("content_text");
    }

    @Test
    void adminComplaintsRestrictsVenueAdminListToManageableVenues() {
        authenticate(new CurrentUser(8L, "venue-admin", Set.of(Constants.ROLE_VENUE_ADMIN),
            Set.of("customer-service:manage")));
        CustomerComplaint complaint = complaintEntity(11L, 1L, "SUBMITTED");
        when(venueAccessService.manageableVenueIds()).thenReturn(List.of(1L, 2L));
        when(complaintMapper.selectPage(any(Page.class), any(Wrapper.class))).thenAnswer(invocation -> {
            Page<CustomerComplaint> page = invocation.getArgument(0);
            page.setRecords(List.of(complaint));
            page.setTotal(1);
            return page;
        });
        CustomerServiceQuery query = new CustomerServiceQuery();
        query.status = "SUBMITTED";
        query.keyword = "灯光";

        PageResult<CustomerComplaint> result = service.adminComplaints(query);

        assertThat(result.getRecords()).containsExactly(complaint);
        verify(venueAccessService).manageableVenueIds();
        verify(venueAccessService, never()).requireVenueManageAccess(any());
        ArgumentCaptor<Wrapper<CustomerComplaint>> wrapperCaptor = wrapperCaptor();
        verify(complaintMapper).selectPage(any(Page.class), wrapperCaptor.capture());
        assertThat(wrapperCaptor.getValue().getSqlSegment())
            .contains("venue_id", "status", "deleted", "title");
    }

    @Test
    void adminComplaintsReturnsEmptyWhenVenueAdminHasNoManageableVenues() {
        authenticate(new CurrentUser(8L, "venue-admin", Set.of(Constants.ROLE_VENUE_ADMIN),
            Set.of("customer-service:manage")));
        when(venueAccessService.manageableVenueIds()).thenReturn(List.of());

        PageResult<CustomerComplaint> result = service.adminComplaints(new CustomerServiceQuery());

        assertThat(result.getRecords()).isEmpty();
        assertThat(result.getTotal()).isZero();
        verify(complaintMapper, never()).selectPage(any(Page.class), any(Wrapper.class));
    }

    @Test
    void adminCasesWithExplicitVenueRequiresAccessAndFiltersByVenue() {
        CustomerServiceCase serviceCase = caseEntity(21L, 11L, 1L, "IN_PROGRESS");
        when(caseMapper.selectPage(any(Page.class), any(Wrapper.class))).thenAnswer(invocation -> {
            Page<CustomerServiceCase> page = invocation.getArgument(0);
            page.setRecords(List.of(serviceCase));
            page.setTotal(1);
            return page;
        });
        CustomerServiceQuery query = new CustomerServiceQuery();
        query.venueId = 1L;
        query.status = "IN_PROGRESS";
        query.severity = "HIGH";
        query.keyword = "已处理";

        PageResult<CustomerServiceCase> result = service.adminCases(query);

        assertThat(result.getRecords()).containsExactly(serviceCase);
        verify(venueAccessService).requireVenueManageAccess(1L);
        verify(venueAccessService, never()).manageableVenueIds();
        ArgumentCaptor<Wrapper<CustomerServiceCase>> wrapperCaptor = wrapperCaptor();
        verify(caseMapper).selectPage(any(Page.class), wrapperCaptor.capture());
        assertThat(wrapperCaptor.getValue().getSqlSegment())
            .contains("venue_id", "status", "priority", "deleted", "case_no", "customer_visible_reply")
            .doesNotContain("resolution_summary");
    }

    @Test
    void adminReviewsAsSystemAdminCanListGlobalDataWithoutVenueFilter() {
        authenticate(new CurrentUser(1L, "admin", Set.of(Constants.ROLE_SYSTEM_ADMIN), Set.of("review:manage")));
        VenueReview review = reviewEntity(31L, 3L, 7L, "PUBLISHED", 5);
        when(venueAccessService.manageableVenueIds()).thenReturn(null);
        when(reviewMapper.selectPage(any(Page.class), any(Wrapper.class))).thenAnswer(invocation -> {
            Page<VenueReview> page = invocation.getArgument(0);
            page.setRecords(List.of(review));
            page.setTotal(1);
            return page;
        });
        CustomerServiceQuery query = new CustomerServiceQuery();
        query.status = "PUBLISHED";

        PageResult<VenueReview> result = service.adminReviews(query);

        assertThat(result.getRecords()).containsExactly(review);
        ArgumentCaptor<Wrapper<VenueReview>> wrapperCaptor = wrapperCaptor();
        verify(reviewMapper).selectPage(any(Page.class), wrapperCaptor.capture());
        assertThat(wrapperCaptor.getValue().getSqlSegment())
            .contains("status", "deleted")
            .doesNotContain("venue_id");
    }

    @Test
    void adminContextUsesVenueScopeAndDoesNotMutateFeedbackFacts() {
        authenticate(new CurrentUser(8L, "venue-admin", Set.of(Constants.ROLE_VENUE_ADMIN),
            Set.of("customer-service:analytics")));
        CustomerComplaint complaint = complaintEntity(11L, 1L, "SUBMITTED");
        CustomerServiceCase serviceCase = caseEntity(21L, 11L, 1L, "OPEN");
        VenueReview review = reviewEntity(31L, 1L, 7L, "PUBLISHED", 5);
        when(venueAccessService.manageableVenueIds()).thenReturn(List.of(1L, 2L));
        when(complaintMapper.selectCount(any(QueryWrapper.class))).thenReturn(1L);
        when(caseMapper.selectCount(any(QueryWrapper.class))).thenReturn(1L);
        when(reviewMapper.selectCount(any(QueryWrapper.class))).thenReturn(1L);
        when(complaintMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(complaint));
        when(caseMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(serviceCase));
        when(reviewMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(review));

        Map<String, Object> context = service.adminContext(new CustomerServiceQuery());

        assertThat(context)
            .containsEntry("venueIds", List.of(1L, 2L))
            .containsEntry("complaintCount", 1L)
            .containsEntry("caseCount", 1L)
            .containsEntry("reviewCount", 1L);
        verify(venueAccessService).manageableVenueIds();
        verify(complaintMapper, never()).updateById(any(CustomerComplaint.class));
        verify(caseMapper, never()).updateById(any(CustomerServiceCase.class));
        verify(eventMapper, never()).insert(any(CustomerServiceCaseEvent.class));
        verify(businessEventService, never()).record(any(BusinessEventRecordRequest.class));
    }

    @Test
    void adminContextCountsAllMatchingRecordsBeyondPreviewLimit() {
        CustomerServiceQuery query = new CustomerServiceQuery();
        query.venueId = 1L;
        query.status = "SUBMITTED";
        query.severity = "HIGH";
        query.keyword = "灯光";

        lenient().when(complaintMapper.selectCount(any(QueryWrapper.class))).thenReturn(25L);
        lenient().when(caseMapper.selectCount(any(QueryWrapper.class))).thenReturn(23L);
        lenient().when(reviewMapper.selectCount(any(QueryWrapper.class))).thenReturn(24L);
        when(complaintMapper.selectList(any(QueryWrapper.class))).thenReturn(complaints(20));
        when(caseMapper.selectList(any(QueryWrapper.class))).thenReturn(cases(20));
        when(reviewMapper.selectList(any(QueryWrapper.class))).thenReturn(reviews(20));

        Map<String, Object> context = service.adminContext(query);

        assertThat(context)
            .containsEntry("venueIds", List.of(1L))
            .containsEntry("complaintCount", 25L)
            .containsEntry("caseCount", 23L)
            .containsEntry("reviewCount", 24L);
        assertThat((List<?>) context.get("complaints")).hasSize(20);
        assertThat((List<?>) context.get("cases")).hasSize(20);
        assertThat((List<?>) context.get("reviews")).hasSize(20);
        verify(venueAccessService).requireVenueManageAccess(1L);
        verify(venueAccessService, never()).manageableVenueIds();

        ArgumentCaptor<Wrapper<CustomerComplaint>> complaintWrapperCaptor = wrapperCaptor();
        verify(complaintMapper).selectCount(complaintWrapperCaptor.capture());
        verify(complaintMapper).selectList(complaintWrapperCaptor.capture());
        assertThat(complaintWrapperCaptor.getAllValues())
            .allSatisfy(wrapper -> assertThat(wrapper.getSqlSegment())
                .contains("venue_id", "status", "severity", "deleted"));
    }

    @Test
    void adminContextReturnsSanitizedPreviewMapsWithoutRawComplaintOrReviewText() {
        CustomerServiceQuery query = new CustomerServiceQuery();
        query.venueId = 1L;

        CustomerComplaint complaint = complaintEntity(11L, 1L, "SUBMITTED");
        complaint.title = "灯光故障 13812345678";
        complaint.contentText = "原始投诉 13812345678 ORD-202607060001";
        complaint.contentSanitized = "原始投诉 138****5678 ORD-202607060001";
        complaint.requestedResolution = "请拨打 13812345678 联系我";
        complaint.orderId = 199L;
        complaint.metadataJson = "{\"paymentProof\":\"raw\"}";
        CustomerServiceCase serviceCase = caseEntity(21L, 11L, 1L, "OPEN");
        serviceCase.resolutionSummary = "内部处理记录 13812345678";
        serviceCase.customerVisibleReply = "客服回复 13812345678";
        serviceCase.metadataJson = "{\"internalNote\":\"raw\"}";
        VenueReview review = reviewEntity(31L, 1L, 7L, "PUBLISHED", 5);
        review.contentText = "原始评价 13812345678 REF202607060002";
        review.contentSanitized = "原始评价 138****5678 REF202607060002";
        review.orderId = 299L;
        review.hiddenReason = "包含电话 13812345678";
        review.metadataJson = "{\"contact\":\"raw\"}";

        when(complaintMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(complaint));
        when(caseMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(serviceCase));
        when(reviewMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(review));

        Map<String, Object> context = service.adminContext(query);

        Object complaintPreview = ((List<?>) context.get("complaints")).get(0);
        Object casePreview = ((List<?>) context.get("cases")).get(0);
        Object reviewPreview = ((List<?>) context.get("reviews")).get(0);
        assertThat(complaintPreview).isInstanceOf(Map.class);
        assertThat(casePreview).isInstanceOf(Map.class);
        assertThat(reviewPreview).isInstanceOf(Map.class);

        @SuppressWarnings("unchecked")
        Map<String, Object> complaintMap = (Map<String, Object>) complaintPreview;
        @SuppressWarnings("unchecked")
        Map<String, Object> caseMap = (Map<String, Object>) casePreview;
        @SuppressWarnings("unchecked")
        Map<String, Object> reviewMap = (Map<String, Object>) reviewPreview;
        assertThat(complaintMap)
            .containsEntry("title", "灯光故障 138****5678")
            .containsEntry("contentSanitized", "原始投诉 138****5678 [业务单号已隐藏]")
            .doesNotContainKeys("contentText", "userId", "orderId", "requestedResolution", "metadataJson", "sourceType");
        assertThat(reviewMap)
            .containsEntry("contentSanitized", "原始评价 138****5678 [业务单号已隐藏]")
            .doesNotContainKeys("contentText", "userId", "orderId", "hiddenReason", "metadataJson", "sourceType");
        assertThat(caseMap)
            .doesNotContainKeys("resolutionSummary", "customerVisibleReply", "metadataJson",
                "noteText", "internalNote", "assignedUserId");
        assertThat(complaintMap.values()).doesNotContain("原始投诉 13812345678 ORD-202607060001");
        assertThat(reviewMap.values()).doesNotContain("原始评价 13812345678 REF202607060002");
        assertThat(complaintMap.values().stream().map(String::valueOf).toList())
            .noneMatch(value -> value.contains("13812345678"))
            .noneMatch(value -> value.contains("ORD-202607060001"));
        assertThat(reviewMap.values().stream().map(String::valueOf).toList())
            .noneMatch(value -> value.contains("13812345678"))
            .noneMatch(value -> value.contains("REF202607060002"));
    }

    @Test
    // AUD-P2-07：并发解决场景下，FOR UPDATE 锁后看到 case/complaint 终态应拒绝，避免状态错乱与重复通知
    void resolveCaseRejectsConcurrentResolveViaForUpdateLockAndTerminalComplaint() {
        // 场景一：requireCase 看到旧视图 IN_PROGRESS，但 FOR UPDATE 锁后看到 case 已被并发请求解决到 RESOLVED
        CustomerServiceCase staleInProgress = caseEntity(21L, 11L, 1L, "IN_PROGRESS");
        CustomerServiceCase lockedResolved = caseEntity(21L, 11L, 1L, "RESOLVED");
        when(caseMapper.selectById(21L)).thenReturn(staleInProgress);
        when(caseMapper.selectByIdForUpdate(21L)).thenReturn(lockedResolved);

        assertThatThrownBy(() -> service.resolveCase(21L, resolveRequest("并发解决")))
            .hasMessageContaining("当前客服工单状态不可解决");

        // 场景二：case 仍可解决，但 complaint 已被并发请求解决到 RESOLVED 终态
        CustomerServiceCase caseInProgress = caseEntity(21L, 11L, 1L, "IN_PROGRESS");
        CustomerComplaint complaintResolved = complaintEntity(11L, 1L, "RESOLVED");
        when(caseMapper.selectByIdForUpdate(21L)).thenReturn(caseInProgress);
        when(complaintMapper.selectByIdForUpdate(11L)).thenReturn(complaintResolved);

        assertThatThrownBy(() -> service.resolveCase(21L, resolveRequest("再次解决")))
            .hasMessageContaining("当前投诉状态不可解决");

        // 两种并发场景下都不应写入 case/complaint/event/通知
        verify(caseMapper, never()).updateById(any(CustomerServiceCase.class));
        verify(complaintMapper, never()).updateById(any(CustomerComplaint.class));
        verify(eventMapper, never()).insert(any(CustomerServiceCaseEvent.class));
        verify(noticeService, never()).createBusinessNotification(any(NotificationEvent.class));
    }

    @Test
    // AUD-P2-07：changeCaseStatus 在 FOR UPDATE 锁后看到 case 已是终态时应拒绝，避免状态错乱
    // A26-P1-05 G1e：targetStatus 改用合法的 IN_PROGRESS（WAITING_USER 已被前置校验拒绝）
    void changeCaseStatusRejectsTerminalCaseUnderForUpdateLock() {
        CustomerServiceCase staleInProgress = caseEntity(21L, 11L, 1L, "IN_PROGRESS");
        CustomerServiceCase lockedResolved = caseEntity(21L, 11L, 1L, "RESOLVED");
        when(caseMapper.selectById(21L)).thenReturn(staleInProgress);
        when(caseMapper.selectByIdForUpdate(21L)).thenReturn(lockedResolved);

        assertThatThrownBy(() -> service.changeCaseStatus(21L, statusRequest("IN_PROGRESS")))
            .hasMessageContaining("当前客服工单状态不可变更投诉状态");

        verify(caseMapper, never()).updateById(any(CustomerServiceCase.class));
        verify(complaintMapper, never()).updateById(any(CustomerComplaint.class));
        verify(eventMapper, never()).insert(any(CustomerServiceCaseEvent.class));
        verify(businessEventService, never()).record(any(BusinessEventRecordRequest.class));
    }

    @Test
    // AUD-P2-08：分派时校验目标用户存在且启用，拒绝指向不存在/禁用用户
    void assignCaseRejectsNonExistentOrDisabledAssignedUser() {
        CustomerServiceCase existing = caseEntity(21L, 11L, 1L, "OPEN");
        when(caseMapper.selectById(21L)).thenReturn(existing);

        // 场景一：分派到不存在的用户
        doThrow(BusinessException.badRequest("用户不存在"))
            .when(entityExistenceService).requireActiveUser(999L);
        CustomerServiceCaseActionRequest nonExistentRequest = new CustomerServiceCaseActionRequest();
        nonExistentRequest.assignedUserId = 999L;

        assertThatThrownBy(() -> service.assignCase(21L, nonExistentRequest))
            .hasMessageContaining("用户不存在");

        // 场景二：分派到已禁用用户
        doThrow(BusinessException.badRequest("用户已禁用"))
            .when(entityExistenceService).requireActiveUser(888L);
        CustomerServiceCaseActionRequest disabledRequest = new CustomerServiceCaseActionRequest();
        disabledRequest.assignedUserId = 888L;

        assertThatThrownBy(() -> service.assignCase(21L, disabledRequest))
            .hasMessageContaining("用户已禁用");

        // 两种场景下都不应写入 case/event
        verify(caseMapper, never()).updateById(any(CustomerServiceCase.class));
        verify(eventMapper, never()).insert(any(CustomerServiceCaseEvent.class));
        verify(businessEventService, never()).record(any(BusinessEventRecordRequest.class));
    }

    @Test
    // SHIGUANG-WAVE-03-REMEDIATION-20260917 S02 / F-15：分派对象四类覆盖。
    // 本馆启用人员可分派；不存在/停用/外馆启用人员均被拒。
    // 拒绝路径不更新 assigned_user_id、不写 case event 或业务事件（失败不落库）。
    void assignCaseEnforcesVenueRelationAcrossFourAssigneeClasses() {
        CustomerServiceCase existing = caseEntity(21L, 11L, 1L, "OPEN");
        when(caseMapper.selectById(21L)).thenReturn(existing);
        when(caseMapper.selectByIdForUpdate(21L)).thenReturn(existing);
        CustomerComplaint complaint = complaintEntity(11L, 1L, "SUBMITTED");
        when(complaintMapper.selectById(11L)).thenReturn(complaint);
        when(eventMapper.insert(any(CustomerServiceCaseEvent.class))).thenAnswer(insertedId(31L));

        // 场景一：本馆启用人员（88 拥有场馆 1 关系）→ 200 且写入 assigned_user_id
        when(venueAdminRelationMapper.countRelation(88L, 1L)).thenReturn(1);
        CustomerServiceCaseActionRequest localRequest = new CustomerServiceCaseActionRequest();
        localRequest.assignedUserId = 88L;
        CustomerServiceCase assigned = service.assignCase(21L, localRequest);
        assertThat(assigned.assignedUserId).isEqualTo(88L);

        // 场景二/三：不存在与停用用户沿用 requireActiveUser 语义被拒（写入前拦截）
        doThrow(BusinessException.badRequest("用户不存在"))
            .when(entityExistenceService).requireActiveUser(999L);
        doThrow(BusinessException.badRequest("用户已禁用"))
            .when(entityExistenceService).requireActiveUser(888L);
        CustomerServiceCaseActionRequest nonExistentRequest = new CustomerServiceCaseActionRequest();
        nonExistentRequest.assignedUserId = 999L;
        assertThatThrownBy(() -> service.assignCase(21L, nonExistentRequest))
            .hasMessageContaining("用户不存在");
        CustomerServiceCaseActionRequest disabledRequest = new CustomerServiceCaseActionRequest();
        disabledRequest.assignedUserId = 888L;
        assertThatThrownBy(() -> service.assignCase(21L, disabledRequest))
            .hasMessageContaining("用户已禁用");

        // 场景四：外馆启用人员（77 存在且启用，但场馆 1 关系数为 0）→ 400 指派人员不属于该场馆
        when(venueAdminRelationMapper.countRelation(77L, 1L)).thenReturn(0);
        CustomerServiceCaseActionRequest crossRequest = new CustomerServiceCaseActionRequest();
        crossRequest.assignedUserId = 77L;
        assertThatThrownBy(() -> service.assignCase(21L, crossRequest))
            .hasMessageContaining("指派人员不属于该场馆");

        // 仅场景一成功写入一次；三个拒绝路径均不落库
        verify(caseMapper, times(1)).updateById(any(CustomerServiceCase.class));
        verify(eventMapper, times(1)).insert(any(CustomerServiceCaseEvent.class));
        verify(businessEventService, times(1)).record(any(BusinessEventRecordRequest.class));
    }

    @Test
    // AUD-P2-09：投诉时校验场馆存在，拒绝指向不存在场馆的投诉
    void submitComplaintRejectsNonExistentVenue() {
        ComplaintSubmitRequest request = complaintRequest();
        request.venueId = 999L;
        doThrow(BusinessException.badRequest("场馆不存在"))
            .when(entityExistenceService).requireVenueExists(999L);

        assertThatThrownBy(() -> service.submitComplaint(request))
            .hasMessageContaining("场馆不存在");

        verify(complaintMapper, never()).insert(any(CustomerComplaint.class));
        verify(caseMapper, never()).insert(any(CustomerServiceCase.class));
        verify(eventMapper, never()).insert(any(CustomerServiceCaseEvent.class));
    }

    @Test
    // A26-P3-26：assignCase 加状态守卫，拒绝 RESOLVED/CLOSED 终态工单分派（对照 changeCaseStatus 已拒绝这两个状态）。
    // 此前无守卫导致终态工单仍可被分派并追加事件，与 closeCase 的"仅 RESOLVED 可关闭"、
    // resolveCase 的"仅 IN_PROGRESS/REOPENED 可解决"语义冲突。
    void assignCaseRejectsResolvedAndClosedTerminalCases() {
        for (String terminalStatus : List.of("RESOLVED", "CLOSED")) {
            CustomerServiceCase existing = caseEntity(21L, 11L, 1L, terminalStatus);
            when(caseMapper.selectById(21L)).thenReturn(existing);
            when(caseMapper.selectByIdForUpdate(21L)).thenReturn(existing);

            CustomerServiceCaseActionRequest request = new CustomerServiceCaseActionRequest();
            request.assignedUserId = 88L;

            assertThatThrownBy(() -> service.assignCase(21L, request))
                .hasMessageContaining("当前客服工单状态不可分派");
        }
        verify(caseMapper, never()).updateById(any(CustomerServiceCase.class));
        verify(eventMapper, never()).insert(any(CustomerServiceCaseEvent.class));
        verify(businessEventService, never()).record(any(BusinessEventRecordRequest.class));
    }

    @Test
    // A26-P3-27：重开后二次 resolve 若本轮 rootCause/resolutionType/replyText 为 null，
    // NOT_NULL 策略下 updateById 会丢弃 null 不落库，库内残留上一轮根因与回复，
    // 用户看到"RESOLVED + 上一轮旧回复"当作本轮结论。
    // 修复后改用 UpdateWrapper 显式 set 这 4 个字段，保证本轮 null 能覆盖上一轮旧值。
    void resolveCasePersistsNullableFieldsViaUpdateWrapperToOverwritePreviousResolve() {
        CustomerServiceCase existing = caseEntity(21L, 11L, 1L, "REOPENED");
        // 上一轮 resolve 残留的旧值
        existing.rootCauseCode = "EQUIPMENT_MAINTENANCE";
        existing.resolutionType = "MANUAL_FOLLOW_UP";
        existing.resolutionSummary = "上一轮处理总结";
        existing.customerVisibleReply = "上一轮回复";
        CustomerComplaint complaint = complaintEntity(11L, 1L, "IN_PROGRESS");
        when(caseMapper.selectById(21L)).thenReturn(existing);
        when(caseMapper.selectByIdForUpdate(21L)).thenReturn(existing);
        when(complaintMapper.selectByIdForUpdate(11L)).thenReturn(complaint);

        // 本轮 resolve 全部传 null（清空上一轮值）
        CustomerServiceCaseActionRequest request = new CustomerServiceCaseActionRequest();
        request.replyText = null;
        request.rootCauseCode = null;
        request.resolutionType = null;

        CustomerServiceCase resolved = service.resolveCase(21L, request);

        // entity 字段被置为 null（证明本轮 null 已写入 entity，UpdateWrapper 会显式 set 这个 null 落库）
        assertThat(resolved.rootCauseCode).isNull();
        assertThat(resolved.resolutionType).isNull();
        assertThat(resolved.resolutionSummary).isNull();
        assertThat(resolved.customerVisibleReply).isNull();
        // 不再走 updateById（NOT_NULL 策略会丢 null），改走 update(entity, UpdateWrapper) 显式 set
        verify(caseMapper).update(eq(existing), any(Wrapper.class));
        verify(caseMapper, never()).updateById(any(CustomerServiceCase.class));
    }

    @Test
    // A26-P3-27：moderateReview 的 RESTORE 应清空 hidden_reason，但 NOT_NULL 策略下 updateById 会丢弃 null，
    // 导致 RESTORE 后 hidden_reason 仍残留 HIDE/FLAG 阶段的理由。修复后改用 UpdateWrapper 显式 set hidden_reason。
    void moderateReviewRestoreClearsHiddenReasonViaUpdateWrapper() {
        VenueReview review = new VenueReview();
        review.id = 31L;
        review.venueId = 1L;
        review.status = "HIDDEN";
        review.hiddenReason = "包含个人联系方式";
        review.deleted = 0;
        when(reviewMapper.selectById(31L)).thenReturn(review);

        ReviewModerationRequest request = new ReviewModerationRequest();
        request.action = "RESTORE";

        VenueReview updated = service.moderateReview(31L, request);

        assertThat(updated.status).isEqualTo("PUBLISHED");
        assertThat(updated.hiddenReason).isNull();
        // 不再走 updateById（NOT_NULL 策略会丢 null），改走 update(entity, UpdateWrapper) 显式 set hidden_reason
        verify(reviewMapper).update(eq(review), any(Wrapper.class));
        verify(reviewMapper, never()).updateById(any(VenueReview.class));
    }

    @Test
    // A26-P3-28：assignCase 业务事件幂等键加唯一 eventNo（参照 resolveCase/addReply/closeCase/reopenCase 已带 eventNo）。
    // 原键只到秒级 (now.format(NO_TIME))，同一秒两次不同操作会丢失第二条审计事件。
    void assignCaseBusinessEventIdempotencyKeyIncludesEventNo() {
        CustomerServiceCase existing = caseEntity(21L, 11L, 1L, "OPEN");
        CustomerComplaint complaint = complaintEntity(11L, 1L, "SUBMITTED");
        when(caseMapper.selectById(21L)).thenReturn(existing);
        when(caseMapper.selectByIdForUpdate(21L)).thenReturn(existing);
        when(complaintMapper.selectById(11L)).thenReturn(complaint);
        when(eventMapper.insert(any(CustomerServiceCaseEvent.class))).thenAnswer(insertedId(31L));
        // F-15：分派对象 88 拥有工单场馆 1 的关系，正例路径放行
        when(venueAdminRelationMapper.countRelation(88L, 1L)).thenReturn(1);

        CustomerServiceCaseActionRequest request = new CustomerServiceCaseActionRequest();
        request.assignedUserId = 88L;
        service.assignCase(21L, request);

        ArgumentCaptor<BusinessEventRecordRequest> eventCaptor = ArgumentCaptor.forClass(BusinessEventRecordRequest.class);
        verify(businessEventService).record(eventCaptor.capture());
        BusinessEventRecordRequest event = eventCaptor.getValue();
        assertThat(event.eventType).isEqualTo(BusinessEventType.CUSTOMER_SERVICE_CASE_ASSIGNED);
        // 键含 eventNo 后缀（不再是 now.format(NO_TIME) 秒级时间戳）
        assertThat(event.idempotencyKey).startsWith("CUSTOMER_SERVICE_CASE_ASSIGNED:21:");
        assertThat(event.idempotencyKey).matches("CUSTOMER_SERVICE_CASE_ASSIGNED:21:CASE_EVT[A-Z0-9]+");
    }

    @Test
    // A26-P3-28：changeCaseStatus 业务事件幂等键加唯一 eventNo。
    void changeCaseStatusBusinessEventIdempotencyKeyIncludesEventNo() {
        CustomerServiceCase existing = caseEntity(21L, 11L, 1L, "OPEN");
        CustomerComplaint complaint = complaintEntity(11L, 1L, "SUBMITTED");
        when(caseMapper.selectById(21L)).thenReturn(existing);
        when(caseMapper.selectByIdForUpdate(21L)).thenReturn(existing);
        when(complaintMapper.selectByIdForUpdate(11L)).thenReturn(complaint);
        when(eventMapper.insert(any(CustomerServiceCaseEvent.class))).thenAnswer(insertedId(31L));

        CustomerServiceCaseActionRequest request = statusRequest("IN_PROGRESS");
        request.replyText = "已开始核查";
        service.changeCaseStatus(21L, request);

        ArgumentCaptor<BusinessEventRecordRequest> eventCaptor = ArgumentCaptor.forClass(BusinessEventRecordRequest.class);
        verify(businessEventService).record(eventCaptor.capture());
        BusinessEventRecordRequest event = eventCaptor.getValue();
        assertThat(event.eventType).isEqualTo(BusinessEventType.CUSTOMER_SERVICE_CASE_STATUS_CHANGED);
        assertThat(event.idempotencyKey).startsWith("CUSTOMER_SERVICE_CASE_STATUS_CHANGED:21:");
        assertThat(event.idempotencyKey).matches("CUSTOMER_SERVICE_CASE_STATUS_CHANGED:21:CASE_EVT[A-Z0-9]+");
    }

    @Test
    // A26-P3-29：submitComplaint 的 spaceId 加存在性校验，拒绝指向不存在场地的投诉。
    void submitComplaintRejectsNonExistentSpace() {
        ComplaintSubmitRequest request = complaintRequest();
        request.spaceId = 999L;
        when(spaceMapper.selectById(999L)).thenReturn(null);

        assertThatThrownBy(() -> service.submitComplaint(request))
            .hasMessageContaining("场地不存在");

        verify(complaintMapper, never()).insert(any(CustomerComplaint.class));
        verify(caseMapper, never()).insert(any(CustomerServiceCase.class));
        verify(eventMapper, never()).insert(any(CustomerServiceCaseEvent.class));
    }

    @Test
    // A26-P3-29：submitComplaint 的 spaceId 加归属校验，拒绝指向他馆场地的投诉。
    void submitComplaintRejectsForeignSpaceVenue() {
        ComplaintSubmitRequest request = complaintRequest();
        request.spaceId = 2L;
        // 场地存在但属于他馆
        when(spaceMapper.selectById(2L)).thenReturn(spaceEntity(2L, 99L));

        assertThatThrownBy(() -> service.submitComplaint(request))
            .hasMessageContaining("场地与场馆不匹配");

        verify(complaintMapper, never()).insert(any(CustomerComplaint.class));
        verify(caseMapper, never()).insert(any(CustomerServiceCase.class));
        verify(eventMapper, never()).insert(any(CustomerServiceCaseEvent.class));
    }

    @Test
    // A26-P3-30：note 经 Jsoup 去 HTML 后再脱敏，与 customerVisibleReply 走同一清洗路径，
    // 两个用户可见出口（REPLY_ADDED 事件 note 与 case.customerVisibleReply）内容一致。
    void noteAndCustomerVisibleReplyBothHtmlStrippedConsistently() {
        CustomerServiceCase existing = caseEntity(21L, 11L, 1L, "IN_PROGRESS");
        CustomerComplaint complaint = complaintEntity(11L, 1L, "IN_PROGRESS");
        complaint.userId = 88L;
        when(caseMapper.selectById(21L)).thenReturn(existing);
        when(caseMapper.selectByIdForUpdate(21L)).thenReturn(existing);
        when(complaintMapper.selectById(11L)).thenReturn(complaint);
        when(eventMapper.insert(any(CustomerServiceCaseEvent.class))).thenAnswer(insertedId(31L));

        CustomerServiceCaseActionRequest request = new CustomerServiceCaseActionRequest();
        request.replyText = "<p>已处理，请联系 <strong>13800000000</strong> 或 user@example.test</p>";
        request.internalNote = "内部备注";

        service.addReply(21L, request);

        ArgumentCaptor<CustomerServiceCaseEvent> eventCaptor = ArgumentCaptor.forClass(CustomerServiceCaseEvent.class);
        verify(eventMapper).insert(eventCaptor.capture());
        CustomerServiceCaseEvent event = eventCaptor.getValue();
        // 两个出口内容完全一致：都去 HTML + 脱敏
        assertThat(event.noteText).isEqualTo(existing.customerVisibleReply);
        assertThat(event.noteSanitized).isEqualTo(existing.customerVisibleReply);
        assertThat(event.noteText)
            .isEqualTo("已处理，请联系 138****0000 或 [邮箱已隐藏]");
        assertThat(event.noteText)
            .doesNotContain("<p>", "<strong>", "13800000000", "user@example.test");
    }

    private static CustomerServiceCaseActionRequest statusRequest(String status) {
        CustomerServiceCaseActionRequest request = new CustomerServiceCaseActionRequest();
        request.status = status;
        return request;
    }

    private static CustomerServiceCaseActionRequest resolveRequest(String replyText) {
        CustomerServiceCaseActionRequest request = new CustomerServiceCaseActionRequest();
        request.resolutionType = "MANUAL_FOLLOW_UP";
        request.rootCauseCode = "EQUIPMENT_MAINTENANCE";
        request.replyText = replyText;
        return request;
    }

    private static CustomerServiceCaseActionRequest closeRequest(String closeReason) {
        CustomerServiceCaseActionRequest request = new CustomerServiceCaseActionRequest();
        request.closeReason = closeReason;
        return request;
    }

    private static CustomerServiceCaseActionRequest reopenRequest(String reopenReason) {
        CustomerServiceCaseActionRequest request = new CustomerServiceCaseActionRequest();
        request.reopenReason = reopenReason;
        return request;
    }

    private static ComplaintSubmitRequest complaintRequest() {
        ComplaintSubmitRequest request = new ComplaintSubmitRequest();
        request.venueId = 1L;
        request.spaceId = 2L;
        request.complaintType = "EQUIPMENT";
        request.severity = "HIGH";
        request.title = "灯光影响体验";
        request.contentText = "灯光故障";
        return request;
    }

    private static Reservation reservationEntity(Long id, Long userId, Long venueId, Long spaceId, String status) {
        Reservation reservation = new Reservation();
        reservation.id = id;
        reservation.userId = userId;
        reservation.venueId = venueId;
        reservation.spaceId = spaceId;
        reservation.status = status;
        reservation.deleted = 0;
        return reservation;
    }

    private static BizOrder orderEntity(Long id, Long userId, Long venueId, Long reservationId) {
        BizOrder order = new BizOrder();
        order.id = id;
        order.userId = userId;
        order.venueId = venueId;
        order.reservationId = reservationId;
        return order;
    }

    private static VenueSpace spaceEntity(Long id, Long venueId) {
        VenueSpace space = new VenueSpace();
        space.id = id;
        space.venueId = venueId;
        space.deleted = 0;
        return space;
    }

    private static List<String> idempotencyKeys(List<BusinessEventRecordRequest> events, BusinessEventType eventType) {
        return events.stream()
            .filter(event -> event.eventType == eventType)
            .map(event -> event.idempotencyKey)
            .toList();
    }

    private static CustomerServiceCase caseEntity(Long id, Long complaintId, Long venueId, String status) {
        CustomerServiceCase item = new CustomerServiceCase();
        item.id = id;
        item.caseNo = "CASE202607060001";
        item.complaintId = complaintId;
        item.venueId = venueId;
        item.status = status;
        item.requiresRefundReview = 0;
        item.requiresCouponReview = 0;
        item.requiresSiteFollowUp = 0;
        item.deleted = 0;
        return item;
    }

    private static CustomerServiceCaseEvent caseEvent(Long id, String eventType, LocalDateTime occurredAt) {
        CustomerServiceCaseEvent event = new CustomerServiceCaseEvent();
        event.id = id;
        event.caseId = 21L;
        event.complaintId = 11L;
        event.eventType = eventType;
        event.occurredAt = occurredAt;
        event.createTime = occurredAt;
        return event;
    }

    private static CustomerComplaint complaintEntity(Long id, Long venueId, String status) {
        CustomerComplaint item = new CustomerComplaint();
        item.id = id;
        item.complaintNo = "COMP202607060001";
        item.userId = 7L;
        item.venueId = venueId;
        item.status = status;
        item.deleted = 0;
        return item;
    }

    private static VenueReview reviewEntity(Long id, Long venueId, Long userId, String status, Integer overallRating) {
        VenueReview item = new VenueReview();
        item.id = id;
        item.reviewNo = "REVIEW202607060001";
        item.venueId = venueId;
        item.userId = userId;
        item.status = status;
        item.overallRating = overallRating;
        item.deleted = 0;
        return item;
    }

    private static List<CustomerComplaint> complaints(int count) {
        return IntStream.range(0, count)
            .mapToObj(index -> complaintEntity(100L + index, 1L, "SUBMITTED"))
            .toList();
    }

    private static List<CustomerServiceCase> cases(int count) {
        return IntStream.range(0, count)
            .mapToObj(index -> caseEntity(200L + index, 100L + index, 1L, "OPEN"))
            .toList();
    }

    private static List<VenueReview> reviews(int count) {
        return IntStream.range(0, count)
            .mapToObj(index -> reviewEntity(300L + index, 1L, 700L + index, "PUBLISHED", 5))
            .toList();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static <T> ArgumentCaptor<Page<T>> pageCaptor() {
        return ArgumentCaptor.forClass((Class) Page.class);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static <T> ArgumentCaptor<Wrapper<T>> wrapperCaptor() {
        return ArgumentCaptor.forClass((Class) Wrapper.class);
    }

    private static void authenticate(CurrentUser user) {
        SecurityContextHolder.getContext()
            .setAuthentication(new UsernamePasswordAuthenticationToken(user, null, List.of()));
    }

    private static Answer<Integer> insertedId(Long id) {
        return invocation -> {
            Object entity = invocation.getArgument(0);
            Field idField = entity.getClass().getField("id");
            idField.set(entity, id);
            return 1;
        };
    }
}
