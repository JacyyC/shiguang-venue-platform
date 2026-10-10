package com.example.venue.resource;

import org.springframework.test.annotation.DirtiesContext;

import com.example.venue.common.BusinessException;
import com.example.venue.common.EntityExistenceService;
import com.example.venue.inventory.mapper.VenueInventoryAuditMapper;
import com.example.venue.reservation.entity.Reservation;
import com.example.venue.reservation.mapper.ReservationMapper;
import com.example.venue.reservation.mapper.ReservationOccupancyMapper;
import com.example.venue.reservation.service.ReservationCommitmentChangePublisher;
import com.example.venue.resource.dto.ResourceRuleChangeRequest;
import com.example.venue.resource.dto.ResourceSeatDefinition;
import com.example.venue.resource.entity.VenueResourceRule;
import com.example.venue.resource.entity.VenueResourceSeat;
import com.example.venue.resource.enums.BookingMode;
import com.example.venue.resource.enums.ResourceCategory;
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
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;

/**
 * F-01 物理承载人数上界的 MySQL 集成回归（SHIGUANG-WAVE-01-REMEDIATION-20260916 S04）。
 *
 * <p>锁定三件事：
 * <ol>
 *   <li>{@code venue_space.capacity} 是场地物理人数上限，三种预订模式都不放宽；</li>
 *   <li>它与 {@code venue_resource_rule.capacity_limit}（可售单位）分属两层：本类固定
 *       物理容量 2、规则容量 10，2 人可通过而 3 人被拒，证明两者不互相推导；</li>
 *   <li>EXCLUSIVE 下人数大于 1 依然合法，{@code reservation_occupancy.quantity} 保持 1，
 *       拒绝时不留下预约或占用残留。</li>
 * </ol>
 */
