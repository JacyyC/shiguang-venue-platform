package com.example.venue.reservation;

import org.springframework.test.annotation.DirtiesContext;

import com.example.venue.business.service.BusinessEventService;
import com.example.venue.checkin.service.ReservationLifecycleService;
import com.example.venue.common.CurrentUser;
import com.example.venue.common.EntityExistenceService;
import com.example.venue.funnel.service.FunnelEventService;
import com.example.venue.inventory.dto.BookabilityDecision;
import com.example.venue.inventory.mapper.VenueInventoryAuditMapper;
import com.example.venue.inventory.service.VenueInventoryService;
import com.example.venue.notice.service.NoticeService;
import com.example.venue.order.service.OrderService;
import com.example.venue.pricing.AmountBreakdown;
import com.example.venue.pricing.PricingSnapshotFactory;
import com.example.venue.promotion.dto.PromotionPricingContext;
import com.example.venue.promotion.service.CouponService;
import com.example.venue.promotion.service.PromotionPricingService;
import com.example.venue.reservation.dto.CreateReservationRequest;
import com.example.venue.reservation.dto.ReservationSubmissionResult;
import com.example.venue.reservation.entity.Reservation;
import com.example.venue.reservation.enums.ReservationSubmissionState;
import com.example.venue.reservation.exception.ReservationSubmissionStateException;
import com.example.venue.reservation.mapper.ReservationCreateCommandMapper;
import com.example.venue.reservation.mapper.ReservationMapper;
import com.example.venue.reservation.mapper.ReservationOccupancyMapper;
import com.example.venue.reservation.mapper.ReservationStatusLogMapper;
import com.example.venue.reservation.service.ReservationCommitmentChangePublisher;
import com.example.venue.reservation.service.ReservationCreateIdempotencyService;
import com.example.venue.reservation.service.ReservationService;
import com.example.venue.resource.dto.ResourceRuleChangeRequest;
import com.example.venue.resource.dto.ResourceSeatDefinition;
import com.example.venue.resource.enums.BookingMode;
import com.example.venue.resource.enums.ResourceCategory;
import com.example.venue.resource.exception.ResourceAllocationConflictException;
import com.example.venue.resource.mapper.ReservationOccupancySeatMapper;
import com.example.venue.resource.mapper.VenueResourceRuleMapper;
import com.example.venue.resource.mapper.VenueResourceSeatMapper;
import com.example.venue.resource.service.ResourceAllocationConflictEventRecorder;
import com.example.venue.resource.service.ResourceAllocationService;
import com.example.venue.resource.service.VenueResourceRuleService;
import com.example.venue.system.mapper.SysConfigMapper;
import com.example.venue.system.service.SysConfigService;
import com.example.venue.venue.mapper.VenueMapper;
import com.example.venue.venue.mapper.VenueSpaceMapper;
import com.example.venue.venue.service.VenueAccessService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;

/**
 * 预约请求身份与结果恢复的真实数据库验证（S03 / AR-01）。
 *
 * <p>与 {@code ReservationServiceTest} 的分工：本类用真实 MySQL 唯一约束、真实事务和真实资源分配，
 * 验证「同一次提交意图只产生一条预约及一组副作用」；纯逻辑与分支覆盖留在单元测试。</p>
 */
// Close the cached Spring context while this class still owns live containers.
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Testcontainers
@SpringBootTest(
    classes = ReservationCreateRecoveryMySqlIntegrationTest.IntegrationApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = "spring.task.scheduling.enabled=false"
)
class ReservationCreateRecoveryMySqlIntegrationTest {
    private static final AtomicLong IDS = new AtomicLong(7_100_000L);
    /** 与容器声明的 Asia/Shanghai 保持一致：整点窗口不跨午夜，且落在默认 90 天可预约范围内。 */
    private static final ZoneId FIXTURE_ZONE = ZoneId.of("Asia/Shanghai");
    private static final LocalDateTime SLOT_START = LocalDate.now(FIXTURE_ZONE).plusDays(1).atTime(10, 0);
    private static final LocalDateTime SLOT_END = SLOT_START.plusHours(2);

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
        .withDatabaseName("reservation_recovery")
        .withUsername("recovery")
        .withPassword("recovery")
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
    private ReservationService reservationService;
    @Autowired
    private VenueResourceRuleService ruleService;
    @Autowired
    private ReservationMapper reservationMapper;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private BusinessEventService businessEventService;
    @Autowired
    private FunnelEventService funnelEventService;
    @Autowired
    private NoticeService noticeService;

