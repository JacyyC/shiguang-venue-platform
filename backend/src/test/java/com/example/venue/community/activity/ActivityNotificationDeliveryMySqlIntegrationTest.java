package com.example.venue.community.activity;

import org.springframework.test.annotation.DirtiesContext;

import com.example.venue.common.EntityExistenceService;
import com.example.venue.community.activity.mapper.CommunityActivityNotificationTaskMapper;
import com.example.venue.community.activity.service.ActivityNotificationAfterCommitListener;
import com.example.venue.community.activity.service.ActivityNotificationDispatcher;
import com.example.venue.community.activity.service.ActivityNotificationService;
import com.example.venue.notice.email.EmailIdempotencyKeyFactory;
import com.example.venue.notice.email.EmailRecipientEligibility;
import com.example.venue.notice.email.EmailRecipientResolver;
import com.example.venue.notice.email.EmailSnapshotService;
import com.example.venue.notice.email.EmailTemplateRegistry;
import com.example.venue.notice.mapper.NoticeEmailDeliveryMapper;
import com.example.venue.notice.mapper.NoticeMapper;
import com.example.venue.notice.mapper.NoticeMessageMapper;
import com.example.venue.notice.mapper.NoticeOutboxMapper;
import com.example.venue.notice.routing.DefaultNotificationRoutingPolicy;
import com.example.venue.notice.service.NoticeDeliveryService;
import com.example.venue.notice.service.NoticeReceiptService;
import com.example.venue.notice.service.NoticeService;
import com.example.venue.reservation.mapper.ReservationMapper;
import com.example.venue.reservation.mapper.ReservationOccupancyMapper;
import com.example.venue.reservation.service.ReservationCommitmentChangePublisher;
import com.example.venue.resource.mapper.ReservationOccupancySeatMapper;
import com.example.venue.resource.mapper.VenueResourceSeatMapper;
import com.example.venue.resource.service.ResourceAllocationConflictEventRecorder;
import com.example.venue.resource.service.ResourceAllocationService;
import com.example.venue.resource.service.VenueResourceRuleService;
import com.example.venue.venue.mapper.VenueSpaceMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.task.TaskExecutor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

