package com.example.venue.notice;

import com.example.venue.notice.controller.NoticeController;
import com.example.venue.common.AuditLog;
import com.example.venue.common.PageResult;
import com.example.venue.notice.dto.NoticeDeliveryOutboxResponse;
import com.example.venue.notice.dto.NoticeDeliveryOverviewResponse;
import com.example.venue.notice.dto.NoticeDeliveryQuery;
import com.example.venue.notice.dto.NotificationProviderOptionResponse;
import com.example.venue.notice.dto.NoticeExperimentResponse;
import com.example.venue.notice.dto.NoticeExperimentResultResponse;
import com.example.venue.notice.dto.NoticeRequest;
import com.example.venue.notice.dto.SaveNoticeExperimentRequest;
import com.example.venue.notice.entity.Notice;
import com.example.venue.notice.entity.NoticeAudienceSnapshot;
import com.example.venue.notice.entity.NoticeDeliveryBatch;
import com.example.venue.notice.entity.NoticeOutbox;
import com.example.venue.notice.mapper.NoticeAudienceSnapshotMapper;
import com.example.venue.notice.mapper.NoticeDeliveryBatchMapper;
import com.example.venue.notice.mapper.NoticeEmailDeliveryMapper;
import com.example.venue.notice.mapper.NoticeExperimentMapper;
import com.example.venue.notice.mapper.NoticeExperimentVariantMapper;
import com.example.venue.notice.mapper.NoticeOutboxMapper;
import com.example.venue.notice.service.NoticeAnalyticsService;
import com.example.venue.notice.service.NoticeAudienceService;
import com.example.venue.notice.service.NoticeDeliveryService;
import com.example.venue.notice.service.NoticeExperimentService;
import com.example.venue.notice.service.NoticeService;
import com.example.venue.provider.service.ProviderAdminService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(NoticeController.class)
@ContextConfiguration(classes = {NoticeController.class, NoticeAdminControllerTest.MethodSecurityConfig.class})
class NoticeAdminControllerTest {
    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private NoticeService noticeService;
    @MockBean
    private NoticeAudienceService audienceService;
    @MockBean
    private NoticeDeliveryService deliveryService;
    @MockBean
    private NoticeExperimentService experimentService;
    @MockBean
    private NoticeAnalyticsService analyticsService;