    @Autowired
    private org.springframework.transaction.PlatformTransactionManager transactionManager;

    private long venueId;
    private long spaceId;
    private long ownerId;
    private long otherUserId;
    private final List<Long> reservationIds = new ArrayList<>();

    @BeforeEach
    void createSpaceFixture() {
        venueId = IDS.incrementAndGet();
        spaceId = IDS.incrementAndGet();
        ownerId = IDS.incrementAndGet();
        otherUserId = IDS.incrementAndGet();
        jdbcTemplate.update("INSERT INTO venue(id, name, status, deleted) VALUES (?, ?, 'ACTIVE', 0)",
            venueId, "预约恢复测试场馆");
        jdbcTemplate.update("""
            INSERT INTO venue_space(id, venue_id, name, type, capacity, price_per_hour, status, deleted)
            VALUES (?, ?, '预约恢复测试场地', 'BADMINTON', 20, 20.00, 'ACTIVE', 0)
            """, spaceId, venueId);
    }

    @AfterEach
    void removeOnlyThisTestFixtures() {
        SecurityContextHolder.clearContext();
        for (Long reservationId : reservationIds) {
            jdbcTemplate.update("DELETE FROM reservation_status_log WHERE reservation_id = ?", reservationId);
            jdbcTemplate.update("DELETE FROM reservation_occupancy_seat WHERE reservation_id = ?", reservationId);
            jdbcTemplate.update("DELETE FROM reservation_occupancy WHERE reservation_id = ?", reservationId);
            jdbcTemplate.update("DELETE FROM reservation WHERE id = ?", reservationId);
        }
        jdbcTemplate.update("DELETE FROM reservation_create_command WHERE user_id IN (?, ?)", ownerId, otherUserId);
        jdbcTemplate.update("DELETE FROM venue_inventory_audit WHERE entity_type = 'VENUE_RESOURCE_RULE' "
            + "AND entity_id IN (SELECT id FROM venue_resource_rule WHERE space_id = ?)", spaceId);
        jdbcTemplate.update("DELETE FROM venue_resource_seat WHERE space_id = ?", spaceId);
        jdbcTemplate.update("DELETE FROM venue_resource_rule WHERE space_id = ?", spaceId);
        jdbcTemplate.update("DELETE FROM venue_space WHERE id = ?", spaceId);
        jdbcTemplate.update("DELETE FROM venue WHERE id = ?", venueId);
    }

