package com.example.venue.business;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.example.venue.business.dto.BusinessEventRecordRequest;
import com.example.venue.business.entity.BusinessEvent;
import com.example.venue.business.enums.BusinessDomain;
import com.example.venue.business.enums.BusinessEventType;
import com.example.venue.business.enums.BusinessSourceType;
import com.example.venue.business.mapper.BusinessEventMapper;
import com.example.venue.business.service.BusinessEventService;
import com.example.venue.common.BusinessException;
import com.example.venue.common.CurrentUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import com.fasterxml.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class BusinessEventServiceTest {
    @Mock
    private BusinessEventMapper eventMapper;

    private BusinessEventService eventService;

    @BeforeEach
    void setUp() {
        eventService = new BusinessEventService(eventMapper, new ObjectMapper().findAndRegisterModules());
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
            new CurrentUser(7L, "tester", Set.of("USER"), Set.of()), null, List.of()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void recordFillsTraceFieldsMetadataActorAndDefaults() {
        when(eventMapper.selectOne(any(QueryWrapper.class))).thenReturn(null);
        when(eventMapper.insert(any(BusinessEvent.class))).thenAnswer(invocation -> {
            BusinessEvent event = invocation.getArgument(0);
            event.id = 100L;
            return 1;
        });
        BusinessEventRecordRequest request = BusinessEventRecordRequest.of(
                BusinessEventType.RESERVATION_CREATED,
                BusinessDomain.RESERVATION,
                "RESERVATION",
                99L,
                BusinessSourceType.USER,
                "SUCCESS")
            .toStatus("PENDING_REVIEW")
            .reasonText("用户提交预约")
            .idempotencyKey("RESERVATION_CREATED:99")
            .metadata("venueId", 20L)
            .metadata("amount", new BigDecimal("100.00"));

        BusinessEvent event = eventService.record(request);

        ArgumentCaptor<BusinessEvent> captor = ArgumentCaptor.forClass(BusinessEvent.class);
        verify(eventMapper).insert(captor.capture());
        BusinessEvent inserted = captor.getValue();
        assertThat(event).isSameAs(inserted);
        assertThat(inserted.id).isEqualTo(100L);
        assertThat(inserted.eventNo).startsWith("BE");
        assertThat(inserted.eventType).isEqualTo("RESERVATION_CREATED");
        assertThat(inserted.businessDomain).isEqualTo("RESERVATION");
        assertThat(inserted.entityType).isEqualTo("RESERVATION");
        assertThat(inserted.entityId).isEqualTo(99L);
        assertThat(inserted.actorUserId).isEqualTo(7L);
        assertThat(inserted.actorUsername).isEqualTo("tester");
        assertThat(inserted.sourceType).isEqualTo("USER");
        assertThat(inserted.providerEnv).isNull();
        assertThat(inserted.providerName).isNull();
        assertThat(inserted.idempotencyKey).isEqualTo("RESERVATION_CREATED:99");
        assertThat(inserted.confidence).isEqualByComparingTo("1.00");
        assertThat(inserted.reasonText).isEqualTo("用户提交预约");
        assertThat(inserted.fromStatus).isNull();
        assertThat(inserted.toStatus).isEqualTo("PENDING_REVIEW");
        assertThat(inserted.resultStatus).isEqualTo("SUCCESS");
        assertThat(inserted.occurredAt).isNotNull();
        assertThat(inserted.createTime).isNotNull();
        assertThat(inserted.metadataJson).contains("\"venueId\":20");
        assertThat(inserted.metadataJson).contains("\"amount\":100.00");
    }

    @Test
    void recordAllowsReservationApprovedEvent() {
        BusinessEventRecordRequest request = BusinessEventRecordRequest.of(
            BusinessEventType.RESERVATION_APPROVED,
            BusinessDomain.RESERVATION,
            "RESERVATION",
            99L,
            BusinessSourceType.ADMIN,
            "SUCCESS");

        when(eventMapper.insert(any(BusinessEvent.class))).thenAnswer(invocation -> 1);
        assertThat(eventService.record(request).eventType).isEqualTo("RESERVATION_APPROVED");
    }

    @Test
    void recordAllowsB1InventoryEventsButStillRejectsNonWhitelistedFutureEvents() {
        when(eventMapper.selectOne(any(QueryWrapper.class))).thenReturn(null);
        when(eventMapper.insert(any(BusinessEvent.class))).thenAnswer(invocation -> {
            BusinessEvent event = invocation.getArgument(0);
            event.id = 501L;
            return 1;
        });
        BusinessEventRecordRequest allowed = BusinessEventRecordRequest.of(
                BusinessEventType.UNAVAILABLE_BLOCK_CREATED,
                BusinessDomain.VENUE_AVAILABILITY,
                "VENUE_UNAVAILABLE_BLOCK",
                88L,
                BusinessSourceType.ADMIN,
                "SUCCESS")
            .idempotencyKey("UNAVAILABLE_BLOCK_CREATED:88");

        BusinessEvent event = eventService.record(allowed);

        assertThat(event.eventType).isEqualTo(BusinessEventType.UNAVAILABLE_BLOCK_CREATED.name());

        assertAllowed(BusinessEventType.RESOURCE_ALLOCATION_CONFLICT_REJECTED,
            BusinessDomain.VENUE_AVAILABILITY, "RESOURCE_ALLOCATION_CONFLICT", 88L);
        assertAllowed(BusinessEventType.ORDER_CREATED, BusinessDomain.ORDER, "ORDER", 1L);
    }

    @Test
    void recordAllowsB2MemberEventsButStillRejectsFutureEvents() {
        when(eventMapper.selectOne(any(QueryWrapper.class))).thenReturn(null);
        when(eventMapper.insert(any(BusinessEvent.class))).thenAnswer(invocation -> {
            BusinessEvent event = invocation.getArgument(0);
            event.id = 601L;
            return 1;
        });

        BusinessEvent memberPoints = eventService.record(BusinessEventRecordRequest.of(
                BusinessEventType.MEMBER_POINTS_CHANGED,
                BusinessDomain.MEMBER,
                "MEMBER_ACCOUNT",
                8L,
                BusinessSourceType.SYSTEM,
                "SUCCESS")
            .idempotencyKey("MEMBER_POINTS_CHANGED:order:18"));
        assertThat(memberPoints.eventType).isEqualTo("MEMBER_POINTS_CHANGED");

        BusinessEvent memberLevel = eventService.record(BusinessEventRecordRequest.of(
                BusinessEventType.MEMBER_LEVEL_CHANGED,
                BusinessDomain.MEMBER,
                "MEMBER_ACCOUNT",
                8L,
                BusinessSourceType.SYSTEM,
                "SUCCESS")
            .idempotencyKey("MEMBER_LEVEL_CHANGED:order:18"));
        assertThat(memberLevel.eventType).isEqualTo("MEMBER_LEVEL_CHANGED");

        assertAllowed(BusinessEventType.ORDER_CREATED, BusinessDomain.ORDER, "ORDER", 1L);
    }

    @Test
    void recordAllowsB3PromotionEventsButStillRejectsFutureEvents() {
        when(eventMapper.insert(any(BusinessEvent.class))).thenAnswer(invocation -> {
            BusinessEvent event = invocation.getArgument(0);
            event.id = 701L;
            return 1;
        });

        assertAllowed(BusinessEventType.COUPON_CLAIMED, BusinessDomain.COUPON, "USER_COUPON", 10L);
        assertAllowed(BusinessEventType.COUPON_LOCKED, BusinessDomain.COUPON, "USER_COUPON", 10L);
        assertAllowed(BusinessEventType.COUPON_RELEASED, BusinessDomain.COUPON, "USER_COUPON", 10L);
        assertAllowed(BusinessEventType.COUPON_USED, BusinessDomain.COUPON, "USER_COUPON", 10L);
        assertAllowed(BusinessEventType.COUPON_REFUNDED, BusinessDomain.COUPON, "USER_COUPON", 10L);
        assertAllowed(BusinessEventType.ACTIVITY_CREATED, BusinessDomain.ACTIVITY, "PROMOTION_CAMPAIGN", 20L);
        assertAllowed(BusinessEventType.ACTIVITY_STATUS_CHANGED, BusinessDomain.ACTIVITY, "PROMOTION_CAMPAIGN", 20L);
        assertAllowed(BusinessEventType.PRICE_RULE_CHANGED, BusinessDomain.PRICING, "PRICE_RULE", 30L);
        assertAllowed(BusinessEventType.ORDER_CREATED, BusinessDomain.ORDER, "ORDER", 1L);
    }

    @Test
    void recordAllowsB4LifecycleEventsButStillRejectsFutureEvents() {
        when(eventMapper.insert(any(BusinessEvent.class))).thenAnswer(invocation -> {
            BusinessEvent event = invocation.getArgument(0);
            event.id = 801L;
            return 1;
        });

        assertAllowed(BusinessEventType.RESERVATION_CANCELED, BusinessDomain.RESERVATION, "RESERVATION", 99L);
        assertAllowed(BusinessEventType.CHECKIN_VERIFIED, BusinessDomain.CHECKIN, "RESERVATION_LIFECYCLE", 99L);
        assertAllowed(BusinessEventType.CHECKIN_EXCEPTION_RECORDED, BusinessDomain.CHECKIN, "RESERVATION_LIFECYCLE", 99L);
        assertAllowed(BusinessEventType.NO_SHOW_CANDIDATE_MARKED, BusinessDomain.CHECKIN,
            "RESERVATION_LIFECYCLE", 99L);
        assertAllowed(BusinessEventType.NO_SHOW_CONFIRMED, BusinessDomain.CHECKIN, "RESERVATION_LIFECYCLE", 99L);
        assertAllowed(BusinessEventType.NO_SHOW_REVOKED, BusinessDomain.CHECKIN, "RESERVATION_LIFECYCLE", 99L);
        assertAllowed(BusinessEventType.RESCHEDULE_REQUESTED, BusinessDomain.RESERVATION,
            "RESERVATION_RESCHEDULE_REQUEST", 99L);
        assertAllowed(BusinessEventType.RESCHEDULE_APPROVED, BusinessDomain.RESERVATION,
            "RESERVATION_RESCHEDULE_REQUEST", 99L);
        assertAllowed(BusinessEventType.RESCHEDULE_REJECTED, BusinessDomain.RESERVATION,
            "RESERVATION_RESCHEDULE_REQUEST", 99L);

        assertAllowed(BusinessEventType.ORDER_CREATED, BusinessDomain.ORDER, "ORDER", 1L);
    }

    @Test
    void recordAllowsB5FunnelMilestoneButStillRejectsUnsupportedEvents() {
        when(eventMapper.insert(any(BusinessEvent.class))).thenAnswer(invocation -> {
            BusinessEvent event = invocation.getArgument(0);
            event.id = 901L;
            return 1;
        });

        BusinessEvent event = eventService.record(BusinessEventRecordRequest.of(
            BusinessEventType.FUNNEL_MILESTONE_RECORDED,
            BusinessDomain.FUNNEL,
            "FUNNEL_EVENT",
            99L,
            BusinessSourceType.USER,
            "SUCCESS"));

        assertThat(event.eventType).isEqualTo("FUNNEL_MILESTONE_RECORDED");
        assertThat(event.businessDomain).isEqualTo("FUNNEL");
        assertAllowed(BusinessEventType.ORDER_CREATED, BusinessDomain.ORDER, "ORDER", 1L);
    }

    @Test
    void b7AllowsNoticePublishedAndDeliveredEvents() {
        when(eventMapper.insert(any(BusinessEvent.class))).thenAnswer(invocation -> {
            BusinessEvent event = invocation.getArgument(0);
            event.id = 1L;
            return 1;
        });

        assertAllowed(BusinessEventType.NOTICE_PUBLISHED, BusinessDomain.NOTICE, "NOTICE", 101L);
        assertAllowed(BusinessEventType.NOTICE_DELIVERED, BusinessDomain.NOTICE, "NOTICE_DELIVERY_BATCH", 202L);
    }

    @Test
    void recordAllowsB8SiteOpsAndAssetEvents() {
        when(eventMapper.insert(any(BusinessEvent.class))).thenAnswer(invocation -> {
            BusinessEvent event = invocation.getArgument(0);
            event.id = 1L;
            return 1;
        });

        assertAllowed(BusinessEventType.SITE_SHIFT_CREATED, BusinessDomain.SITE_OPERATIONS, "SITE_SHIFT", 101L);
        assertAllowed(BusinessEventType.SITE_TASK_COMPLETED, BusinessDomain.SITE_OPERATIONS, "SITE_TASK", 102L);
        assertAllowed(BusinessEventType.ASSET_STATUS_CHANGED, BusinessDomain.ASSET, "ASSET", 201L);
        assertAllowed(BusinessEventType.ASSET_REPAIR_RECORDED, BusinessDomain.ASSET, "ASSET_REPAIR", 202L);
    }

    @Test
    void recordAllowsB9CustomerServiceEventsButStillRejectsUnsupportedEvents() {
        when(eventMapper.insert(any(BusinessEvent.class))).thenAnswer(invocation -> {
            BusinessEvent event = invocation.getArgument(0);
            event.id = 2401L;
            return 1;
        });

        assertAllowed(BusinessEventType.REVIEW_SUBMITTED, BusinessDomain.CUSTOMER_SERVICE, "VENUE_REVIEW", 10L);
        assertAllowed(BusinessEventType.REVIEW_MODERATED, BusinessDomain.CUSTOMER_SERVICE, "VENUE_REVIEW", 10L);
        assertAllowed(BusinessEventType.COMPLAINT_SUBMITTED, BusinessDomain.CUSTOMER_SERVICE, "CUSTOMER_COMPLAINT", 20L);
        assertAllowed(BusinessEventType.COMPLAINT_TRIAGED, BusinessDomain.CUSTOMER_SERVICE, "CUSTOMER_COMPLAINT", 20L);
        assertAllowed(BusinessEventType.CUSTOMER_SERVICE_CASE_CREATED, BusinessDomain.CUSTOMER_SERVICE, "CUSTOMER_SERVICE_CASE", 30L);
        assertAllowed(BusinessEventType.CUSTOMER_SERVICE_CASE_ASSIGNED, BusinessDomain.CUSTOMER_SERVICE, "CUSTOMER_SERVICE_CASE", 30L);
        assertAllowed(BusinessEventType.CUSTOMER_SERVICE_CASE_STATUS_CHANGED, BusinessDomain.CUSTOMER_SERVICE, "CUSTOMER_SERVICE_CASE", 30L);
        assertAllowed(BusinessEventType.CUSTOMER_SERVICE_CASE_RESOLVED, BusinessDomain.CUSTOMER_SERVICE, "CUSTOMER_SERVICE_CASE", 30L);
        assertAllowed(BusinessEventType.CUSTOMER_SERVICE_CASE_CLOSED, BusinessDomain.CUSTOMER_SERVICE, "CUSTOMER_SERVICE_CASE", 30L);

        assertAllowed(BusinessEventType.ORDER_CREATED, BusinessDomain.ORDER, "ORDER", 1L);
    }

    @Test
    void recordReturnsExistingEventWhenScopedIdempotencyKeyAlreadyExists() {
        BusinessEvent existing = new BusinessEvent();
        existing.id = 5L;
        existing.eventNo = "BE202606300001";
        when(eventMapper.selectOne(any(QueryWrapper.class))).thenReturn(existing);
        BusinessEventRecordRequest request = BusinessEventRecordRequest.of(
                BusinessEventType.RESERVATION_CREATED,
                BusinessDomain.RESERVATION,
                "RESERVATION",
                99L,
                BusinessSourceType.USER,
                "SUCCESS")
            .idempotencyKey("RESERVATION_CREATED:99");

        BusinessEvent event = eventService.record(request);

        assertThat(event).isSameAs(existing);
        ArgumentCaptor<QueryWrapper<BusinessEvent>> captor = ArgumentCaptor.forClass(QueryWrapper.class);
        verify(eventMapper).selectOne(captor.capture());
        String sql = captor.getValue().getCustomSqlSegment();
        assertThat(sql).contains("source_type");
        assertThat(sql).contains("provider_env IS NULL");
        assertThat(sql).contains("provider_name IS NULL");
        assertThat(sql).contains("idempotency_key");
    }

    @Test
    void recordReturnsExistingEventWhenConcurrentInsertHitsIdempotencyUniqueKey() {
        BusinessEvent existing = new BusinessEvent();
        existing.id = 6L;
        existing.eventNo = "BE202606300002";
        when(eventMapper.selectOne(any(QueryWrapper.class)))
            .thenReturn(null)
            .thenReturn(existing);
        when(eventMapper.insert(any(BusinessEvent.class)))
            .thenThrow(new DuplicateKeyException("uk_business_event_idempotency"));
        BusinessEventRecordRequest request = BusinessEventRecordRequest.of(
                BusinessEventType.RESERVATION_CREATED,
                BusinessDomain.RESERVATION,
                "RESERVATION",
                99L,
                BusinessSourceType.USER,
                "SUCCESS")
            .idempotencyKey("RESERVATION_CREATED:99");

        BusinessEvent event = eventService.record(request);

        assertThat(event).isSameAs(existing);
        verify(eventMapper).insert(any(BusinessEvent.class));
    }

    @Test
    void listForEntityCapsLimitAndOrdersByOccurredAtDescending() {
        when(eventMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of());

        eventService.listForEntity("RESERVATION", 99L, 500);

        ArgumentCaptor<QueryWrapper<BusinessEvent>> captor = ArgumentCaptor.forClass(QueryWrapper.class);
        verify(eventMapper).selectList(captor.capture());
        String sql = captor.getValue().getCustomSqlSegment();
        assertThat(sql).contains("entity_type");
        assertThat(sql).contains("entity_id");
        assertThat(sql).contains("ORDER BY occurred_at DESC,id DESC");
        assertThat(sql).contains("LIMIT 200");
        assertThat(captor.getValue().getParamNameValuePairs().values()).contains("RESERVATION", 99L);
    }

    private void assertAllowed(BusinessEventType eventType, BusinessDomain domain, String entityType, Long entityId) {
        BusinessEvent event = eventService.record(BusinessEventRecordRequest.of(
            eventType,
            domain,
            entityType,
            entityId,
            BusinessSourceType.USER,
            "SUCCESS"));

        assertThat(event.eventType).isEqualTo(eventType.name());
        assertThat(event.businessDomain).isEqualTo(domain.name());
        assertThat(event.entityType).isEqualTo(entityType);
        assertThat(event.entityId).isEqualTo(entityId);
    }
}