// Close the cached Spring context while this class still owns live containers.
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Testcontainers
@SpringBootTest(
    classes = {
        CommunityActivityMySqlIntegrationTest.IntegrationApplication.class,
        ActivityNotificationDeliveryMySqlIntegrationTest.DeliveryConfiguration.class
    },
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = "spring.task.scheduling.enabled=false"
)
class ActivityNotificationDeliveryMySqlIntegrationTest {
    private static final AtomicLong IDS = new AtomicLong(18_000_000L);
    private static final LocalDateTime START = LocalDateTime.of(2026, 10, 10, 10, 0);
    private static final LocalDateTime END = START.plusHours(2);

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
        .withDatabaseName("activity_notification_delivery")
        .withUsername("activity")
        .withPassword("activity")
        .withEnv("TZ", "Asia/Shanghai")
        .withCommand("--character-set-server=utf8mb4",
                     "--collation-server=utf8mb4_unicode_ci",
                     "--log-bin-trust-function-creators=1");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> MYSQL.getJdbcUrl()
            + "?connectionTimeZone=Asia/Shanghai&forceConnectionTimeZoneToSession=true");
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.flyway.locations", () -> "classpath:db/migration");
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private ActivityNotificationService notificationService;
    @Autowired
    private ResourceAllocationService resourceAllocationService;

    @BeforeEach
    void cleanFixtures() {
        jdbcTemplate.update("DELETE FROM notice_email_delivery");
        jdbcTemplate.update("DELETE FROM notice_outbox");
        jdbcTemplate.update("DELETE FROM notice_message");
        jdbcTemplate.update("DELETE FROM community_activity_notification_task");
        jdbcTemplate.update("DELETE FROM community_activity_state_event");
        jdbcTemplate.update("DELETE FROM community_activity_participation");
        jdbcTemplate.update("DELETE FROM community_companion_request");
        jdbcTemplate.update("DELETE FROM community_activity");
        jdbcTemplate.update("DELETE FROM community_group_member");
        jdbcTemplate.update("DELETE FROM community_group");
        jdbcTemplate.update("DELETE FROM reservation_occupancy_seat");
        jdbcTemplate.update("DELETE FROM reservation_occupancy");
        jdbcTemplate.update("DELETE FROM reservation");
        jdbcTemplate.update("DELETE FROM venue_space");
        jdbcTemplate.update("DELETE FROM venue");
    }

    @Test
    void notificationDispatchesOnlyAfterCommitCreatesAnInAppOutboxAndRetriesFailures() {
        String rolledBackKey = "ACTIVITY_COMMIT_TEST:rolled-back:" + nextId();
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.executeWithoutResult(status -> {
            notificationService.enqueue("ACTIVITY_UPDATED", rolledBackKey, 8101L,
                "活动更新", "活动地点已更新。", "ACTIVITY", 41L);
            status.setRollbackOnly();
        });

        assertThat(count("community_activity_notification_task", "event_key = ?", rolledBackKey)).isZero();
        assertThat(count("notice_outbox", "event_key = ?", rolledBackKey)).isZero();

        String committedKey = "ACTIVITY_COMMIT_TEST:committed:" + nextId();
        transaction.executeWithoutResult(status -> notificationService.enqueue(
            "ACTIVITY_UPDATED", committedKey, 8102L,
            "活动更新", "活动地点已更新。", "ACTIVITY", 42L));

        awaitStatus(committedKey, "DELIVERED");
        assertThat(count("notice_outbox", "event_key = ? AND channel = 'IN_APP'", committedKey)).isEqualTo(1);
        assertThat(count("notice_message", "user_id = ? AND business_id = ?", 8102L, 42L)).isEqualTo(1);

        String failedKey = "ACTIVITY_COMMIT_TEST:retry:" + nextId();
        transaction.executeWithoutResult(status -> notificationService.enqueue(
            "ACTIVITY_UPDATED", failedKey, -8103L,
            "活动更新", "活动地点已更新。", "ACTIVITY", 43L));

        awaitStatus(failedKey, "RETRY_WAITING");
        assertThat(count("notice_outbox", "event_key = ?", failedKey)).isZero();
    }

    @Test
    void realResourceReleasePropagatesAfterCommitToTheActivityLocationAndNotificationOutbox() {
        long ownerId = nextId();
        long participantId = nextId();
        ReservationFixture reservation = insertReservation(ownerId);
        long groupId = insertGroup(ownerId);
        long activityId = insertConfirmedActivity(groupId, ownerId, reservation);
        insertParticipation(activityId, ownerId, "ORGANIZER");
        insertParticipation(activityId, participantId, "PARTICIPANT");

        assertThat(resourceAllocationService.release(reservation.reservationId(), "RESERVATION_CANCELED"))
            .isEqualTo(1);

        assertThat(jdbcTemplate.queryForObject("""
            SELECT location_commitment_status FROM community_activity WHERE id = ?
            """, String.class, activityId)).isEqualTo("DEGRADED");
        assertThat(count("community_activity_state_event",
            "event_type = 'ACTIVITY_LOCATION_DEGRADED' AND subject_id = ?", activityId)).isEqualTo(1);
        assertThat(count("community_activity_notification_task",
            "event_type = 'ACTIVITY_LOCATION_DEGRADED' AND business_id = ?", activityId)).isEqualTo(2);
        awaitCount("notice_outbox",
            "event_type = 'ACTIVITY_LOCATION_DEGRADED' AND channel = 'IN_APP'", 2);
    }

    private long count(String table, String predicate, Object... parameters) {
        if (parameters.length == 0) {
            return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE " + predicate,
                Long.class);
        }
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE " + predicate,
            Long.class, parameters);
    }

    private String statusFor(String eventKey) {
        return jdbcTemplate.queryForObject("""
            SELECT status FROM community_activity_notification_task WHERE event_key = ?
            """, String.class, eventKey);
    }

    private void awaitStatus(String eventKey, String expectedStatus) {
        awaitCondition(() -> expectedStatus.equals(statusFor(eventKey)),
            "通知任务未进入状态 " + expectedStatus + ": " + eventKey);
    }

    private void awaitCount(String table, String predicate, long expectedCount) {
        awaitCondition(() -> count(table, predicate) == expectedCount,
            table + " 未在时限内达到记录数 " + expectedCount);
    }

    private void awaitCondition(java.util.function.BooleanSupplier condition, String failureMessage) {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(20L);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AssertionError("等待活动通知时被中断", exception);
            }
        }
        throw new AssertionError(failureMessage);
    }

    private ReservationFixture insertReservation(long ownerId) {
        long venueId = nextId();
        long spaceId = nextId();
        long reservationId = nextId();
        jdbcTemplate.update("INSERT INTO venue(id, name, status, deleted) VALUES (?, ?, 'ACTIVE', 0)",
            venueId, "真实释放场馆");
        jdbcTemplate.update("""
            INSERT INTO venue_space(id, venue_id, name, type, capacity, price_per_hour, status, deleted)
            VALUES (?, ?, '真实释放空间', 'BADMINTON', 20, 10.00, 'ACTIVE', 0)
            """, spaceId, venueId);
        jdbcTemplate.update("""
            INSERT INTO reservation(
                id, user_id, venue_id, space_id, start_time, end_time, status, amount,
                create_time, update_time, deleted, booking_mode_snapshot, participant_count
            ) VALUES (?, ?, ?, ?, ?, ?, 'APPROVED', 0.00, NOW(), NOW(), 0, 'EXCLUSIVE', 3)
            """, reservationId, ownerId, venueId, spaceId, START.minusHours(1), END.plusHours(1));
        jdbcTemplate.update("""
            INSERT INTO reservation_occupancy(
                reservation_id, space_id, slot_date, start_time, end_time, active_flag,
                booking_mode, quantity
            ) VALUES (?, ?, ?, ?, ?, 1, 'EXCLUSIVE', 1)
            """, reservationId, spaceId, START.toLocalDate(), START.minusHours(1).toLocalTime(),
            END.plusHours(1).toLocalTime());
        return new ReservationFixture(reservationId, venueId, spaceId);
    }

    private long insertGroup(long ownerId) {
        long groupId = nextId();
        jdbcTemplate.update("""
            INSERT INTO community_group(
                id, group_no, name, description, owner_user_id, visibility, join_policy,
                status, idempotency_key, request_hash
            ) VALUES (?, ?, '释放传播小组', '真实释放传播测试', ?, 'PLATFORM_VISIBLE', 'DIRECT',
                      'ACTIVE', ?, ?)
            """, groupId, "CG-RELEASE-" + groupId, ownerId,
            "group-key-" + groupId, "group-hash-" + groupId);
        return groupId;
    }

    private long insertConfirmedActivity(long groupId, long ownerId, ReservationFixture reservation) {
        long activityId = nextId();
        jdbcTemplate.update("""
            INSERT INTO community_activity(
                id, activity_no, group_id, organizer_user_id, activity_type, title, description,
                start_time, end_time, location_type, venue_id, space_id, reservation_id,
                location_commitment_status, commitment_version, capacity_total, participant_count,
                status, version_no, published_at
            ) VALUES (?, ?, ?, ?, 'STUDY', '真实释放活动', '真实调用资源释放后的传播测试',
                      ?, ?, 'VENUE_SPACE', ?, ?, ?, 'CONFIRMED', 1, 3, 2, 'PUBLISHED', 1, NOW())
            """, activityId, "ACT-RELEASE-" + activityId, groupId, ownerId, START, END,
            reservation.venueId(), reservation.spaceId(), reservation.reservationId());
        return activityId;
    }

    private void insertParticipation(long activityId, long userId, String role) {
        jdbcTemplate.update("""
            INSERT INTO community_activity_participation(
                participation_no, target_type, target_id, user_id, role_type, status, joined_at
            ) VALUES (?, 'ACTIVITY', ?, ?, ?, 'ACTIVE', NOW())
            """, "PAR-RELEASE-" + nextId(), activityId, userId, role);
    }

    private long nextId() {
        return IDS.incrementAndGet();
    }

    private record ReservationFixture(long reservationId, long venueId, long spaceId) {
    }

    @TestConfiguration(proxyBeanMethods = false)
    @MapperScan(basePackageClasses = NoticeOutboxMapper.class)
    static class DeliveryConfiguration {
        @Bean
        EmailTemplateRegistry emailTemplateRegistry() {
            return new EmailTemplateRegistry();
        }

        @Bean
        NoticeService noticeService(NoticeMapper noticeMapper,
                                    NoticeMessageMapper messageMapper,
                                    NoticeOutboxMapper outboxMapper,
                                    NoticeEmailDeliveryMapper deliveryMapper,
                                    EmailTemplateRegistry registry,
                                    ObjectMapper objectMapper) {
            EmailRecipientResolver recipientResolver = (userId, recipientKind) ->
                new EmailRecipientEligibility(userId, recipientKind, true, true,
                    "activity@example.test", "a***y@e***e.test", true, null);
            return new NoticeService(
                noticeMapper, messageMapper, outboxMapper, mock(com.example.venue.venue.service.VenueAccessService.class),
                mock(NoticeDeliveryService.class), mock(NoticeReceiptService.class),
                recipientResolver, new DefaultNotificationRoutingPolicy(), registry, registry,
                new EmailIdempotencyKeyFactory(), new EmailSnapshotService(deliveryMapper), objectMapper);
        }

        @Bean
        ActivityNotificationDispatcher activityNotificationDispatcher(
            com.example.venue.community.activity.service.ActivityNotificationTaskStateService stateService,
            NoticeService noticeService
        ) {
            return new ActivityNotificationDispatcher(stateService, noticeService);
        }

        @Bean
        ThreadPoolTaskExecutor activityNotificationTaskExecutor() {
            ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
            executor.setCorePoolSize(1);
            executor.setMaxPoolSize(1);
            executor.setQueueCapacity(20);
            executor.setThreadNamePrefix("activity-notification-test-");
            return executor;
        }

        @Bean
        ActivityNotificationAfterCommitListener activityNotificationAfterCommitListener(
            ActivityNotificationDispatcher dispatcher,
            @Qualifier("activityNotificationTaskExecutor") TaskExecutor taskExecutor
        ) {
            return new ActivityNotificationAfterCommitListener(dispatcher, taskExecutor);
        }

        @Bean
        ResourceAllocationService resourceAllocationService(
            ReservationOccupancyMapper occupancyMapper,
            ObjectMapper objectMapper,
            ReservationCommitmentChangePublisher changePublisher
        ) {
            ResourceAllocationService service = new ResourceAllocationService(
                mock(VenueResourceRuleService.class), mock(VenueResourceSeatMapper.class), occupancyMapper,
                mock(ReservationOccupancySeatMapper.class), mock(ReservationMapper.class),
                mock(EntityExistenceService.class), mock(VenueSpaceMapper.class),
                mock(ResourceAllocationConflictEventRecorder.class), objectMapper);
            service.setCommitmentChangePublisher(changePublisher);
            return service;
        }
    }
}
