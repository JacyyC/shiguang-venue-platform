package com.example.venue.resource;

import org.springframework.test.annotation.DirtiesContext;

import com.example.venue.common.EntityExistenceService;
import com.example.venue.common.BusinessException;
import com.example.venue.inventory.mapper.VenueInventoryAuditMapper;
import com.example.venue.reservation.entity.Reservation;
import com.example.venue.reservation.mapper.ReservationMapper;
import com.example.venue.reservation.mapper.ReservationOccupancyMapper;
import com.example.venue.reservation.service.ReservationCommitmentChangePublisher;
import com.example.venue.resource.dto.ResourceRuleChangeRequest;
import com.example.venue.resource.dto.ResourceAvailabilityDecision;
import com.example.venue.resource.dto.ResourceAvailabilityResponse;
import com.example.venue.resource.dto.ResourceSeatDefinition;
import com.example.venue.resource.entity.VenueResourceRule;
import com.example.venue.resource.entity.VenueResourceSeat;
import com.example.venue.resource.enums.AllocationConflictCode;
import com.example.venue.resource.enums.BookingMode;
import com.example.venue.resource.enums.ResourceCategory;
import com.example.venue.resource.exception.ResourceAllocationConflictException;
import com.example.venue.resource.mapper.ReservationOccupancySeatMapper;
import com.example.venue.resource.mapper.VenueResourceRuleMapper;
import com.example.venue.resource.mapper.VenueResourceSeatMapper;
import com.example.venue.resource.service.ResourceAllocationConflictEventRecorder;
import com.example.venue.resource.service.ResourceAllocationService;
import com.example.venue.resource.service.VenueResourceRuleService;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

// Close the cached Spring context while this class still owns live containers.
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Testcontainers
@SpringBootTest(
    classes = ResourceAllocationMySqlIntegrationTest.IntegrationApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = "spring.task.scheduling.enabled=false"
)
class ResourceAllocationMySqlIntegrationTest {
    private static final AtomicLong IDS = new AtomicLong(9_600_000L);
    private static final LocalDateTime SLOT_START = LocalDateTime.parse("2099-08-10T10:00:00");
    private static final LocalDateTime SLOT_END = LocalDateTime.parse("2099-08-10T12:00:00");

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
        .withDatabaseName("resource_allocation")
        .withUsername("resource")
        .withPassword("resource")
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
    private ResourceAllocationService allocationService;
    @Autowired
    private VenueResourceRuleService ruleService;
    @Autowired
    private ReservationMapper reservationMapper;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private ReservationCommitmentChangePublisher commitmentChangePublisher;

    private long venueId;
    private long spaceId;
    private final List<Long> reservationIds = new ArrayList<>();

    @BeforeEach
    void createSpaceFixture() {
        reset(commitmentChangePublisher);
        venueId = IDS.incrementAndGet();
        spaceId = IDS.incrementAndGet();
        jdbcTemplate.update("INSERT INTO venue(id, name, status, deleted) VALUES (?, ?, 'ACTIVE', 0)",
            venueId, "Resource allocation venue");
        jdbcTemplate.update("""
            INSERT INTO venue_space(id, venue_id, name, type, capacity, price_per_hour, status, deleted)
            VALUES (?, ?, ?, 'BADMINTON', 20, 20.00, 'ACTIVE', 0)
            """, spaceId, venueId, "Resource allocation space");
    }

    @AfterEach
    void removeOnlyThisTestFixtures() {
        if (!reservationIds.isEmpty()) {
            jdbcTemplate.update("DELETE FROM reservation_occupancy_seat WHERE reservation_id IN ("
                + placeholders(reservationIds.size()) + ")", reservationIds.toArray());
            jdbcTemplate.update("DELETE FROM reservation_occupancy WHERE reservation_id IN ("
                + placeholders(reservationIds.size()) + ")", reservationIds.toArray());
            jdbcTemplate.update("DELETE FROM reservation WHERE id IN ("
                + placeholders(reservationIds.size()) + ")", reservationIds.toArray());
        }
        jdbcTemplate.update("DELETE FROM venue_inventory_audit WHERE entity_type = 'VENUE_RESOURCE_RULE' "
            + "AND entity_id IN (SELECT id FROM venue_resource_rule WHERE space_id = ?)", spaceId);
        jdbcTemplate.update("DELETE FROM venue_resource_seat WHERE space_id = ?", spaceId);
        jdbcTemplate.update("DELETE FROM venue_resource_rule WHERE space_id = ?", spaceId);
        jdbcTemplate.update("DELETE FROM venue_space WHERE id = ?", spaceId);
        jdbcTemplate.update("DELETE FROM venue WHERE id = ?", venueId);
    }

