package com.example.venue.notice;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.venue.common.CurrentUser;
import com.example.venue.common.PageQuery;
import com.example.venue.common.PageResult;
import com.example.venue.notice.dto.NoticeRequest;
import com.example.venue.notice.dto.PublicNoticeResponse;
import com.example.venue.notice.entity.Notice;
import com.example.venue.notice.entity.NoticeDeliveryLog;
import com.example.venue.notice.entity.NoticeEmailDelivery;
import com.example.venue.notice.entity.NoticeMessage;
import com.example.venue.notice.entity.NoticeOutbox;
import com.example.venue.notice.email.EmailIdempotencyKeyFactory;
import com.example.venue.notice.email.EmailRecipientEligibility;
import com.example.venue.notice.email.EmailSnapshotService;
import com.example.venue.notice.email.EmailTemplateRegistry;
import com.example.venue.notice.mapper.NoticeMapper;
import com.example.venue.notice.mapper.NoticeDeliveryLogMapper;
import com.example.venue.notice.mapper.NoticeEmailDeliveryMapper;
import com.example.venue.notice.mapper.NoticeMessageMapper;
import com.example.venue.notice.mapper.NoticeOutboxMapper;
import com.example.venue.notice.routing.DefaultNotificationRoutingPolicy;
import com.example.venue.notice.routing.NotificationEvent;
import com.example.venue.notice.routing.NotificationRecipientKind;
import com.example.venue.notice.service.NoticeDeliveryService;
import com.example.venue.notice.service.NoticeReceiptService;
import com.example.venue.notice.service.NoticeService;
import com.example.venue.venue.service.VenueAccessService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.same;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NoticeServiceTest {
    @Mock
    private NoticeMapper noticeMapper;
    @Mock
    private NoticeMessageMapper messageMapper;
    @Mock
    private NoticeOutboxMapper outboxMapper;
    @Mock
    private NoticeEmailDeliveryMapper emailDeliveryMapper;
    @Mock
    private NoticeDeliveryLogMapper deliveryLogMapper;
    @Mock
    private VenueAccessService venueAccessService;
    @Mock
    private NoticeDeliveryService deliveryService;
    @Mock
    private NoticeReceiptService receiptService;

    private NoticeService noticeService;

    @BeforeEach
    void setUp() {
        noticeService = new NoticeService(noticeMapper, messageMapper, outboxMapper,
            venueAccessService, deliveryService, receiptService, deliveryLogMapper);
        authenticate(new CurrentUser(7L, "tester", Set.of("USER"), Set.of()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void createMessagePersistsPendingMessageAndEnqueuesOneInAppRoute() {
        when(messageMapper.insert(any(NoticeMessage.class))).thenAnswer(invocation -> {
            NoticeMessage message = invocation.getArgument(0);
            message.id = 41L;
            return 1;
        });

        NoticeMessage message = noticeService.createMessage(7L, "预约已提交", "<b>请等待</b>", "RESERVATION", 99L);

        assertThat(message.title).isEqualTo("预约已提交");
        assertThat(message.content).isEqualTo("请等待");
        assertThat(message.providerEnv).isEqualTo("DEV");
        assertThat(message.providerName).isEqualTo("IN_APP_MESSAGE");
        assertThat(message.deliveryStatus).isEqualTo("PENDING");
        assertThat(message.readFlag).isEqualTo(0);

        ArgumentCaptor<NoticeMessage> messageCaptor = ArgumentCaptor.forClass(NoticeMessage.class);
        verify(messageMapper).insert(messageCaptor.capture());
        assertThat(messageCaptor.getValue().userId).isEqualTo(7L);
        assertThat(messageCaptor.getValue().businessId).isEqualTo(99L);

        ArgumentCaptor<NoticeOutbox> outboxCaptor = ArgumentCaptor.forClass(NoticeOutbox.class);
        verify(outboxMapper).insert(outboxCaptor.capture());
        NoticeOutbox outbox = outboxCaptor.getValue();
        assertThat(outbox.batchId).isNull();
        assertThat(outbox.audienceId).isNull();
        assertThat(outbox.noticeId).isNull();
        assertThat(outbox.status).isEqualTo("PENDING");
        assertThat(outbox.attemptCount).isZero();
        assertThat(outbox.idempotencyKey).isEqualTo("NOTICE_MESSAGE:41");
        assertThat(outbox.metadataJson).contains("notice.in_app.dev");
    }

    @Test
    void unreadActionsUseCurrentUser() {
        NoticeMessage unreadMessage = new NoticeMessage();
        unreadMessage.id = 12L;
        unreadMessage.userId = 7L;
        List<NoticeMessage> unreadMessages = List.of(unreadMessage);
        when(messageMapper.unreadCount(7L)).thenReturn(3L);
        when(messageMapper.selectList(any())).thenReturn(unreadMessages);
        when(messageMapper.markAllRead(7L)).thenReturn(2);
        when(messageMapper.markRead(12L, 7L)).thenReturn(1);

        assertThat(noticeService.unreadCount()).isEqualTo(3L);
        noticeService.markAllRead();
        noticeService.markRead(12L);

        verify(messageMapper).unreadCount(7L);
        verify(messageMapper).markAllRead(7L);
        verify(messageMapper).markRead(12L, 7L);
        verify(receiptService).recordReads(same(unreadMessages), eq(7L), eq("MESSAGE_CENTER"));
        verify(receiptService).recordRead(12L, 7L, "MESSAGE_CENTER");
    }

    @Test
    // A26-P2-18：markAllRead 的快照查询必须用 FOR UPDATE 触发当前读 + Next-Key Lock，
    // 保证 SELECT 与随后的 markAllRead UPDATE 看到同一组未读消息；否则 RR 下普通 SELECT 读事务快照，
    // 期间并发送达的消息会被 UPDATE 置 read_flag=1 却不在 unread 列表中，recordReads 不为它写回执，
    // 受众快照永久停留 UNREAD、readCount/readRate 永久少计。
    void markAllReadLocksUnreadSnapshotForUpdateToAlignWithBulkUpdate() {
        NoticeMessage unreadMessage = new NoticeMessage();
        unreadMessage.id = 12L;
        unreadMessage.userId = 7L;
        when(messageMapper.selectList(any())).thenReturn(List.of(unreadMessage));
        when(messageMapper.markAllRead(7L)).thenReturn(1);

        noticeService.markAllRead("MESSAGE_CENTER");

        @SuppressWarnings("rawtypes")
        ArgumentCaptor<QueryWrapper> wrapperCaptor = ArgumentCaptor.forClass(QueryWrapper.class);
        verify(messageMapper).selectList(wrapperCaptor.capture());
        QueryWrapper<?> wrapper = wrapperCaptor.getValue();
        assertThat(wrapper.getSqlSegment()).contains("user_id", "read_flag");
        // QueryWrapper.last("for update") 通过 lastSql 字段附加在 SQL 末尾，getCustomSqlSegment 才能看到
        String fullSql = wrapper.getCustomSqlSegment();
        assertThat(fullSql).endsWith("for update");
        verify(messageMapper).markAllRead(7L);
        verify(receiptService).recordReads(any(), eq(7L), eq("MESSAGE_CENTER"));
    }

    @Test
    void publishDelegatesToDeliveryServiceForLegacyEndpointCompatibility() {
        NoticeRequest request = new NoticeRequest();
        request.title = "公告";
        request.content = "内容";
        request.type = "GLOBAL";
        request.status = "PUBLISHED";
        Notice expected = new Notice();
        expected.id = 55L;
        when(deliveryService.publish(request)).thenReturn(expected);

        Notice notice = noticeService.publish(request);

        assertThat(notice).isSameAs(expected);
        verify(deliveryService).publish(same(request));
        verify(emailDeliveryMapper, never()).insert(any(NoticeEmailDelivery.class));
    }

    @Test
    void publicNoticesExposeOnlyGlobalAllUserAnnouncementsThroughTheMinimalDto() throws Exception {
        Notice publicNotice = new Notice();
        publicNotice.id = 51L;
        publicNotice.title = "暑期开放";
        publicNotice.content = "周末延长营业";
        publicNotice.type = "GLOBAL";
        publicNotice.status = "PUBLISHED";
        publicNotice.targetRuleJson = "{\"targetScope\":\"ALL_USERS\"}";
        publicNotice.publisherId = 7L;
        publicNotice.deliveryBatchId = 61L;
        publicNotice.experimentId = 11L;
        Notice targetedNotice = new Notice();
        targetedNotice.id = 52L;
        targetedNotice.title = "会员专属";
        targetedNotice.content = "仅限指定会员";
        targetedNotice.type = "GLOBAL";
        targetedNotice.status = "PUBLISHED";
        targetedNotice.targetRuleJson = "{\"targetScope\":\"MEMBER_LEVEL\",\"memberLevelId\":3}";
        targetedNotice.publisherId = 8L;
        Page<Notice> page = new Page<>(1, 10);
        page.setRecords(List.of(publicNotice, targetedNotice));
        page.setTotal(1);
        when(noticeMapper.selectPage(any(), any())).thenReturn(page);

        PageResult<PublicNoticeResponse> response = noticeService.publicNotices(new PageQuery());

        assertThat(response.getRecords()).hasSize(1);
        PublicNoticeResponse notice = response.getRecords().get(0);
        assertThat(notice.id).isEqualTo(51L);
        assertThat(notice.title).isEqualTo("暑期开放");
        assertThat(notice.content).isEqualTo("周末延长营业");
        String payload = new ObjectMapper().writeValueAsString(notice);
        assertThat(payload)
            .doesNotContain("targetRuleJson", "publisherId", "deliveryBatchId", "experimentId", "targetVenueId", "userId");

        @SuppressWarnings("rawtypes")
        ArgumentCaptor<QueryWrapper> wrapperCaptor = ArgumentCaptor.forClass(QueryWrapper.class);
        verify(noticeMapper).selectPage(any(), wrapperCaptor.capture());
        String where = wrapperCaptor.getValue().getSqlSegment();
        assertThat(where).contains("deleted", "status", "type", "target_venue_id", "target_rule_json");
        assertThat(wrapperCaptor.getValue().getParamNameValuePairs().values()).contains("ALL_USERS");
    }

    @Test
    void noEligibleVenueAdminEvidencePersistsOneSafeRecipientlessDeliveryLogOnly() {
        when(deliveryLogMapper.insert(any(NoticeDeliveryLog.class))).thenReturn(1);

        noticeService.recordNoEligibleVenueAdminExclusion(
            "RESERVATION_REVIEW_REQUIRED", "RESERVATION_REVIEW_REQUIRED:42", "RESERVATION", 42L);

        ArgumentCaptor<NoticeDeliveryLog> logCaptor = ArgumentCaptor.forClass(NoticeDeliveryLog.class);
        verify(deliveryLogMapper).insert(logCaptor.capture());
        NoticeDeliveryLog log = logCaptor.getValue();
        assertThat(log.messageId).isNull();
        assertThat(log.channel).isEqualTo("EMAIL");
        assertThat(log.target).isNull();
        assertThat(log.status).isEqualTo("EXCLUDED");
        assertThat(log.errorMessage).isNull();
        assertThat(log.sourceType).isEqualTo("BUSINESS_NOTIFICATION");
        assertThat(log.confidence).isNull();
        assertThat(log.providerEnv).isEqualTo("SANDBOX");
        assertThat(log.providerName).isEqualTo("SMTP_EMAIL");
        assertThat(log.externalRef).isNull();
        assertThat(log.idempotencyKey).startsWith("EMAIL_EXCLUDED:").hasSize(79);
        assertThat(log.requestSummaryJson).isEqualTo(
            "{\"eventType\":\"RESERVATION_REVIEW_REQUIRED\","
                + "\"eventKey\":\"RESERVATION_REVIEW_REQUIRED:42\","
                + "\"businessType\":\"RESERVATION\",\"businessId\":42,"
                + "\"recipientKind\":\"VENUE_ADMIN\"}");
        assertThat(log.responseSummaryJson).isEqualTo("{}");
        assertThat(log.failureCode).isEqualTo("NO_ELIGIBLE_VENUE_ADMIN");
        assertThat(log.configVersion).isEqualTo("SMTP_SANDBOX_V1");
        assertThat(log.durationMs).isZero();
        assertThat(log.metadataJson)
            .isEqualTo("{\"providerKey\":\"notice.smtp.sandbox\",\"source\":\"BUSINESS_NOTIFICATION\"}");
        assertThat(log.createTime).isNotNull();
        verify(messageMapper, never()).insert(any(NoticeMessage.class));
        verify(outboxMapper, never()).insert(any(NoticeOutbox.class));
        verify(emailDeliveryMapper, never()).insert(any(NoticeEmailDelivery.class));
    }

    @Test
    void noEligibleVenueAdminEvidenceRejectsAnyTupleOutsideTheNamedReviewEvents() {
        assertThatThrownBy(() -> noticeService.recordNoEligibleVenueAdminExclusion(
            "NOTICE_PUBLISHED", "NOTICE_PUBLISHED:42", "NOTICE", 42L))
            .hasMessageContaining("NOTICE_NO_RECIPIENT_EVIDENCE_INVALID");
        assertThatThrownBy(() -> noticeService.recordNoEligibleVenueAdminExclusion(
            "RESERVATION_REVIEW_REQUIRED", "RESERVATION_REVIEW_REQUIRED:41", "RESERVATION", 42L))
            .hasMessageContaining("NOTICE_NO_RECIPIENT_EVIDENCE_INVALID");
        assertThatThrownBy(() -> noticeService.recordNoEligibleVenueAdminExclusion(
            "REFUND_REVIEW_REQUIRED", "REFUND_REVIEW_REQUIRED:42", "REFUND", 0L))
            .hasMessageContaining("NOTICE_NO_RECIPIENT_EVIDENCE_INVALID");
        verify(deliveryLogMapper, never()).insert(any(NoticeDeliveryLog.class));
    }

    @Test
    void eligibleBusinessEventCreatesOneInAppAndOneEmailRouteWithAStableSnapshot() {
        stubGeneratedIds();
        when(emailDeliveryMapper.insert(any(NoticeEmailDelivery.class))).thenAnswer(invocation -> {
            NoticeEmailDelivery snapshot = invocation.getArgument(0);
            snapshot.id = 201L;
            return 1;
        });
        NoticeService businessService = businessService(true, eligibleRecipient());

        businessService.createBusinessNotification(
            approvedReservationEvent(7L, "RESERVATION_APPROVED:42"));

        ArgumentCaptor<NoticeMessage> messageCaptor = ArgumentCaptor.forClass(NoticeMessage.class);
        verify(messageMapper).insert(messageCaptor.capture());
        assertThat(messageCaptor.getValue().content).isEqualTo("预约 42 已审核通过");

        ArgumentCaptor<NoticeOutbox> outboxCaptor = ArgumentCaptor.forClass(NoticeOutbox.class);
        verify(outboxMapper, times(2)).insert(outboxCaptor.capture());
        assertThat(outboxCaptor.getAllValues()).extracting(item -> item.channel)
            .containsExactlyInAnyOrder("IN_APP", "EMAIL");
        NoticeOutbox emailOutbox = outboxCaptor.getAllValues().stream()
            .filter(item -> "EMAIL".equals(item.channel))
            .findFirst()
            .orElseThrow();
        assertThat(emailOutbox.eventType).isEqualTo("RESERVATION_APPROVED");
        assertThat(emailOutbox.eventKey).isEqualTo("RESERVATION_APPROVED:42");
        assertThat(emailOutbox.recipientKind).isEqualTo("USER");
        assertThat(emailOutbox.providerKey).isEqualTo("notice.smtp.sandbox");
        assertThat(emailOutbox.templateKey).isEqualTo("reservation.approved");
        assertThat(emailOutbox.templateVersion).isEqualTo("v1");
        assertThat(emailOutbox.idempotencyKey).startsWith("EMAIL:").hasSize(70);
        assertThat(emailOutbox.metadataJson)
            .isEqualTo("{\"providerKey\":\"notice.smtp.sandbox\",\"source\":\"BUSINESS_NOTIFICATION\"}");

        ArgumentCaptor<NoticeEmailDelivery> snapshotCaptor =
            ArgumentCaptor.forClass(NoticeEmailDelivery.class);
        verify(emailDeliveryMapper).insert(snapshotCaptor.capture());
        NoticeEmailDelivery snapshot = snapshotCaptor.getValue();
        assertThat(snapshot.outboxId).isEqualTo(emailOutbox.id);
        assertThat(snapshot.recipientAddress).isEqualTo("alice@example.test");
        assertThat(snapshot.recipientMasked).isEqualTo("a***e@e***e.test");
        assertThat(snapshot.subjectSnapshot).isEqualTo("预约 42 已审核通过");
        assertThat(snapshot.bodyTextSnapshot).contains("预约 42");
        assertThat(snapshot.messageIdHeader).isEqualTo("<" + sha256(
            emailOutbox.providerKey + "\n" + emailOutbox.idempotencyKey) + "@venue.local>");
        assertThat(snapshot.acceptanceStatus).isEqualTo("PENDING");
        assertThat(snapshot.downstreamStatus).isEqualTo("NOT_OBSERVED");

        assertThat(outboxCaptor.getAllValues()).allSatisfy(outbox -> assertThat(
            String.join("|", nullToEmpty(outbox.requestSummaryJson), nullToEmpty(outbox.metadataJson),
                nullToEmpty(outbox.eventKey), nullToEmpty(outbox.idempotencyKey)))
            .doesNotContain("alice@example.test", snapshot.subjectSnapshot, snapshot.bodyTextSnapshot));
    }

    @Test
    void replayOfTheSameEmailTupleKeepsOneEmailOutboxAndDoesNotRewriteItsSnapshot() {
        stubGeneratedIds();
        NoticeOutbox existing = businessEmailOutbox(301L);
        when(outboxMapper.findEmailRoute(
            "RESERVATION_APPROVED", "RESERVATION_APPROVED:42", 7L,
            "notice.smtp.sandbox", "SMTP_SANDBOX_V1")).thenReturn(existing);

        businessService(true, eligibleRecipient()).createBusinessNotification(
            approvedReservationEvent(7L, "RESERVATION_APPROVED:42"));

        verify(emailDeliveryMapper, never()).updateById(any(NoticeEmailDelivery.class));
        verify(emailDeliveryMapper, never()).insert(any(NoticeEmailDelivery.class));
        ArgumentCaptor<NoticeOutbox> outboxCaptor = ArgumentCaptor.forClass(NoticeOutbox.class);
        verify(outboxMapper).insert(outboxCaptor.capture());
        assertThat(outboxCaptor.getValue().channel).isEqualTo("IN_APP");
    }

    @Test
    void replayReusesTheExistingInAppMessageAndCanAddTheNewlyEligibleEmailRoute() {
        NoticeMessage existingMessage = new NoticeMessage();
        existingMessage.id = 41L;
        existingMessage.userId = 7L;
        existingMessage.type = "RESERVATION";
        existingMessage.businessId = 42L;
        NoticeOutbox existingInApp = new NoticeOutbox();
        existingInApp.id = 81L;
        existingInApp.userId = 7L;
        existingInApp.eventType = "RESERVATION_APPROVED";
        existingInApp.eventKey = "RESERVATION_APPROVED:42";
        existingInApp.recipientKind = "USER";
        existingInApp.channel = "IN_APP";
        existingInApp.providerEnv = "DEV";
        existingInApp.providerName = "IN_APP_MESSAGE";
        existingInApp.providerKey = "notice.in_app.dev";
        existingInApp.configVersion = "IN_APP_MESSAGE_V1";
        existingInApp.idempotencyKey = inAppKey("RESERVATION_APPROVED", "RESERVATION_APPROVED:42", 7L);
        existingInApp.requestSummaryJson = "{\"messageId\":41,\"userId\":7}";
        when(outboxMapper.findByIdempotency(
            "DEV", "IN_APP_MESSAGE", existingInApp.idempotencyKey)).thenReturn(existingInApp);
        when(messageMapper.selectById(41L)).thenReturn(existingMessage);
        when(outboxMapper.insert(any(NoticeOutbox.class))).thenAnswer(invocation -> {
            NoticeOutbox outbox = invocation.getArgument(0);
            outbox.id = 101L;
            return 1;
        });
        when(emailDeliveryMapper.insert(any(NoticeEmailDelivery.class))).thenAnswer(invocation -> {
            NoticeEmailDelivery snapshot = invocation.getArgument(0);
            snapshot.id = 201L;
            return 1;
        });

        NoticeMessage result = businessService(true, eligibleRecipient()).createBusinessNotification(
            approvedReservationEvent(7L, "RESERVATION_APPROVED:42"));

        assertThat(result).isSameAs(existingMessage);
        verify(messageMapper, never()).insert(any(NoticeMessage.class));
        ArgumentCaptor<NoticeOutbox> outboxCaptor = ArgumentCaptor.forClass(NoticeOutbox.class);
        verify(outboxMapper).insert(outboxCaptor.capture());
        assertThat(outboxCaptor.getValue().channel).isEqualTo("EMAIL");
        verify(emailDeliveryMapper).insert(any(NoticeEmailDelivery.class));
    }

    @Test
    void concurrentInAppReplayDeletesItsCandidateMessageAndReturnsTheWinningRoute() {
        when(messageMapper.insert(any(NoticeMessage.class))).thenAnswer(invocation -> {
            NoticeMessage message = invocation.getArgument(0);
            message.id = 41L;
            return 1;
        });
        NoticeMessage winningMessage = businessMessage(88L);
        NoticeOutbox winningInApp = businessInAppOutbox(81L, 88L);
        when(outboxMapper.findByIdempotency(
            "DEV", "IN_APP_MESSAGE", winningInApp.idempotencyKey)).thenReturn(null);
        when(outboxMapper.findByIdempotencyForUpdate(
            "DEV", "IN_APP_MESSAGE", winningInApp.idempotencyKey)).thenReturn(winningInApp);
        when(messageMapper.findByIdForUpdate(88L)).thenReturn(winningMessage);
        when(messageMapper.deleteById(41L)).thenReturn(1);
        when(outboxMapper.insert(any(NoticeOutbox.class)))
            .thenThrow(new DuplicateKeyException("uk_notice_outbox_idempotency"));

        NoticeMessage result = businessService(false, eligibleRecipient()).createBusinessNotification(
            approvedReservationEvent(7L, "RESERVATION_APPROVED:42"));

        assertThat(result).isSameAs(winningMessage);
        verify(messageMapper).deleteById(41L);
        verify(emailDeliveryMapper, never()).insert(any(NoticeEmailDelivery.class));
    }

    @Test
    void concurrentEmailReplayLocksAndReturnsTheWinningRouteWithoutRewritingSnapshot() {
        NoticeMessage winningMessage = businessMessage(88L);
        NoticeOutbox winningInApp = businessInAppOutbox(81L, 88L);
        NoticeOutbox winningEmail = businessEmailOutbox(301L);
        when(outboxMapper.findByIdempotency(
            "DEV", "IN_APP_MESSAGE", winningInApp.idempotencyKey)).thenReturn(winningInApp);
        when(messageMapper.selectById(88L)).thenReturn(winningMessage);
        when(outboxMapper.findEmailRoute(
            "RESERVATION_APPROVED", "RESERVATION_APPROVED:42", 7L,
            "notice.smtp.sandbox", "SMTP_SANDBOX_V1")).thenReturn(null);
        when(outboxMapper.findEmailRouteForUpdate(
            "RESERVATION_APPROVED", "RESERVATION_APPROVED:42", 7L,
            "notice.smtp.sandbox", "SMTP_SANDBOX_V1")).thenReturn(winningEmail);
        when(outboxMapper.insert(any(NoticeOutbox.class)))
            .thenThrow(new DuplicateKeyException("uk_notice_outbox_email_route"));

        NoticeMessage result = businessService(true, eligibleRecipient()).createBusinessNotification(
            approvedReservationEvent(7L, "RESERVATION_APPROVED:42"));

        assertThat(result).isSameAs(winningMessage);
        verify(emailDeliveryMapper, never()).insert(any(NoticeEmailDelivery.class));
        verify(emailDeliveryMapper, never()).updateById(any(NoticeEmailDelivery.class));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("inAppOnlyBusinessCases")
    void ineligibleOrExcludedBusinessEventCreatesOnlyInAppWithoutFailedEmailOutbox(
        String ignoredCaseName,
        boolean smtpEnabled,
        EmailRecipientEligibility recipient,
        NotificationEvent event
    ) {
        stubGeneratedIds();

        businessService(smtpEnabled, recipient).createBusinessNotification(event);

        ArgumentCaptor<NoticeOutbox> outboxCaptor = ArgumentCaptor.forClass(NoticeOutbox.class);
        verify(outboxMapper).insert(outboxCaptor.capture());
        assertThat(outboxCaptor.getValue().channel).isEqualTo("IN_APP");
        assertThat(outboxCaptor.getValue().status).isEqualTo("PENDING");
        verify(emailDeliveryMapper, never()).insert(any(NoticeEmailDelivery.class));
    }

    private void authenticate(CurrentUser user) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user, null, List.of()));
    }

    private NoticeService businessService(boolean smtpEnabled, EmailRecipientEligibility recipient) {
        EmailTemplateRegistry registry = new EmailTemplateRegistry();
        return new NoticeService(
            noticeMapper, messageMapper, outboxMapper, venueAccessService, deliveryService, receiptService,
            (userId, recipientKind) -> recipient,
            new DefaultNotificationRoutingPolicy(smtpEnabled, registry, registry),
            registry, registry, new EmailIdempotencyKeyFactory(),
            new EmailSnapshotService(emailDeliveryMapper));
    }

    private void stubGeneratedIds() {
        AtomicLong messageIds = new AtomicLong(40L);
        AtomicLong outboxIds = new AtomicLong(100L);
        when(messageMapper.insert(any(NoticeMessage.class))).thenAnswer(invocation -> {
            NoticeMessage message = invocation.getArgument(0);
            message.id = messageIds.incrementAndGet();
            return 1;
        });
        when(outboxMapper.insert(any(NoticeOutbox.class))).thenAnswer(invocation -> {
            NoticeOutbox outbox = invocation.getArgument(0);
            outbox.id = outboxIds.incrementAndGet();
            return 1;
        });
    }

    private NotificationEvent approvedReservationEvent(Long userId, String eventKey) {
        return new NotificationEvent(
            "RESERVATION_APPROVED", eventKey, userId, NotificationRecipientKind.USER,
            "预约审核结果", "<b>预约 42 已审核通过</b>", "RESERVATION", 42L,
            Map.of("reservationId", "42"));
    }

    private EmailRecipientEligibility eligibleRecipient() {
        return new EmailRecipientEligibility(
            7L, NotificationRecipientKind.USER, true, true,
            "alice@example.test", "a***e@e***e.test", true, null);
    }

    private NoticeMessage businessMessage(Long id) {
        NoticeMessage message = new NoticeMessage();
        message.id = id;
        message.userId = 7L;
        message.type = "RESERVATION";
        message.businessId = 42L;
        return message;
    }

    private NoticeOutbox businessInAppOutbox(Long id, Long messageId) {
        NoticeOutbox outbox = new NoticeOutbox();
        outbox.id = id;
        outbox.userId = 7L;
        outbox.eventType = "RESERVATION_APPROVED";
        outbox.eventKey = "RESERVATION_APPROVED:42";
        outbox.recipientKind = "USER";
        outbox.channel = "IN_APP";
        outbox.providerEnv = "DEV";
        outbox.providerName = "IN_APP_MESSAGE";
        outbox.providerKey = "notice.in_app.dev";
        outbox.configVersion = "IN_APP_MESSAGE_V1";
        outbox.idempotencyKey = inAppKey(
            "RESERVATION_APPROVED", "RESERVATION_APPROVED:42", 7L);
        outbox.requestSummaryJson = "{\"messageId\":" + messageId + ",\"userId\":7}";
        return outbox;
    }

    private NoticeOutbox businessEmailOutbox(Long id) {
        NoticeOutbox outbox = new NoticeOutbox();
        outbox.id = id;
        outbox.userId = 7L;
        outbox.eventType = "RESERVATION_APPROVED";
        outbox.eventKey = "RESERVATION_APPROVED:42";
        outbox.recipientKind = "USER";
        outbox.channel = "EMAIL";
        outbox.providerEnv = "SANDBOX";
        outbox.providerName = "SMTP_EMAIL";
        outbox.providerKey = "notice.smtp.sandbox";
        outbox.configVersion = "SMTP_SANDBOX_V1";
        outbox.templateKey = "reservation.approved";
        outbox.templateVersion = "v1";
        outbox.idempotencyKey = new EmailIdempotencyKeyFactory().forRoute(
            outbox.eventType, outbox.eventKey, outbox.userId,
            outbox.providerKey, outbox.configVersion);
        return outbox;
    }

    private static Stream<Arguments> inAppOnlyBusinessCases() {
        NotificationEvent approved = new NotificationEvent(
            "RESERVATION_APPROVED", "RESERVATION_APPROVED:42", 7L,
            NotificationRecipientKind.USER, "审核结果", "站内正文", "RESERVATION", 42L,
            Map.of("reservationId", "42"));
        NotificationEvent wrongKind = new NotificationEvent(
            "RESERVATION_APPROVED", "RESERVATION_APPROVED:42", 7L,
            NotificationRecipientKind.VENUE_ADMIN, "审核结果", "站内正文", "RESERVATION", 42L,
            Map.of("reservationId", "42"));
        NotificationEvent excluded = new NotificationEvent(
            "NOTICE_PUBLISHED", "NOTICE_PUBLISHED:9", 7L,
            NotificationRecipientKind.USER, "公告", "公告正文", "NOTICE", 9L, Map.of());
        return Stream.of(
            Arguments.of("SMTP disabled", false, eligibleStatic(), approved),
            Arguments.of("missing preference", true, new EmailRecipientEligibility(
                7L, NotificationRecipientKind.USER, true, false, null, null, false,
                "EMAIL_PREFERENCE_DISABLED"), approved),
            Arguments.of("invalid recipient", true, new EmailRecipientEligibility(
                7L, NotificationRecipientKind.USER, true, false, null, null, true,
                "EMAIL_ADDRESS_INVALID"), approved),
            Arguments.of("recipient kind mismatch", true, eligibleStatic(), wrongKind),
            Arguments.of("event not whitelisted", true, eligibleStatic(), excluded));
    }

    private static EmailRecipientEligibility eligibleStatic() {
        return new EmailRecipientEligibility(
            7L, NotificationRecipientKind.USER, true, true,
            "alice@example.test", "a***e@e***e.test", true, null);
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new AssertionError(exception);
        }
    }

    private String inAppKey(String eventType, String eventKey, Long userId) {
        return "IN_APP:" + sha256(eventType + "\n" + eventKey + "\n" + userId);
    }
}