// Close the cached Spring context while this class still owns live containers.
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Testcontainers
@SpringBootTest(
    classes = PhysicalParticipantCapacityMySqlIntegrationTest.IntegrationApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = "spring.task.scheduling.enabled=false"
)
class PhysicalParticipantCapacityMySqlIntegrationTest {
    private static final AtomicLong IDS = new AtomicLong(9_800_000L);
    private static final LocalDateTime SLOT_START = LocalDateTime.parse("2099-09-10T10:00:00");
    private static final LocalDateTime SLOT_END = LocalDateTime.parse("2099-09-10T12:00:00");
    /** 场地物理承载人数：故意取 2，小于下面 CAPACITY 规则的 capacityLimit=10。 */
    private static final int PHYSICAL_CAPACITY = 2;

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
        .withDatabaseName("physical_capacity")
        .withUsername("capacity")
        .withPassword("capacity")
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
            venueId, "Physical capacity venue");
        jdbcTemplate.update("""
            INSERT INTO venue_space(id, venue_id, name, type, capacity, price_per_hour, status, deleted)
            VALUES (?, ?, ?, 'BADMINTON', ?, 20.00, 'ACTIVE', 0)
            """, spaceId, venueId, "Physical capacity space", PHYSICAL_CAPACITY);
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

    @Test
    void exclusiveKeepsQuantityOneAndAcceptsMultiPersonUpToPhysicalCapacity() {
        Reservation twoPeople = insertReservation(SLOT_START, SLOT_END);

        inTransaction(() -> allocationService.allocate(twoPeople, 2, List.of()));

        // 多人整场：人数与占用量是两件事
        assertThat(participantCountOf(twoPeople.id)).isEqualTo(2);
        assertThat(activeQuantityOf(twoPeople.id)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("""
            SELECT CONCAT(booking_mode, ':', quantity) FROM reservation_occupancy
            WHERE reservation_id = ? AND active_flag = 1
            """, String.class, twoPeople.id)).isEqualTo("EXCLUSIVE:1");
        // 缺省人数仍为 1
        Reservation defaulted = insertReservation(SLOT_END, SLOT_END.plusHours(1));
        inTransaction(() -> allocationService.allocate(defaulted, null, List.of()));
        assertThat(participantCountOf(defaulted.id)).isEqualTo(1);
    }

    @Test
    void exclusiveRejectsAbovePhysicalCapacityAndLeavesNoResidue() {
        Reservation tooMany = insertReservation(SLOT_START, SLOT_END);

        assertThatThrownBy(() -> inTransaction(() -> allocationService.allocate(tooMany, PHYSICAL_CAPACITY + 1, List.of())))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("超过场地可容纳人数");

        // 拒绝必须无副作用：没有占用、没有人数快照写入
        assertThat(activeAllocationCount()).isZero();
        assertThat(jdbcTemplate.queryForObject(
            "SELECT participant_count FROM reservation WHERE id = ?", Integer.class, tooMany.id)).isEqualTo(1);
    }

    @Test
    void physicalCapacityIsIndependentFromSellableCapacityLimit() {
        // 规则可售单位 10，物理容量 2：2 人可通过、3 人被拒，证明两层不互相推导。
        ruleService.configure(spaceId, new ResourceRuleChangeRequest(
            ResourceCategory.STUDY_ROOM.name(), BookingMode.CAPACITY.name(), 10, List.of(), "两层容量口径测试"));

        Reservation atPhysicalBoundary = insertReservation(SLOT_START, SLOT_END);
        inTransaction(() -> allocationService.allocate(atPhysicalBoundary, PHYSICAL_CAPACITY, List.of()));
        assertThat(participantCountOf(atPhysicalBoundary.id)).isEqualTo(PHYSICAL_CAPACITY);
        assertThat(activeQuantityOf(atPhysicalBoundary.id)).isEqualTo(PHYSICAL_CAPACITY);

        Reservation abovePhysical = insertReservation(SLOT_END, SLOT_END.plusHours(1));
        assertThatThrownBy(() -> inTransaction(() -> allocationService.allocate(abovePhysical, PHYSICAL_CAPACITY + 1, List.of())))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("超过场地可容纳人数");
        // 被拒的 3 人请求不得留下第二行占用：此时恰好只有 1 笔成功占用（quantity=2）
        assertThat(activeAllocationCount()).isEqualTo(1);
        assertThat(activeQuantityOf(atPhysicalBoundary.id)).isEqualTo(PHYSICAL_CAPACITY);
    }

    @Test
    void assignedSeatCannotCarryMorePeopleThanTheSpaceHolds() {
        ruleService.configure(spaceId, new ResourceRuleChangeRequest(
            ResourceCategory.STUDY_ROOM.name(), BookingMode.ASSIGNED_SEAT.name(), null,
            List.of(new ResourceSeatDefinition("P-01", "P区01座"),
                    new ResourceSeatDefinition("P-02", "P区02座"),
                    new ResourceSeatDefinition("P-03", "P区03座")),
            "物理上限与座位数分工测试"));
        VenueResourceRule rule = ruleService.currentRule(spaceId);
        List<VenueResourceSeat> seats = ruleService.activeSeats(rule.id);
        assertThat(seats).hasSize(3);

        List<Long> threeSeats = seats.stream().map(seat -> seat.id).toList();
        Reservation threePeople = insertReservation(SLOT_START, SLOT_END);
        assertThatThrownBy(() -> inTransaction(() -> allocationService.allocate(threePeople, 3, threeSeats)))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("超过场地可容纳人数");
        assertThat(activeAllocationCount()).isZero();

        List<Long> twoSeats = seats.stream().limit(PHYSICAL_CAPACITY).map(seat -> seat.id).toList();
        Reservation twoPeople = insertReservation(SLOT_START, SLOT_END);
        inTransaction(() -> allocationService.allocate(twoPeople, PHYSICAL_CAPACITY, twoSeats));
        assertThat(participantCountOf(twoPeople.id)).isEqualTo(PHYSICAL_CAPACITY);
    }

    @Test
    void rescheduleUsesTheSamePhysicalCapacityRule() {
        Reservation original = insertReservation(SLOT_START, SLOT_END);
        inTransaction(() -> allocationService.allocate(original, PHYSICAL_CAPACITY, List.of()));

        Reservation persisted = reservationMapper.selectById(original.id);
        assertThatThrownBy(() -> inTransaction(() -> allocationService.replace(persisted,
            SLOT_END, SLOT_END.plusHours(1), PHYSICAL_CAPACITY + 1, null)))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("超过场地可容纳人数");

        // 失败改期不得释放原有占用
        assertThat(activeQuantityOf(original.id)).isEqualTo(1);
        assertThat(participantCountOf(original.id)).isEqualTo(PHYSICAL_CAPACITY);
    }

    @Test
    void availabilityReportsPhysicalCapacityRejectionWithoutThrowing() {
        var response = allocationService.availability(spaceId, SLOT_START, SLOT_END, PHYSICAL_CAPACITY + 1, List.of(), null);

        assertThat(response.bookable()).isFalse();
        assertThat(response.reasonCode()).isEqualTo("PHYSICAL_CAPACITY_EXCEEDED");
        assertThat(response.rule().physicalCapacity()).isEqualTo(PHYSICAL_CAPACITY);
        assertThat(response.rule().capacityLimit()).isEqualTo(1);
        assertThat(response.reasonText()).contains("超过场地可容纳人数");

        var withinCapacity = allocationService.availability(spaceId, SLOT_START, SLOT_END, PHYSICAL_CAPACITY, List.of(), null);
        assertThat(withinCapacity.bookable()).isTrue();
        assertThat(withinCapacity.reasonCode()).isNull();
    }

    private int participantCountOf(long reservationId) {
        return jdbcTemplate.queryForObject(
            "SELECT participant_count FROM reservation WHERE id = ?", Integer.class, reservationId);
    }

    private int activeQuantityOf(long reservationId) {
        return jdbcTemplate.queryForObject(
            "SELECT quantity FROM reservation_occupancy WHERE reservation_id = ? AND active_flag = 1",
            Integer.class, reservationId);
    }

    private int activeAllocationCount() {
        return jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM reservation_occupancy WHERE space_id = ? AND active_flag = 1",
            Integer.class, spaceId);
    }

    private String placeholders(int count) {
        return String.join(",", java.util.Collections.nCopies(count, "?"));
    }

    private void inTransaction(Runnable action) {
        new TransactionTemplate(transactionManager).execute(status -> {
            action.run();
            return null;
        });
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