    @Test
    void exclusiveReplayReturnsTheSameReservationWhenTheOriginalResultAlreadyOccupiesTheSlot() {
        int businessEventsBefore = invocationCount(businessEventService);
        int funnelEventsBefore = invocationCount(funnelEventService);
        int noticesBefore = invocationCount(noticeService);

        long id = runAs(ownerId, () -> reservationService.create(exclusiveRequest(2), "recovery-exclusive-key"))
            .id;
        trackReservation(id);
        // 首次提交各产生一次业务事件、漏斗里程碑，以及「已提交」站内信与「无管理员可通知」记录
        assertThat(invocationCount(businessEventService)).isEqualTo(businessEventsBefore + 1);
        assertThat(invocationCount(funnelEventService)).isEqualTo(funnelEventsBefore + 1);
        assertThat(invocationCount(noticeService)).isEqualTo(noticesBefore + 2);
        assertThat(reservationCount()).isEqualTo(1);
        assertThat(statusLogCount()).isEqualTo(1);

        int businessEventsAfterCreate = invocationCount(businessEventService);
        int funnelEventsAfterCreate = invocationCount(funnelEventService);
        int noticesAfterCreate = invocationCount(noticeService);

        // 原结果已占用库存：重放必须先核对再判断，不能因为时段已被自己占用而报冲突。
        Reservation replayed = runAs(ownerId,
            () -> reservationService.create(exclusiveRequest(2), "recovery-exclusive-key"));
        assertThat(replayed.id).isEqualTo(id);

        assertThat(reservationCount()).isEqualTo(1);
        assertThat(occupancyCount()).isEqualTo(1);
        assertThat(statusLogCount()).isEqualTo(1);
        // 重放不得新增任何副作用
        assertThat(invocationCount(businessEventService)).isEqualTo(businessEventsAfterCreate);
        assertThat(invocationCount(funnelEventService)).isEqualTo(funnelEventsAfterCreate);
        assertThat(invocationCount(noticeService)).isEqualTo(noticesAfterCreate);

        // 同一时段换一个请求键是真的冲突，证明时段确实已被占用，上一步的重放确实绕过了库存判断。
        assertThatThrownBy(() -> runAs(ownerId,
            () -> reservationService.create(exclusiveRequest(2), "recovery-exclusive-other")))
            .isInstanceOf(ResourceAllocationConflictException.class);
        assertThat(reservationCount()).isEqualTo(1);
    }

    @Test
    void capacityIntentReplaysWithoutDuplicatingItsAllocation() {
        int funnelEventsBefore = invocationCount(funnelEventService);
        configureCapacityRule(2);
        long reservationId = runAs(ownerId,
            () -> reservationService.create(capacityRequest(2), "recovery-capacity-key")).id;
        trackReservation(reservationId);
        assertThat(invocationCount(funnelEventService)).isEqualTo(funnelEventsBefore + 1);

        assertThat(runAs(ownerId, () -> reservationService.create(capacityRequest(2), "recovery-capacity-key")).id)
            .isEqualTo(reservationId);
        assertThat(runAs(ownerId, () -> reservationService.submissionResult("recovery-capacity-key")).status)
            .isEqualTo("COMPLETED");
        assertThat(jdbcTemplate.queryForObject("""
            SELECT SUM(quantity) FROM reservation_occupancy WHERE space_id = ? AND active_flag = 1
            """, Integer.class, spaceId)).isEqualTo(2);
        assertThat(reservationCount()).isEqualTo(1);
        assertThat(occupancyCount()).isEqualTo(1);
        assertThat(statusLogCount()).isEqualTo(1);
        assertThat(invocationCount(funnelEventService)).isEqualTo(funnelEventsBefore + 1);
    }

    @Test
    void assignedSeatIntentReplaysAfterItsSeatsAreAlreadyAllocated() {
        int noticesBefore = invocationCount(noticeService);
        configureAssignedSeatRule();
        Long seatId = ruleService.activeSeats(ruleService.currentRule(spaceId).id).get(0).id;
        long reservationId = runAs(ownerId,
            () -> reservationService.create(seatRequest(List.of(seatId)), "recovery-seat-key")).id;
        trackReservation(reservationId);
        assertThat(invocationCount(noticeService)).isEqualTo(noticesBefore + 2);

        Reservation replayed = runAs(ownerId,
            () -> reservationService.create(seatRequest(List.of(seatId)), "recovery-seat-key"));
        assertThat(replayed.id).isEqualTo(reservationId);

        assertThat(jdbcTemplate.queryForObject("""
            SELECT COUNT(*) FROM reservation_occupancy_seat WHERE reservation_id = ?
            """, Integer.class, reservationId)).isEqualTo(1);
        assertThat(reservationCount()).isEqualTo(1);
        assertThat(occupancyCount()).isEqualTo(1);
        assertThat(statusLogCount()).isEqualTo(1);
        assertThat(invocationCount(noticeService)).isEqualTo(noticesBefore + 2);
    }

