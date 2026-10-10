package com.example.venue.notice;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.example.venue.business.dto.BusinessEventRecordRequest;
import com.example.venue.business.enums.BusinessEventType;
import com.example.venue.business.service.BusinessEventService;
import com.example.venue.common.BusinessException;
import com.example.venue.common.CurrentUser;
import com.example.venue.notice.dto.NoticeRequest;
import com.example.venue.notice.entity.Notice;
import com.example.venue.notice.entity.NoticeAudienceSnapshot;
import com.example.venue.notice.entity.NoticeDeliveryBatch;
import com.example.venue.notice.entity.NoticeDeliveryLog;
import com.example.venue.notice.entity.NoticeExperiment;
import com.example.venue.notice.entity.NoticeExperimentVariant;
import com.example.venue.notice.entity.NoticeMessage;
import com.example.venue.notice.entity.NoticeOutbox;
import com.example.venue.notice.mapper.NoticeAudienceSnapshotMapper;
import com.example.venue.notice.mapper.NoticeDeliveryBatchMapper;
import com.example.venue.notice.mapper.NoticeDeliveryLogMapper;
import com.example.venue.notice.mapper.NoticeEmailDeliveryMapper;
import com.example.venue.notice.mapper.NoticeExperimentMapper;
import com.example.venue.notice.mapper.NoticeExperimentVariantMapper;
import com.example.venue.notice.mapper.NoticeMapper;
import com.example.venue.notice.mapper.NoticeMessageMapper;
import com.example.venue.notice.mapper.NoticeOutboxMapper;
import com.example.venue.notice.provider.NotificationDeliveryCommand;
import com.example.venue.notice.provider.NotificationDeliveryResult;
import com.example.venue.notice.provider.NotificationProviderDescriptor;
import com.example.venue.notice.routing.NotificationRoute;
import com.example.venue.notice.routing.NotificationRoutingPolicy;
import com.example.venue.notice.service.NoticeAudienceService;
import com.example.venue.notice.service.NoticeDeliveryService;
import com.example.venue.notice.service.NotificationOutboxAttemptStateService;
import com.example.venue.venue.service.VenueAccessService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Set;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NoticeDeliveryServiceTest {
    private static final NotificationRoute IN_APP_ROUTE = new NotificationRoute(
        "IN_APP", "notice.in_app.dev", "DEV", "IN_APP_MESSAGE", "IN_APP_MESSAGE_V1");

    @Mock
    private NoticeMapper noticeMapper;
    @Mock
    private NoticeDeliveryBatchMapper batchMapper;
    @Mock
    private NoticeAudienceSnapshotMapper audienceMapper;
    @Mock
    private NoticeOutboxMapper outboxMapper;
    @Mock
    private NoticeMessageMapper messageMapper;
    @Mock
    private NoticeDeliveryLogMapper deliveryLogMapper;
    @Mock
    private NoticeEmailDeliveryMapper emailDeliveryMapper;
    @Mock
    private NoticeAudienceService audienceService;
    @Mock
    private VenueAccessService venueAccessService;
    @Mock
    private BusinessEventService businessEventService;
    @Mock
    private NoticeExperimentMapper experimentMapper;
    @Mock
    private NoticeExperimentVariantMapper variantMapper;
    @Mock
    private NotificationRoutingPolicy routingPolicy;

    private NoticeDeliveryService deliveryService;

    @BeforeEach
    void setUp() {
        deliveryService = new NoticeDeliveryService(
            noticeMapper, batchMapper, audienceMapper, outboxMapper, audienceService,
            venueAccessService, businessEventService, experimentMapper, variantMapper,
            new ObjectMapper().findAndRegisterModules(), routingPolicy);
        lenient().when(routingPolicy.routesFor(any())).thenReturn(List.of(IN_APP_ROUTE));
        authenticate(new CurrentUser(1L, "admin", Set.of("SYSTEM_ADMIN"), Set.of()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void publishPersistsPendingOutboxWithoutMessageOrDeliveryLog() {
        NoticeRequest request = publishedRequest();
        stubPublishedGraph(List.of(audience(71L, 3L)));

        Notice notice = deliveryService.publish(request);

        assertThat(notice.id).isEqualTo(51L);
        ArgumentCaptor<NoticeDeliveryBatch> batch = ArgumentCaptor.forClass(NoticeDeliveryBatch.class);
        verify(batchMapper).insert(batch.capture());
        assertThat(batch.getValue().status).isEqualTo("PENDING");
        ArgumentCaptor<NoticeOutbox> outbox = ArgumentCaptor.forClass(NoticeOutbox.class);
        verify(outboxMapper).insert(outbox.capture());
        assertThat(outbox.getValue().status).isEqualTo("PENDING");
        assertThat(outbox.getValue().attemptCount).isZero();
        assertThat(outbox.getValue().metadataJson).contains("notice.in_app.dev");
        assertThat(outbox.getValue().requestSummaryJson)
            .contains("\"batchId\":61", "\"audienceId\":71", "\"title\":\"闭馆通知\"")
            .doesNotContain("<b>");
        verify(messageMapper, never()).insert(any(NoticeMessage.class));
        verify(deliveryLogMapper, never()).insert(any(NoticeDeliveryLog.class));
        verify(businessEventService).record(any(BusinessEventRecordRequest.class));
    }

    @Test
    void publishCompletesBatchWhenEveryAudienceIsExcluded() {
        NoticeRequest request = publishedRequest();
        NoticeAudienceSnapshot excluded = audience(71L, 3L);
        excluded.deliveryStatus = "EXCLUDED";
        excluded.excludeReason = "USER_NOT_ACTIVE";
        stubPublishedGraph(List.of(excluded));

        deliveryService.publish(request);

        ArgumentCaptor<NoticeDeliveryBatch> batch = ArgumentCaptor.forClass(NoticeDeliveryBatch.class);
        verify(batchMapper).insert(batch.capture());
        assertThat(batch.getValue().status).isEqualTo("COMPLETED");
        assertThat(batch.getValue().deliverableCount).isZero();
        assertThat(batch.getValue().excludedCount).isEqualTo(1);
        verify(outboxMapper, never()).insert(any(NoticeOutbox.class));
    }

    @Test
    void publishPersistsExperimentAssignmentsAndVariantPayloadsForWorker() {
        NoticeRequest request = publishedRequest();
        request.targetScope = "SPECIFIC_USERS";
        request.userIds = List.of(3L, 4L);
        request.experimentId = 11L;
        when(experimentMapper.selectById(11L)).thenReturn(runningExperiment(11L));
        when(variantMapper.findByExperimentId(11L)).thenReturn(List.of(
            variant(101L, 11L, "A", "<b>标题A</b>", "<b>内容A</b>", 50),
            variant(102L, 11L, "B", "标题B", "内容B", 50)));
        stubPublishedGraph(List.of(audience(71L, 3L), audience(72L, 4L)));

        deliveryService.publish(request);

        ArgumentCaptor<NoticeAudienceSnapshot> audiences = ArgumentCaptor.forClass(NoticeAudienceSnapshot.class);
        verify(audienceMapper, org.mockito.Mockito.times(2)).updateById(audiences.capture());
        assertThat(audiences.getAllValues()).extracting(item -> item.experimentVariantId)
            .containsExactly(101L, 102L);
        assertThat(audiences.getAllValues()).extracting(item -> item.deliveryStatus)
            .containsOnly("PENDING");
        ArgumentCaptor<NoticeOutbox> outboxes = ArgumentCaptor.forClass(NoticeOutbox.class);
        verify(outboxMapper, org.mockito.Mockito.times(2)).insert(outboxes.capture());
        assertThat(outboxes.getAllValues()).extracting(item -> item.requestSummaryJson)
            .anySatisfy(json -> assertThat(json).contains("标题A", "内容A"))
            .anySatisfy(json -> assertThat(json).contains("标题B", "内容B"));
        assertThat(outboxes.getAllValues()).extracting(item -> item.requestSummaryJson)
            .allSatisfy(json -> assertThat(json).doesNotContain("<b>"));
    }

    @Test
    void draftPublishCreatesNoticeWithoutBatchOrOutbox() {
        NoticeRequest request = publishedRequest();
        request.status = "DRAFT";
        when(noticeMapper.insert(any(Notice.class))).thenAnswer(invocation -> {
            Notice notice = invocation.getArgument(0);
            notice.id = 52L;
            return 1;
        });

        Notice notice = deliveryService.publish(request);

        assertThat(notice.status).isEqualTo("DRAFT");
        verify(batchMapper, never()).insert(any(NoticeDeliveryBatch.class));
        verify(outboxMapper, never()).insert(any(NoticeOutbox.class));
    }

    @Test
    void venueAdminCannotPublishUnboundGlobalNotice() {
        authenticate(new CurrentUser(8L, "venue-admin", Set.of("VENUE_ADMIN"), Set.of()));
        NoticeRequest request = publishedRequest();

        assertThatThrownBy(() -> deliveryService.publish(request))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("场馆管理员只能发布已绑定场馆的公告");
        verify(noticeMapper, never()).insert(any(Notice.class));
    }

    @Test
    void venueAdminCannotRetryAPlatformAnnouncementOutbox() {
        authenticate(new CurrentUser(8L, "venue-admin", Set.of("VENUE_ADMIN"), Set.of()));
        NoticeOutbox outbox = failedOutbox();
        Notice notice = notice();
        notice.targetVenueId = null;
        when(outboxMapper.selectById(81L)).thenReturn(outbox);
        when(noticeMapper.selectById(51L)).thenReturn(notice);

        assertThatThrownBy(() -> deliveryService.retryOutbox(81L))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("无权重试该公告投递任务");

        verify(venueAccessService, never()).requireVenueManageAccess(any());
        verify(outboxMapper, never()).update(isNull(), any());
        verify(audienceMapper, never()).updateById(any(NoticeAudienceSnapshot.class));
        verify(batchMapper, never()).updateById(any(NoticeDeliveryBatch.class));
    }

    @Test
    void venueAdminCannotRetryAnotherVenueAnnouncementOutbox() {
        authenticate(new CurrentUser(8L, "venue-admin", Set.of("VENUE_ADMIN"), Set.of()));
        NoticeOutbox outbox = failedOutbox();
        Notice notice = notice();
        notice.targetVenueId = 21L;
        when(outboxMapper.selectById(81L)).thenReturn(outbox);
        when(noticeMapper.selectById(51L)).thenReturn(notice);
        doThrow(BusinessException.forbidden("无权管理该场馆数据"))
            .when(venueAccessService).requireVenueManageAccess(21L);

        assertThatThrownBy(() -> deliveryService.retryOutbox(81L))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("无权管理该场馆数据");

        verify(venueAccessService).requireVenueManageAccess(21L);
        verify(outboxMapper, never()).update(isNull(), any());
        verify(audienceMapper, never()).updateById(any(NoticeAudienceSnapshot.class));
        verify(batchMapper, never()).updateById(any(NoticeDeliveryBatch.class));
    }

    @Test
    void venueAdminCanRetryAnAuthorizedVenueAnnouncementOutbox() {
        authenticate(new CurrentUser(8L, "venue-admin", Set.of("VENUE_ADMIN"), Set.of()));
        NoticeOutbox outbox = failedOutbox();
        Notice notice = notice();
        notice.targetVenueId = 20L;
        NoticeAudienceSnapshot audience = audience(71L, 3L);
        audience.deliveryStatus = "FAILED";
        NoticeDeliveryBatch batch = batch();
        batch.status = "FAILED";
        when(outboxMapper.selectById(81L)).thenReturn(outbox);
        when(noticeMapper.selectById(51L)).thenReturn(notice);
        when(audienceMapper.selectById(71L)).thenReturn(audience);
        when(batchMapper.selectById(61L)).thenReturn(batch);
        when(outboxMapper.update(isNull(), any())).thenReturn(1);

        deliveryService.retryOutbox(81L);

        verify(venueAccessService).requireVenueManageAccess(20L);
        verify(outboxMapper).update(isNull(), any());
        verify(audienceMapper).updateById(audience);
        verify(batchMapper).updateById(batch);
    }

    @Test
    void sentOutboxCannotBeRetried() {
        NoticeOutbox outbox = failedOutbox();
        outbox.status = "SENT";
        when(outboxMapper.selectById(81L)).thenReturn(outbox);

        assertThatThrownBy(() -> deliveryService.retryOutbox(81L))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("已发送的投递任务不能重复重试");
        verify(outboxMapper, never()).updateById(any(NoticeOutbox.class));
    }

    @Test
    void failedOutboxRetryOnlyRequeuesAndRecordsEvidence() {
        NoticeOutbox outbox = failedOutbox();
        Notice notice = notice();
        NoticeAudienceSnapshot audience = audience(71L, 3L);
        audience.deliveryStatus = "FAILED";
        NoticeDeliveryBatch batch = batch();
        batch.status = "FAILED";
        when(outboxMapper.selectById(81L)).thenReturn(outbox);
        when(noticeMapper.selectById(51L)).thenReturn(notice);
        when(audienceMapper.selectById(71L)).thenReturn(audience);
        when(batchMapper.selectById(61L)).thenReturn(batch);
        when(outboxMapper.update(isNull(), any())).thenReturn(1);

        deliveryService.retryOutbox(81L);

        verify(outboxMapper).update(isNull(), any());
        assertThat(outbox.status).isEqualTo("PENDING");
        assertThat(outbox.nextRetryTime).isNull();
        assertThat(outbox.lastErrorCode).isNull();
        assertThat(outbox.lastErrorMessage).isNull();
        assertThat(audience.deliveryStatus).isEqualTo("PENDING");
        assertThat(batch.status).isEqualTo("PROCESSING");
        verify(messageMapper, never()).insert(any(NoticeMessage.class));
        verify(deliveryLogMapper, never()).insert(any(NoticeDeliveryLog.class));
        ArgumentCaptor<BusinessEventRecordRequest> event = ArgumentCaptor.forClass(BusinessEventRecordRequest.class);
        verify(businessEventService).record(event.capture());
        assertThat(event.getValue().eventType).isEqualTo(BusinessEventType.NOTICE_DELIVERED);
        assertThat(event.getValue().resultStatus).isEqualTo("PENDING");
        assertThat(event.getValue().idempotencyKey).contains("NOTICE_RETRY_ENQUEUED:81:1");
    }

    @Test
    void terminalEmailRetryRequeuesWithoutChangingAttemptOrSnapshot() {
        NoticeOutbox outbox = failedEmailOutbox();
        int originalAttemptCount = outbox.attemptCount;
        String originalRequestSummary = outbox.requestSummaryJson;
        when(outboxMapper.selectById(81L)).thenReturn(outbox);
        when(outboxMapper.update(isNull(), any())).thenReturn(1);

        deliveryService.retryOutbox(81L);

        ArgumentCaptor<UpdateWrapper<NoticeOutbox>> wrapper = ArgumentCaptor.forClass(UpdateWrapper.class);
        verify(outboxMapper).update(isNull(), wrapper.capture());
        assertThat(wrapper.getValue().getSqlSet())
            .contains("status", "next_retry_time", "last_error_code", "last_error_message")
            .doesNotContain("attempt_count", "template_key", "template_version", "request_summary_json");
        assertThat(outbox.attemptCount).isEqualTo(originalAttemptCount);
        assertThat(outbox.requestSummaryJson).isEqualTo(originalRequestSummary);
        verifyNoInteractions(noticeMapper, audienceMapper, batchMapper, businessEventService);
        verify(messageMapper, never()).insert(any(NoticeMessage.class));
        verify(deliveryLogMapper, never()).insert(any(NoticeDeliveryLog.class));
    }

    @Test
    void nonTerminalEmailRetryWaitingCannotBeManuallyRequeued() {
        NoticeOutbox outbox = failedEmailOutbox();
        outbox.status = "RETRY_WAITING";
        when(outboxMapper.selectById(81L)).thenReturn(outbox);

        assertThatThrownBy(() -> deliveryService.retryOutbox(81L))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("当前投递任务状态不能重试");

        verify(outboxMapper, never()).update(isNull(), any());
        verifyNoInteractions(noticeMapper, audienceMapper, batchMapper, businessEventService);
    }

    @Test
    void failedBusinessInAppOutboxRetriesWithoutRequiringNoticeAudienceOrBatch() {
        NoticeOutbox outbox = failedBusinessInAppOutbox();
        int originalAttemptCount = outbox.attemptCount;
        when(outboxMapper.selectById(81L)).thenReturn(outbox);
        when(outboxMapper.update(isNull(), any())).thenReturn(1);

        deliveryService.retryOutbox(81L);

        ArgumentCaptor<UpdateWrapper<NoticeOutbox>> wrapper = ArgumentCaptor.forClass(UpdateWrapper.class);
        verify(outboxMapper).update(isNull(), wrapper.capture());
        assertThat(wrapper.getValue().getSqlSet())
            .contains("status", "next_retry_time", "last_error_code", "last_error_message")
            .doesNotContain("attempt_count");
        assertThat(outbox.status).isEqualTo("PENDING");
        assertThat(outbox.nextRetryTime).isNull();
        assertThat(outbox.lastErrorCode).isNull();
        assertThat(outbox.lastErrorMessage).isNull();
        assertThat(outbox.attemptCount).isEqualTo(originalAttemptCount);
        verifyNoInteractions(noticeMapper, audienceMapper, batchMapper, businessEventService);
    }

    @Test
    void businessInAppOutboxRetryWaitingCanBeManuallyRequeued() {
        NoticeOutbox outbox = failedBusinessInAppOutbox();
        outbox.status = "RETRY_WAITING";
        when(outboxMapper.selectById(81L)).thenReturn(outbox);
        when(outboxMapper.update(isNull(), any())).thenReturn(1);

        deliveryService.retryOutbox(81L);

        assertThat(outbox.status).isEqualTo("PENDING");
        verify(outboxMapper).update(isNull(), any());
        verifyNoInteractions(noticeMapper, audienceMapper, batchMapper, businessEventService);
    }

    @Test
    void businessInAppOutboxRetryRejectsWhenStatusAlreadyChanged() {
        NoticeOutbox outbox = failedBusinessInAppOutbox();
        when(outboxMapper.selectById(81L)).thenReturn(outbox);
        when(outboxMapper.update(isNull(), any())).thenReturn(0);

        assertThatThrownBy(() -> deliveryService.retryOutbox(81L))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("状态已变化");

        verifyNoInteractions(noticeMapper, audienceMapper, batchMapper, businessEventService);
    }

    @Test
    void venueAdminCannotRetryBusinessInAppOutbox() {
        authenticate(new CurrentUser(8L, "venue-admin", Set.of("VENUE_ADMIN"), Set.of()));
        NoticeOutbox outbox = failedBusinessInAppOutbox();
        when(outboxMapper.selectById(81L)).thenReturn(outbox);

        assertThatThrownBy(() -> deliveryService.retryOutbox(81L))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("无权重试该公告投递任务");

        verify(outboxMapper, never()).update(isNull(), any());
        verifyNoInteractions(noticeMapper, audienceMapper, batchMapper, businessEventService);
    }

    @Test
    void retryRejectsWhenEligibleOutboxWasClaimedConcurrently() {
        NoticeOutbox outbox = failedOutbox();
        Notice notice = notice();
        NoticeAudienceSnapshot audience = audience(71L, 3L);
        audience.deliveryStatus = "FAILED";
        NoticeDeliveryBatch batch = batch();
        batch.status = "FAILED";
        when(outboxMapper.selectById(81L)).thenReturn(outbox);
        when(noticeMapper.selectById(51L)).thenReturn(notice);
        when(audienceMapper.selectById(71L)).thenReturn(audience);
        when(batchMapper.selectById(61L)).thenReturn(batch);
        when(outboxMapper.update(isNull(), any())).thenReturn(0);

        assertThatThrownBy(() -> deliveryService.retryOutbox(81L))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("状态已变化");

        verify(audienceMapper, never()).updateById(any(NoticeAudienceSnapshot.class));
        verify(batchMapper, never()).updateById(any(NoticeDeliveryBatch.class));
        verify(businessEventService, never()).record(any());
    }

    @Test
    void retryRejectsSameStatusWhenAttemptGenerationChanged() {
        NoticeOutbox outbox = failedOutbox();
        Notice notice = notice();
        NoticeAudienceSnapshot audience = audience(71L, 3L);
        audience.deliveryStatus = "FAILED";
        NoticeDeliveryBatch batch = batch();
        batch.status = "FAILED";
        when(outboxMapper.selectById(81L)).thenReturn(outbox);
        when(noticeMapper.selectById(51L)).thenReturn(notice);
        when(audienceMapper.selectById(71L)).thenReturn(audience);
        when(batchMapper.selectById(61L)).thenReturn(batch);
        when(outboxMapper.update(isNull(), any())).thenAnswer(invocation -> {
            UpdateWrapper<?> wrapper = invocation.getArgument(1);
            String sql = wrapper.getSqlSegment().toLowerCase();
            boolean generationGuarded = sql.contains("attempt_count")
                && wrapper.getParamNameValuePairs().containsValue(1);
            return generationGuarded ? 0 : 1;
        });

        assertThatThrownBy(() -> deliveryService.retryOutbox(81L))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("状态已变化");

        verify(audienceMapper, never()).updateById(any(NoticeAudienceSnapshot.class));
        verify(batchMapper, never()).updateById(any(NoticeDeliveryBatch.class));
        verify(businessEventService, never()).record(any());
    }

    @Test
    void acceptedCampaignAttemptLinksMessageAndCompletesBatchIgnoringExcludedAudience() {
        NoticeOutbox outbox = sendingOutbox(81L, 71L);
        NoticeAudienceSnapshot delivered = audience(71L, 3L);
        NoticeAudienceSnapshot excluded = audience(72L, 4L);
        excluded.deliveryStatus = "EXCLUDED";
        excluded.excludeReason = "NO_MATCH";
        NoticeDeliveryBatch batch = batch();
        batch.status = "PROCESSING";
        stubCampaignState(outbox, delivered, List.of(delivered, excluded), batch, 101L);
        when(outboxMapper.markSent(eq(81L), eq(1), eq("91"), any(), any())).thenReturn(1);

        boolean updated = attemptStateService().markSent(
            outbox, campaignCommand(outbox), accepted(91L), "{\"status\":\"ACCEPTED\",\"messageId\":91}",
            LocalDateTime.of(2026, 7, 11, 10, 0), 7L);

        assertThat(updated).isTrue();
        assertThat(delivered.deliveryStatus).isEqualTo("SENT");
        assertThat(delivered.readStatus).isEqualTo("UNREAD");
        assertThat(delivered.messageId).isEqualTo(91L);
        assertThat(delivered.lastDeliveryLogId).isEqualTo(101L);
        assertThat(batch.status).isEqualTo("COMPLETED");
        assertThat(batch.deliverableCount).isEqualTo(1);
        assertThat(batch.excludedCount).isEqualTo(1);
    }

    @Test
    void acceptedCampaignAttemptKeepsBatchProcessingWhileAnotherAudienceIsPending() {
        NoticeOutbox outbox = sendingOutbox(81L, 71L);
        NoticeAudienceSnapshot delivered = audience(71L, 3L);
        NoticeAudienceSnapshot pending = audience(72L, 4L);
        NoticeDeliveryBatch batch = batch();
        stubCampaignState(outbox, delivered, List.of(delivered, pending), batch, 101L);
        when(outboxMapper.markSent(eq(81L), eq(1), eq("91"), any(), any())).thenReturn(1);

        attemptStateService().markSent(
            outbox, campaignCommand(outbox), accepted(91L), "{\"status\":\"ACCEPTED\",\"messageId\":91}",
            LocalDateTime.of(2026, 7, 11, 10, 0), 7L);

        assertThat(batch.status).isEqualTo("PROCESSING");
    }

    @Test
    void acceptedCampaignAttemptDoesNotOverwriteExistingReadReceiptState() {
        NoticeOutbox outbox = sendingOutbox(81L, 71L);
        NoticeAudienceSnapshot delivered = audience(71L, 3L);
        delivered.readStatus = "READ";
        delivered.readTime = LocalDateTime.of(2026, 7, 11, 9, 59);
        NoticeDeliveryBatch batch = batch();
        stubCampaignState(outbox, delivered, List.of(delivered), batch, 101L);
        when(outboxMapper.markSent(eq(81L), eq(1), eq("91"), any(), any())).thenReturn(1);

        attemptStateService().markSent(
            outbox, campaignCommand(outbox), accepted(91L), "{\"status\":\"ACCEPTED\",\"messageId\":91}",
            LocalDateTime.of(2026, 7, 11, 10, 0), 7L);

        assertThat(delivered.readStatus).isEqualTo("READ");
        assertThat(delivered.readTime).isEqualTo(LocalDateTime.of(2026, 7, 11, 9, 59));
    }

    @Test
    void failedCampaignAttemptProducesPartialFailureWhenAnotherAudienceWasSent() {
        NoticeOutbox outbox = sendingOutbox(81L, 71L);
        NoticeAudienceSnapshot failed = audience(71L, 3L);
        NoticeAudienceSnapshot sent = audience(72L, 4L);
        sent.deliveryStatus = "SENT";
        NoticeDeliveryBatch batch = batch();
        stubCampaignState(outbox, failed, List.of(failed, sent), batch, 102L);
        when(outboxMapper.markFailed(eq(81L), eq(1), eq("INVALID_PAYLOAD"), eq("invalid"), any(), any()))
            .thenReturn(1);

        attemptStateService().markFailed(
            outbox, campaignCommand(outbox), permanentFailure(), "INVALID_PAYLOAD", "invalid",
            "{\"status\":\"FAILED_PERMANENT\"}", LocalDateTime.of(2026, 7, 11, 10, 0), 7L);

        assertThat(failed.deliveryStatus).isEqualTo("FAILED");
        assertThat(failed.lastDeliveryLogId).isEqualTo(102L);
        assertThat(batch.status).isEqualTo("PARTIAL_FAILED");
    }

    @Test
    void failedCampaignAttemptMarksBatchFailedIgnoringExcludedAudience() {
        NoticeOutbox outbox = sendingOutbox(81L, 71L);
        NoticeAudienceSnapshot failed = audience(71L, 3L);
        NoticeAudienceSnapshot excluded = audience(72L, 4L);
        excluded.deliveryStatus = "EXCLUDED";
        excluded.excludeReason = "NO_MATCH";
        NoticeDeliveryBatch batch = batch();
        stubCampaignState(outbox, failed, List.of(failed, excluded), batch, 102L);
        when(outboxMapper.markFailed(eq(81L), eq(1), eq("INVALID_PAYLOAD"), eq("invalid"), any(), any()))
            .thenReturn(1);

        attemptStateService().markFailed(
            outbox, campaignCommand(outbox), permanentFailure(), "INVALID_PAYLOAD", "invalid",
            "{\"status\":\"FAILED_PERMANENT\"}", LocalDateTime.of(2026, 7, 11, 10, 0), 7L);

        assertThat(batch.status).isEqualTo("FAILED");
        assertThat(batch.deliverableCount).isEqualTo(1);
        assertThat(batch.excludedCount).isEqualTo(1);
    }

    @Test
    void retryWaitingCampaignAttemptKeepsAudienceAndBatchProcessing() {
        NoticeOutbox outbox = sendingOutbox(81L, 71L);
        NoticeAudienceSnapshot audience = audience(71L, 3L);
        NoticeDeliveryBatch batch = batch();
        stubCampaignState(outbox, audience, List.of(audience), batch, 103L);
        LocalDateTime now = LocalDateTime.of(2026, 7, 11, 10, 0);
        LocalDateTime nextRetry = now.plusSeconds(30);
        when(outboxMapper.markRetryWaiting(eq(81L), eq(1), eq("TEMPORARY"), eq("temporary"),
            any(), eq(nextRetry), eq(now))).thenReturn(1);

        attemptStateService().markRetryWaiting(
            outbox, campaignCommand(outbox), retryableFailure(), "TEMPORARY", "temporary",
            "{\"status\":\"FAILED_RETRYABLE\"}", nextRetry, now, 7L);

        assertThat(audience.deliveryStatus).isEqualTo("RETRY_WAITING");
        assertThat(audience.lastDeliveryLogId).isEqualTo(103L);
        assertThat(batch.status).isEqualTo("PROCESSING");
    }

    @Test
    void directOutboxTerminalUpdateSkipsCampaignAggregation() {
        NoticeOutbox outbox = sendingOutbox(81L, null);
        when(outboxMapper.markSent(eq(81L), eq(1), eq("91"), any(), any())).thenReturn(1);

        boolean updated = attemptStateService().markSent(
            outbox, campaignCommand(outbox), accepted(91L), "{\"status\":\"ACCEPTED\",\"messageId\":91}",
            LocalDateTime.of(2026, 7, 11, 10, 0), 7L);

        assertThat(updated).isTrue();
        verifyNoInteractions(audienceMapper, batchMapper);
    }

    @Test
    void terminalEmailWithCampaignIdsNeverChangesAudienceReceiptOrBatch() {
        NoticeOutbox outbox = sendingOutbox(81L, 71L);
        outbox.channel = "EMAIL";
        outbox.providerEnv = "SANDBOX";
        outbox.providerName = "SMTP_EMAIL";
        outbox.providerKey = "notice.smtp.sandbox";
        outbox.configVersion = "SMTP_SANDBOX_V1";
        outbox.eventType = "RESERVATION_APPROVED";
        outbox.templateKey = "reservation.approved";
        outbox.templateVersion = "v1";
        outbox.idempotencyKey = "EMAIL:" + "a".repeat(64);
        String messageId = "<" + "b".repeat(64) + "@venue.local>";
        NotificationProviderDescriptor descriptor = new NotificationProviderDescriptor(
            "notice.smtp.sandbox", "MESSAGE", "SANDBOX", "SMTP_EMAIL",
            "EMAIL", "SMTP 邮件沙箱", "SMTP_SANDBOX_V1", true);
        NotificationDeliveryCommand command = new NotificationDeliveryCommand(
            outbox.id, outbox.idempotencyKey, descriptor, outbox.userId, null, null,
            null, null, null, null, 101L, "a***e@e***e.test",
            outbox.templateKey, outbox.templateVersion, messageId, outbox.eventType);
        NotificationDeliveryResult result = new NotificationDeliveryResult(
            NotificationDeliveryResult.ACCEPTED, messageId, false,
            null, null, "{\"status\":\"ACCEPTED\"}");
        when(outboxMapper.markSent(eq(81L), eq(1), eq(messageId), any(), any())).thenReturn(1);
        when(emailDeliveryMapper.markAccepted(eq(81L), any())).thenReturn(1);

        boolean updated = attemptStateService().markSent(
            outbox, command, result, "{\"status\":\"ACCEPTED\"}",
            LocalDateTime.of(2026, 7, 11, 10, 0), 7L);

        assertThat(updated).isTrue();
        verify(emailDeliveryMapper).markAccepted(eq(81L), any());
        verify(deliveryLogMapper).insert(any(NoticeDeliveryLog.class));
        verifyNoInteractions(audienceMapper, batchMapper);
    }

    private void stubPublishedGraph(List<NoticeAudienceSnapshot> audiences) {
        when(noticeMapper.insert(any(Notice.class))).thenAnswer(invocation -> {
            Notice notice = invocation.getArgument(0);
            notice.id = 51L;
            return 1;
        });
        when(batchMapper.insert(any(NoticeDeliveryBatch.class))).thenAnswer(invocation -> {
            NoticeDeliveryBatch batch = invocation.getArgument(0);
            batch.id = 61L;
            return 1;
        });
        when(audienceService.snapshotAudience(eq(61L), eq(51L), any())).thenReturn(audiences);
        lenient().when(outboxMapper.insert(any(NoticeOutbox.class))).thenAnswer(invocation -> {
            NoticeOutbox outbox = invocation.getArgument(0);
            outbox.id = 81L + (outbox.audienceId == null ? 0 : outbox.audienceId - 71L);
            return 1;
        });
    }

    private NoticeRequest publishedRequest() {
        NoticeRequest request = new NoticeRequest();
        request.title = "闭馆通知";
        request.content = "<b>今晚闭馆</b>";
        request.type = "GLOBAL";
        request.status = "PUBLISHED";
        request.targetScope = "ALL_USERS";
        return request;
    }

    private NoticeAudienceSnapshot audience(Long id, Long userId) {
        NoticeAudienceSnapshot audience = new NoticeAudienceSnapshot();
        audience.id = id;
        audience.batchId = 61L;
        audience.noticeId = 51L;
        audience.userId = userId;
        audience.deliveryStatus = "PENDING";
        audience.readStatus = "UNREAD";
        audience.deleted = 0;
        return audience;
    }

    private NoticeOutbox failedOutbox() {
        NoticeOutbox outbox = new NoticeOutbox();
        outbox.id = 81L;
        outbox.batchId = 61L;
        outbox.audienceId = 71L;
        outbox.noticeId = 51L;
        outbox.userId = 3L;
        outbox.channel = "IN_APP";
        outbox.providerEnv = "DEV";
        outbox.providerName = "IN_APP_MESSAGE";
        outbox.configVersion = "IN_APP_MESSAGE_V1";
        outbox.idempotencyKey = "NOTICE_OUTBOX:61:3:IN_APP_MESSAGE";
        outbox.status = "FAILED";
        outbox.attemptCount = 1;
        outbox.nextRetryTime = LocalDateTime.of(2026, 7, 11, 10, 30);
        outbox.lastErrorCode = "IN_APP_DELIVERY_FAILED";
        outbox.lastErrorMessage = "temporary";
        outbox.deleted = 0;
        return outbox;
    }

    private NoticeOutbox failedEmailOutbox() {
        NoticeOutbox outbox = failedOutbox();
        outbox.batchId = null;
        outbox.audienceId = null;
        outbox.noticeId = null;
        outbox.channel = "EMAIL";
        outbox.providerEnv = "SANDBOX";
        outbox.providerName = "SMTP_EMAIL";
        outbox.providerKey = "notice.smtp.sandbox";
        outbox.configVersion = "SMTP_SANDBOX_V1";
        outbox.eventType = "RESERVATION_APPROVED";
        outbox.templateKey = "reservation.approved";
        outbox.templateVersion = "v1";
        outbox.idempotencyKey = "EMAIL:" + "a".repeat(64);
        outbox.requestSummaryJson = "{\"userId\":3,\"eventType\":\"RESERVATION_APPROVED\"}";
        outbox.attemptCount = 3;
        return outbox;
    }

    private NoticeOutbox failedBusinessInAppOutbox() {
        NoticeOutbox outbox = failedOutbox();
        outbox.batchId = null;
        outbox.audienceId = null;
        outbox.noticeId = null;
        outbox.channel = "IN_APP";
        outbox.providerEnv = "DEV";
        outbox.providerName = "IN_APP_MESSAGE";
        outbox.providerKey = "notice.in_app.dev";
        outbox.configVersion = "IN_APP_MESSAGE_V1";
        outbox.eventType = "RESERVATION_APPROVED";
        outbox.idempotencyKey = "IN_APP:" + "a".repeat(64);
        outbox.requestSummaryJson = "{\"messageId\":91,\"userId\":3,\"channel\":\"IN_APP\","
            + "\"type\":\"RESERVATION\",\"businessId\":42,\"source\":\"BUSINESS_MESSAGE\"}";
        outbox.attemptCount = 2;
        return outbox;
    }

    private NoticeOutbox sendingOutbox(Long id, Long audienceId) {
        NoticeOutbox outbox = failedOutbox();
        outbox.id = id;
        outbox.audienceId = audienceId;
        outbox.status = "SENDING";
        return outbox;
    }

    private void stubCampaignState(NoticeOutbox outbox, NoticeAudienceSnapshot audience,
                                   List<NoticeAudienceSnapshot> audiences, NoticeDeliveryBatch batch,
                                   Long deliveryLogId) {
        when(audienceMapper.selectOne(any())).thenReturn(audience);
        when(audienceMapper.selectList(any())).thenReturn(audiences);
        when(batchMapper.selectOne(any())).thenReturn(batch);
        when(deliveryLogMapper.insert(any(NoticeDeliveryLog.class))).thenAnswer(invocation -> {
            NoticeDeliveryLog log = invocation.getArgument(0);
            log.id = deliveryLogId;
            return 1;
        });
    }

    private NotificationOutboxAttemptStateService attemptStateService() {
        return new NotificationOutboxAttemptStateService(
            outboxMapper, deliveryLogMapper, emailDeliveryMapper, audienceMapper, batchMapper,
            new ObjectMapper().findAndRegisterModules());
    }

    private NotificationDeliveryCommand campaignCommand(NoticeOutbox outbox) {
        NotificationProviderDescriptor descriptor = new NotificationProviderDescriptor(
            "notice.in_app.dev", "MESSAGE", "DEV", "IN_APP_MESSAGE",
            "IN_APP", "站内消息", "IN_APP_MESSAGE_V1", true);
        return new NotificationDeliveryCommand(
            outbox.id, outbox.idempotencyKey, descriptor, outbox.userId, null, outbox.noticeId,
            "NOTICE", "闭馆通知", "今晚闭馆",
            "{\"noticeId\":51,\"batchId\":61,\"audienceId\":71}");
    }

    private NotificationDeliveryResult accepted(Long messageId) {
        return new NotificationDeliveryResult(
            NotificationDeliveryResult.ACCEPTED, String.valueOf(messageId), false,
            null, null, "{\"status\":\"ACCEPTED\",\"messageId\":" + messageId + "}");
    }

    private NotificationDeliveryResult retryableFailure() {
        return new NotificationDeliveryResult(
            NotificationDeliveryResult.FAILED_RETRYABLE, null, true,
            "TEMPORARY", "temporary", "{\"status\":\"FAILED_RETRYABLE\"}");
    }

    private NotificationDeliveryResult permanentFailure() {
        return new NotificationDeliveryResult(
            NotificationDeliveryResult.FAILED_PERMANENT, null, false,
            "INVALID_PAYLOAD", "invalid", "{\"status\":\"FAILED_PERMANENT\"}");
    }

    private Notice notice() {
        Notice notice = new Notice();
        notice.id = 51L;
        notice.title = "闭馆通知";
        notice.content = "今晚闭馆";
        return notice;
    }

    private NoticeDeliveryBatch batch() {
        NoticeDeliveryBatch batch = new NoticeDeliveryBatch();
        batch.id = 61L;
        batch.noticeId = 51L;
        batch.status = "PENDING";
        batch.providerEnv = "DEV";
        batch.providerName = "IN_APP_MESSAGE";
        batch.configVersion = "IN_APP_MESSAGE_V1";
        batch.deleted = 0;
        return batch;
    }

    private NoticeExperiment runningExperiment(Long id) {
        NoticeExperiment experiment = new NoticeExperiment();
        experiment.id = id;
        experiment.status = "RUNNING";
        experiment.deleted = 0;
        return experiment;
    }

    private NoticeExperimentVariant variant(Long id, Long experimentId, String code, String title,
                                            String content, int allocationPercent) {
        NoticeExperimentVariant variant = new NoticeExperimentVariant();
        variant.id = id;
        variant.experimentId = experimentId;
        variant.variantCode = code;
        variant.title = title;
        variant.content = content;
        variant.allocationPercent = allocationPercent;
        return variant;
    }

    private void authenticate(CurrentUser user) {
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(user, null, List.of()));
    }
}
