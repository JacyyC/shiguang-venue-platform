package com.example.venue.funnel;

import com.example.venue.business.dto.BusinessEventRecordRequest;
import com.example.venue.business.entity.BusinessEvent;
import com.example.venue.business.enums.BusinessDomain;
import com.example.venue.business.enums.BusinessEventType;
import com.example.venue.business.enums.BusinessSourceType;
import com.example.venue.business.enums.ProviderEnv;
import com.example.venue.business.service.BusinessEventService;
import com.example.venue.common.BusinessException;
import com.example.venue.common.CurrentUser;
import com.example.venue.common.EntityExistenceService;
import com.example.venue.funnel.dto.FunnelEventBatchRequest;
import com.example.venue.funnel.dto.FunnelEventRequest;
import com.example.venue.funnel.dto.FunnelEventResponse;
import com.example.venue.funnel.entity.FunnelEvent;
import com.example.venue.funnel.entity.FunnelSession;
import com.example.venue.funnel.mapper.FunnelEventMapper;
import com.example.venue.funnel.mapper.FunnelSessionMapper;
import com.example.venue.funnel.service.FunnelEventService;
import com.example.venue.funnel.service.FunnelSessionContext;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import com.fasterxml.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class FunnelEventServiceTest {
    @Mock
    private FunnelEventMapper eventMapper;
    @Mock
    private FunnelSessionMapper sessionMapper;
    @Mock
    private BusinessEventService businessEventService;
    @Mock
    private EntityExistenceService entityExistenceService;

    private FunnelEventService eventService;

    @BeforeEach
    void setUp() {
        eventService = new FunnelEventService(eventMapper, sessionMapper, businessEventService,
            new ObjectMapper().findAndRegisterModules(), entityExistenceService);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
            new CurrentUser(7L, "funnel-user", Set.of("USER"), Set.of()), null, List.of()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void recordCreatesSessionMasksKeywordAndHashesUserAgent() {
        FunnelEventRequest request = defaultRequest();
        when(sessionMapper.findBySessionKey("sess-b5-001")).thenReturn(null);
        when(sessionMapper.insert(any(FunnelSession.class))).thenAnswer(invocation -> {
            FunnelSession session = invocation.getArgument(0);
            session.id = 501L;
            return 1;
        });
        when(eventMapper.insert(any(FunnelEvent.class))).thenAnswer(invocation -> {
            FunnelEvent event = invocation.getArgument(0);
            event.id = 901L;
            return 1;
        });

        FunnelEventResponse response = eventService.record(request);

        ArgumentCaptor<FunnelSession> sessionCaptor = ArgumentCaptor.forClass(FunnelSession.class);
        verify(sessionMapper).insert(sessionCaptor.capture());
        FunnelSession insertedSession = sessionCaptor.getValue();

        ArgumentCaptor<FunnelEvent> eventCaptor = ArgumentCaptor.forClass(FunnelEvent.class);
        verify(eventMapper).insert(eventCaptor.capture());
        FunnelEvent insertedEvent = eventCaptor.getValue();

        assertThat(response.id).isEqualTo(901L);
        assertThat(response.eventName).isEqualTo("SEARCH_SUBMITTED");
        assertThat(response.eventStage).isEqualTo("SEARCH");
        assertThat(insertedEvent.keywordNormalized).doesNotContain("13800138000");
        assertThat(insertedEvent.keywordHash).hasSize(64);
        assertThat(insertedEvent.metadataJson).doesNotContain("Mozilla/5.0");
        assertThat(insertedSession.userAgentHash).hasSize(64);
        assertThat(insertedEvent.metadataJson).contains("resultCount");
        assertThat(insertedEvent.metadataJson).doesNotContain("contactPhone");
    }

    @Test
    void recordReturnsExistingEventForIdempotencyKey() {
        FunnelEventRequest request = defaultRequest();
        request.idempotencyKey = "search:sess-b5-001:1";
        FunnelEvent existing = event("SEARCH_SUBMITTED", "SEARCH", 20L);
        existing.id = 55L;
        existing.idempotencyKey = "search:sess-b5-001:1";
        when(eventMapper.findByIdempotency("DEV", "WEB", "search:sess-b5-001:1")).thenReturn(existing);

        FunnelEventResponse response = eventService.record(request);

        assertThat(response.id).isEqualTo(55L);
        assertThat(response.eventName).isEqualTo("SEARCH_SUBMITTED");
        verify(sessionMapper, never()).insert(any(FunnelSession.class));
        verify(eventMapper, never()).insert(any(FunnelEvent.class));
    }

    @Test
    void recordNormalizesBrowserEventNameBeforePersisting() {
        FunnelEventRequest request = defaultRequest();
        request.eventName = "search_submitted";
        request.eventStage = "search";
        when(sessionMapper.findBySessionKey("sess-b5-001")).thenReturn(session(501L));
        when(eventMapper.insert(any(FunnelEvent.class))).thenAnswer(invocation -> {
            invocation.<FunnelEvent>getArgument(0).id = 902L;
            return 1;
        });

        FunnelEventResponse response = eventService.record(request);

        ArgumentCaptor<FunnelEvent> eventCaptor = ArgumentCaptor.forClass(FunnelEvent.class);
        verify(eventMapper).insert(eventCaptor.capture());
        assertThat(response.eventName).isEqualTo("SEARCH_SUBMITTED");
        assertThat(eventCaptor.getValue().eventName).isEqualTo("SEARCH_SUBMITTED");
        assertThat(eventCaptor.getValue().eventStage).isEqualTo("SEARCH");
    }

    @Test
    void recordTouchesExistingSessionWithoutRegressingLastSeenTime() {
        FunnelEventRequest request = defaultRequest();
        FunnelSession existing = session(501L);
        LocalDateTime previousLastSeenAt = LocalDateTime.now().plusMinutes(1);
        LocalDateTime previousUpdateTime = LocalDateTime.now().minusMinutes(1);
        existing.lastSeenAt = previousLastSeenAt;
        existing.updateTime = previousUpdateTime;
        when(sessionMapper.findBySessionKey("sess-b5-001")).thenReturn(existing);
        when(eventMapper.insert(any(FunnelEvent.class))).thenAnswer(invocation -> {
            invocation.<FunnelEvent>getArgument(0).id = 904L;
            return 1;
        });

        FunnelEventResponse response = eventService.record(request);

        ArgumentCaptor<LocalDateTime> lastSeenCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        ArgumentCaptor<LocalDateTime> updateCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(sessionMapper).touchLastSeen(eq("sess-b5-001"), lastSeenCaptor.capture(), updateCaptor.capture());
        assertThat(response.id).isEqualTo(904L);
        assertThat(lastSeenCaptor.getValue()).isBefore(previousLastSeenAt);
        assertThat(existing.lastSeenAt).isEqualTo(previousLastSeenAt);
        assertThat(existing.updateTime).isEqualTo(updateCaptor.getValue()).isAfter(previousUpdateTime);
        verify(sessionMapper, never()).insert(any(FunnelSession.class));
    }

    @Test
    void sessionTouchMapperKeepsLastSeenMonotonicAndRefreshesUpdateTime() throws Exception {
        Update update = FunnelSessionMapper.class
            .getMethod("touchLastSeen", String.class, LocalDateTime.class, LocalDateTime.class)
            .getAnnotation(Update.class);

        assertThat(update.value()).hasSize(1);
        assertThat(update.value()[0]).contains(
            "last_seen_at = case when last_seen_at < #{lastSeenAt} then #{lastSeenAt} else last_seen_at end",
            "update_time = #{updateTime}",
            "deleted = 0");
    }

    @Test
    void recordContinuesWithSessionCreatedByConcurrentFirstRequest() {
        FunnelEventRequest request = defaultRequest();
        FunnelSession concurrentSession = session(777L);
        when(sessionMapper.findBySessionKey("sess-b5-001")).thenReturn(null, concurrentSession);
        org.mockito.Mockito.doThrow(new org.springframework.dao.DuplicateKeyException("duplicate session"))
            .when(sessionMapper).insert(any(FunnelSession.class));
        when(eventMapper.insert(any(FunnelEvent.class))).thenAnswer(invocation -> {
            invocation.<FunnelEvent>getArgument(0).id = 903L;
            return 1;
        });

        FunnelEventResponse response = eventService.record(request);

        ArgumentCaptor<FunnelEvent> eventCaptor = ArgumentCaptor.forClass(FunnelEvent.class);
        verify(eventMapper).insert(eventCaptor.capture());
        assertThat(response.id).isEqualTo(903L);
        assertThat(eventCaptor.getValue().sessionId).isEqualTo(777L);
        verify(sessionMapper, org.mockito.Mockito.times(2)).findBySessionKey("sess-b5-001");
    }

    @Test
    void funnelWritesAreTransactional() throws Exception {
        assertThat(FunnelEventService.class.getMethod("record", FunnelEventRequest.class)
            .getAnnotation(Transactional.class)).isNotNull();
        assertThat(FunnelEventService.class.getMethod("recordBatch", FunnelEventBatchRequest.class)
            .getAnnotation(Transactional.class)).isNotNull();
        assertThat(FunnelEventService.class.getMethod("recordMilestone", String.class, String.class, Long.class,
            Long.class, Long.class, Long.class, BigDecimal.class, String.class, String.class, String.class,
            Map.class).getAnnotation(Transactional.class)).isNotNull();
        // A26-P1-03：新增 12 参数重载方法也必须事务化
        assertThat(FunnelEventService.class.getMethod("recordMilestone", String.class, String.class, Long.class,
            Long.class, Long.class, Long.class, BigDecimal.class, String.class, String.class, String.class,
            Map.class, String.class).getAnnotation(Transactional.class)).isNotNull();
    }

    @Test
    void recordMilestoneMirrorsBusinessEvent() {
        when(eventMapper.findByIdempotency("DEV", "WEB", "favorite:7:20:add")).thenReturn(null);
        when(sessionMapper.insert(any(FunnelSession.class))).thenAnswer(invocation -> {
            FunnelSession session = invocation.getArgument(0);
            session.id = 601L;
            return 1;
        });
        when(eventMapper.insert(any(FunnelEvent.class))).thenAnswer(invocation -> {
            FunnelEvent event = invocation.getArgument(0);
            event.id = 1001L;
            return 1;
        });
        when(businessEventService.record(any(BusinessEventRecordRequest.class))).thenReturn(new BusinessEvent());

        FunnelEvent event = eventService.recordMilestone(
            "FAVORITE_ADDED",
            "FAVORITE",
            7L,
            20L,
            null,
            null,
            new BigDecimal("88.00"),
            "SUCCESS",
            "FAVORITE_ADDED",
            "favorite:7:20:add",
            Map.of("entry", "venue-card"));

        assertThat(event.id).isEqualTo(1001L);
        assertThat(event.eventName).isEqualTo("FAVORITE_ADDED");
        assertThat(event.eventStage).isEqualTo("FAVORITE");
        assertThat(event.venueId).isEqualTo(20L);
        assertThat(event.payableAmount).isEqualByComparingTo("88.00");

        ArgumentCaptor<BusinessEventRecordRequest> businessCaptor =
            ArgumentCaptor.forClass(BusinessEventRecordRequest.class);
        verify(businessEventService).record(businessCaptor.capture());
        BusinessEventRecordRequest mirrored = businessCaptor.getValue();
        assertThat(mirrored.eventType).isEqualTo(BusinessEventType.FUNNEL_MILESTONE_RECORDED);
        assertThat(mirrored.businessDomain).isEqualTo(BusinessDomain.FUNNEL);
        assertThat(mirrored.entityType).isEqualTo("FUNNEL_EVENT");
        assertThat(mirrored.entityId).isEqualTo(1001L);
        assertThat(mirrored.actorUserId).isEqualTo(7L);
        assertThat(mirrored.sourceType).isEqualTo(BusinessSourceType.USER);
        assertThat(mirrored.providerEnv).isEqualTo(ProviderEnv.DEV);
        assertThat(mirrored.providerName).isEqualTo("FUNNEL");
        assertThat(mirrored.idempotencyKey).isEqualTo("favorite:7:20:add");
        assertThat(mirrored.reasonCode).isEqualTo("FAVORITE_ADDED");
        assertThat(mirrored.resultStatus).isEqualTo("SUCCESS");
        assertThat(mirrored.metadata).containsEntry("eventName", "FAVORITE_ADDED");
        assertThat(mirrored.metadata).containsEntry("entry", "venue-card");
    }

    @Test
    void recordMilestoneUsesMetadataSourceChannelForEventColumn() {
        when(sessionMapper.insert(any(FunnelSession.class))).thenAnswer(invocation -> {
            FunnelSession session = invocation.getArgument(0);
            session.id = 602L;
            return 1;
        });
        when(eventMapper.insert(any(FunnelEvent.class))).thenAnswer(invocation -> {
            FunnelEvent event = invocation.getArgument(0);
            event.id = 1002L;
            return 1;
        });
        when(businessEventService.record(any(BusinessEventRecordRequest.class))).thenReturn(new BusinessEvent());

        FunnelEvent event = eventService.recordMilestone(
            "FAVORITE_ADDED",
            "FAVORITE",
            7L,
            20L,
            null,
            null,
            null,
            "SUCCESS",
            null,
            "favorite:7:20:h5",
            Map.of("sourceChannel", "h5", "entry", "venue-card"));

        verify(eventMapper).findByIdempotency("DEV", "H5", "favorite:7:20:h5");
        assertThat(event.sourceChannel).isEqualTo("H5");
        ArgumentCaptor<BusinessEventRecordRequest> businessCaptor =
            ArgumentCaptor.forClass(BusinessEventRecordRequest.class);
        verify(businessEventService).record(businessCaptor.capture());
        assertThat(businessCaptor.getValue().metadata).containsEntry("sourceChannel", "H5");
    }

    @Test
    void recordRejectsContactPhoneAndRemarkInsideMetadata() {
        FunnelEventRequest request = defaultRequest();
        request.metadata = Map.of(
            "contactPhone", "13800138000",
            "remark", "call me after 8pm",
            "safeKey", "kept");
        when(sessionMapper.findBySessionKey("sess-b5-001")).thenReturn(null);
        when(sessionMapper.insert(any(FunnelSession.class))).thenAnswer(invocation -> {
            FunnelSession session = invocation.getArgument(0);
            session.id = 701L;
            return 1;
        });
        when(eventMapper.insert(any(FunnelEvent.class))).thenAnswer(invocation -> {
            FunnelEvent event = invocation.getArgument(0);
            event.id = 1101L;
            return 1;
        });

        eventService.record(request);

        ArgumentCaptor<FunnelEvent> eventCaptor = ArgumentCaptor.forClass(FunnelEvent.class);
        verify(eventMapper).insert(eventCaptor.capture());
        FunnelEvent insertedEvent = eventCaptor.getValue();
        assertThat(insertedEvent.metadataJson).contains("safeKey");
        assertThat(insertedEvent.metadataJson).doesNotContain("contactPhone");
        assertThat(insertedEvent.metadataJson).doesNotContain("remark");
        assertThat(insertedEvent.metadataJson).doesNotContain("13800138000");
        assertThat(insertedEvent.metadataJson).doesNotContain("call me after 8pm");
    }

    @Test
    void recordRecursivelySanitizesSensitiveMetadataAndReasonText() {
        FunnelEventRequest request = defaultRequest();
        String jwt = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjMifQ.sflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c";
        request.reasonText = "用户备注 13800138000 from 192.168.1.12 Mozilla/5.0 B5 Browser "
            + "Authorization: Bearer sk-live-abc password=hunter2 token=abc credential=card_1234 "
            + "secret=my-secret api_key=key-123 signature=sig-123 jwt=" + jwt;
        Map<String, Object> metadata = new java.util.LinkedHashMap<>();
        metadata.put("resultCount", 3);
        metadata.put("contact_phone", "13800138000");
        metadata.put("contactPhone", "13900139000");
        metadata.put("rawUserAgent", "Mozilla/5.0 Raw");
        metadata.put("userAgent", "Mozilla/5.0 Nested");
        metadata.put("ip", "192.168.1.12");
        metadata.put("rawPayload", Map.of("unsafe", "payload"));
        metadata.put("token", "secret-token");
        metadata.put("password", "secret-password");
        metadata.put("paymentCredential", "4111111111111111");
        metadata.put("remark", "please call 13800138000");
        metadata.put("nested", Map.of(
            "safeKey", "kept",
            "contactPhone", "13900139000",
            "details", "token=abc password=xyz credential=card_1234 secret=my-secret privateKey=private-123",
            "safeNote", "Mozilla/5.0 from 10.0.0.1 with 13800138000"));
        metadata.put("items", List.of(
            Map.of("safeKey", "list-safe", "ip", "172.16.0.1"),
            "Mozilla/5.0 from 172.16.0.2 with 13900139000",
            Map.of("details", "api_key=key-123 signature=sig-123 jwt=" + jwt)));
        metadata.put("details", "Authorization: Bearer sk-live-abc password=hunter2 token=abc "
            + "credential=card_1234 secret=my-secret apiKey=key-456 signature=sig-456 " + jwt);
        request.metadata = metadata;
        when(sessionMapper.findBySessionKey("sess-b5-001")).thenReturn(null);
        when(sessionMapper.insert(any(FunnelSession.class))).thenAnswer(invocation -> {
            FunnelSession session = invocation.getArgument(0);
            session.id = 801L;
            return 1;
        });
        when(eventMapper.insert(any(FunnelEvent.class))).thenAnswer(invocation -> {
            FunnelEvent event = invocation.getArgument(0);
            event.id = 1201L;
            return 1;
        });

        eventService.record(request);

        ArgumentCaptor<FunnelEvent> eventCaptor = ArgumentCaptor.forClass(FunnelEvent.class);
        verify(eventMapper).insert(eventCaptor.capture());
        FunnelEvent insertedEvent = eventCaptor.getValue();
        assertThat(insertedEvent.metadataJson).contains("resultCount");
        assertThat(insertedEvent.metadataJson).contains("safeKey");
        assertThat(insertedEvent.metadataJson).contains("list-safe");
        assertThat(insertedEvent.metadataJson).contains("details");
        assertThat(insertedEvent.metadataJson).contains("***");
        assertThat(insertedEvent.metadataJson)
            .doesNotContain("contact_phone", "contactPhone", "rawUserAgent", "userAgent", "rawPayload",
                "paymentCredential", "secret-token", "secret-password", "4111111111111111", "please call",
                "sk-live-abc", "hunter2", "token=abc", "password=xyz", "card_1234", "my-secret",
                "key-123", "key-456", "sig-123", "sig-456", "private-123", jwt);
        assertThat(insertedEvent.metadataJson)
            .doesNotContain("13800138000", "13900139000", "192.168.1.12", "10.0.0.1",
                "172.16.0.1", "172.16.0.2", "Mozilla/5.0");
        assertThat(insertedEvent.reasonText)
            .contains("***")
            .doesNotContain("13800138000", "192.168.1.12", "Mozilla/5.0", "sk-live-abc", "hunter2",
                "token=abc", "card_1234", "my-secret", "key-123", "sig-123", jwt);
    }

    @Test
    void recordMasksNonBearerAuthorizationAndQuotedMultiWordSensitiveValues() {
        FunnelEventRequest request = defaultRequest();
        String jwt = "eyJhbGciOiJIUzI1NiJ9.eyJyb2xlIjoiYWRtaW4ifQ.testSignature";
        String sensitiveText = "Authorization: Basic abc123 authorization=\"Basic quoted123\" "
            + "Authorization: ApiKey api-key-leak Authorization: OAuth oauth-leak "
            + "authorization=Custom custom token leak "
            + "password=\"my secret phrase\" secret='two words inside' apiKey: \"abc def token\" "
            + "signature='sig value phrase' privateKey=\"private value phrase\" jwt=" + jwt;
        request.reasonText = "safe reason " + sensitiveText;
        request.metadata = Map.of(
            "safeKey", "safe-value",
            "details", sensitiveText,
            "nested", Map.of("note", "preserve me", "details", sensitiveText),
            "items", List.of("visible item", Map.of("details", sensitiveText)));
        when(sessionMapper.findBySessionKey("sess-b5-001")).thenReturn(null);
        when(sessionMapper.insert(any(FunnelSession.class))).thenAnswer(invocation -> {
            FunnelSession session = invocation.getArgument(0);
            session.id = 802L;
            return 1;
        });
        when(eventMapper.insert(any(FunnelEvent.class))).thenAnswer(invocation -> {
            FunnelEvent event = invocation.getArgument(0);
            event.id = 1202L;
            return 1;
        });

        eventService.record(request);

        ArgumentCaptor<FunnelEvent> eventCaptor = ArgumentCaptor.forClass(FunnelEvent.class);
        verify(eventMapper).insert(eventCaptor.capture());
        FunnelEvent insertedEvent = eventCaptor.getValue();
        assertThat(insertedEvent.metadataJson).contains("safeKey", "safe-value", "preserve me", "visible item", "***");
        assertThat(insertedEvent.metadataJson)
            .doesNotContain("abc123", "quoted123", "secret phrase", "words inside", "def token",
                "sig value phrase", "private value phrase", "api-key-leak", "oauth-leak",
                "custom token leak", jwt);
        assertThat(insertedEvent.reasonText)
            .contains("safe reason", "***")
            .doesNotContain("abc123", "quoted123", "secret phrase", "words inside", "def token",
                "sig value phrase", "private value phrase", "api-key-leak", "oauth-leak",
                "custom token leak", jwt);
    }

    @Test
    void recordMilestoneSanitizesMetadataBeforeBusinessEventMirror() {
        String sensitiveText = "Authorization: Basic abc123 Authorization: ApiKey api-key-leak "
            + "authorization=Custom custom token leak password=\"my secret phrase\" "
            + "apiKey: \"abc def token\" signature='sig value phrase' privateKey=\"private value phrase\"";
        when(sessionMapper.insert(any(FunnelSession.class))).thenAnswer(invocation -> {
            FunnelSession session = invocation.getArgument(0);
            session.id = 803L;
            return 1;
        });
        when(eventMapper.insert(any(FunnelEvent.class))).thenAnswer(invocation -> {
            FunnelEvent event = invocation.getArgument(0);
            event.id = 1203L;
            return 1;
        });
        when(businessEventService.record(any(BusinessEventRecordRequest.class))).thenReturn(new BusinessEvent());

        FunnelEvent event = eventService.recordMilestone(
            "PAYMENT_INITIATED",
            "PAYMENT",
            7L,
            20L,
            99L,
            11L,
            new BigDecimal("88.00"),
            "STARTED",
            null,
            "payment-initiated-11-safe",
            Map.of("safeKey", "kept", "details", sensitiveText, "reasonText", sensitiveText));

        assertThat(event.metadataJson).contains("safeKey", "kept", "***");
        assertThat(event.metadataJson)
            .doesNotContain("abc123", "api-key-leak", "custom token leak", "secret phrase",
                "def token", "sig value phrase", "private value phrase");
        ArgumentCaptor<BusinessEventRecordRequest> businessCaptor =
            ArgumentCaptor.forClass(BusinessEventRecordRequest.class);
        verify(businessEventService).record(businessCaptor.capture());
        BusinessEventRecordRequest mirrored = businessCaptor.getValue();
        assertThat(mirrored.reasonText).isNull();
        assertThat(String.valueOf(mirrored.metadata))
            .contains("safeKey=kept", "***")
            .doesNotContain("abc123", "api-key-leak", "custom token leak", "secret phrase",
                "def token", "sig value phrase", "private value phrase");
    }

    @Test
    void recordRejectsBrowserForgedServerAuthoritativeMilestones() {
        for (String eventName : List.of("RESERVATION_SUBMITTED", "ORDER_CREATED",
            "PAYMENT_INITIATED", "PAYMENT_RESULT_RECORDED")) {
            FunnelEventRequest request = defaultRequest();
            request.eventName = eventName;
            request.eventStage = "PAYMENT";

            assertThatThrownBy(() -> eventService.record(request))
                .isInstanceOf(BusinessException.class)
                .hasMessage("服务端权威漏斗事件不能由浏览器直接记录");
        }

        verify(eventMapper, never()).insert(any(FunnelEvent.class));
        verify(sessionMapper, never()).insert(any(FunnelSession.class));
    }

    @Test
    void recordBatchRejectsBrowserForgedServerAuthoritativeMilestones() {
        FunnelEventRequest request = defaultRequest();
        request.eventName = "ORDER_CREATED";
        request.eventStage = "ORDER";
        FunnelEventBatchRequest batch = new FunnelEventBatchRequest();
        batch.events = List.of(request);

        assertThatThrownBy(() -> eventService.recordBatch(batch))
            .isInstanceOf(BusinessException.class)
            .hasMessage("服务端权威漏斗事件不能由浏览器直接记录");

        verify(eventMapper, never()).insert(any(FunnelEvent.class));
        verify(sessionMapper, never()).insert(any(FunnelSession.class));
    }

    @Test
    void recordRejectsNonExistentVenue() {
        // AUD-P2-11: 匿名写接口传入不存在的场馆 ID 时应被拒绝，不能写入污染统计
        FunnelEventRequest request = defaultRequest();
        request.venueId = 999L;
        doThrow(BusinessException.badRequest("场馆不存在"))
            .when(entityExistenceService).requireVenueExists(999L);

        assertThatThrownBy(() -> eventService.record(request))
            .isInstanceOf(BusinessException.class)
            .hasMessage("场馆不存在");

        verify(entityExistenceService).requireVenueExists(999L);
        verify(eventMapper, never()).insert(any(FunnelEvent.class));
        verify(sessionMapper, never()).insert(any(FunnelSession.class));
    }

    @Test
    void recordMilestoneStillAllowsServerAuthoritativeMilestones() {
        when(sessionMapper.insert(any(FunnelSession.class))).thenAnswer(invocation -> {
            FunnelSession session = invocation.getArgument(0);
            session.id = 804L;
            return 1;
        });
        when(eventMapper.insert(any(FunnelEvent.class))).thenAnswer(invocation -> {
            FunnelEvent event = invocation.getArgument(0);
            event.id = 1204L;
            return 1;
        });
        when(businessEventService.record(any(BusinessEventRecordRequest.class))).thenReturn(new BusinessEvent());

        FunnelEvent event = eventService.recordMilestone(
            "PAYMENT_INITIATED",
            "PAYMENT",
            7L,
            20L,
            99L,
            11L,
            new BigDecimal("88.00"),
            "STARTED",
            null,
            "payment-initiated-11-server",
            Map.of("channel", "MOCK"));

        assertThat(event.eventName).isEqualTo("PAYMENT_INITIATED");
        assertThat(event.eventStage).isEqualTo("PAYMENT");
        assertThat(event.orderId).isEqualTo(11L);
        verify(businessEventService).record(any(BusinessEventRecordRequest.class));
    }

    @Test
    void recordUppercasesSourceChannelBeforeIdempotencyLookupAndStorage() {
        FunnelEventRequest request = defaultRequest();
        request.sourceChannel = "web";
        request.idempotencyKey = "search:sess-b5-001:source";
        when(sessionMapper.findBySessionKey("sess-b5-001")).thenReturn(null);
        when(sessionMapper.insert(any(FunnelSession.class))).thenAnswer(invocation -> {
            FunnelSession session = invocation.getArgument(0);
            session.id = 901L;
            return 1;
        });
        when(eventMapper.insert(any(FunnelEvent.class))).thenAnswer(invocation -> {
            FunnelEvent event = invocation.getArgument(0);
            event.id = 1301L;
            return 1;
        });

        eventService.record(request);

        verify(eventMapper).findByIdempotency("DEV", "WEB", "search:sess-b5-001:source");
        ArgumentCaptor<FunnelSession> sessionCaptor = ArgumentCaptor.forClass(FunnelSession.class);
        verify(sessionMapper).insert(sessionCaptor.capture());
        ArgumentCaptor<FunnelEvent> eventCaptor = ArgumentCaptor.forClass(FunnelEvent.class);
        verify(eventMapper).insert(eventCaptor.capture());
        assertThat(sessionCaptor.getValue().sourceChannel).isEqualTo("WEB");
        assertThat(eventCaptor.getValue().sourceChannel).isEqualTo("WEB");
    }

    private FunnelEventRequest defaultRequest() {
        FunnelEventRequest request = new FunnelEventRequest();
        request.sessionKey = "sess-b5-001";
        request.visitorId = "visitor-browser-001";
        request.eventName = "SEARCH_SUBMITTED";
        request.eventStage = "SEARCH";
        request.sourceChannel = "WEB";
        request.keyword = " 羽毛球 13800138000 ";
        request.pagePath = "/venues";
        request.userAgent = "Mozilla/5.0 B5 Test Browser";
        request.metadata = Map.of("resultCount", 3);
        return request;
    }

    private FunnelSession session(Long id) {
        FunnelSession session = new FunnelSession();
        session.id = id;
        session.sessionKey = "sess-b5-001";
        return session;
    }

    private FunnelEvent event(String eventName, String eventStage, Long venueId) {
        FunnelEvent event = new FunnelEvent();
        event.eventName = eventName;
        event.eventStage = eventStage;
        event.sourceChannel = "WEB";
        event.providerEnv = "DEV";
        event.resultStatus = "SUCCESS";
        event.venueId = venueId;
        return event;
    }

    // ===== A26-P1-03：漏斗 sessionKey 维修测试 =====

    /**
     * A26-P1-03：recordMilestone 12 参数重载方法接受 sessionKey 参数时，
     * 事件应写入该 sessionKey，使服务端权威里程碑事件与前序浏览器事件共享同一 sessionKey，
     * 避免漏斗 cohort 第 6~9 步结构性断链（转化率恒为 0）。
     */
    @Test
    void recordMilestoneUsesExplicitSessionKeyWhenProvided() {
        when(sessionMapper.findBySessionKey("browser-sess-p1-03")).thenReturn(null);
        when(sessionMapper.insert(any(FunnelSession.class))).thenAnswer(invocation -> {
            FunnelSession session = invocation.getArgument(0);
            session.id = 900L;
            return 1;
        });
        when(eventMapper.insert(any(FunnelEvent.class))).thenAnswer(invocation -> {
            FunnelEvent event = invocation.getArgument(0);
            event.id = 1900L;
            return 1;
        });
        when(businessEventService.record(any(BusinessEventRecordRequest.class))).thenReturn(new BusinessEvent());

        FunnelEvent event = eventService.recordMilestone(
            "RESERVATION_SUBMITTED",
            "RESERVATION",
            7L,
            20L,
            99L,
            null,
            new BigDecimal("100.00"),
            "SUCCESS",
            null,
            "reservation-submitted-99",
            Map.of(),
            "browser-sess-p1-03");

        ArgumentCaptor<FunnelSession> sessionCaptor = ArgumentCaptor.forClass(FunnelSession.class);
        verify(sessionMapper).insert(sessionCaptor.capture());
        assertThat(sessionCaptor.getValue().sessionKey).isEqualTo("browser-sess-p1-03");
        ArgumentCaptor<FunnelEvent> eventCaptor = ArgumentCaptor.forClass(FunnelEvent.class);
        verify(eventMapper).insert(eventCaptor.capture());
        assertThat(eventCaptor.getValue().sessionKey).isEqualTo("browser-sess-p1-03");
        assertThat(eventCaptor.getValue().sessionId).isEqualTo(900L);
        assertThat(event.sessionKey).isEqualTo("browser-sess-p1-03");
    }

    /**
     * A26-P1-03：recordMilestone 11 参数旧方法委托新方法时，sessionKey 从
     * FunnelSessionContext（ThreadLocal）自动获取。模拟 FunnelSessionFilter 绑定
     * X-Funnel-Session 到 ThreadLocal 后，旧方法调用应使用该 sessionKey。
     */
    @Test
    void recordMilestoneLegacyOverloadReadsSessionKeyFromFunnelSessionContext() {
        when(sessionMapper.findBySessionKey("threadlocal-sess-p1-03")).thenReturn(null);
        when(sessionMapper.insert(any(FunnelSession.class))).thenAnswer(invocation -> {
            FunnelSession session = invocation.getArgument(0);
            session.id = 901L;
            return 1;
        });
        when(eventMapper.insert(any(FunnelEvent.class))).thenAnswer(invocation -> {
            FunnelEvent event = invocation.getArgument(0);
            event.id = 1901L;
            return 1;
        });
        when(businessEventService.record(any(BusinessEventRecordRequest.class))).thenReturn(new BusinessEvent());

        FunnelSessionContext.set("threadlocal-sess-p1-03");
        try {
            FunnelEvent event = eventService.recordMilestone(
                "ORDER_CREATED",
                "ORDER",
                7L,
                20L,
                99L,
                12L,
                new BigDecimal("100.00"),
                "SUCCESS",
                null,
                "order-created-12",
                Map.of());
            ArgumentCaptor<FunnelEvent> eventCaptor = ArgumentCaptor.forClass(FunnelEvent.class);
            verify(eventMapper).insert(eventCaptor.capture());
            assertThat(eventCaptor.getValue().sessionKey).isEqualTo("threadlocal-sess-p1-03");
            assertThat(event.sessionKey).isEqualTo("threadlocal-sess-p1-03");
        } finally {
            FunnelSessionContext.clear();
        }
    }

    @Test
    void orderCreatedInheritsReservationSessionInsteadOfAdminRequestSession() {
        when(eventMapper.findContinuationSessionKey(99L, 12L)).thenReturn("original-user-session");
        when(sessionMapper.findBySessionKey("original-user-session")).thenReturn(session(901L));
        when(eventMapper.insert(any(FunnelEvent.class))).thenAnswer(invocation -> {
            FunnelEvent event = invocation.getArgument(0);
            event.id = 1902L;
            return 1;
        });
        when(businessEventService.record(any(BusinessEventRecordRequest.class))).thenReturn(new BusinessEvent());

        FunnelEvent event = eventService.recordMilestone(
            "ORDER_CREATED", "ORDER", 7L, 20L, 99L, 12L, new BigDecimal("100.00"),
            "SUCCESS", null, "order-created-12", Map.of(), "admin-request-session");

        assertThat(event.sessionKey).isEqualTo("original-user-session");
        verify(sessionMapper, never()).findBySessionKey("admin-request-session");
    }

    @Test
    void paymentResultCallbackInheritsReservationSessionWithoutRequestHeader() {
        when(eventMapper.findContinuationSessionKey(99L, 12L)).thenReturn("original-user-session");
        when(sessionMapper.findBySessionKey("original-user-session")).thenReturn(session(902L));
        when(eventMapper.insert(any(FunnelEvent.class))).thenAnswer(invocation -> {
            FunnelEvent event = invocation.getArgument(0);
            event.id = 1903L;
            return 1;
        });
        when(businessEventService.record(any(BusinessEventRecordRequest.class))).thenReturn(new BusinessEvent());

        FunnelEvent event = eventService.recordMilestone(
            "PAYMENT_RESULT_RECORDED", "PAYMENT", 7L, 20L, 99L, 12L, new BigDecimal("100.00"),
            "SUCCESS", null, "payment-result-success-12-callback", Map.of(), null);

        assertThat(event.sessionKey).isEqualTo("original-user-session");
        assertThat(event.sessionKey).doesNotStartWith("server-");
    }

    /**
     * A26-P1-03：sessionKey 为 null 时（如定时任务、回调链路），recordInternal 记 WARN
     * 并保留 server-<uuid> 兜底，不阻断业务流程。验证事件仍写入 server- 前缀的兜底 sessionKey。
     */
    @Test
    void recordMilestoneFallsBackToServerSessionKeyWhenSessionKeyMissing() {
        when(sessionMapper.findBySessionKey(org.mockito.ArgumentMatchers.startsWith("server-"))).thenReturn(null);
        when(sessionMapper.insert(any(FunnelSession.class))).thenAnswer(invocation -> {
            FunnelSession session = invocation.getArgument(0);
            session.id = 902L;
            return 1;
        });
        when(eventMapper.insert(any(FunnelEvent.class))).thenAnswer(invocation -> {
            FunnelEvent event = invocation.getArgument(0);
            event.id = 1902L;
            return 1;
        });
        when(businessEventService.record(any(BusinessEventRecordRequest.class))).thenReturn(new BusinessEvent());

        // 显式传 null（如回调链路 FunnelSessionContext.current() 返回 null）
        FunnelEvent event = eventService.recordMilestone(
            "PAYMENT_RESULT_RECORDED",
            "PAYMENT",
            7L,
            20L,
            99L,
            12L,
            new BigDecimal("100.00"),
            "SUCCESS",
            null,
            "payment-result-success-12-callback",
            Map.of(),
            null);

        ArgumentCaptor<FunnelEvent> eventCaptor = ArgumentCaptor.forClass(FunnelEvent.class);
        verify(eventMapper).insert(eventCaptor.capture());
        assertThat(eventCaptor.getValue().sessionKey).startsWith("server-");
        assertThat(event.sessionKey).startsWith("server-");
    }

    /**
     * A26-P1-03：浏览器直录事件（record/recordBatch）sessionKey 由前端
     * FunnelEventRequest.sessionKey 提供，正常情况下不为 null，验证仍按 request.sessionKey 写入。
     * 这里通过 recordMilestone 12 参数方法验证 FunnelEventRequest.sessionKey 的传递路径。
     */
    @Test
    void recordMilestoneWithBlankSessionKeyFallsBackToServerPrefix() {
        when(sessionMapper.findBySessionKey(org.mockito.ArgumentMatchers.startsWith("server-"))).thenReturn(null);
        when(sessionMapper.insert(any(FunnelSession.class))).thenAnswer(invocation -> {
            FunnelSession session = invocation.getArgument(0);
            session.id = 903L;
            return 1;
        });
        when(eventMapper.insert(any(FunnelEvent.class))).thenAnswer(invocation -> {
            FunnelEvent event = invocation.getArgument(0);
            event.id = 1903L;
            return 1;
        });
        when(businessEventService.record(any(BusinessEventRecordRequest.class))).thenReturn(new BusinessEvent());

        FunnelEvent event = eventService.recordMilestone(
            "PAYMENT_INITIATED",
            "PAYMENT",
            7L,
            20L,
            99L,
            12L,
            new BigDecimal("100.00"),
            "STARTED",
            null,
            "payment-initiated-12-blank",
            Map.of(),
            "   ");

        ArgumentCaptor<FunnelEvent> eventCaptor = ArgumentCaptor.forClass(FunnelEvent.class);
        verify(eventMapper).insert(eventCaptor.capture());
        assertThat(eventCaptor.getValue().sessionKey).startsWith("server-");
        assertThat(event.sessionKey).startsWith("server-");
    }
}