    @Test
    void reusedKeyWithChangedPayloadIsRejectedWithoutOverwritingOrCreating() throws Exception {
        long id = runAs(ownerId, () -> reservationService.create(exclusiveRequest(2), "recovery-payload-key")).id;
        trackReservation(id);

        CreateReservationRequest changed = exclusiveRequest(3);
        assertThatThrownBy(() -> runAs(ownerId, () -> reservationService.create(changed, "recovery-payload-key")))
            .isInstanceOf(ReservationSubmissionStateException.class)
            .extracting(error -> ((ReservationSubmissionStateException) error).getState())
            .isEqualTo(ReservationSubmissionState.SUBMISSION_KEY_REUSED);

        assertThat(reservationCount()).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("""
            SELECT COUNT(*) FROM reservation_create_command WHERE user_id = ? AND reservation_id = ?
            """, Integer.class, ownerId, id)).isEqualTo(1);
        assertThat(runAs(ownerId, () -> reservationService.submissionResult("recovery-payload-key")).reservationId)
            .isEqualTo(id);
    }

    @Test
    void concurrentSameKeySubmissionsProduceExactlyOneReservation() throws Exception {
        int noticesBefore = invocationCount(noticeService);
        List<Reservation> results = runConcurrently(
            () -> runAs(ownerId, () -> reservationService.create(exclusiveRequest(2), "recovery-concurrent-key")),
            () -> runAs(ownerId, () -> reservationService.create(exclusiveRequest(2), "recovery-concurrent-key")));

        List<Long> ids = results.stream().map(reservation -> reservation.id).distinct().toList();
        assertThat(ids).hasSize(1);
        trackReservation(ids.get(0));
        assertThat(reservationCount()).isEqualTo(1);
        assertThat(occupancyCount()).isEqualTo(1);
        assertThat(statusLogCount()).isEqualTo(1);
        assertThat(invocationCount(noticeService)).isEqualTo(noticesBefore + 2);
    }

    @Test
    void anotherUserCannotReuseOrReadSomeoneElsesSubmissionIntent() {
        long firstId = runAs(ownerId, () -> reservationService.create(exclusiveRequest(2), "recovery-owner-key")).id;
        trackReservation(firstId);

        // 同名键属于另一个用户：不会命中他人请求，也不返回他人预约。
        assertThat(runAs(otherUserId, () -> reservationService.submissionResult("recovery-owner-key")).status)
            .isEqualTo("NONE");

        // 另一位用户只能另选一个（相邻、不重叠）时段，本用例只关心请求身份的归属。
        long secondId = runAs(otherUserId, () -> {
            CreateReservationRequest request = requestAt(
                SLOT_END, SLOT_END.plusHours(2), "另一位用户", "13900000000", 2);
            return reservationService.create(request, "recovery-owner-key").id;
        });
        trackReservation(secondId);

        assertThat(secondId).isNotEqualTo(firstId);
        assertThat(runAs(otherUserId, () -> reservationService.submissionResult("recovery-owner-key")).reservationId)
            .isEqualTo(secondId);
        assertThat(runAs(ownerId, () -> reservationService.create(exclusiveRequest(2), "recovery-owner-key")).id)
            .isEqualTo(firstId);
        assertThat(reservationCount()).isEqualTo(2);
    }

    @Test
    void rolledBackAttemptLeavesNoIntentSoTheSameKeyCanBeRetriedAfterTheBlockerIsGone() {
        long blockerId = runAs(ownerId, () -> reservationService.create(exclusiveRequest(2), "recovery-blocker-key")).id;
        trackReservation(blockerId);

        assertThatThrownBy(() -> runAs(ownerId,
            () -> reservationService.create(exclusiveRequest(2), "recovery-retry-key")))
            .isInstanceOf(ResourceAllocationConflictException.class);
        assertThat(jdbcTemplate.queryForObject("""
            SELECT COUNT(*) FROM reservation_create_command
            WHERE user_id = ? AND idempotency_key = 'recovery-retry-key'
            """, Integer.class, ownerId)).isZero();

        // 释放阻塞后，同一个键可以正常形成新意图（记录随失败事务一并回滚）。
        jdbcTemplate.update("""
            UPDATE reservation_occupancy SET active_flag = NULL, released_at = NOW(), release_reason = 'TEST_RELEASE'
            WHERE reservation_id = ? AND active_flag = 1
            """, blockerId);
        long retriedId = runAs(ownerId,
            () -> reservationService.create(exclusiveRequest(2), "recovery-retry-key")).id;
        trackReservation(retriedId);
        assertThat(retriedId).isNotEqualTo(blockerId);
        assertThat(runAs(ownerId, () -> reservationService.submissionResult("recovery-retry-key")).status)
            .isEqualTo("COMPLETED");
    }

    @Test
    void readOnlyResultQueryDistinguishesNoneProcessingAndCompleted() {
        assertThat(runAs(ownerId, () -> reservationService.submissionResult("recovery-unknown-key")).status)
            .isEqualTo("NONE");
        assertThat(runAs(ownerId, () -> reservationService.submissionResult(null)).status).isEqualTo("NONE");

        // 已提交 PROCESSING 行的兼容分支；真实未提交窗口由独立事务屏障用例覆盖。
        LocalDateTime now = LocalDateTime.now();
        jdbcTemplate.update("""
            INSERT INTO reservation_create_command(
                user_id, idempotency_key, request_hash, status, create_time
            ) VALUES (?, 'recovery-inflight-key', ?, 'PROCESSING', ?)
            """, ownerId, "0".repeat(64), now);
        ReservationSubmissionResult inFlight = runAs(ownerId,
            () -> reservationService.submissionResult("recovery-inflight-key"));
        assertThat(inFlight.status).isEqualTo("PROCESSING");
        assertThat(inFlight.reservation).isNull();

        long id = runAs(ownerId, () -> reservationService.create(exclusiveRequest(2), "recovery-done-key")).id;
        trackReservation(id);
        ReservationSubmissionResult done = runAs(ownerId,
            () -> reservationService.submissionResult("recovery-done-key"));
        assertThat(done.status).isEqualTo("COMPLETED");
        assertThat(done.reservationId).isEqualTo(id);
        assertThat(done.reservation).isNotNull();
        assertThat(done.reservation.contactName).isEqualTo("王小明");
    }

    @Test
    void uncommittedResultIsInvisibleButSameKeyRetryWaitsAndReplaysWithoutExtraCapacity() throws Exception {
        configureCapacityRule(10);
        CountDownLatch written = new CountDownLatch(1);
        CountDownLatch commit = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        String key = "recovery-real-uncommitted";
        try {
            Future<Reservation> original = executor.submit(() ->
                new org.springframework.transaction.support.TransactionTemplate(transactionManager).execute(status -> {
                    Reservation reservation = runAs(ownerId, () -> reservationService.create(capacityRequest(1), key));
                    written.countDown();
                    try {
                        if (!commit.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("commit barrier timeout");
                    } catch (InterruptedException error) {
                        Thread.currentThread().interrupt(); throw new IllegalStateException(error);
                    }
                    return reservation;
                }));
            assertThat(written.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(reservationCount()).isZero();
            assertThat(runAs(ownerId, () -> reservationService.submissionResult(key)).status).isEqualTo("NONE");
            Future<Reservation> retry = executor.submit(() ->
                runAs(ownerId, () -> reservationService.create(capacityRequest(1), key)));
            commit.countDown();
            Reservation first = original.get(20, TimeUnit.SECONDS);
            trackReservation(first.id);
            assertThat(retry.get(20, TimeUnit.SECONDS).id).isEqualTo(first.id);
            assertThat(reservationCount()).isEqualTo(1);
            assertThat(occupancyCount()).isEqualTo(1);
            assertThat(statusLogCount()).isEqualTo(1);
        } finally {
            commit.countDown(); executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void requestsWithoutAKeyKeepTheLegacyBehaviour() {
        long id = runAs(ownerId, () -> reservationService.create(exclusiveRequest(2))).id;
        trackReservation(id);
        assertThat(reservationCount()).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM reservation_create_command WHERE user_id = ?", Integer.class, ownerId)).isZero();
    }

    private CreateReservationRequest exclusiveRequest(int participantCount) {
        return requestAt(SLOT_START, SLOT_END, "王小明", "13800000000", participantCount);
    }

    private CreateReservationRequest requestAt(LocalDateTime startTime, LocalDateTime endTime,
                                               String contactName, String contactPhone, int participantCount) {
        CreateReservationRequest request = new CreateReservationRequest();
        request.spaceId = spaceId;
        request.startTime = startTime;
        request.endTime = endTime;
        request.participantCount = participantCount;
        request.seatIds = List.of();
        request.contactName = contactName;
        request.contactPhone = contactPhone;
        request.remark = "预约恢复测试";
        return request;
    }

    private CreateReservationRequest capacityRequest(int participantCount) {
        return exclusiveRequest(participantCount);
    }

    private CreateReservationRequest seatRequest(List<Long> seatIds) {
        CreateReservationRequest request = exclusiveRequest(seatIds.size());
        request.participantCount = Math.max(1, seatIds.size());
        request.seatIds = seatIds;
        return request;
    }

    private void configureCapacityRule(int capacity) {
        ruleService.configure(spaceId, new ResourceRuleChangeRequest(
            ResourceCategory.STUDY_ROOM.name(), BookingMode.CAPACITY.name(), capacity, List.of(),
            "容量模式恢复测试"));
    }

    private void configureAssignedSeatRule() {
        ruleService.configure(spaceId, new ResourceRuleChangeRequest(
            ResourceCategory.STUDY_ROOM.name(), BookingMode.ASSIGNED_SEAT.name(), null,
            List.of(new ResourceSeatDefinition("A-01", "A区01座"), new ResourceSeatDefinition("A-02", "A区02座")),
            "选座模式恢复测试"));
    }

    private void trackReservation(Long reservationId) {
        if (reservationId != null && !reservationIds.contains(reservationId)) {
            reservationIds.add(reservationId);
        }
    }

    private int reservationCount() {
        return jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM reservation WHERE space_id = ?", Integer.class, spaceId);
    }

    private int occupancyCount() {
        return jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM reservation_occupancy WHERE space_id = ?", Integer.class, spaceId);
    }

    private int statusLogCount() {
        return jdbcTemplate.queryForObject("""
            SELECT COUNT(*) FROM reservation_status_log log
            JOIN reservation r ON r.id = log.reservation_id
            WHERE r.space_id = ?
            """, Integer.class, spaceId);
    }

    private int invocationCount(Object mock) {
        return mockingDetails(mock).getInvocations().size();
    }

    private <T> T runAs(long userId, Supplier<T> operation) {
        CurrentUser user = new CurrentUser(userId, "user-" + userId, Set.of("USER"), Set.of("reservation:my"));
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(user, null, List.of()));
        try {
            return operation.get();
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private <T> List<T> runConcurrently(Supplier<T> first, Supplier<T> second) throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<T> firstFuture = executor.submit(() -> afterStart(ready, start, first));
            Future<T> secondFuture = executor.submit(() -> afterStart(ready, start, second));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<T> results = new ArrayList<>(2);
            results.add(firstFuture.get(30, TimeUnit.SECONDS));
            results.add(secondFuture.get(30, TimeUnit.SECONDS));
            return results;
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    private <T> T afterStart(CountDownLatch ready, CountDownLatch start, Supplier<T> operation) {
        ready.countDown();
        try {
            if (!start.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("reservation recovery concurrency timed out");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("reservation recovery concurrency interrupted", exception);
        }
        return operation.get();
    }

    @SpringBootConfiguration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @MapperScan({
        "com.example.venue.reservation.mapper",
        "com.example.venue.resource.mapper",
        "com.example.venue.venue.mapper",
        "com.example.venue.inventory.mapper",
        "com.example.venue.system.mapper"
    })
    static class IntegrationApplication {
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper().findAndRegisterModules();
        }

        @Bean
        VenueAccessService venueAccessService() {
            return mock(VenueAccessService.class);
        }

        @Bean
        BusinessEventService businessEventService() {
            return mock(BusinessEventService.class);
        }

        @Bean
        FunnelEventService funnelEventService() {
            return mock(FunnelEventService.class);
        }

        @Bean
        NoticeService noticeService() {
            return mock(NoticeService.class);
        }

        @Bean
        OrderService orderService() {
            return mock(OrderService.class);
        }

        @Bean
        ReservationLifecycleService reservationLifecycleService() {
            return mock(ReservationLifecycleService.class);
        }

        @Bean
        CouponService couponService() {
            return mock(CouponService.class);
        }

        @Bean
        ResourceAllocationConflictEventRecorder allocationConflictEventRecorder() {
            return mock(ResourceAllocationConflictEventRecorder.class);
        }

        @Bean
        ReservationCommitmentChangePublisher reservationCommitmentChangePublisher() {
            return mock(ReservationCommitmentChangePublisher.class);
        }

        @Bean
        VenueInventoryService venueInventoryService() {
            VenueInventoryService inventoryService = mock(VenueInventoryService.class);
            lenient().when(inventoryService.checkBookable(anyLong(), any(), any()))
                .thenReturn(BookabilityDecision.ok());
            lenient().when(inventoryService.checkBookable(anyLong(), any(), any(), anyInt(), anyList()))
                .thenReturn(BookabilityDecision.ok());
            lenient().when(inventoryService.checkBookableExcludingReservation(anyLong(), any(), any(), anyLong()))
                .thenReturn(BookabilityDecision.ok());
            lenient().when(inventoryService.checkBookableExcludingReservation(
                anyLong(), any(), any(), anyLong(), anyInt(), anyList()))
                .thenReturn(BookabilityDecision.ok());
            return inventoryService;
        }

        /**
         * 报价协作者返回确定性的余额为零的定价上下文：本类验证的是请求身份与副作用，
         * 计价规则由仓内既有测试负责，这里只要保证不引入活动预算占位分支。
         */
        @Bean
        PromotionPricingService promotionPricingService() {
            PromotionPricingService pricingService = mock(PromotionPricingService.class);
            lenient().when(pricingService.previewForReservation(
                anyLong(), anyLong(), anyLong(), any(), any(), any(), any()))
                .thenAnswer(invocation -> pricingContext(
                    invocation.getArgument(3), invocation.getArgument(4), invocation.getArgument(5)));
            return pricingService;
        }

        @Bean
        StringRedisTemplate stringRedisTemplate() {
            StringRedisTemplate template = mock(StringRedisTemplate.class);
            @SuppressWarnings("unchecked")
            ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
            lenient().when(template.opsForValue()).thenReturn(valueOperations);
            lenient().when(valueOperations.setIfAbsent(any(), any(), any(Duration.class))).thenReturn(true);
            return template;
        }

        @Bean
        SysConfigService sysConfigService(SysConfigMapper configMapper) {
            return new SysConfigService(configMapper);
        }

        @Bean
        EntityExistenceService entityExistenceService(VenueMapper venueMapper, VenueSpaceMapper spaceMapper) {
            return new EntityExistenceService(null, venueMapper, spaceMapper);
        }

        @Bean
        VenueResourceRuleService venueResourceRuleService(
            VenueResourceRuleMapper ruleMapper,
            VenueResourceSeatMapper seatMapper,
            ReservationOccupancyMapper occupancyMapper,
            ReservationOccupancySeatMapper occupancySeatMapper,
            EntityExistenceService entityExistenceService,
            VenueSpaceMapper spaceMapper,
            VenueAccessService venueAccessService,
            VenueInventoryAuditMapper auditMapper,
            ObjectMapper objectMapper) {
            return new VenueResourceRuleService(ruleMapper, seatMapper, occupancyMapper, occupancySeatMapper,
                entityExistenceService, spaceMapper, venueAccessService, auditMapper, objectMapper);
        }

        @Bean
        ResourceAllocationService resourceAllocationService(
            VenueResourceRuleService ruleService,
            VenueResourceSeatMapper seatMapper,
            ReservationOccupancyMapper occupancyMapper,
            ReservationOccupancySeatMapper occupancySeatMapper,
            ReservationMapper reservationMapper,
            EntityExistenceService entityExistenceService,
            VenueSpaceMapper spaceMapper,
            ResourceAllocationConflictEventRecorder conflictEventRecorder,
            ObjectMapper objectMapper) {
            return new ResourceAllocationService(ruleService, seatMapper, occupancyMapper, occupancySeatMapper,
                reservationMapper, entityExistenceService, spaceMapper, conflictEventRecorder, objectMapper);
        }

        @Bean
        ReservationCreateIdempotencyService reservationCreateIdempotencyService(
            ReservationCreateCommandMapper commandMapper) {
            return new ReservationCreateIdempotencyService(commandMapper);
        }

        @Bean
        ReservationService reservationService(
            ReservationMapper reservationMapper,
            ReservationOccupancyMapper occupancyMapper,
            ReservationStatusLogMapper statusLogMapper,
            VenueMapper venueMapper,
            VenueSpaceMapper spaceMapper,
            EntityExistenceService entityExistenceService,
            VenueAccessService venueAccessService,
            SysConfigService sysConfigService,
            StringRedisTemplate redisTemplate,
            OrderService orderService,
            ReservationLifecycleService lifecycleService,
            NoticeService noticeService,
            BusinessEventService businessEventService,
            FunnelEventService funnelEventService,
            VenueInventoryService inventoryService,
            PromotionPricingService promotionPricingService,
            CouponService couponService,
            ReservationCreateIdempotencyService createIdempotencyService,
            ResourceAllocationService resourceAllocationService) {
            ReservationService service = new ReservationService(
                reservationMapper, occupancyMapper, statusLogMapper, venueMapper, spaceMapper,
                entityExistenceService, venueAccessService, sysConfigService, redisTemplate, orderService,
                lifecycleService, noticeService, businessEventService, funnelEventService, inventoryService,
                promotionPricingService, couponService,
                new PricingSnapshotFactory(new ObjectMapper().findAndRegisterModules()),
                createIdempotencyService, null);
            service.setResourceAllocationService(resourceAllocationService);
            return service;
        }

        private static PromotionPricingContext pricingContext(LocalDateTime startTime, LocalDateTime endTime,
                                                              BigDecimal unitPrice) {
            PromotionPricingContext context = new PromotionPricingContext();
            context.startTime = startTime;
            context.endTime = endTime;
            context.unitPrice = unitPrice;
            BigDecimal baseAmount = unitPrice.multiply(BigDecimal.valueOf(
                java.time.Duration.between(startTime, endTime).toMinutes()))
                .divide(BigDecimal.valueOf(60), 2, java.math.RoundingMode.HALF_UP);
            context.baseAmount = baseAmount;
            context.priceRuleAdjustmentAmount = BigDecimal.ZERO;
            context.amountBreakdown = new AmountBreakdown(baseAmount, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, baseAmount, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
            context.pricingSnapshotJson = "{\"pricingVersion\":\"B3_PROMOTION_PRICING_V1\"}";
            return context;
        }
    }
}