    @Autowired
    private ReservationOccupancyMapper occupancyMapper;

    @Test
    void firstAllocationsInDifferentNewSpacesCreateTheirDefaultRulesConcurrently() throws Exception {
        List<Long> spaces = new ArrayList<>();
        List<Reservation> reservations = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            long id = IDS.incrementAndGet(); spaces.add(id);
            jdbcTemplate.update("INSERT INTO venue_space(id,venue_id,name,type,capacity,price_per_hour,status,deleted) VALUES (?,?,'New','MEETING',20,20,'ACTIVE',0)", id, venueId);
            Reservation reservation = insertReservation(SLOT_START, SLOT_END);
            jdbcTemplate.update("UPDATE reservation SET space_id=? WHERE id=?", id, reservation.id);
            reservation.spaceId = id; reservations.add(reservation);
        }
        ExecutorService workers = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<AllocationOutcome>> tasks = new ArrayList<>();
        try {
            for (Reservation reservation : reservations) {
                tasks.add(workers.submit(() -> allocateAfterStart(start,
                    () -> allocationService.allocate(reservation, 1, List.of()))));
            }
            start.countDown();
            for (Future<AllocationOutcome> task : tasks) assertThat(task.get(10, TimeUnit.SECONDS).success()).isTrue();
            for (long id : spaces) {
                assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM venue_resource_rule WHERE space_id=? AND active_flag=1", Integer.class, id)).isOne();
            }
        } finally {
            start.countDown(); workers.shutdownNow();
            assertThat(workers.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
            for (long id : spaces) {
                jdbcTemplate.update("DELETE FROM reservation_occupancy WHERE space_id=?", id);
                jdbcTemplate.update("DELETE FROM venue_resource_rule WHERE space_id=?", id);
                jdbcTemplate.update("DELETE FROM venue_space WHERE id=?", id);
            }
        }
    }

