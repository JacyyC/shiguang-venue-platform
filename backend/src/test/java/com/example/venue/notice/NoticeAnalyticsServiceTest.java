package com.example.venue.notice;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.venue.common.PageResult;
import com.example.venue.notice.dto.NoticeAudienceResponse;
import com.example.venue.notice.dto.NoticeDeliveryBatchResponse;
import com.example.venue.notice.dto.NoticeDeliveryOutboxResponse;
import com.example.venue.notice.dto.NoticeDeliveryOverviewResponse;
import com.example.venue.notice.dto.NoticeDeliveryQuery;
import com.example.venue.notice.dto.NotificationProviderOptionResponse;
import com.example.venue.notice.entity.NoticeAudienceSnapshot;
import com.example.venue.notice.entity.NoticeDeliveryBatch;
import com.example.venue.notice.entity.NoticeEmailDelivery;
import com.example.venue.notice.entity.NoticeExperiment;
import com.example.venue.notice.entity.NoticeOutbox;
import com.example.venue.notice.mapper.NoticeAudienceSnapshotMapper;
import com.example.venue.notice.mapper.NoticeDeliveryBatchMapper;
import com.example.venue.notice.mapper.NoticeEmailDeliveryMapper;
import com.example.venue.notice.mapper.NoticeExperimentMapper;
import com.example.venue.notice.mapper.NoticeExperimentVariantMapper;
import com.example.venue.notice.mapper.NoticeOutboxMapper;
import com.example.venue.notice.provider.NotificationProvider;
import com.example.venue.notice.provider.NotificationProviderDescriptor;
import com.example.venue.notice.provider.NotificationProviderRouter;
import com.example.venue.notice.service.NoticeAnalyticsService;
import com.example.venue.provider.dto.ProviderOverviewResponse;
import com.example.venue.provider.dto.ProviderStatusResponse;
import com.example.venue.provider.service.ProviderAdminService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NoticeAnalyticsServiceTest {
    @Mock
    private NoticeDeliveryBatchMapper batchMapper;
    @Mock
    private NoticeAudienceSnapshotMapper audienceMapper;
    @Mock
    private NoticeOutboxMapper outboxMapper;
    @Mock
    private NoticeEmailDeliveryMapper emailDeliveryMapper;
    @Mock
    private NoticeExperimentMapper experimentMapper;
    @Mock
    private NoticeExperimentVariantMapper variantMapper;
    @Mock
    private NotificationProviderRouter providerRouter;
    @Mock
    private ProviderAdminService providerAdminService;

    private NoticeAnalyticsService service;
    private NotificationProvider defaultProvider;

    @BeforeEach
    void setUp() {
        defaultProvider = mock(NotificationProvider.class);
        lenient().when(defaultProvider.descriptor()).thenReturn(inAppDescriptor());
        lenient().when(providerRouter.require("DEV", "IN_APP_MESSAGE")).thenReturn(defaultProvider);
        lenient().when(providerRouter.catalog()).thenReturn(List.of(inAppDescriptor()));
        lenient().when(providerAdminService.overview()).thenReturn(providerOverview(
            providerStatus(inAppDescriptor(), true)));
        service = new NoticeAnalyticsService(batchMapper, audienceMapper, outboxMapper,
            emailDeliveryMapper, experimentMapper, variantMapper, providerRouter, providerAdminService);
    }

    @Test
    void providerCatalogMirrorsDescriptorsAndMarksOnlyCurrentRouteAsDefault() {
        NotificationProviderDescriptor inApp = inAppDescriptor();
        NotificationProviderDescriptor disabledFake = new NotificationProviderDescriptor(
            "notice.fake.disabled", "MESSAGE", "SANDBOX", "FAKE_MESSAGE", "EMAIL",
            "Disabled Fake", "FAKE_V1", true);
        NotificationProvider inAppProvider = mock(NotificationProvider.class);
        when(inAppProvider.descriptor()).thenReturn(inApp);
        when(providerRouter.catalog()).thenReturn(List.of(inApp, disabledFake));
        when(providerRouter.require("DEV", "IN_APP_MESSAGE")).thenReturn(inAppProvider);
        when(providerAdminService.overview()).thenReturn(providerOverview(
            providerStatus(inApp, true), providerStatus(disabledFake, false)));

        List<NotificationProviderOptionResponse> options = service.providerOptions();

        assertThat(options).containsExactly(
            new NotificationProviderOptionResponse(
                "notice.in_app.dev", "MESSAGE", "DEV", "IN_APP_MESSAGE", "IN_APP",
                "站内消息", "IN_APP_MESSAGE_V1", true, true),
            new NotificationProviderOptionResponse(
                "notice.fake.disabled", "MESSAGE", "SANDBOX", "FAKE_MESSAGE", "EMAIL",
                "Disabled Fake", "FAKE_V1", false, false));
    }

    @Test
    void disabledSmtpProjectionRemainsVisibleButDoesNotBecomeTheDefaultQueryProvider() {
        NotificationProviderDescriptor smtp = smtpDescriptor(false);
        when(providerRouter.catalog()).thenReturn(List.of(inAppDescriptor(), smtp));
        when(providerAdminService.overview()).thenReturn(providerOverview(
            providerStatus(inAppDescriptor(), true), providerStatus(smtp, false)));
        stubBatchList(List.of());
        stubAudienceList(List.of());
        stubOutboxList(List.of());

        List<NotificationProviderOptionResponse> options = service.providerOptions();
        NoticeDeliveryOverviewResponse overview = service.overview(new NoticeDeliveryQuery());

        assertThat(options).extracting(NotificationProviderOptionResponse::providerName)
            .containsExactly("IN_APP_MESSAGE", "SMTP_EMAIL");
        assertThat(options.get(1).enabled()).isFalse();
        assertThat(options.get(1).defaultProvider()).isFalse();
        assertThat(overview.providerName).isEqualTo("IN_APP_MESSAGE");
        verify(providerAdminService).overview();
    }

    @Test
    void emailOutboxProjectionUsesOnlyMaskedSnapshotEvidence() throws ReflectiveOperationException {
        NotificationProviderDescriptor smtp = smtpDescriptor(true);
        when(providerRouter.catalog()).thenReturn(List.of(inAppDescriptor(), smtp));
        NoticeOutbox outbox = emailOutbox(901L, "SENT");
        outbox.requestSummaryJson = "{\"recipientAddress\":\"alice@example.test\"}";
        outbox.responseSummaryJson = "{\"secret\":\"must-not-leak\"}";
        outbox.metadataJson = "{\"raw\":true}";
        stubOutboxPage(List.of(outbox));
        NoticeEmailDelivery delivery = emailDelivery(outbox.id, "ACCEPTED", "NOT_OBSERVED");
        delivery.recipientAddress = "alice@example.test";
        delivery.subjectSnapshot = "private subject";
        delivery.bodyTextSnapshot = "private body";
        stubEmailDeliveryList(List.of(delivery));

        PageResult<NoticeDeliveryOutboxResponse> response = service.outboxes(
            providerQuery("SANDBOX", "SMTP_EMAIL"));

        assertThat(response.getRecords()).hasSize(1);
        NoticeDeliveryOutboxResponse record = response.getRecords().get(0);
        assertThat(record.maskedRecipient).isEqualTo("a***e@e***e.test");
        assertThat(record.acceptanceStatus).isEqualTo("ACCEPTED");
        assertThat(record.downstreamStatus).isEqualTo("NOT_OBSERVED");
        assertThat(record.status).isEqualTo("SENT");
        assertThat(record.statusText).isEqualTo("SMTP 已接受");
        assertThat(List.of(record.getClass().getFields())).extracting(Field::getName)
            .doesNotContain("recipientAddress", "subjectSnapshot", "bodyTextSnapshot",
                "requestSummaryJson", "responseSummaryJson", "metadataJson", "secret");
    }

    @Test
    void emailOutboxEvidenceJoinSelectsOnlySafeColumns() {
        NotificationProviderDescriptor smtp = smtpDescriptor(true);
        when(providerRouter.catalog()).thenReturn(List.of(inAppDescriptor(), smtp));
        NoticeOutbox outbox = emailOutbox(901L, "SENT");
        stubOutboxPage(List.of(outbox));
        stubEmailDeliveryList(List.of(emailDelivery(outbox.id, "ACCEPTED", "NOT_OBSERVED")));

        service.outboxes(providerQuery("SANDBOX", "SMTP_EMAIL"));

        assertOnlySafeEmailEvidenceColumns(captureEmailDeliveryWrapper());
    }

    @Test
    void emailOverviewEvidenceJoinSelectsOnlySafeColumns() {
        NotificationProviderDescriptor smtp = smtpDescriptor(true);
        NotificationProvider smtpProvider = mock(NotificationProvider.class);
        when(smtpProvider.descriptor()).thenReturn(smtp);
        when(providerRouter.require("SANDBOX", "SMTP_EMAIL")).thenReturn(smtpProvider);
        NoticeOutbox outbox = emailOutbox(901L, "SENT");
        stubOutboxList(List.of(outbox));
        stubEmailDeliveryList(List.of(emailDelivery(outbox.id, "ACCEPTED", "NOT_OBSERVED")));

        service.overview(providerQuery("SANDBOX", "SMTP_EMAIL"));

        assertOnlySafeEmailEvidenceColumns(captureEmailDeliveryWrapper());
    }

    @Test
    void emailOutboxWrapperKeepsProviderStatusIdAndTimeFilters() {
        NotificationProviderDescriptor smtp = smtpDescriptor(true);
        when(providerRouter.catalog()).thenReturn(List.of(inAppDescriptor(), smtp));
        stubOutboxPage(List.of());
        NoticeDeliveryQuery query = providerQuery("SANDBOX", "SMTP_EMAIL");
        query.status = " sent ";
        query.batchId = 81L;
        query.noticeId = 82L;
        query.userId = 83L;
        query.startTime = time(9);
        query.endTime = time(13);

        service.outboxes(query);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<QueryWrapper<NoticeOutbox>> wrapperCaptor = ArgumentCaptor.forClass(QueryWrapper.class);
        verify(outboxMapper).selectPage(any(Page.class), wrapperCaptor.capture());
        QueryWrapper<NoticeOutbox> wrapper = wrapperCaptor.getValue();
        assertThat(wrapper.getSqlSegment())
            .contains("provider_env", "provider_name", "channel", "status", "batch_id", "notice_id", "user_id",
                "create_time");
        assertThat(wrapperValues(wrapper))
            .contains(0, "SANDBOX", "SMTP_EMAIL", "EMAIL", "SENT", 81L, 82L, 83L, time(9), time(13));
    }

    @Test
    void emailSentCountsAsAcceptedWithoutClaimingDeliveryBounceOrRead() {
        NotificationProviderDescriptor smtp = smtpDescriptor(true);
        NotificationProvider smtpProvider = mock(NotificationProvider.class);
        when(smtpProvider.descriptor()).thenReturn(smtp);
        when(providerRouter.require("SANDBOX", "SMTP_EMAIL")).thenReturn(smtpProvider);
        NoticeOutbox sent = emailOutbox(901L, "SENT");
        NoticeOutbox retryWaiting = emailOutbox(902L, "RETRY_WAITING");
        NoticeOutbox failed = emailOutbox(903L, "FAILED");
        stubOutboxList(List.of(sent, retryWaiting, failed));
        stubEmailDeliveryList(List.of(emailDelivery(sent.id, "ACCEPTED", "NOT_OBSERVED")));

        NoticeDeliveryOverviewResponse response = service.overview(
            providerQuery("SANDBOX", "SMTP_EMAIL"));

        assertThat(response.batchCount).isZero();
        assertThat(response.audienceCount).isZero();
        assertThat(response.sentCount).isZero();
        assertThat(response.acceptedCount).isEqualTo(1);
        assertThat(response.retryWaitingCount).isEqualTo(1);
        assertThat(response.failedCount).isEqualTo(1);
        assertThat(response.deliveredCount).isZero();
        assertThat(response.bouncedCount).isZero();
        assertThat(response.readCount).isZero();
        assertThat(response.readRate).isEqualByComparingTo("0.0000");
        assertThat(response.dataGaps)
            .containsExactly("Mailpit 仅证明 SMTP 接受，不证明互联网送达或退信。");
    }

    @Test
    void inAppReadRateAndAnnouncementAnalyticsExcludeDirectEmailOutboxes() {
        NoticeDeliveryBatch inAppBatch = batch(30L);
        stubBatchList(List.of(inAppBatch));
        stubAudienceList(List.of(
            audience(301L, 30L, 7001L, "SENT", "READ", time(10)),
            audience(302L, 30L, 7002L, "SENT", "UNREAD", time(11))));
        NoticeOutbox directEmail = emailOutbox(901L, "SENT");
        directEmail.noticeId = 930L;
        stubOutboxList(List.of(directEmail));
        stubEmailDeliveryList(List.of(emailDelivery(directEmail.id, "ACCEPTED", "NOT_OBSERVED")));

        NoticeDeliveryOverviewResponse overview = service.overview(new NoticeDeliveryQuery());
        PageResult<NoticeDeliveryBatchResponse> batches = service.batches(new NoticeDeliveryQuery());

        assertThat(overview.audienceCount).isEqualTo(2);
        assertThat(overview.readCount).isEqualTo(1);
        assertThat(overview.readRate).isEqualByComparingTo("0.5000");
        assertThat(overview.acceptedCount).isZero();
        assertThat(batches.getRecords()).extracting(record -> record.batchId).containsExactly(30L);
    }

    @Test
    void deliveryQueryOnlyNormalizesProviderSelection() {
        NoticeDeliveryQuery query = new NoticeDeliveryQuery();
        assertThat(query.providerEnv()).isNull();
        assertThat(query.providerName()).isNull();

        query.providerEnv = " sandbox ";
        query.providerName = " fake_message ";

        assertThat(query.providerEnv()).isEqualTo("SANDBOX");
        assertThat(query.providerName()).isEqualTo("FAKE_MESSAGE");
    }

    @Test
    void nonEmptyProviderSelectionIsValidatedByRouter() {
        NoticeDeliveryQuery query = new NoticeDeliveryQuery();
        query.providerEnv = " sandbox ";
        query.providerName = " fake_message ";
        when(providerRouter.require("SANDBOX", "FAKE_MESSAGE"))
            .thenThrow(com.example.venue.common.BusinessException.notFound("NOTICE_PROVIDER_NOT_FOUND"));

        assertThatThrownBy(() -> service.overview(query))
            .isInstanceOf(com.example.venue.common.BusinessException.class)
            .hasMessageContaining("NOTICE_PROVIDER_NOT_FOUND");
        verify(providerRouter).require("SANDBOX", "FAKE_MESSAGE");
    }

    @Test
    void experimentResultsUseRequestedProviderScopeAndExposeResolvedMetadata() {
        NoticeExperiment experiment = new NoticeExperiment();
        experiment.id = 11L;
        experiment.experimentNo = "NE-11";
        experiment.name = "Provider 无关实验";
        experiment.status = "RUNNING";
        experiment.targetMetric = "READ_RATE";
        experiment.sampleScope = "ALL_USERS";
        when(experimentMapper.selectById(11L)).thenReturn(experiment);
        when(variantMapper.findByExperimentId(11L)).thenReturn(List.of());
        NotificationProviderDescriptor descriptor = new NotificationProviderDescriptor(
            "notice.fake", "MESSAGE", "SANDBOX", "FAKE_MESSAGE", "EMAIL",
            "Fake Message", "FAKE_V1", true);
        NotificationProvider provider = mock(NotificationProvider.class);
        when(provider.descriptor()).thenReturn(descriptor);
        when(providerRouter.require("SANDBOX", "FAKE_MESSAGE")).thenReturn(provider);
        NoticeDeliveryBatch fakeBatch = batch(30L);
        fakeBatch.providerEnv = "SANDBOX";
        fakeBatch.providerName = "FAKE_MESSAGE";
        stubBatchList(List.of(fakeBatch));
        stubAudienceList(List.of(audience(301L, 30L, 7001L, "SENT", "READ", time(10))));
        NoticeDeliveryQuery query = providerQuery("SANDBOX", "FAKE_MESSAGE");

        var response = service.experimentResults(11L, query);

        verify(providerRouter).require("SANDBOX", "FAKE_MESSAGE");
        assertThat(response.providerKey).isEqualTo("notice.fake");
        assertThat(response.providerEnv).isEqualTo("SANDBOX");
        assertThat(response.providerName).isEqualTo("FAKE_MESSAGE");
        assertThat(response.channel).isEqualTo("EMAIL");
        assertThat(response.displayName).isEqualTo("Fake Message");
        assertThat(response.configVersion).isEqualTo("FAKE_V1");
        assertThat(response.audienceCount).isZero();
        assertThat(response.readCount).isZero();
    }

    @Test
    void batchesWithFailedDeliveryStatusOnlyReturnsMatchingBatchesAndSliceCounts() {
        NoticeDeliveryBatch failedBatch = batch(30L);
        NoticeDeliveryBatch sentBatch = batch(20L);
        stubBatchPage(List.of(failedBatch, sentBatch));
        stubBatchList(List.of(failedBatch, sentBatch));
        stubAudienceList(List.of(
            audience(301L, 30L, 7001L, "FAILED", "UNREAD", time(10)),
            audience(302L, 30L, 7002L, "SENT", "READ", time(11)),
            audience(201L, 20L, 7003L, "SENT", "READ", time(12))));

        NoticeDeliveryQuery query = new NoticeDeliveryQuery();
        query.deliveryStatus = "failed";

        PageResult<NoticeDeliveryBatchResponse> response = service.batches(query);

        assertThat(response.getTotal()).isEqualTo(1);
        assertThat(response.getRecords()).extracting(record -> record.batchId)
            .containsExactly(30L);
        NoticeDeliveryBatchResponse record = response.getRecords().get(0);
        assertThat(record.sentCount).isZero();
        assertThat(record.failedCount).isEqualTo(1);
        assertThat(record.readCount).isZero();
        assertThat(record.unreadCount).isEqualTo(1);
    }

    @Test
    void batchesWithUserIdFiltersBeforePagingAcrossAllCandidateBatches() {
        NoticeDeliveryBatch newestBatch = batch(30L);
        NoticeDeliveryBatch middleBatch = batch(20L);
        NoticeDeliveryBatch matchingBatch = batch(10L);
        stubBatchPage(List.of(newestBatch));
        stubBatchList(List.of(newestBatch, middleBatch, matchingBatch));
        stubAudienceList(List.of(
            audience(301L, 30L, 8001L, "SENT", "UNREAD", time(10)),
            audience(201L, 20L, 8002L, "FAILED", "UNREAD", time(11)),
            audience(101L, 10L, 9001L, "SENT", "UNREAD", time(12))));

        NoticeDeliveryQuery query = new NoticeDeliveryQuery();
        query.userId = 9001L;
        query.setPageSize(1);

        PageResult<NoticeDeliveryBatchResponse> response = service.batches(query);

        assertThat(response.getTotal()).isEqualTo(1);
        assertThat(response.getRecords()).extracting(record -> record.batchId)
            .containsExactly(10L);
        NoticeDeliveryBatchResponse record = response.getRecords().get(0);
        assertThat(record.sentCount).isEqualTo(1);
        assertThat(record.failedCount).isZero();
        assertThat(record.unreadCount).isEqualTo(1);
    }

    @Test
    void overviewFailureReasonsStayInsideFilteredAudienceSlice() {
        NoticeDeliveryBatch newestBatch = batch(30L);
        NoticeDeliveryBatch olderBatch = batch(20L);
        stubBatchList(List.of(newestBatch, olderBatch));
        stubAudienceList(List.of(
            audience(401L, 30L, 7001L, "FAILED", "UNREAD", time(10)),
            audience(402L, 30L, 7002L, "FAILED", "UNREAD", time(10)),
            audience(403L, 20L, 7001L, "FAILED", "UNREAD", time(1))));
        stubOutboxList(List.of(
            outbox(30L, 401L, 7001L, "MATCHED_FAILURE", time(10)),
            outbox(30L, 401L, 7001L, "OUTSIDE_OUTBOX_TIME_FAILURE", time(14)),
            outbox(30L, 402L, 7002L, "OTHER_USER_FAILURE", time(10)),
            outbox(20L, 403L, 7001L, "OUTSIDE_TIME_FAILURE", time(1))));

        NoticeDeliveryQuery query = new NoticeDeliveryQuery();
        query.userId = 7001L;
        query.deliveryStatus = "FAILED";
        query.startTime = time(9);
        query.endTime = time(13);

        NoticeDeliveryOverviewResponse response = service.overview(query);

        assertThat(response.audienceCount).isEqualTo(1);
        assertThat(response.failedCount).isEqualTo(1);
        assertThat(response.failureReasonCounts).hasSize(1);
        assertThat(response.failureReasonCounts).containsEntry("MATCHED_FAILURE", 1L);
    }

    @Test
    void audiencesExposeNewestRetryableOutboxIdForFailedAudience() throws ReflectiveOperationException {
        NoticeDeliveryBatch deliveryBatch = batch(30L);
        stubBatchById(deliveryBatch);
        NoticeAudienceSnapshot failedAudience = audience(401L, 30L, 7001L, "FAILED", "UNREAD", time(10));
        failedAudience.lastDeliveryLogId = 8101L;
        stubAudiencePage(List.of(failedAudience));
        NoticeOutbox olderFailedOutbox = outbox(30L, 401L, 7001L, "OLDER_FAILURE", time(10));
        olderFailedOutbox.id = 9101L;
        NoticeOutbox newestFailedOutbox = outbox(30L, 401L, 7001L, "NEWEST_FAILURE", time(11));
        newestFailedOutbox.id = 9102L;
        NoticeOutbox sentOutbox = outbox(30L, 401L, 7001L, "SENT_IGNORED", time(12));
        sentOutbox.id = 9103L;
        sentOutbox.status = "SENT";
        NoticeOutbox otherProviderOutbox = outbox(30L, 401L, 7001L, "PROVIDER_IGNORED", time(13));
        otherProviderOutbox.id = 9104L;
        otherProviderOutbox.providerName = "EMAIL";
        stubOutboxList(List.of(olderFailedOutbox, newestFailedOutbox, sentOutbox, otherProviderOutbox));

        PageResult<NoticeAudienceResponse> response = service.audiences(30L, new NoticeDeliveryQuery());

        assertThat(response.getRecords()).hasSize(1);
        NoticeAudienceResponse audience = response.getRecords().get(0);
        assertThat(List.of(audience.getClass().getFields()))
            .extracting(Field::getName)
            .contains("outboxId");
        assertThat(audience.getClass().getField("outboxId").get(audience)).isEqualTo(9102L);
        assertThat(audience.lastDeliveryLogId).isEqualTo(8101L);
    }

    private void stubBatchById(NoticeDeliveryBatch batch) {
        lenient().when(batchMapper.selectById(batch.id)).thenReturn(batch);
    }

    private void stubBatchPage(List<NoticeDeliveryBatch> batches) {
        lenient().when(batchMapper.selectPage(any(Page.class), any())).thenAnswer(invocation -> {
            Page<NoticeDeliveryBatch> page = invocation.getArgument(0);
            page.setRecords(batches);
            page.setTotal(batches.size());
            return page;
        });
    }

    private void stubBatchList(List<NoticeDeliveryBatch> batches) {
        lenient().when(batchMapper.selectList(any())).thenReturn(batches);
    }

    private void stubAudienceList(List<NoticeAudienceSnapshot> audiences) {
        lenient().when(audienceMapper.selectList(any())).thenAnswer(invocation -> {
            Collection<Object> values = wrapperValues(invocation.getArgument(0));
            return audiences.stream()
                .filter(audience -> !containsAny(values, 10L, 20L, 30L)
                    || values.contains(audience.batchId))
                .filter(audience -> !containsAny(values, 7001L, 7002L, 7003L, 8001L, 8002L, 9001L)
                    || values.contains(audience.userId))
                .filter(audience -> !containsAny(values, "SENT", "FAILED")
                    || values.contains(audience.deliveryStatus))
                .filter(audience -> !containsAny(values, "READ", "UNREAD")
                    || values.contains(audience.readStatus))
                .filter(audience -> inTimeRange(values, audience.createTime))
                .toList();
        });
    }

    private void stubAudiencePage(List<NoticeAudienceSnapshot> audiences) {
        lenient().when(audienceMapper.selectPage(any(Page.class), any())).thenAnswer(invocation -> {
            Page<NoticeAudienceSnapshot> page = invocation.getArgument(0);
            Collection<Object> values = wrapperValues(invocation.getArgument(1));
            List<NoticeAudienceSnapshot> records = audiences.stream()
                .filter(audience -> !containsAny(values, 10L, 20L, 30L)
                    || values.contains(audience.batchId))
                .filter(audience -> !containsAny(values, 7001L, 7002L, 7003L, 8001L, 8002L, 9001L)
                    || values.contains(audience.userId))
                .filter(audience -> !containsAny(values, "SENT", "FAILED", "RETRY_WAITING")
                    || values.contains(audience.deliveryStatus))
                .filter(audience -> !containsAny(values, "READ", "UNREAD")
                    || values.contains(audience.readStatus))
                .filter(audience -> inTimeRange(values, audience.createTime))
                .toList();
            page.setRecords(records);
            page.setTotal(records.size());
            return page;
        });
    }

    private void stubOutboxList(List<NoticeOutbox> outboxes) {
        lenient().when(outboxMapper.selectList(any())).thenAnswer(invocation -> {
            Collection<Object> values = wrapperValues(invocation.getArgument(0));
            return outboxes.stream()
                .filter(outbox -> !containsAny(values, 20L, 30L) || values.contains(outbox.batchId))
                .filter(outbox -> !containsAny(values, 401L, 402L, 403L) || values.contains(outbox.audienceId))
                .filter(outbox -> !containsAny(values, "DEV") || values.contains(outbox.providerEnv))
                .filter(outbox -> !containsAny(values, "IN_APP_MESSAGE", "EMAIL") || values.contains(outbox.providerName))
                .filter(outbox -> !containsAny(values, "FAILED", "RETRY_WAITING", "SENT") || values.contains(outbox.status))
                .filter(outbox -> inTimeRange(values, outbox.createTime))
                .toList();
        });
    }

    private void stubOutboxPage(List<NoticeOutbox> outboxes) {
        lenient().when(outboxMapper.selectPage(any(Page.class), any())).thenAnswer(invocation -> {
            Page<NoticeOutbox> page = invocation.getArgument(0);
            Collection<Object> values = wrapperValues(invocation.getArgument(1));
            List<NoticeOutbox> records = outboxes.stream()
                .filter(outbox -> !containsAny(values, "DEV", "SANDBOX") || values.contains(outbox.providerEnv))
                .filter(outbox -> !containsAny(values, "IN_APP_MESSAGE", "SMTP_EMAIL")
                    || values.contains(outbox.providerName))
                .filter(outbox -> !containsAny(values, "IN_APP", "EMAIL") || values.contains(outbox.channel))
                .filter(outbox -> !containsAny(values, "FAILED", "RETRY_WAITING", "SENT")
                    || values.contains(outbox.status))
                .filter(outbox -> inTimeRange(values, outbox.createTime))
                .toList();
            page.setRecords(records);
            page.setTotal(records.size());
            return page;
        });
    }

    private void stubEmailDeliveryList(List<NoticeEmailDelivery> deliveries) {
        lenient().when(emailDeliveryMapper.selectList(any())).thenAnswer(invocation -> {
            Collection<Object> values = wrapperValues(invocation.getArgument(0));
            return deliveries.stream()
                .filter(delivery -> values.isEmpty() || values.contains(delivery.outboxId))
                .toList();
        });
    }

    private QueryWrapper<NoticeEmailDelivery> captureEmailDeliveryWrapper() {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<QueryWrapper<NoticeEmailDelivery>> wrapperCaptor = ArgumentCaptor.forClass(QueryWrapper.class);
        verify(emailDeliveryMapper).selectList(wrapperCaptor.capture());
        return wrapperCaptor.getValue();
    }

    private void assertOnlySafeEmailEvidenceColumns(QueryWrapper<NoticeEmailDelivery> wrapper) {
        assertThat(wrapper.getSqlSelect()).isEqualTo(
            "outbox_id,recipient_masked,acceptance_status,downstream_status,accepted_at");
        assertThat(wrapper.getSqlSelect()).doesNotContain(
            "recipient_address", "recipient_user_id", "subject_snapshot", "body_text_snapshot",
            "message_id_header", "template_key", "template_version");
    }

    private Collection<Object> wrapperValues(Object wrapper) {
        if (wrapper instanceof QueryWrapper<?> queryWrapper) {
            queryWrapper.getSqlSegment();
            List<Object> values = new ArrayList<>();
            for (Object value : queryWrapper.getParamNameValuePairs().values()) {
                if (value instanceof Collection<?> collection) {
                    values.addAll(collection);
                } else {
                    values.add(value);
                }
            }
            return values;
        }
        return Set.of();
    }

    private boolean containsAny(Collection<Object> values, Object... expectedValues) {
        return List.of(expectedValues).stream().anyMatch(values::contains);
    }

    private boolean inTimeRange(Collection<Object> values, LocalDateTime createTime) {
        List<LocalDateTime> times = values.stream()
            .filter(LocalDateTime.class::isInstance)
            .map(LocalDateTime.class::cast)
            .toList();
        if (times.isEmpty()) {
            return true;
        }
        LocalDateTime start = times.stream().min(LocalDateTime::compareTo).orElse(null);
        LocalDateTime end = times.size() > 1 ? times.stream().max(LocalDateTime::compareTo).orElse(null) : null;
        return (start == null || !createTime.isBefore(start))
            && (end == null || createTime.isBefore(end));
    }

    private NoticeDeliveryBatch batch(Long id) {
        NoticeDeliveryBatch batch = new NoticeDeliveryBatch();
        batch.id = id;
        batch.batchNo = "BATCH-" + id;
        batch.noticeId = 900L + id;
        batch.targetScope = "SPECIFIC_USERS";
        batch.audienceCount = 2;
        batch.deliverableCount = 2;
        batch.excludedCount = 0;
        batch.status = "COMPLETED";
        batch.providerEnv = "DEV";
        batch.providerName = "IN_APP_MESSAGE";
        batch.configVersion = "IN_APP_MESSAGE_V1";
        batch.deleted = 0;
        return batch;
    }

    private NotificationProviderDescriptor inAppDescriptor() {
        return new NotificationProviderDescriptor(
            "notice.in_app.dev", "MESSAGE", "DEV", "IN_APP_MESSAGE", "IN_APP",
            "站内消息", "IN_APP_MESSAGE_V1", true);
    }

    private NotificationProviderDescriptor smtpDescriptor(boolean enabled) {
        return new NotificationProviderDescriptor(
            "notice.smtp.sandbox", "MESSAGE", "SANDBOX", "SMTP_EMAIL", "EMAIL",
            "SMTP Email", "SMTP_EMAIL_V1", enabled);
    }

    private ProviderOverviewResponse providerOverview(ProviderStatusResponse... statuses) {
        ProviderOverviewResponse response = new ProviderOverviewResponse();
        response.providers.addAll(List.of(statuses));
        return response;
    }

    private ProviderStatusResponse providerStatus(NotificationProviderDescriptor descriptor, boolean enabled) {
        ProviderStatusResponse status = new ProviderStatusResponse();
        status.providerKey = descriptor.providerKey();
        status.providerDomain = descriptor.providerDomain();
        status.providerEnv = descriptor.providerEnv();
        status.providerName = descriptor.providerName();
        status.displayName = descriptor.displayName();
        status.enabled = enabled;
        status.configVersion = descriptor.configVersion();
        return status;
    }

    private NoticeDeliveryQuery providerQuery(String providerEnv, String providerName) {
        NoticeDeliveryQuery query = new NoticeDeliveryQuery();
        query.providerEnv = providerEnv;
        query.providerName = providerName;
        return query;
    }

    private NoticeAudienceSnapshot audience(Long id, Long batchId, Long userId, String deliveryStatus,
                                            String readStatus, LocalDateTime createTime) {
        NoticeAudienceSnapshot audience = new NoticeAudienceSnapshot();
        audience.id = id;
        audience.batchId = batchId;
        audience.noticeId = 900L + batchId;
        audience.userId = userId;
        audience.deliveryStatus = deliveryStatus;
        audience.readStatus = readStatus;
        audience.createTime = createTime;
        audience.deleted = 0;
        return audience;
    }

    private NoticeOutbox outbox(Long batchId, Long audienceId, Long userId, String errorCode,
                                LocalDateTime createTime) {
        NoticeOutbox outbox = new NoticeOutbox();
        outbox.batchId = batchId;
        outbox.audienceId = audienceId;
        outbox.userId = userId;
        outbox.status = "FAILED";
        outbox.providerEnv = "DEV";
        outbox.providerName = "IN_APP_MESSAGE";
        outbox.lastErrorCode = errorCode;
        outbox.createTime = createTime;
        outbox.deleted = 0;
        return outbox;
    }

    private NoticeOutbox emailOutbox(Long id, String status) {
        NoticeOutbox outbox = new NoticeOutbox();
        outbox.id = id;
        outbox.outboxNo = "OUTBOX-" + id;
        outbox.noticeId = 930L;
        outbox.userId = 7001L;
        outbox.eventType = "RESERVATION_CONFIRMED";
        outbox.recipientKind = "USER";
        outbox.channel = "EMAIL";
        outbox.providerKey = "notice.smtp.sandbox";
        outbox.providerEnv = "SANDBOX";
        outbox.providerName = "SMTP_EMAIL";
        outbox.configVersion = "SMTP_EMAIL_V1";
        outbox.templateKey = "reservation-confirmed";
        outbox.templateVersion = "V1";
        outbox.status = status;
        outbox.attemptCount = 1;
        outbox.createTime = time(10);
        outbox.updateTime = time(11);
        outbox.deleted = 0;
        return outbox;
    }

    private NoticeEmailDelivery emailDelivery(Long outboxId, String acceptanceStatus, String downstreamStatus) {
        NoticeEmailDelivery delivery = new NoticeEmailDelivery();
        delivery.outboxId = outboxId;
        delivery.recipientMasked = "a***e@e***e.test";
        delivery.acceptanceStatus = acceptanceStatus;
        delivery.downstreamStatus = downstreamStatus;
        delivery.acceptedAt = time(11);
        return delivery;
    }

    private LocalDateTime time(int hour) {
        return LocalDateTime.of(2026, 7, 5, hour, 0);
    }
}