    @Test
    void publishRequiresNoticeManageAuthorityOnly() throws Exception {
        Notice notice = new Notice();
        notice.id = 51L;
        notice.title = "公告";
        when(noticeService.publish(any())).thenReturn(notice);
        String payload = """
            {"title":"公告","content":"内容","type":"GLOBAL","status":"PUBLISHED","targetScope":"ALL_USERS"}
            """;

        mockMvc.perform(post("/api/admin/notices")
                .with(csrf())
                .with(user("admin").authorities(new SimpleGrantedAuthority("system:view")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload))
            .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/admin/notices")
                .with(csrf())
                .with(user("venue-admin").authorities(new SimpleGrantedAuthority("venue-admin:view")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload))
            .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/admin/notices")
                .with(csrf())
                .with(user("admin").authorities(new SimpleGrantedAuthority("notice:manage")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.id").value(51));
    }

    @Test
    void deliveryOverviewRequiresAnalyticsAuthority() throws Exception {
        NoticeDeliveryOverviewResponse overview = new NoticeDeliveryOverviewResponse();
        overview.providerEnv = "DEV";
        overview.providerName = "IN_APP_MESSAGE";
        when(analyticsService.overview(any(NoticeDeliveryQuery.class))).thenReturn(overview);

        mockMvc.perform(get("/api/admin/notices/delivery/overview")
                .with(user("admin").authorities(new SimpleGrantedAuthority("notice:analytics"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.providerEnv").value("DEV"))
            .andExpect(jsonPath("$.data.providerName").value("IN_APP_MESSAGE"));

        mockMvc.perform(get("/api/admin/notices/delivery/overview")
                .with(user("admin").authorities(new SimpleGrantedAuthority("notice:manage"))))
            .andExpect(status().isForbidden());
    }

    @Test
    void deliveryProviderCatalogRequiresAnalyticsAuthorityAndExposesOnlyDescriptorFields() throws Exception {
        when(analyticsService.providerOptions()).thenReturn(List.of(new NotificationProviderOptionResponse(
            "notice.in_app.dev", "MESSAGE", "DEV", "IN_APP_MESSAGE", "IN_APP",
            "站内消息", "IN_APP_MESSAGE_V1", true, true)));

        mockMvc.perform(get("/api/admin/notices/delivery/providers")
                .with(user("admin").authorities(new SimpleGrantedAuthority("notice:analytics"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[0].providerKey").value("notice.in_app.dev"))
            .andExpect(jsonPath("$.data[0].providerDomain").value("MESSAGE"))
            .andExpect(jsonPath("$.data[0].providerEnv").value("DEV"))
            .andExpect(jsonPath("$.data[0].providerName").value("IN_APP_MESSAGE"))
            .andExpect(jsonPath("$.data[0].channel").value("IN_APP"))
            .andExpect(jsonPath("$.data[0].displayName").value("站内消息"))
            .andExpect(jsonPath("$.data[0].configVersion").value("IN_APP_MESSAGE_V1"))
            .andExpect(jsonPath("$.data[0].enabled").value(true))
            .andExpect(jsonPath("$.data[0].defaultProvider").value(true))
            .andExpect(jsonPath("$.data[0].secret").doesNotExist())
            .andExpect(jsonPath("$.data[0].lastFailureMessage").doesNotExist())
            .andExpect(jsonPath("$.data[0].metadataJson").doesNotExist());

        mockMvc.perform(get("/api/admin/notices/delivery/providers")
                .with(user("admin").authorities(new SimpleGrantedAuthority("notice:manage"))))
            .andExpect(status().isForbidden());
    }

    @Test
    void deliveryOutboxesRequireAnalyticsAuthorityAndExposeOnlySafeEmailEvidence() throws Exception {
        NoticeDeliveryOutboxResponse outbox = new NoticeDeliveryOutboxResponse();
        outbox.outboxId = 901L;
        outbox.outboxNo = "OUTBOX-901";
        outbox.eventType = "RESERVATION_CONFIRMED";
        outbox.recipientKind = "USER";
        outbox.maskedRecipient = "a***e@e***e.test";
        outbox.channel = "EMAIL";
        outbox.providerKey = "notice.smtp.sandbox";
        outbox.providerEnv = "SANDBOX";
        outbox.providerName = "SMTP_EMAIL";
        outbox.configVersion = "SMTP_EMAIL_V1";
        outbox.templateKey = "reservation-confirmed";
        outbox.templateVersion = "V1";
        outbox.status = "SENT";
        outbox.statusText = "SMTP 已接受";
        outbox.acceptanceStatus = "ACCEPTED";
        outbox.downstreamStatus = "NOT_OBSERVED";
        outbox.attemptCount = 1;
        outbox.createTime = LocalDateTime.of(2026, 7, 15, 10, 0);
        outbox.updateTime = LocalDateTime.of(2026, 7, 15, 10, 1);
        outbox.acceptedAt = LocalDateTime.of(2026, 7, 15, 10, 1);
        when(analyticsService.outboxes(any(NoticeDeliveryQuery.class))).thenReturn(
            PageResult.of(List.of(outbox), 1, 1, 10));

        mockMvc.perform(get("/api/admin/notices/delivery/outboxes")
                .param("providerEnv", "SANDBOX")
                .param("providerName", "SMTP_EMAIL")
                .with(user("admin").authorities(new SimpleGrantedAuthority("notice:analytics"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.records[0].maskedRecipient").value("a***e@e***e.test"))
            .andExpect(jsonPath("$.data.records[0].acceptanceStatus").value("ACCEPTED"))
            .andExpect(jsonPath("$.data.records[0].downstreamStatus").value("NOT_OBSERVED"))
            .andExpect(jsonPath("$.data.records[0].statusText").value("SMTP 已接受"))
            .andExpect(jsonPath("$.data.records[0].recipientAddress").doesNotExist())
            .andExpect(jsonPath("$.data.records[0].subjectSnapshot").doesNotExist())
            .andExpect(jsonPath("$.data.records[0].bodyTextSnapshot").doesNotExist())
            .andExpect(jsonPath("$.data.records[0].requestSummaryJson").doesNotExist())
            .andExpect(jsonPath("$.data.records[0].responseSummaryJson").doesNotExist())
            .andExpect(jsonPath("$.data.records[0].metadataJson").doesNotExist())
            .andExpect(jsonPath("$.data.records[0].secret").doesNotExist());

        mockMvc.perform(get("/api/admin/notices/delivery/outboxes")
                .with(user("admin").authorities(new SimpleGrantedAuthority("notice:manage"))))
            .andExpect(status().isForbidden());

        ArgumentCaptor<NoticeDeliveryQuery> queryCaptor = ArgumentCaptor.forClass(NoticeDeliveryQuery.class);
        verify(analyticsService).outboxes(queryCaptor.capture());
        assertThat(queryCaptor.getValue().providerEnv()).isEqualTo("SANDBOX");
        assertThat(queryCaptor.getValue().providerName()).isEqualTo("SMTP_EMAIL");
    }

    @Test
    void deliveryOutboxesBindSupportedQueryFieldsWithoutAcceptingClientChannel() throws Exception {
        when(analyticsService.outboxes(any(NoticeDeliveryQuery.class))).thenReturn(
            PageResult.of(List.of(), 0, 3, 25));

        mockMvc.perform(get("/api/admin/notices/delivery/outboxes")
                .param("providerEnv", "SANDBOX")
                .param("providerName", "SMTP_EMAIL")
                .param("channel", "IN_APP")
                .param("status", "SENT")
                .param("batchId", "81")
                .param("noticeId", "82")
                .param("userId", "83")
                .param("startTime", "2026-07-15T10:15:00")
                .param("endTime", "2026-07-15T11:45:00")
                .param("pageNum", "3")
                .param("pageSize", "25")
                .with(user("admin").authorities(new SimpleGrantedAuthority("notice:analytics"))))
            .andExpect(status().isOk());

        ArgumentCaptor<NoticeDeliveryQuery> queryCaptor = ArgumentCaptor.forClass(NoticeDeliveryQuery.class);
        verify(analyticsService).outboxes(queryCaptor.capture());
        NoticeDeliveryQuery query = queryCaptor.getValue();
        assertThat(query.providerEnv).isEqualTo("SANDBOX");
        assertThat(query.providerName).isEqualTo("SMTP_EMAIL");
        assertThat(query.channel).isNull();
        assertThat(query.status).isEqualTo("SENT");
        assertThat(query.batchId).isEqualTo(81L);
        assertThat(query.noticeId).isEqualTo(82L);
        assertThat(query.userId).isEqualTo(83L);
        assertThat(query.startTime).isEqualTo(LocalDateTime.of(2026, 7, 15, 10, 15));
        assertThat(query.endTime).isEqualTo(LocalDateTime.of(2026, 7, 15, 11, 45));
        assertThat(query.getPageNum()).isEqualTo(3L);
        assertThat(query.getPageSize()).isEqualTo(25L);
    }

    @Test
    void deliveryOutboxesBindDeliveryStatusReadStatusAndExperimentIdViaSetters() throws Exception {
        when(analyticsService.outboxes(any(NoticeDeliveryQuery.class))).thenReturn(
            PageResult.of(List.of(), 0, 1, 10));

        mockMvc.perform(get("/api/admin/notices/delivery/outboxes")
                .param("providerEnv", "SANDBOX")
                .param("providerName", "SMTP_EMAIL")
                .param("deliveryStatus", "FAILED")
                .param("readStatus", "UNREAD")
                .param("experimentId", "11")
                .with(user("admin").authorities(new SimpleGrantedAuthority("notice:analytics"))))
            .andExpect(status().isOk());

        ArgumentCaptor<NoticeDeliveryQuery> queryCaptor = ArgumentCaptor.forClass(NoticeDeliveryQuery.class);
        verify(analyticsService).outboxes(queryCaptor.capture());
        NoticeDeliveryQuery query = queryCaptor.getValue();
        assertThat(query.deliveryStatus).isEqualTo("FAILED");
        assertThat(query.readStatus).isEqualTo("UNREAD");
        assertThat(query.experimentId).isEqualTo(11L);
    }

    @Test
    void createExperimentRequiresAnalyticsAuthorityAndDelegates() throws Exception {
        NoticeExperimentResponse response = new NoticeExperimentResponse();
        response.id = 11L;
        response.name = "暑期通知";
        response.status = "RUNNING";
        when(experimentService.create(any(SaveNoticeExperimentRequest.class))).thenReturn(response);

        mockMvc.perform(post("/api/admin/notices/experiments")
                .with(csrf())
                .with(user("admin").authorities(new SimpleGrantedAuthority("notice:analytics")))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"name":"暑期通知","targetMetric":"READ_RATE","sampleScope":"ALL_USERS",
                     "variants":[{"variantCode":"A","title":"A","content":"A","allocationPercent":50},
                                 {"variantCode":"B","title":"B","content":"B","allocationPercent":50}]}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.id").value(11))
            .andExpect(jsonPath("$.data.name").value("暑期通知"));

        verify(experimentService).create(any(SaveNoticeExperimentRequest.class));
    }

    @Test
    void experimentResultsPassProviderContextAndExposeResolvedMetadata() throws Exception {
        NoticeExperimentResultResponse response = new NoticeExperimentResultResponse();
        response.experimentId = 11L;
        response.providerKey = "notice.fake";
        response.providerEnv = "SANDBOX";
        response.providerName = "FAKE_MESSAGE";
        response.channel = "EMAIL";
        response.displayName = "Fake Message";
        response.configVersion = "FAKE_V1";
        when(analyticsService.experimentResults(any(Long.class), any(NoticeDeliveryQuery.class)))
            .thenReturn(response);

        mockMvc.perform(get("/api/admin/notices/experiments/11/results")
                .param("providerEnv", "SANDBOX")
                .param("providerName", "FAKE_MESSAGE")
                .with(user("admin").authorities(new SimpleGrantedAuthority("notice:analytics"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.providerKey").value("notice.fake"))
            .andExpect(jsonPath("$.data.providerEnv").value("SANDBOX"))
            .andExpect(jsonPath("$.data.providerName").value("FAKE_MESSAGE"))
            .andExpect(jsonPath("$.data.channel").value("EMAIL"))
            .andExpect(jsonPath("$.data.displayName").value("Fake Message"))
            .andExpect(jsonPath("$.data.configVersion").value("FAKE_V1"));

        ArgumentCaptor<NoticeDeliveryQuery> queryCaptor = ArgumentCaptor.forClass(NoticeDeliveryQuery.class);
        verify(analyticsService).experimentResults(eq(11L), queryCaptor.capture());
        assertThat(queryCaptor.getValue().providerEnv()).isEqualTo("SANDBOX");
        assertThat(queryCaptor.getValue().providerName()).isEqualTo("FAKE_MESSAGE");
    }

    @Test
    void adminEndpointsUseSharedContractRoutesAndAnalyticsPermission() throws Exception {
        assertGet("deliveryProviders", "/api/admin/notices/delivery/providers");
        assertGet("deliveryOverview", "/api/admin/notices/delivery/overview", NoticeDeliveryQuery.class);
        assertGet("deliveryOutboxes", "/api/admin/notices/delivery/outboxes", NoticeDeliveryQuery.class);
        assertGet("deliveryBatches", "/api/admin/notices/delivery/batches", NoticeDeliveryQuery.class);
        assertGet("deliveryBatch", "/api/admin/notices/delivery/batches/{id}", Long.class);
        assertGet("deliveryAudiences", "/api/admin/notices/delivery/batches/{id}/audiences",
            Long.class, NoticeDeliveryQuery.class);
        assertPost("createExperiment", "/api/admin/notices/experiments", SaveNoticeExperimentRequest.class);
        assertGet("experiments", "/api/admin/notices/experiments", NoticeDeliveryQuery.class);
        assertGet("experimentResults", "/api/admin/notices/experiments/{id}/results",
            Long.class, NoticeDeliveryQuery.class);
    }

    @Test
    void publishRetryAndExperimentCreateDeclareAuditLogAnnotations() throws Exception {
        assertAudit("publish", "公告管理", "发布公告", NoticeRequest.class);
        assertAudit("retryOutbox", "公告投递", "重试公告投递", Long.class);
        assertAudit("createExperiment", "公告实验", "创建公告实验", SaveNoticeExperimentRequest.class);
    }

    @Test
    void analyticsOverviewAggregatesDeliveryReadAndFailureMetrics() {
        NoticeDeliveryBatchMapper batchMapper = mock(NoticeDeliveryBatchMapper.class);
        NoticeAudienceSnapshotMapper audienceMapper = mock(NoticeAudienceSnapshotMapper.class);
        NoticeOutboxMapper outboxMapper = mock(NoticeOutboxMapper.class);
        NoticeEmailDeliveryMapper emailDeliveryMapper = mock(NoticeEmailDeliveryMapper.class);
        NoticeExperimentMapper experimentMapper = mock(NoticeExperimentMapper.class);
        NoticeExperimentVariantMapper variantMapper = mock(NoticeExperimentVariantMapper.class);
        com.example.venue.notice.provider.NotificationProviderRouter providerRouter =
            mock(com.example.venue.notice.provider.NotificationProviderRouter.class);
        com.example.venue.notice.provider.NotificationProvider provider =
            mock(com.example.venue.notice.provider.NotificationProvider.class);
        ProviderAdminService providerAdminService = mock(ProviderAdminService.class);
        when(provider.descriptor()).thenReturn(new com.example.venue.notice.provider.NotificationProviderDescriptor(
            "notice.in_app.dev", "MESSAGE", "DEV", "IN_APP_MESSAGE", "IN_APP",
            "站内消息", "IN_APP_MESSAGE_V1", true));
        when(providerRouter.require("DEV", "IN_APP_MESSAGE")).thenReturn(provider);
        NoticeAnalyticsService service = new NoticeAnalyticsService(batchMapper, audienceMapper, outboxMapper,
            emailDeliveryMapper, experimentMapper, variantMapper, providerRouter, providerAdminService);
        when(batchMapper.selectList(any())).thenReturn(List.of(batch(61L)));
        when(audienceMapper.selectList(any())).thenReturn(List.of(
            audience("SENT", "READ"),
            audience("SENT", "UNREAD"),
            audience("FAILED", "UNREAD")));
        when(outboxMapper.selectList(any())).thenReturn(List.of(failedOutbox("IN_APP_DELIVERY_FAILED")));

        NoticeDeliveryOverviewResponse response = service.overview(new NoticeDeliveryQuery());

        assertThat(response.batchCount).isEqualTo(1);
        assertThat(response.audienceCount).isEqualTo(3);
        assertThat(response.sentCount).isEqualTo(2);
        assertThat(response.failedCount).isEqualTo(1);
        assertThat(response.readCount).isEqualTo(1);
        assertThat(response.unreadCount).isEqualTo(2);
        assertThat(response.readRate).isEqualByComparingTo(new BigDecimal("0.3333"));
        assertThat(response.failureReasonCounts).containsEntry("IN_APP_DELIVERY_FAILED", 1L);
        assertThat(response.providerEnv).isEqualTo("DEV");
        assertThat(response.providerName).isEqualTo("IN_APP_MESSAGE");
    }

    private void assertGet(String methodName, String path, Class<?>... parameters) throws Exception {
        Method method = NoticeController.class.getDeclaredMethod(methodName, parameters);
        assertThat(method.getAnnotation(GetMapping.class).value()).containsExactly(path);
        assertAnalyticsPermission(method);
    }

    private void assertPost(String methodName, String path, Class<?>... parameters) throws Exception {
        Method method = NoticeController.class.getDeclaredMethod(methodName, parameters);
        assertThat(method.getAnnotation(PostMapping.class).value()).containsExactly(path);
        assertAnalyticsPermission(method);
    }

    private void assertAnalyticsPermission(Method method) {
        assertThat(method.getAnnotation(PreAuthorize.class).value())
            .isEqualTo("hasAuthority('notice:analytics')");
    }

    private void assertAudit(String methodName, String module, String action, Class<?>... parameters) throws Exception {
        Method method = NoticeController.class.getDeclaredMethod(methodName, parameters);
        AuditLog auditLog = method.getAnnotation(AuditLog.class);
        assertThat(auditLog).isNotNull();
        assertThat(auditLog.module()).isEqualTo(module);
        assertThat(auditLog.action()).isEqualTo(action);
    }

    @TestConfiguration
    @EnableMethodSecurity
    static class MethodSecurityConfig {
    }

    private NoticeDeliveryBatch batch(Long id) {
        NoticeDeliveryBatch batch = new NoticeDeliveryBatch();
        batch.id = id;
        batch.providerEnv = "DEV";
        batch.providerName = "IN_APP_MESSAGE";
        batch.configVersion = "IN_APP_MESSAGE_V1";
        batch.status = "COMPLETED";
        batch.deleted = 0;
        return batch;
    }

    private NoticeAudienceSnapshot audience(String deliveryStatus, String readStatus) {
        NoticeAudienceSnapshot audience = new NoticeAudienceSnapshot();
        audience.id = "FAILED".equals(deliveryStatus) ? 613L : 611L;
        audience.batchId = 61L;
        audience.deliveryStatus = deliveryStatus;
        audience.readStatus = readStatus;
        audience.deleted = 0;
        return audience;
    }

    private NoticeOutbox failedOutbox(String errorCode) {
        NoticeOutbox outbox = new NoticeOutbox();
        outbox.batchId = 61L;
        outbox.audienceId = 613L;
        outbox.status = "FAILED";
        outbox.lastErrorCode = errorCode;
        outbox.deleted = 0;
        return outbox;
    }

}