    @Test
    void differentSpacesShareVenueReadLockWhileVenueMutationStillWaits() throws Exception {
        long otherSpace = IDS.incrementAndGet();
        jdbcTemplate.update("INSERT INTO venue_space(id,venue_id,name,type,capacity,price_per_hour,status,deleted) VALUES (?,?,'Independent','MEETING',20,20,'ACTIVE',0)", otherSpace, venueId);
        for (long id : List.of(spaceId, otherSpace)) {
            ruleService.configure(id, new ResourceRuleChangeRequest("SPORTS_FIELD", "EXCLUSIVE", 1, List.of(), "并发边界"));
        }
        // Bound each resource's occupancy index range with existing rows, as in
        // the capacity fixture; this test isolates venue locking from empty gaps.
        for (long id : List.of(spaceId, otherSpace)) {
            Reservation seed = insertReservation(SLOT_START.minusDays(1), SLOT_END.minusDays(1));
            jdbcTemplate.update("UPDATE reservation SET space_id=? WHERE id=?", id, seed.id);
            seed.spaceId = id;
            inTransaction(() -> allocationService.allocate(seed, 1, List.of()));
        }
        Reservation first = insertReservation(SLOT_START, SLOT_END);
        Reservation second = insertReservation(SLOT_START, SLOT_END);
        jdbcTemplate.update("UPDATE reservation SET space_id=? WHERE id=?", otherSpace, second.id);
        second.spaceId = otherSpace;
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<?> holder = workers.submit(() -> inTransaction(() -> {
                allocationService.allocate(first, 1, List.of());
                held.countDown();
                try { if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("barrier timeout"); }
                catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new IllegalStateException(ex); }
            }));
            assertThat(held.await(5, TimeUnit.SECONDS)).isTrue();
            Future<?> independent = workers.submit(() -> inTransaction(() -> allocationService.allocate(second, 1, List.of())));
            try {
                independent.get(3, TimeUnit.SECONDS);
            } catch (java.util.concurrent.TimeoutException timeout) {
                try (var connection = java.sql.DriverManager.getConnection(MYSQL.getJdbcUrl(), "root", MYSQL.getPassword());
                     var statement = connection.createStatement();
                     var rows = statement.executeQuery("SELECT r.OBJECT_NAME,r.INDEX_NAME,r.LOCK_MODE,r.LOCK_DATA,b.LOCK_MODE,b.LOCK_DATA FROM performance_schema.data_lock_waits w JOIN performance_schema.data_locks r ON r.ENGINE_LOCK_ID=w.REQUESTING_ENGINE_LOCK_ID JOIN performance_schema.data_locks b ON b.ENGINE_LOCK_ID=w.BLOCKING_ENGINE_LOCK_ID")) {
                    while (rows.next()) System.out.printf("RESOURCE_LOCK_WAIT table=%s index=%s request=%s key=%s holder=%s heldKey=%s%n",
                        rows.getString(1),rows.getString(2),rows.getString(3),rows.getString(4),rows.getString(5),rows.getString(6));
                }
                throw timeout;
            }
            Future<Integer> mutation = workers.submit(() -> jdbcTemplate.update("UPDATE venue SET status='INACTIVE' WHERE id=?", venueId));
            assertThatThrownBy(() -> mutation.get(200, TimeUnit.MILLISECONDS))
                .isInstanceOf(java.util.concurrent.TimeoutException.class);
            release.countDown(); holder.get(5, TimeUnit.SECONDS);
            assertThat(mutation.get(5, TimeUnit.SECONDS)).isOne();
            Reservation rejected = insertReservation(SLOT_END, SLOT_END.plusHours(1));
            assertThatThrownBy(() -> inTransaction(() -> allocationService.allocate(rejected, 1, List.of())))
                .isInstanceOf(ResourceAllocationConflictException.class);
        } finally {
            release.countDown(); workers.shutdownNow();
            assertThat(workers.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
            jdbcTemplate.update("DELETE FROM reservation_occupancy WHERE space_id=?", otherSpace);
            jdbcTemplate.update("DELETE FROM venue_inventory_audit WHERE entity_type='VENUE_RESOURCE_RULE' AND entity_id IN (SELECT id FROM venue_resource_rule WHERE space_id=?)", otherSpace);
            jdbcTemplate.update("DELETE FROM venue_resource_rule WHERE space_id=?", otherSpace);
            jdbcTemplate.update("DELETE FROM venue_space WHERE id=?", otherSpace);
        }
    }

    @Test
    void overlapLockDoesNotWaitForUnrelatedSpaceRows() throws Exception {
        // A small table makes MySQL prefer PRIMARY for ORDER BY id unless the locking
        // query bounds its index scan. Holding another space must not block this one.
        Reservation first = insertReservation(SLOT_START, SLOT_END);
        inTransaction(() -> allocationService.allocate(first, 1, List.of()));
        long independentSpace = IDS.incrementAndGet();
        jdbcTemplate.update("INSERT INTO venue_space(id,venue_id,name,type,capacity,price_per_hour,status,deleted) VALUES (?,?,'Independent','MEETING',20,20,'ACTIVE',0)", independentSpace, venueId);
        Reservation other = insertReservation(SLOT_START, SLOT_END);
        jdbcTemplate.update("UPDATE reservation SET space_id=? WHERE id=?", independentSpace, other.id);
        other.spaceId = independentSpace;
        inTransaction(() -> allocationService.allocate(other, 1, List.of()));
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> holder = executor.submit(() -> inTransaction(() -> {
                occupancyMapper.selectActiveByReservationIdForUpdate(first.id);
                held.countDown();
                try { if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("lock timeout"); }
                catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new IllegalStateException(ex); }
            }));
            assertThat(held.await(5, TimeUnit.SECONDS)).isTrue();
            Future<Integer> independent = executor.submit(() -> inTransaction(() ->
                occupancyMapper.findActiveOverlapsForUpdate(independentSpace, SLOT_START.toLocalDate(),
                    SLOT_START.toLocalTime(), SLOT_END.toLocalTime(), null).size()));
            assertThat(independent.get(3, TimeUnit.SECONDS)).isEqualTo(1);
            release.countDown(); holder.get(5, TimeUnit.SECONDS);
        } finally {
            release.countDown(); executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
            jdbcTemplate.update("DELETE FROM reservation_occupancy WHERE space_id=?", independentSpace);
            jdbcTemplate.update("DELETE FROM venue_inventory_audit WHERE entity_type='VENUE_RESOURCE_RULE' AND entity_id IN (SELECT id FROM venue_resource_rule WHERE space_id=?)", independentSpace);
            jdbcTemplate.update("DELETE FROM venue_resource_rule WHERE space_id=?", independentSpace);
            jdbcTemplate.update("DELETE FROM venue_space WHERE id=?", independentSpace);
        }
    }

    @Test
    void legacySpaceDefaultsToExclusiveV1AndPersistsHistoricalSnapshots() {
        Reservation reservation = insertReservation(SLOT_START, SLOT_END);

        inTransaction(() -> allocationService.allocate(reservation, null, List.of()));

        assertThat(jdbcTemplate.queryForObject("""
            SELECT CONCAT(resource_category_snapshot, ':', booking_mode_snapshot, ':',
                          rule_version_snapshot, ':', participant_count)
            FROM reservation WHERE id = ?
            """, String.class, reservation.id)).isEqualTo("SPORTS_FIELD:EXCLUSIVE:1:1");
        assertThat(jdbcTemplate.queryForObject("""
            SELECT CONCAT(resource_category_snapshot, ':', booking_mode, ':', rule_version_snapshot, ':', quantity)
            FROM reservation_occupancy WHERE reservation_id = ? AND active_flag = 1
            """, String.class, reservation.id)).isEqualTo("SPORTS_FIELD:EXCLUSIVE:1:1");
    }

    @Test
    void exclusiveModeRejectsPartialOverlapButAllowsAdjacentHalfOpenRange() {
        Reservation first = insertReservation(SLOT_START, SLOT_END);
        inTransaction(() -> allocationService.allocate(first, 1, List.of()));

        Reservation overlapping = insertReservation(SLOT_START.plusHours(1), SLOT_END.plusHours(1));
        assertThatThrownBy(() -> inTransaction(() -> allocationService.allocate(overlapping, 1, List.of())))
            .isInstanceOf(ResourceAllocationConflictException.class)
            .extracting(error -> ((ResourceAllocationConflictException) error).getConflictCode())
            .isEqualTo(AllocationConflictCode.EXCLUSIVE_OVERLAP);

        Reservation adjacent = insertReservation(SLOT_END, SLOT_END.plusHours(1));
        inTransaction(() -> allocationService.allocate(adjacent, 1, List.of()));
        assertThat(activeAllocationCount()).isEqualTo(2);
    }

    @Test
    void mysqlSpaceLockPreventsLastCapacityOversellWithoutRedisGuard() throws Exception {
        ruleService.configure(spaceId, new ResourceRuleChangeRequest(
            ResourceCategory.STUDY_ROOM.name(), BookingMode.CAPACITY.name(), 3, List.of(), "容量模式测试"));
        Reservation existing = insertReservation(SLOT_START, SLOT_END);
        inTransaction(() -> allocationService.allocate(existing, 2, List.of()));
        Reservation contenderOne = insertReservation(SLOT_START.plusMinutes(30), SLOT_END.minusMinutes(30));
        Reservation contenderTwo = insertReservation(SLOT_START.plusMinutes(30), SLOT_END.minusMinutes(30));

        List<AllocationOutcome> outcomes = allocateConcurrently(
            () -> allocationService.allocate(contenderOne, 1, List.of()),
            () -> allocationService.allocate(contenderTwo, 1, List.of()));

        assertThat(outcomes).filteredOn(AllocationOutcome::success).hasSize(1);
        assertThat(outcomes).filteredOn(outcome -> outcome.conflictCode == AllocationConflictCode.CAPACITY_EXCEEDED)
            .hasSize(1);
        assertThat(jdbcTemplate.queryForObject(
            "SELECT SUM(quantity) FROM reservation_occupancy WHERE space_id = ? AND active_flag = 1",
            Integer.class, spaceId)).isEqualTo(3);
    }

    @Test
    void mysqlSpaceLockPreventsSameSeatAllocationWithoutRedisGuard() throws Exception {
        ruleService.configure(spaceId, new ResourceRuleChangeRequest(
            ResourceCategory.STUDY_ROOM.name(), BookingMode.ASSIGNED_SEAT.name(), null,
            List.of(new ResourceSeatDefinition("A-01", "A区01座"), new ResourceSeatDefinition("A-02", "A区02座")),
            "选座模式测试"));
        VenueResourceRule rule = ruleService.currentRule(spaceId);
        List<VenueResourceSeat> seats = ruleService.activeSeats(rule.id);
        Long contestedSeatId = seats.stream().filter(seat -> "A-01".equals(seat.seatCode)).findFirst().orElseThrow().id;
        Reservation contenderOne = insertReservation(SLOT_START, SLOT_END);
        Reservation contenderTwo = insertReservation(SLOT_START.plusMinutes(30), SLOT_END.minusMinutes(30));

        List<AllocationOutcome> outcomes = allocateConcurrently(
            () -> allocationService.allocate(contenderOne, 1, List.of(contestedSeatId)),
            () -> allocationService.allocate(contenderTwo, 1, List.of(contestedSeatId)));

        assertThat(outcomes).filteredOn(AllocationOutcome::success).hasSize(1);
        assertThat(outcomes).filteredOn(outcome -> outcome.conflictCode == AllocationConflictCode.SEAT_ALREADY_ALLOCATED)
            .hasSize(1);
        assertThat(jdbcTemplate.queryForObject("""
            SELECT COUNT(*)
            FROM reservation_occupancy_seat seat_allocation
            JOIN reservation_occupancy occupancy ON occupancy.id = seat_allocation.occupancy_id
            WHERE seat_allocation.seat_id = ? AND occupancy.active_flag = 1
            """, Integer.class, contestedSeatId)).isEqualTo(1);
    }

    @Test
    void futureAllocationRejectsDangerousCapacityShrinkAndModeSwitch() {
        ruleService.configure(spaceId, new ResourceRuleChangeRequest(
            ResourceCategory.STUDY_ROOM.name(), BookingMode.CAPACITY.name(), 3, List.of(), "容量模式测试"));
        Reservation reservation = insertReservation(SLOT_START, SLOT_END);
        inTransaction(() -> allocationService.allocate(reservation, 2, List.of()));

        assertThatThrownBy(() -> ruleService.configure(spaceId, new ResourceRuleChangeRequest(
            ResourceCategory.STUDY_ROOM.name(), BookingMode.CAPACITY.name(), 1, List.of(), "尝试缩减容量")))
            .isInstanceOf(BusinessException.class)
            .hasMessage("存在未来有效预约，不能切换模式、缩减容量或变更座位拓扑");
        assertThatThrownBy(() -> ruleService.configure(spaceId, new ResourceRuleChangeRequest(
            ResourceCategory.ACTIVITY_ROOM.name(), BookingMode.EXCLUSIVE.name(), 1, List.of(), "尝试切换模式")))
            .isInstanceOf(BusinessException.class)
            .hasMessage("存在未来有效预约，不能切换模式、缩减容量或变更座位拓扑");

        VenueResourceRule current = ruleService.currentRule(spaceId);
        assertThat(current.bookingMode).isEqualTo(BookingMode.CAPACITY.name());
        assertThat(current.capacityLimit).isEqualTo(3);
    }

    @Test
    void assignedSeatRescheduleRollsBackOnConflictAndPreservesSeatOnSuccess() {
        ruleService.configure(spaceId, new ResourceRuleChangeRequest(
            ResourceCategory.STUDY_ROOM.name(), BookingMode.ASSIGNED_SEAT.name(), null,
            List.of(new ResourceSeatDefinition("A-01", "A区01座"), new ResourceSeatDefinition("A-02", "A区02座")),
            "选座改期测试"));
        VenueResourceRule rule = ruleService.currentRule(spaceId);
        Long seatId = ruleService.activeSeats(rule.id).stream()
            .filter(seat -> "A-01".equals(seat.seatCode))
            .findFirst()
            .orElseThrow()
            .id;
        Reservation original = insertReservation(SLOT_START, SLOT_END);
        Reservation blocker = insertReservation(SLOT_END.plusHours(1), SLOT_END.plusHours(2));
        inTransaction(() -> allocationService.allocate(original, 1, List.of(seatId)));
        inTransaction(() -> allocationService.allocate(blocker, 1, List.of(seatId)));

        assertThatThrownBy(() -> inTransaction(() -> allocationService.replace(original,
            SLOT_END.plusMinutes(90), SLOT_END.plusHours(2).plusMinutes(30), null, null)))
            .isInstanceOf(ResourceAllocationConflictException.class)
            .extracting(error -> ((ResourceAllocationConflictException) error).getConflictCode())
            .isEqualTo(AllocationConflictCode.SEAT_ALREADY_ALLOCATED);
        assertThat(jdbcTemplate.queryForObject("""
            SELECT COUNT(*) FROM reservation_occupancy
            WHERE reservation_id = ? AND active_flag = 1
              AND slot_date = ? AND start_time = ? AND end_time = ?
            """, Integer.class, original.id, SLOT_START.toLocalDate(), SLOT_START.toLocalTime(),
            SLOT_END.toLocalTime())).isEqualTo(1);

        Reservation persistedOriginal = reservationMapper.selectById(original.id);
        LocalDateTime replacementStart = SLOT_END.plusHours(3);
        LocalDateTime replacementEnd = replacementStart.plusHours(1);
        inTransaction(() -> allocationService.replace(persistedOriginal, replacementStart, replacementEnd, null, null));

        assertThat(jdbcTemplate.queryForObject("""
            SELECT COUNT(*)
            FROM reservation_occupancy_seat seat_allocation
            JOIN reservation_occupancy occupancy ON occupancy.id = seat_allocation.occupancy_id
            WHERE occupancy.reservation_id = ? AND occupancy.active_flag = 1
              AND seat_allocation.seat_id = ?
            """, Integer.class, original.id, seatId)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("""
            SELECT COUNT(*) FROM reservation_occupancy
            WHERE reservation_id = ? AND active_flag IS NULL AND release_reason = 'RESCHEDULE_REPLACED'
            """, Integer.class, original.id)).isEqualTo(1);
        assertThat(reservationMapper.selectById(original.id).startTime).isEqualTo(replacementStart);
        verify(commitmentChangePublisher).publish(
            original.id, "RESCHEDULED", "RESCHEDULE_REPLACED");
    }

    @Test
    void releasePublishesTheStableReasonExactlyOnceWhenActiveOccupancyChanges() {
        Reservation reservation = insertReservation(SLOT_START, SLOT_END);
        inTransaction(() -> allocationService.allocate(reservation, 1, List.of()));

        assertThat(inTransaction(() -> allocationService.release(reservation.id, "PAYMENT_TIMEOUT")))
            .isEqualTo(1);
        assertThat(inTransaction(() -> allocationService.release(reservation.id, "PAYMENT_TIMEOUT")))
            .isZero();

        assertThat(jdbcTemplate.queryForObject("""
            SELECT release_reason FROM reservation_occupancy WHERE reservation_id = ?
            """, String.class, reservation.id)).isEqualTo("PAYMENT_TIMEOUT");
        verify(commitmentChangePublisher, times(1)).publish(
            reservation.id, "RELEASED", "PAYMENT_TIMEOUT");
    }

    @Test
    void assignedSeatAvailabilityCountsAllAllocatedSeatsWhenRequestedSeatIsFree() {
        ruleService.configure(spaceId, new ResourceRuleChangeRequest(
            ResourceCategory.STUDY_ROOM.name(), BookingMode.ASSIGNED_SEAT.name(), null,
            List.of(new ResourceSeatDefinition("A-01", "A区01座"), new ResourceSeatDefinition("A-02", "A区02座"),
                new ResourceSeatDefinition("A-03", "A区03座")),
            "选座余量测试"));
        VenueResourceRule rule = ruleService.currentRule(spaceId);
        List<VenueResourceSeat> seats = ruleService.activeSeats(rule.id);
        Long occupiedSeatId = seats.stream().filter(seat -> "A-01".equals(seat.seatCode)).findFirst().orElseThrow().id;
        Long requestedSeatId = seats.stream().filter(seat -> "A-02".equals(seat.seatCode)).findFirst().orElseThrow().id;
        Reservation existing = insertReservation(SLOT_START, SLOT_END);
        inTransaction(() -> allocationService.allocate(existing, 1, List.of(occupiedSeatId)));

        ResourceAvailabilityDecision decision = allocationService.checkRequestedAvailability(
            spaceId, SLOT_START, SLOT_END, 1, List.of(requestedSeatId), null);

        assertThat(decision.bookable()).isTrue();
        assertThat(decision.remainingCapacity()).isEqualTo(1);
    }

    @Test
    void availabilityResponseReturnsStableSeatStatusAndSupportsReservationExclusion() {
        ruleService.configure(spaceId, new ResourceRuleChangeRequest(
            ResourceCategory.STUDY_ROOM.name(), BookingMode.ASSIGNED_SEAT.name(), null,
            List.of(new ResourceSeatDefinition("A-01", "A区01座"), new ResourceSeatDefinition("A-02", "A区02座")),
            "选座可售响应测试"));
        VenueResourceRule rule = ruleService.currentRule(spaceId);
        List<VenueResourceSeat> seats = ruleService.activeSeats(rule.id);
        Long allocatedSeatId = seats.get(0).id;
        Reservation existing = insertReservation(SLOT_START, SLOT_END);
        inTransaction(() -> allocationService.allocate(existing, 1, List.of(allocatedSeatId)));

        ResourceAvailabilityResponse response = allocationService.availability(
            spaceId, SLOT_START, SLOT_END, 1, List.of(), null);

        assertThat(response.rule().bookingMode()).isEqualTo(BookingMode.ASSIGNED_SEAT.name());
        assertThat(response.remainingCapacity()).isEqualTo(1);
        assertThat(response.rule().seats())
            .filteredOn(seat -> seat.id().equals(allocatedSeatId))
            .singleElement()
            .extracting("available")
            .isEqualTo(false);

        ResourceAvailabilityResponse selectedConflict = allocationService.availability(
            spaceId, SLOT_START, SLOT_END, 1, List.of(allocatedSeatId), null);
        assertThat(selectedConflict.bookable()).isFalse();
        assertThat(selectedConflict.reasonCode()).isEqualTo(AllocationConflictCode.SEAT_ALREADY_ALLOCATED.name());

        ResourceAvailabilityResponse excludingOwnReservation = allocationService.availability(
            spaceId, SLOT_START, SLOT_END, 1, List.of(allocatedSeatId), existing.id);
        assertThat(excludingOwnReservation.bookable()).isTrue();
        assertThat(excludingOwnReservation.remainingCapacity()).isEqualTo(2);
    }

    private List<AllocationOutcome> allocateConcurrently(Runnable first, Runnable second) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<AllocationOutcome> firstResult = executor.submit(() -> allocateAfterStart(start, first));
            Future<AllocationOutcome> secondResult = executor.submit(() -> allocateAfterStart(start, second));
            start.countDown();
            return List.of(firstResult.get(30, TimeUnit.SECONDS), secondResult.get(30, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    private AllocationOutcome allocateAfterStart(CountDownLatch start, Runnable action) {
        try {
            assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
            inTransaction(action);
            return AllocationOutcome.succeeded();
        } catch (ResourceAllocationConflictException exception) {
            return AllocationOutcome.rejected(exception.getConflictCode());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("allocation race interrupted", exception);
        }
    }

    private void inTransaction(Runnable action) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> action.run());
    }

    private <T> T inTransaction(Supplier<T> action) {
        return new TransactionTemplate(transactionManager).execute(status -> action.get());
    }

    private Reservation insertReservation(LocalDateTime startTime, LocalDateTime endTime) {
        long reservationId = IDS.incrementAndGet();
        reservationIds.add(reservationId);
        jdbcTemplate.update("""
            INSERT INTO reservation(id, user_id, venue_id, space_id, start_time, end_time, status, amount, deleted)
            VALUES (?, 1, ?, ?, ?, ?, 'PENDING_REVIEW', 20.00, 0)
            """, reservationId, venueId, spaceId, startTime, endTime);
        return reservationMapper.selectById(reservationId);
    }

    private int activeAllocationCount() {
        return jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM reservation_occupancy WHERE space_id = ? AND active_flag = 1",
            Integer.class, spaceId);
    }

    private String placeholders(int count) {
        return String.join(",", java.util.Collections.nCopies(count, "?"));
    }

    private record AllocationOutcome(boolean success, AllocationConflictCode conflictCode) {
        static AllocationOutcome succeeded() {
            return new AllocationOutcome(true, null);
        }

        static AllocationOutcome rejected(AllocationConflictCode conflictCode) {
            return new AllocationOutcome(false, conflictCode);
        }
    }

    @SpringBootConfiguration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @MapperScan(basePackages = {
        "com.example.venue.resource.mapper",
        "com.example.venue.reservation.mapper",
        "com.example.venue.venue.mapper",
        "com.example.venue.inventory.mapper"
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
        ResourceAllocationConflictEventRecorder allocationConflictEventRecorder() {
            return mock(ResourceAllocationConflictEventRecorder.class);
        }

        @Bean
        ReservationCommitmentChangePublisher reservationCommitmentChangePublisher() {
            return mock(ReservationCommitmentChangePublisher.class);
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
    }
}
