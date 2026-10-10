package com.example.venue.community.activity;

import org.springframework.test.annotation.DirtiesContext;

import com.example.venue.common.BusinessException;
import com.example.venue.common.CurrentUser;
import com.example.venue.common.ResultCode;
import com.example.venue.community.activity.dto.ActivityRequests;
import com.example.venue.community.activity.dto.ActivityResponses;
import com.example.venue.community.activity.entity.CommunityActivity;
import com.example.venue.community.activity.entity.CommunityActivityApplication;
import com.example.venue.community.activity.entity.CommunityActivityNotificationTask;
import com.example.venue.community.activity.entity.CommunityCompanionRequest;
import com.example.venue.community.activity.mapper.CommunityActivityApplicationMapper;
import com.example.venue.community.activity.mapper.CommunityActivityCommandMapper;
import com.example.venue.community.activity.mapper.CommunityActivityMapper;
import com.example.venue.community.activity.mapper.CommunityActivityNotificationTaskMapper;
import com.example.venue.community.activity.mapper.CommunityActivityParticipationMapper;
import com.example.venue.community.activity.mapper.CommunityActivityStateEventMapper;
import com.example.venue.community.activity.mapper.CommunityCompanionRequestMapper;
import com.example.venue.community.activity.mapper.CommunityMatchDecisionMapper;
import com.example.venue.community.activity.service.ActivityCommandService;
import com.example.venue.community.activity.service.ActivityLocationPolicy;
import com.example.venue.community.activity.service.ActivityNotificationService;
import com.example.venue.community.activity.service.ActivityNotificationTaskStateService;
import com.example.venue.community.activity.service.ActivityParticipationService;
import com.example.venue.community.activity.service.ActivityReservationChangeListener;
import com.example.venue.community.activity.service.ActivityReservationPropagationBatchService;
import com.example.venue.community.activity.service.ActivityReservationPropagationService;
import com.example.venue.community.activity.service.ActivityReservationReconciliationService;
import com.example.venue.community.activity.service.ActivityStateEventService;
import com.example.venue.community.activity.service.CommunityActivityService;
import com.example.venue.community.activity.service.CompanionMatchingService;
import com.example.venue.community.dto.CursorPage;
import com.example.venue.community.mapper.CommunityCommentMapper;
import com.example.venue.community.mapper.CommunityGroupMapper;
import com.example.venue.community.mapper.CommunityGroupMemberMapper;
import com.example.venue.community.mapper.CommunityPostMapper;
import com.example.venue.community.mapper.CommunityUserBlockMapper;
import com.example.venue.community.service.CommunityAccessService;
import com.example.venue.community.service.CommunityRateLimitService;
import com.example.venue.community.service.CommunityTextPolicy;
import com.example.venue.reservation.mapper.ReservationMapper;
import com.example.venue.reservation.mapper.ReservationOccupancyMapper;
import com.example.venue.reservation.service.ReservationCommitmentChangePublisher;
import com.example.venue.reservation.service.ReservationCommitmentService;
import com.example.venue.resource.mapper.ReservationOccupancySeatMapper;
import com.example.venue.venue.mapper.VenueAdminRelationMapper;
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
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;

// Close the cached Spring context while this class still owns live containers.
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Testcontainers
@SpringBootTest(
    classes = CommunityActivityMySqlIntegrationTest.IntegrationApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = "spring.task.scheduling.enabled=false"
)
class CommunityActivityMySqlIntegrationTest {
    private static final AtomicLong IDS = new AtomicLong(12_000_000L);
    /**
     * 统一未来时间夹具。锚定容器声明的 {@code Asia/Shanghai}（与 {@code spring.datasource.url}
     * 强制的连接时区一致），取当前日期之后第 7 天的 10:00–12:00：
     * 整点窗口使 ±1 小时的预约占用区间（09:00–13:00）不跨午夜；固定 7 天偏差让夹具远离
     * 过期边界，又不会像推到远年那样失去意义。服务端过期校验保持权威，过期反例仍由
     * {@link #insertExpiredActivity(long, long, long)} 按相对当前时间生成。
     */
    private static final ZoneId FIXTURE_ZONE = ZoneId.of("Asia/Shanghai");
    private static final LocalDateTime FUTURE_START = LocalDate.now(FIXTURE_ZONE).plusDays(7).atTime(10, 0);
    private static final LocalDateTime FUTURE_END = FUTURE_START.plusHours(2);

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
        .withDatabaseName("community_activity")
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
    private CommunityActivityService activityService;
    @Autowired
    private ActivityParticipationService participationService;
    @Autowired
    private CompanionMatchingService matchingService;
    @Autowired
    private ActivityNotificationService notificationService;
    @Autowired
    private ActivityNotificationTaskStateService notificationStateService;
    @Autowired
    private ActivityReservationReconciliationService reconciliationService;
    @Autowired
    private ReservationCommitmentChangePublisher commitmentChangePublisher;
    @Autowired
    private CommunityRateLimitService rateLimitService;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void cleanFixtures() {
        SecurityContextHolder.clearContext();
        reset(rateLimitService);
        jdbcTemplate.update("DELETE FROM community_activity_notification_task");
        jdbcTemplate.update("DELETE FROM community_activity_state_event");
        jdbcTemplate.update("DELETE FROM community_match_decision");
        jdbcTemplate.update("DELETE FROM community_activity_participation");
        jdbcTemplate.update("DELETE FROM community_activity_application");
        jdbcTemplate.update("DELETE FROM community_activity_command");
        jdbcTemplate.update("DELETE FROM community_companion_request");
        jdbcTemplate.update("DELETE FROM community_activity");
        jdbcTemplate.update("DELETE FROM community_report");
        jdbcTemplate.update("DELETE FROM community_comment");
        jdbcTemplate.update("DELETE FROM community_post");
        jdbcTemplate.update("DELETE FROM community_group_member");
        jdbcTemplate.update("DELETE FROM community_user_block");
        jdbcTemplate.update("DELETE FROM community_group");
        jdbcTemplate.update("DELETE FROM reservation_occupancy_seat");
        jdbcTemplate.update("DELETE FROM reservation_occupancy");
        jdbcTemplate.update("DELETE FROM reservation");
        jdbcTemplate.update("DELETE FROM venue_resource_seat");
        jdbcTemplate.update("DELETE FROM venue_resource_rule");
        jdbcTemplate.update("DELETE FROM venue_space");
        jdbcTemplate.update("DELETE FROM venue");
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void concurrentAcceptsSerializeTheLastSlotAndCommandsRejectDifferentPayloads() throws Exception {
        long ownerId = nextId();
        long firstApplicantId = nextId();
        long secondApplicantId = nextId();
        long groupId = insertGroup(ownerId, "last-slot");
        insertMember(groupId, ownerId, "OWNER");
        insertMember(groupId, firstApplicantId, "MEMBER");
        insertMember(groupId, secondApplicantId, "MEMBER");

        CommunityActivity activity = createPublishedActivity(ownerId, groupId, null, 2, "last-slot");
        CommunityActivityApplication first = runAs(firstApplicantId,
            () -> participationService.apply("ACTIVITY", activity.id, "apply-first"));
        CommunityActivityApplication second = runAs(secondApplicantId,
            () -> participationService.apply("ACTIVITY", activity.id, "apply-second"));

        List<CommandOutcome> outcomes = executeConcurrently(
            () -> acceptOutcome(ownerId, first.id, "accept-first"),
            () -> acceptOutcome(ownerId, second.id, "accept-second"));

        assertThat(outcomes).filteredOn(CommandOutcome::succeeded).hasSize(1);
        assertThat(outcomes).filteredOn(outcome -> !outcome.succeeded())
            .extracting(CommandOutcome::code).containsExactly(ResultCode.CONFLICT);
        assertThat(jdbcTemplate.queryForObject(
            "SELECT participant_count FROM community_activity WHERE id = ?", Integer.class, activity.id))
            .isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject("""
            SELECT COUNT(*) FROM community_activity_participation
            WHERE target_type = 'ACTIVITY' AND target_id = ? AND status = 'ACTIVE'
            """, Integer.class, activity.id)).isEqualTo(2);

        ActivityRequests.CreateActivity firstPayload = activityRequest(groupId, null, 3, "idempotent-a");
        CommunityActivity replayed = runAs(ownerId,
            () -> activityService.createActivity(firstPayload, "same-create-key"));
        assertThat(runAs(ownerId,
            () -> activityService.createActivity(firstPayload, "same-create-key")).id).isEqualTo(replayed.id);
        ActivityRequests.CreateActivity changedPayload = activityRequest(groupId, null, 3, "idempotent-b");
        assertThatThrownBy(() -> runAs(ownerId,
            () -> activityService.createActivity(changedPayload, "same-create-key")))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("不同请求");
    }

    @Test
    void acceptRevalidatesMembershipBlocksAndReferencedContentButRejectCanCloseInvalidApplicants() {
        long ownerId = nextId();
        long postAuthorId = nextId();
        long leftApplicantId = nextId();
        long blockedApplicantId = nextId();
        long hiddenPostApplicantId = nextId();
        long groupId = insertGroup(ownerId, "accept-revalidation");
        for (long userId : List.of(ownerId, postAuthorId, leftApplicantId, blockedApplicantId,
            hiddenPostApplicantId)) {
            insertMember(groupId, userId, userId == ownerId ? "OWNER" : "MEMBER");
        }
        long postId = insertPost(groupId, postAuthorId, "accept-reference");
        CommunityActivity activity = createPublishedActivity(ownerId, groupId, postId, 6, "revalidation");

        CommunityActivityApplication left = runAs(leftApplicantId,
            () -> participationService.apply("ACTIVITY", activity.id, "left-apply"));
        leaveGroup(groupId, leftApplicantId);
        assertThatThrownBy(() -> runAs(ownerId,
            () -> participationService.accept(left.id, "left-accept")))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("有效的小组成员");
        assertThat(runAs(ownerId, () -> participationService.reject(
            left.id, decision("LEFT_GROUP"), "left-reject")).status).isEqualTo("REJECTED");
        rejoinGroup(groupId, leftApplicantId);
        CommunityActivityApplication reapplied = runAs(leftApplicantId,
            () -> participationService.apply("ACTIVITY", activity.id, "left-reapply"));
        assertThat(reapplied.id).isEqualTo(left.id);
        assertThat(reapplied.status).isEqualTo("PENDING");
        assertThat(jdbcTemplate.queryForMap("""
            SELECT reason_code, decided_by, decided_at, withdrawn_at
            FROM community_activity_application WHERE id = ?
            """, reapplied.id).values()).containsOnlyNulls();

        CommunityActivityApplication blocked = runAs(blockedApplicantId,
            () -> participationService.apply("ACTIVITY", activity.id, "blocked-apply"));
        insertBlock(blockedApplicantId, ownerId, "blocked-applicant");
        assertThat(runAs(ownerId, () -> participationService.applications(
            "ACTIVITY", activity.id, applicationQuery("PENDING"))).records())
            .extracting(application -> application.id).doesNotContain(blocked.id);
        assertThatThrownBy(() -> runAs(ownerId,
            () -> participationService.accept(blocked.id, "blocked-accept")))
            .isInstanceOf(BusinessException.class)
            .extracting(error -> ((BusinessException) error).getCode()).isEqualTo(ResultCode.NOT_FOUND);
        assertThat(runAs(ownerId, () -> participationService.reject(
            blocked.id, decision("BLOCKED"), "blocked-reject")).status).isEqualTo("REJECTED");

        CommunityActivityApplication hidden = runAs(hiddenPostApplicantId,
            () -> participationService.apply("ACTIVITY", activity.id, "hidden-apply"));
        hidePost(postId);
        assertThatThrownBy(() -> runAs(ownerId,
            () -> participationService.accept(hidden.id, "hidden-accept")))
            .isInstanceOf(BusinessException.class)
            .extracting(error -> ((BusinessException) error).getCode()).isEqualTo(ResultCode.NOT_FOUND);
    }

    @Test
    void participantListsFilterBothBlockDirectionsForActivitiesAndCompanionRequests() {
        long ownerId = nextId();
        long viewerId = nextId();
        long outboundBlockedId = nextId();
        long inboundBlockedId = nextId();
        long visibleParticipantId = nextId();
        long groupId = insertGroup(ownerId, "participant-blocks");
        for (long userId : List.of(ownerId, viewerId, outboundBlockedId, inboundBlockedId,
            visibleParticipantId)) {
            insertMember(groupId, userId, userId == ownerId ? "OWNER" : "MEMBER");
        }

        CommunityActivity activity = createPublishedActivity(ownerId, groupId, null, 8, "participant-blocks");
        CommunityCompanionRequest companion = createPublishedCompanion(
            ownerId, groupId, null, "participant-blocks");
        for (String targetType : List.of("ACTIVITY", "COMPANION_REQUEST")) {
            long targetId = "ACTIVITY".equals(targetType) ? activity.id : companion.id;
            insertParticipation(targetType, targetId, viewerId, "PARTICIPANT");
            insertParticipation(targetType, targetId, outboundBlockedId, "PARTICIPANT");
            insertParticipation(targetType, targetId, inboundBlockedId, "PARTICIPANT");
            insertParticipation(targetType, targetId, visibleParticipantId, "PARTICIPANT");
        }
        insertBlock(viewerId, outboundBlockedId, "participant-outbound");
        insertBlock(inboundBlockedId, viewerId, "participant-inbound");

        assertVisibleParticipants(viewerId, "ACTIVITY", activity.id, ownerId, viewerId,
            visibleParticipantId, outboundBlockedId, inboundBlockedId);
        assertVisibleParticipants(viewerId, "COMPANION_REQUEST", companion.id, ownerId, viewerId,
            visibleParticipantId, outboundBlockedId, inboundBlockedId);
    }

    @Test
    void discoveryAndSavedMatchesApplyReferenceAuthorBlocksGroupVisibilityAndSelfMatchExclusion()
        throws Exception {
        long ownerId = nextId();
        long blockedPostAuthorId = nextId();
        long blockedCandidateOwnerId = nextId();
        long candidateOwnerId = nextId();
        long fallbackCandidateOwnerId = nextId();
        long viewerId = nextId();
        long groupId = insertGroup(ownerId, "matching-visibility");
        for (long userId : List.of(ownerId, blockedPostAuthorId, blockedCandidateOwnerId,
            candidateOwnerId, fallbackCandidateOwnerId, viewerId)) {
            insertMember(groupId, userId, userId == ownerId ? "OWNER" : "MEMBER");
        }
        long referencedPostId = insertPost(groupId, blockedPostAuthorId, "blocked-reference");

        CommunityActivity referencedActivity = createPublishedActivity(
            ownerId, groupId, referencedPostId, 5, "blocked-activity");
        insertBlock(viewerId, blockedPostAuthorId, "activity-reference-author");
        insertBlock(ownerId, blockedPostAuthorId, "matching-reference-author");
        assertThat(runAs(viewerId, () -> activityService.discoverActivities(discoveryQuery(20))).records())
            .extracting(activity -> activity.id).doesNotContain(referencedActivity.id);

        CommunityCompanionRequest source = createPublishedCompanion(ownerId, groupId, null, "source");
        CommunityCompanionRequest sameOwner = createPublishedCompanion(ownerId, groupId, null, "same-owner");
        CommunityCompanionRequest blockedReference = createPublishedCompanion(
            blockedCandidateOwnerId, groupId, referencedPostId, "blocked-reference-candidate");
        CommunityCompanionRequest candidate = createPublishedCompanion(
            candidateOwnerId, groupId, null, "visible-candidate");
        CommunityCompanionRequest fallbackCandidate = createPublishedCompanion(
            fallbackCandidateOwnerId, groupId, null, "fallback-candidate");

        ActivityResponses.MatchPage refreshed = runAs(ownerId,
            () -> matchingService.refresh(source.id, 1, "refresh-matches"));
        assertThat(refreshed.records()).extracting(match -> match.candidate.id).containsExactly(candidate.id);
        assertThat(refreshed.records().get(0).reasonCodes)
            .contains("ACTIVITY_TYPE_MATCH", "TIME_WINDOW_OVERLAP", "LOCATION_SAME_AREA", "CAPACITY_AVAILABLE");
        assertThat(objectMapper.writeValueAsString(refreshed))
            .doesNotContain("evidenceJson", "reasonCodesJson", "overlapMinutes");
        assertThat(jdbcTemplate.queryForObject("""
            SELECT COUNT(*) FROM community_match_decision
            WHERE source_request_id = ? AND candidate_request_id IN (?, ?)
            """, Integer.class, source.id, sameOwner.id, blockedReference.id)).isZero();

        insertBlock(candidateOwnerId, ownerId, "saved-match-filter");
        assertThat(runAs(ownerId, () -> matchingService.matches(source.id, 1)).records())
            .extracting(match -> match.candidate.id).containsExactly(fallbackCandidate.id);

        hideGroup(groupId);
        assertThatThrownBy(() -> runAs(viewerId, () -> activityService.getActivity(referencedActivity.id)))
            .isInstanceOf(BusinessException.class)
            .extracting(error -> ((BusinessException) error).getCode()).isEqualTo(ResultCode.NOT_FOUND);
    }

    @Test
    void matchingFiltersBeforeItsEvaluationCapAndDoesNotExposeStaleDecisions() {
        long ownerId = nextId();
        long candidateOwnerId = nextId();
        long distractorOwnerId = nextId();
        long groupId = insertGroup(ownerId, "matching-evaluation-cap");
        insertMember(groupId, ownerId, "OWNER");
        insertMember(groupId, candidateOwnerId, "MEMBER");
        insertMember(groupId, distractorOwnerId, "MEMBER");

        CommunityCompanionRequest source = createPublishedCompanion(ownerId, groupId, null, "cap-source");
        CommunityCompanionRequest candidate = createPublishedCompanion(
            candidateOwnerId, groupId, null, "cap-candidate");
        for (int index = 0; index < 501; index++) {
            insertPublishedCompanionRow(groupId, distractorOwnerId, "SPORTS", "SOUTH_CAMPUS",
                "cap-distractor-" + index);
        }

        ActivityResponses.MatchPage refreshed = runAs(ownerId,
            () -> matchingService.refresh(source.id, 20, "refresh-after-structural-filter"));
        assertThat(refreshed.records()).extracting(match -> match.candidate.id)
            .containsExactly(candidate.id);
        assertThat(jdbcTemplate.queryForObject("""
            SELECT COUNT(*) FROM community_match_decision WHERE source_request_id = ?
            """, Integer.class, source.id)).isEqualTo(1);

        ActivityRequests.UpdateLocation moveCandidate = new ActivityRequests.UpdateLocation();
        moveCandidate.versionNo = candidate.versionNo;
        ActivityRequests.Location movedLocation = new ActivityRequests.Location();
        movedLocation.type = "CAMPUS_AREA";
        movedLocation.areaCode = "SOUTH_CAMPUS";
        moveCandidate.location = movedLocation;
        runAs(candidateOwnerId, () -> activityService.updateCompanionLocation(
            candidate.id, moveCandidate, "move-matched-candidate"));

        assertThat(runAs(ownerId, () -> matchingService.matches(source.id, 20)).records()).isEmpty();
    }

    @Test
    void cursorDiscoveryUsesStablePagesAndTheV16DiscoveryIndex() {
        long ownerId = nextId();
        long viewerId = nextId();
        long groupId = insertGroup(ownerId, "cursor-index");
        insertMember(groupId, ownerId, "OWNER");
        insertMember(groupId, viewerId, "MEMBER");
        List<Long> created = new ArrayList<>();
        for (int index = 0; index < 3; index++) {
            created.add(createPublishedActivity(ownerId, groupId, null, 5, "page-" + index).id);
        }

        ActivityRequests.DiscoveryQuery firstQuery = discoveryQuery(2);
        CursorPage<CommunityActivity> first = runAs(viewerId,
            () -> activityService.discoverActivities(firstQuery));
        assertThat(first.records()).hasSize(2);
        assertThat(first.hasMore()).isTrue();
        assertThat(first.nextCursor()).isNotNull();
        ActivityRequests.DiscoveryQuery secondQuery = discoveryQuery(2);
        secondQuery.cursorId = first.nextCursor();
        CursorPage<CommunityActivity> second = runAs(viewerId,
            () -> activityService.discoverActivities(secondQuery));
        assertThat(second.records()).hasSize(1);
        assertThat(second.hasMore()).isFalse();
        assertThat(first.records()).extracting(activity -> activity.id)
            .doesNotContainAnyElementsOf(second.records().stream().map(activity -> activity.id).toList());
        assertThat(first.records().stream().map(activity -> activity.id).toList())
            .isSortedAccordingTo((left, right) -> Long.compare(right, left));

        Map<String, Object> plan = jdbcTemplate.queryForMap("""
            EXPLAIN SELECT * FROM community_activity FORCE INDEX (idx_community_activity_discovery)
            WHERE status = 'PUBLISHED' AND activity_type = 'STUDY' AND start_time > NOW()
            ORDER BY id DESC LIMIT 20
            """);
        assertThat(String.valueOf(plan.get("key"))).isEqualTo("idx_community_activity_discovery");
        assertThat(String.valueOf(plan.get("type"))).isNotEqualToIgnoringCase("ALL");
    }

    @Test
    void reservationReleasePropagatesBeyondOneBatchAndReconciliationRepairsMissedEvents() {
        long ownerId = nextId();
        long groupId = insertGroup(ownerId, "propagation");
        insertMember(groupId, ownerId, "OWNER");
        ReservationFixture reservation = insertReservation(ownerId, 100);
        List<Long> activityIds = new ArrayList<>();
        for (int index = 0; index < 55; index++) {
            activityIds.add(insertConfirmedActivity(groupId, ownerId, reservation, "batch-" + index));
        }

        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.executeWithoutResult(status -> {
            jdbcTemplate.update("""
                UPDATE reservation_occupancy
                SET active_flag = NULL, released_at = NOW(), release_reason = 'RESERVATION_CANCELED'
                WHERE reservation_id = ? AND active_flag = 1
                """, reservation.reservationId());
            commitmentChangePublisher.publish(
                reservation.reservationId(), "RELEASED", "RESERVATION_CANCELED");
        });

        assertThat(jdbcTemplate.queryForObject("""
            SELECT COUNT(*) FROM community_activity
            WHERE reservation_id = ? AND location_commitment_status = 'DEGRADED'
            """, Integer.class, reservation.reservationId())).isEqualTo(55);
        assertThat(jdbcTemplate.queryForObject("""
            SELECT COUNT(*) FROM community_activity_state_event
            WHERE event_type = 'ACTIVITY_LOCATION_DEGRADED' AND reason_code = 'RESERVATION_CANCELED'
            """, Integer.class)).isEqualTo(55);
        assertThat(jdbcTemplate.queryForObject("""
            SELECT COUNT(*) FROM community_activity_notification_task
            WHERE event_type = 'ACTIVITY_LOCATION_DEGRADED'
            """, Integer.class)).isEqualTo(55);

        commitmentChangePublisher.publish(reservation.reservationId(), "RELEASED", "RESERVATION_CANCELED");
        assertThat(jdbcTemplate.queryForObject("""
            SELECT COUNT(*) FROM community_activity_state_event
            WHERE event_type = 'ACTIVITY_LOCATION_DEGRADED'
            """, Integer.class)).isEqualTo(55);

        long missedActivityId = insertConfirmedActivity(groupId, ownerId, reservation, "reconciliation");
        assertThat(reconciliationService.reconcile()).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("""
            SELECT location_commitment_status FROM community_activity WHERE id = ?
            """, String.class, missedActivityId)).isEqualTo("DEGRADED");
        assertThat(activityIds).hasSize(55);
    }

    @Test
    void reservationAndLocationConfirmationUseCompatibleLockOrderWithoutDeadlock() throws Exception {
        long ownerId = nextId();
        long groupId = insertGroup(ownerId, "commitment-locks");
        insertMember(groupId, ownerId, "OWNER");
        ReservationFixture reservation = insertReservation(ownerId, 5);
        CommunityActivity activity = runAs(ownerId, () -> activityService.createActivity(
            activityRequest(groupId, null, 5, "lock-order"), "lock-order-create"));

        CountDownLatch reservationLocked = new CountDownLatch(1);
        CountDownLatch releaseLocks = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> rescheduleReview = executor.submit(() -> inNewTransaction(() -> {
                jdbcTemplate.queryForObject("SELECT id FROM reservation WHERE id = ? FOR UPDATE",
                    Long.class, reservation.reservationId());
                jdbcTemplate.queryForObject("SELECT id FROM venue WHERE id = ? FOR UPDATE",
                    Long.class, reservation.venueId());
                jdbcTemplate.queryForObject("SELECT id FROM venue_space WHERE id = ? FOR UPDATE",
                    Long.class, reservation.spaceId());
                jdbcTemplate.queryForObject("""
                    SELECT id FROM reservation_occupancy
                    WHERE reservation_id = ? AND active_flag = 1 FOR UPDATE
                    """, Long.class, reservation.reservationId());
                reservationLocked.countDown();
                await(releaseLocks);
                jdbcTemplate.update("UPDATE reservation SET update_time = NOW() WHERE id = ?",
                    reservation.reservationId());
                return true;
            }));
            assertThat(reservationLocked.await(10, TimeUnit.SECONDS)).isTrue();
            Future<CommunityActivity> confirmation = executor.submit(() -> runAs(ownerId, () -> {
                ActivityRequests.UpdateLocation update = new ActivityRequests.UpdateLocation();
                update.versionNo = activity.versionNo;
                update.location = venueLocation(reservation);
                return activityService.updateActivityLocation(activity.id, update, "confirm-during-reschedule");
            }));
            releaseLocks.countDown();
            assertThat(rescheduleReview.get(20, TimeUnit.SECONDS)).isTrue();
            CommunityActivity confirmed = confirmation.get(20, TimeUnit.SECONDS);
            assertThat(confirmed.locationCommitmentStatus).isEqualTo("CONFIRMED");

            ActivityRequests.UpdateLocation backToArea = new ActivityRequests.UpdateLocation();
            backToArea.versionNo = confirmed.versionNo;
            backToArea.location = areaLocation();
            CommunityActivity unconfirmed = runAs(ownerId, () -> activityService.updateActivityLocation(
                activity.id, backToArea, "switch-back-to-area"));
            assertThat(unconfirmed.locationCommitmentStatus).isEqualTo("UNCONFIRMED");
            Map<String, Object> storedLocation = jdbcTemplate.queryForMap("""
                SELECT venue_id, space_id, reservation_id, area_code
                FROM community_activity WHERE id = ?
                """, activity.id);
            assertThat(storedLocation.get("area_code")).isEqualTo("NORTH_CAMPUS");
            assertThat(storedLocation.get("venue_id")).isNull();
            assertThat(storedLocation.get("space_id")).isNull();
            assertThat(storedLocation.get("reservation_id")).isNull();

            CommunityCompanionRequest companion = runAs(ownerId, () -> activityService.createCompanion(
                companionRequest(groupId, null, "location-switch"), "companion-location-create"));
            ActivityRequests.UpdateLocation companionLocation = new ActivityRequests.UpdateLocation();
            companionLocation.versionNo = companion.versionNo;
            companionLocation.location = venueLocation(reservation);
            CommunityCompanionRequest confirmedCompanion = runAs(ownerId,
                () -> activityService.updateCompanionLocation(
                    companion.id, companionLocation, "companion-location-confirm"));
            assertThat(confirmedCompanion.locationCommitmentStatus).isEqualTo("CONFIRMED");
            assertThat(confirmedCompanion.areaCode).isNull();
        } finally {
            releaseLocks.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void activityLocationUpdateSynchronizesOpenLinkedCompanionsInTheSameTransaction() {
        long ownerId = nextId();
        long companionMemberId = nextId();
        long groupId = insertGroup(ownerId, "linked-location-sync");
        insertMember(groupId, ownerId, "OWNER");
        insertMember(groupId, companionMemberId, "MEMBER");
        ReservationFixture reservation = insertReservation(ownerId, 5);

        CommunityActivity activity = createPublishedActivity(ownerId, groupId, null, 4, "linked-location");
        ActivityRequests.CreateCompanionRequest request = companionRequest(groupId, null, "linked-location");
        request.activityId = activity.id;
        request.capacityTotal = 3;
        CommunityCompanionRequest createdCompanion = runAs(ownerId,
            () -> activityService.createCompanion(request, "linked-location-create"));
        CommunityCompanionRequest companion = runAs(ownerId,
            () -> activityService.publishCompanion(createdCompanion.id, "linked-location-publish"));
        insertParticipation("COMPANION_REQUEST", companion.id, companionMemberId, "PARTICIPANT");
        jdbcTemplate.update("UPDATE community_companion_request SET participant_count = 2 WHERE id = ?", companion.id);

        ActivityRequests.UpdateLocation confirm = new ActivityRequests.UpdateLocation();
        confirm.versionNo = activity.versionNo;
        confirm.location = venueLocation(reservation);
        CommunityActivity confirmedActivity = runAs(ownerId,
            () -> activityService.updateActivityLocation(activity.id, confirm, "linked-location-confirm"));

        Map<String, Object> confirmedCompanion = jdbcTemplate.queryForMap("""
            SELECT location_type, venue_id, space_id, reservation_id, area_code,
                   location_commitment_status, commitment_version, version_no
            FROM community_companion_request WHERE id = ?
            """, companion.id);
        assertThat(confirmedCompanion).containsEntry("location_type", "VENUE_SPACE")
            .containsEntry("venue_id", reservation.venueId())
            .containsEntry("space_id", reservation.spaceId())
            .containsEntry("reservation_id", reservation.reservationId())
            .containsEntry("location_commitment_status", "CONFIRMED")
            .containsEntry("commitment_version", 1L);
        assertThat(confirmedCompanion.get("area_code")).isNull();
        assertThat(confirmedCompanion.get("version_no")).isEqualTo(2L);
        assertThat(jdbcTemplate.queryForObject("""
            SELECT COUNT(*) FROM community_activity_state_event
            WHERE event_type = 'COMPANION_LOCATION_SYNCHRONIZED' AND subject_id = ?
            """, Integer.class, companion.id)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("""
            SELECT COUNT(*) FROM community_activity_notification_task
            WHERE event_type = 'COMPANION_LOCATION_SYNCHRONIZED'
              AND recipient_user_id = ? AND business_id = ?
            """, Integer.class, companionMemberId, companion.id)).isEqualTo(1);

        ActivityRequests.UpdateLocation unconfirm = new ActivityRequests.UpdateLocation();
        unconfirm.versionNo = confirmedActivity.versionNo;
        unconfirm.location = areaLocation();
        runAs(ownerId, () -> activityService.updateActivityLocation(
            activity.id, unconfirm, "linked-location-unconfirm"));

        Map<String, Object> unconfirmedCompanion = jdbcTemplate.queryForMap("""
            SELECT venue_id, space_id, reservation_id, area_code,
                   location_commitment_status, commitment_version, version_no
            FROM community_companion_request WHERE id = ?
            """, companion.id);
        assertThat(unconfirmedCompanion.get("venue_id")).isNull();
        assertThat(unconfirmedCompanion.get("space_id")).isNull();
        assertThat(unconfirmedCompanion.get("reservation_id")).isNull();
        assertThat(unconfirmedCompanion).containsEntry("area_code", "NORTH_CAMPUS")
            .containsEntry("location_commitment_status", "UNCONFIRMED")
            .containsEntry("commitment_version", 2L)
            .containsEntry("version_no", 3L);
        assertThat(jdbcTemplate.queryForObject("""
            SELECT COUNT(*) FROM community_activity_notification_task
            WHERE event_type = 'COMPANION_LOCATION_SYNCHRONIZED'
              AND recipient_user_id = ? AND business_id = ?
            """, Integer.class, companionMemberId, companion.id)).isEqualTo(2);
    }

    @Test
    void locationUpdateIdempotencyReplaysBeforeAChangedReservationIsRevalidated() {
        long ownerId = nextId();
        long groupId = insertGroup(ownerId, "location-replay");
        insertMember(groupId, ownerId, "OWNER");
        ReservationFixture reservation = insertReservation(ownerId, 5);

        CommunityActivity activity = runAs(ownerId, () -> activityService.createActivity(
            activityRequest(groupId, null, 4, "location-replay"), "location-replay-activity-create"));
        ActivityRequests.UpdateLocation activityUpdate = new ActivityRequests.UpdateLocation();
        activityUpdate.versionNo = activity.versionNo;
        activityUpdate.location = venueLocation(reservation);
        CommunityActivity confirmedActivity = runAs(ownerId, () -> activityService.updateActivityLocation(
            activity.id, activityUpdate, "location-replay-activity-update"));

        CommunityCompanionRequest companion = runAs(ownerId, () -> activityService.createCompanion(
            companionRequest(groupId, null, "location-replay"), "location-replay-companion-create"));
        ActivityRequests.UpdateLocation companionUpdate = new ActivityRequests.UpdateLocation();
        companionUpdate.versionNo = companion.versionNo;
        companionUpdate.location = venueLocation(reservation);
        CommunityCompanionRequest confirmedCompanion = runAs(ownerId,
            () -> activityService.updateCompanionLocation(
                companion.id, companionUpdate, "location-replay-companion-update"));

        jdbcTemplate.update("""
            UPDATE reservation_occupancy
            SET active_flag = NULL, released_at = NOW(), release_reason = 'TEST_RELEASE'
            WHERE reservation_id = ? AND active_flag = 1
            """, reservation.reservationId());

        assertThat(runAs(ownerId, () -> activityService.updateActivityLocation(
            activity.id, activityUpdate, "location-replay-activity-update")).id)
            .isEqualTo(confirmedActivity.id);
        assertThat(runAs(ownerId, () -> activityService.updateCompanionLocation(
            companion.id, companionUpdate, "location-replay-companion-update")).id)
            .isEqualTo(confirmedCompanion.id);
        assertThat(jdbcTemplate.queryForObject("""
            SELECT location_commitment_status FROM community_activity WHERE id = ?
            """, String.class, activity.id)).isEqualTo("CONFIRMED");
        assertThat(jdbcTemplate.queryForObject("""
            SELECT location_commitment_status FROM community_companion_request WHERE id = ?
            """, String.class, companion.id)).isEqualTo("CONFIRMED");
    }

    @Test
    void notificationClaimsRetryStaleRecoveryAndStableKeysAreDatabaseAtomic() throws Exception {
        notificationService.enqueue("ACTIVITY_UPDATED", "ACTIVITY_UPDATED:stable:1", 9001L,
            "活动更新", "活动信息发生变化。", "ACTIVITY", 1L);
        notificationService.enqueue("ACTIVITY_UPDATED", "ACTIVITY_UPDATED:stable:1", 9001L,
            "活动更新", "活动信息发生变化。", "ACTIVITY", 1L);
        Long taskId = jdbcTemplate.queryForObject(
            "SELECT id FROM community_activity_notification_task WHERE event_key = 'ACTIVITY_UPDATED:stable:1'",
            Long.class);
        assertThat(jdbcTemplate.queryForObject("""
            SELECT COUNT(*) FROM community_activity_notification_task
            WHERE event_key = 'ACTIVITY_UPDATED:stable:1' AND recipient_user_id = 9001
            """, Integer.class)).isEqualTo(1);

        List<CommunityActivityNotificationTask> claims = executeConcurrently(
            () -> notificationStateService.claim(taskId),
            () -> notificationStateService.claim(taskId));
        assertThat(claims).filteredOn(task -> task != null).hasSize(1);
        CommunityActivityNotificationTask winner = claims.stream().filter(task -> task != null).findFirst().orElseThrow();
        notificationStateService.failedAttempt(winner);
        assertThat(notificationStatus(taskId)).isEqualTo("RETRY_WAITING");
        jdbcTemplate.update("""
            UPDATE community_activity_notification_task SET next_retry_time = DATE_SUB(NOW(), INTERVAL 1 SECOND)
            WHERE id = ?
            """, taskId);
        CommunityActivityNotificationTask retry = notificationStateService.claim(taskId);
        assertThat(retry).isNotNull();
        notificationStateService.delivered(retry);
        assertThat(notificationStatus(taskId)).isEqualTo("DELIVERED");

        Long exhaustedId = enqueueAndFind("ACTIVITY_CANCELED:exhausted", 9002L);
        jdbcTemplate.update("""
            UPDATE community_activity_notification_task
            SET status = 'RETRY_WAITING', attempt_count = 7,
                next_retry_time = DATE_SUB(NOW(), INTERVAL 1 SECOND), claimed_at = NULL
            WHERE id = ?
            """, exhaustedId);
        CommunityActivityNotificationTask eighth = notificationStateService.claim(exhaustedId);
        assertThat(eighth.attemptCount).isEqualTo(8);
        notificationStateService.failedAttempt(eighth);
        assertThat(notificationStatus(exhaustedId)).isEqualTo("FAILED");

        Long staleId = enqueueAndFind("ACTIVITY_CANCELED:stale", 9003L);
        jdbcTemplate.update("""
            UPDATE community_activity_notification_task
            SET status = 'DISPATCHING', attempt_count = 8,
                claimed_at = DATE_SUB(NOW(), INTERVAL 3 MINUTE), next_retry_time = NULL
            WHERE id = ?
            """, staleId);
        assertThat(notificationStateService.claim(staleId)).isNull();
        assertThat(notificationStatus(staleId)).isEqualTo("FAILED");
        assertThat(jdbcTemplate.queryForObject("""
            SELECT claimed_at IS NULL FROM community_activity_notification_task WHERE id = ?
            """, Boolean.class, staleId)).isTrue();
    }

    @Test
    void staleReclaimRejectsLateResultsFromThePreviousAttempt() {
        Long taskId = enqueueAndFind("ACTIVITY_CANCELED:stale-reclaim", 9004L);
        CommunityActivityNotificationTask firstWorker = notificationStateService.claim(taskId);
        assertThat(firstWorker).isNotNull();
        assertThat(firstWorker.attemptCount).isEqualTo(1);

        jdbcTemplate.update("""
            UPDATE community_activity_notification_task
            SET claimed_at = DATE_SUB(NOW(), INTERVAL 3 MINUTE)
            WHERE id = ?
            """, taskId);
        CommunityActivityNotificationTask secondWorker = notificationStateService.claim(taskId);
        assertThat(secondWorker).isNotNull();
        assertThat(secondWorker.attemptCount).isEqualTo(2);

        notificationStateService.delivered(firstWorker);
        assertThat(activeNotificationClaimCount(taskId, secondWorker.attemptCount)).isEqualTo(1);

        notificationStateService.failedAttempt(firstWorker);
        assertThat(activeNotificationClaimCount(taskId, secondWorker.attemptCount)).isEqualTo(1);
    }

    @Test
    void systemExpiryAppendsEventsAndQueuesStableNotificationsForEveryParticipant() {
        long ownerId = nextId();
        long memberId = nextId();
        long groupId = insertGroup(ownerId, "expiry");
        insertMember(groupId, ownerId, "OWNER");
        insertMember(groupId, memberId, "MEMBER");
        long activityId = insertExpiredActivity(groupId, ownerId, memberId);

        assertThat(activityService.expireDue(20)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
            "SELECT status FROM community_activity WHERE id = ?", String.class, activityId))
            .isEqualTo("EXPIRED");
        assertThat(jdbcTemplate.queryForObject("""
            SELECT COUNT(*) FROM community_activity_state_event
            WHERE subject_type = 'ACTIVITY' AND subject_id = ? AND event_type = 'ACTIVITY_EXPIRED'
            """, Integer.class, activityId)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("""
            SELECT COUNT(*) FROM community_activity_notification_task
            WHERE business_type = 'ACTIVITY' AND business_id = ? AND event_type = 'ACTIVITY_EXPIRED'
            """, Integer.class, activityId)).isEqualTo(2);
        assertThat(activityService.expireDue(20)).isZero();
    }

    private CommandOutcome acceptOutcome(long ownerId, long applicationId, String key) {
        try {
            runAs(ownerId, () -> participationService.accept(applicationId, key));
            return new CommandOutcome(true, ResultCode.SUCCESS);
        } catch (BusinessException exception) {
            return new CommandOutcome(false, exception.getCode());
        }
    }

    private CommunityActivity createPublishedActivity(long ownerId, long groupId, Long postId,
                                                       int capacity, String suffix) {
        CommunityActivity activity = runAs(ownerId, () -> activityService.createActivity(
            activityRequest(groupId, postId, capacity, suffix), "create-" + suffix));
        return runAs(ownerId, () -> activityService.publishActivity(activity.id, "publish-" + suffix));
    }

    private void assertVisibleParticipants(long viewerId,
                                           String targetType,
                                           long targetId,
                                           long ownerId,
                                           long expectedViewerId,
                                           long visibleParticipantId,
                                           long outboundBlockedId,
                                           long inboundBlockedId) {
        assertThat(runAs(viewerId,
            () -> participationService.participants(targetType, targetId, null, 20)).records())
            .extracting(participation -> participation.userId)
            .contains(ownerId, expectedViewerId, visibleParticipantId)
            .doesNotContain(outboundBlockedId, inboundBlockedId);
    }

    private CommunityCompanionRequest createPublishedCompanion(long ownerId, long groupId,
                                                                Long postId, String suffix) {
        ActivityRequests.CreateCompanionRequest request = companionRequest(groupId, postId, suffix);
        CommunityCompanionRequest companion = runAs(ownerId,
            () -> activityService.createCompanion(request, "create-companion-" + suffix));
        return runAs(ownerId,
            () -> activityService.publishCompanion(companion.id, "publish-companion-" + suffix));
    }

    private long insertPublishedCompanionRow(long groupId,
                                             long ownerId,
                                             String activityType,
                                             String areaCode,
                                             String suffix) {
        long requestId = nextId();
        jdbcTemplate.update("""
            INSERT INTO community_companion_request(
                id, request_no, group_id, owner_user_id, activity_type, title, description,
                window_start, window_end, location_type, area_code, location_commitment_status,
                commitment_version, capacity_total, participant_count, status, version_no, published_at
            ) VALUES (?, ?, ?, ?, ?, ?, '匹配干扰数据', ?, ?, 'CAMPUS_AREA', ?, 'UNCONFIRMED',
                      0, 4, 1, 'PUBLISHED', 1, NOW())
            """, requestId, "CPR-CAP-" + requestId, groupId, ownerId, activityType,
            "搭子-" + suffix, FUTURE_START, FUTURE_END, areaCode);
        return requestId;
    }

    private ActivityRequests.CreateCompanionRequest companionRequest(long groupId, Long postId, String suffix) {
        ActivityRequests.CreateCompanionRequest request = new ActivityRequests.CreateCompanionRequest();
        request.groupId = groupId;
        request.referencePostId = postId;
        request.activityType = "STUDY";
        request.title = "搭子-" + suffix;
        request.description = "结构化搭子需求";
        request.windowStart = FUTURE_START;
        request.windowEnd = FUTURE_END;
        request.capacityTotal = 4;
        request.location = areaLocation();
        return request;
    }

    private ActivityRequests.CreateActivity activityRequest(long groupId, Long postId,
                                                             int capacity, String suffix) {
        ActivityRequests.CreateActivity request = new ActivityRequests.CreateActivity();
        request.groupId = groupId;
        request.referencePostId = postId;
        request.activityType = "STUDY";
        request.title = "活动-" + suffix;
        request.description = "结构化活动说明";
        request.startTime = FUTURE_START;
        request.endTime = FUTURE_END;
        request.capacityTotal = capacity;
        request.location = areaLocation();
        return request;
    }

    private ActivityRequests.Location areaLocation() {
        ActivityRequests.Location location = new ActivityRequests.Location();
        location.type = "CAMPUS_AREA";
        location.areaCode = "NORTH_CAMPUS";
        return location;
    }

    private ActivityRequests.Location venueLocation(ReservationFixture fixture) {
        ActivityRequests.Location location = new ActivityRequests.Location();
        location.type = "VENUE_SPACE";
        location.venueId = fixture.venueId();
        location.spaceId = fixture.spaceId();
        location.reservationId = fixture.reservationId();
        return location;
    }

    private ActivityRequests.DiscoveryQuery discoveryQuery(int limit) {
        ActivityRequests.DiscoveryQuery query = new ActivityRequests.DiscoveryQuery();
        query.activityType = "STUDY";
        query.limit = limit;
        return query;
    }

    private ActivityRequests.ApplicationQuery applicationQuery(String status) {
        ActivityRequests.ApplicationQuery query = new ActivityRequests.ApplicationQuery();
        query.status = status;
        query.limit = 20;
        return query;
    }

    private ActivityRequests.Decision decision(String reason) {
        ActivityRequests.Decision decision = new ActivityRequests.Decision();
        decision.reasonCode = reason;
        return decision;
    }

    private long insertGroup(long ownerId, String suffix) {
        long groupId = nextId();
        jdbcTemplate.update("""
            INSERT INTO community_group(
                id, group_no, name, description, owner_user_id, visibility, join_policy,
                status, idempotency_key, request_hash
            ) VALUES (?, ?, ?, '活动测试小组', ?, 'PLATFORM_VISIBLE', 'DIRECT', 'ACTIVE', ?, ?)
            """, groupId, "CG-ACT-" + suffix + groupId, "活动小组-" + suffix, ownerId,
            "group-key-" + suffix + groupId, "group-hash-" + groupId);
        return groupId;
    }

    private void insertMember(long groupId, long userId, String role) {
        jdbcTemplate.update("""
            INSERT INTO community_group_member(group_id, user_id, member_role, status, joined_at)
            VALUES (?, ?, ?, 'ACTIVE', NOW())
            """, groupId, userId, role);
    }

    private long insertPost(long groupId, long authorId, String suffix) {
        long postId = nextId();
        jdbcTemplate.update("""
            INSERT INTO community_post(
                id, post_no, group_id, author_user_id, title, content_text, status,
                idempotency_key, request_hash, published_at
            ) VALUES (?, ?, ?, ?, ?, '引用帖子正文', 'PUBLISHED', ?, ?, NOW())
            """, postId, "CP-ACT-" + postId, groupId, authorId, "引用帖-" + suffix,
            "post-key-" + postId, "post-hash-" + postId);
        return postId;
    }

    private void insertBlock(long blockerId, long blockedId, String suffix) {
        jdbcTemplate.update("""
            INSERT INTO community_user_block(
                blocker_user_id, blocked_user_id, status, last_idempotency_key, created_at
            ) VALUES (?, ?, 'ACTIVE', ?, NOW())
            """, blockerId, blockedId, "block-key-" + suffix);
    }

    private void leaveGroup(long groupId, long userId) {
        jdbcTemplate.update("""
            UPDATE community_group_member
            SET status = 'LEFT', left_at = NOW(), update_time = NOW()
            WHERE group_id = ? AND user_id = ?
            """, groupId, userId);
    }

    private void rejoinGroup(long groupId, long userId) {
        jdbcTemplate.update("""
            UPDATE community_group_member
            SET status = 'ACTIVE', joined_at = NOW(), left_at = NULL, update_time = NOW()
            WHERE group_id = ? AND user_id = ?
            """, groupId, userId);
    }

    private void hidePost(long postId) {
        jdbcTemplate.update("""
            UPDATE community_post
            SET status = 'HIDDEN', hidden_by = author_user_id, hidden_scope = 'PLATFORM',
                hidden_reason_code = 'POLICY', hidden_at = NOW(), deleted_at = NOW(),
                retain_until = DATE_ADD(NOW(), INTERVAL 180 DAY), update_time = NOW()
            WHERE id = ?
            """, postId);
    }

    private void hideGroup(long groupId) {
        jdbcTemplate.update("""
            UPDATE community_group
            SET status = 'HIDDEN', hidden_at = NOW(), deleted_at = NOW(),
                retain_until = DATE_ADD(NOW(), INTERVAL 180 DAY), update_time = NOW()
            WHERE id = ?
            """, groupId);
    }

    private ReservationFixture insertReservation(long ownerId, int participants) {
        long venueId = nextId();
        long spaceId = nextId();
        long reservationId = nextId();
        jdbcTemplate.update("INSERT INTO venue(id, name, status, deleted) VALUES (?, ?, 'ACTIVE', 0)",
            venueId, "活动承诺场馆");
        jdbcTemplate.update("""
            INSERT INTO venue_space(id, venue_id, name, type, capacity, price_per_hour, status, deleted)
            VALUES (?, ?, '活动承诺空间', 'BADMINTON', 200, 10.00, 'ACTIVE', 0)
            """, spaceId, venueId);
        jdbcTemplate.update("""
            INSERT INTO reservation(
                id, user_id, venue_id, space_id, start_time, end_time, status, amount,
                create_time, update_time, deleted, booking_mode_snapshot, participant_count
            ) VALUES (?, ?, ?, ?, ?, ?, 'APPROVED', 0.00, NOW(), NOW(), 0, 'EXCLUSIVE', ?)
            """, reservationId, ownerId, venueId, spaceId,
            FUTURE_START.minusHours(1), FUTURE_END.plusHours(1), participants);
        jdbcTemplate.update("""
            INSERT INTO reservation_occupancy(
                reservation_id, space_id, slot_date, start_time, end_time, active_flag,
                booking_mode, quantity
            ) VALUES (?, ?, ?, ?, ?, 1, 'EXCLUSIVE', 1)
            """, reservationId, spaceId, FUTURE_START.toLocalDate(),
            FUTURE_START.minusHours(1).toLocalTime(), FUTURE_END.plusHours(1).toLocalTime());
        return new ReservationFixture(reservationId, venueId, spaceId);
    }

    private long insertConfirmedActivity(long groupId, long ownerId, ReservationFixture reservation,
                                         String suffix) {
        long activityId = nextId();
        jdbcTemplate.update("""
            INSERT INTO community_activity(
                id, activity_no, group_id, organizer_user_id, activity_type, title, description,
                start_time, end_time, location_type, venue_id, space_id, reservation_id,
                location_commitment_status, commitment_version, capacity_total, participant_count,
                status, version_no, published_at
            ) VALUES (?, ?, ?, ?, 'STUDY', ?, '传播测试', ?, ?, 'VENUE_SPACE', ?, ?, ?,
                      'CONFIRMED', 1, 2, 1, 'PUBLISHED', 1, NOW())
            """, activityId, "ACT-BATCH-" + activityId, groupId, ownerId, "传播-" + suffix,
            FUTURE_START, FUTURE_END, reservation.venueId(), reservation.spaceId(), reservation.reservationId());
        insertParticipation("ACTIVITY", activityId, ownerId, "ORGANIZER");
        return activityId;
    }

    private long insertExpiredActivity(long groupId, long ownerId, long memberId) {
        long activityId = nextId();
        LocalDateTime start = LocalDateTime.now().minusHours(2);
        LocalDateTime end = LocalDateTime.now().minusHours(1);
        jdbcTemplate.update("""
            INSERT INTO community_activity(
                id, activity_no, group_id, organizer_user_id, activity_type, title,
                start_time, end_time, location_type, area_code, location_commitment_status,
                capacity_total, participant_count, status, version_no, published_at
            ) VALUES (?, ?, ?, ?, 'STUDY', '已到期活动', ?, ?, 'CAMPUS_AREA', 'NORTH_CAMPUS',
                      'UNCONFIRMED', 3, 2, 'PUBLISHED', 1, ?)
            """, activityId, "ACT-EXPIRED-" + activityId, groupId, ownerId, start, end, start);
        insertParticipation("ACTIVITY", activityId, ownerId, "ORGANIZER");
        insertParticipation("ACTIVITY", activityId, memberId, "PARTICIPANT");
        return activityId;
    }

    private void insertParticipation(String targetType, long targetId, long userId, String role) {
        jdbcTemplate.update("""
            INSERT INTO community_activity_participation(
                participation_no, target_type, target_id, user_id, role_type, status, joined_at
            ) VALUES (?, ?, ?, ?, ?, 'ACTIVE', NOW())
            """, "PAR-FIXTURE-" + nextId(), targetType, targetId, userId, role);
    }

    private Long enqueueAndFind(String eventKey, long recipientId) {
        notificationService.enqueue("ACTIVITY_CANCELED", eventKey, recipientId,
            "活动已取消", "活动取消通知。", "ACTIVITY", 1L);
        return jdbcTemplate.queryForObject("""
            SELECT id FROM community_activity_notification_task
            WHERE event_key = ? AND recipient_user_id = ?
            """, Long.class, eventKey, recipientId);
    }

    private String notificationStatus(Long taskId) {
        return jdbcTemplate.queryForObject("""
            SELECT status FROM community_activity_notification_task WHERE id = ?
            """, String.class, taskId);
    }

    private int activeNotificationClaimCount(Long taskId, int attemptCount) {
        return jdbcTemplate.queryForObject("""
            SELECT COUNT(*) FROM community_activity_notification_task
            WHERE id = ? AND status = 'DISPATCHING' AND attempt_count = ?
              AND claimed_at IS NOT NULL AND delivered_at IS NULL
              AND next_retry_time IS NULL AND last_error_code IS NULL AND last_error_message IS NULL
            """, Integer.class, taskId, attemptCount);
    }

    private <T> List<T> executeConcurrently(Supplier<T> first, Supplier<T> second) throws Exception {
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
        await(start);
        return operation.get();
    }

    private <T> T inNewTransaction(Supplier<T> operation) {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return transaction.execute(status -> operation.get());
    }

    private <T> T runAs(long userId, Supplier<T> operation) {
        CurrentUser user = new CurrentUser(userId, "user-" + userId, Set.of(), Set.of("community:access"));
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(user, null, List.of()));
        try {
            return operation.get();
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("community activity concurrency timed out");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("community activity concurrency interrupted", exception);
        }
    }

    private long nextId() {
        return IDS.incrementAndGet();
    }

    private record CommandOutcome(boolean succeeded, ResultCode code) {
    }

    private record ReservationFixture(long reservationId, long venueId, long spaceId) {
    }

    @SpringBootConfiguration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @MapperScan({
        "com.example.venue.community.mapper",
        "com.example.venue.community.activity.mapper",
        "com.example.venue.reservation.mapper",
        "com.example.venue.resource.mapper",
        "com.example.venue.venue.mapper"
    })
    static class IntegrationApplication {
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper().findAndRegisterModules();
        }

        @Bean
        CommunityTextPolicy communityTextPolicy() {
            return new CommunityTextPolicy();
        }

        @Bean
        CommunityRateLimitService communityRateLimitService() {
            return mock(CommunityRateLimitService.class);
        }

        @Bean
        CommunityAccessService communityAccessService(CommunityGroupMapper groupMapper,
                                                      CommunityGroupMemberMapper memberMapper,
                                                      CommunityPostMapper postMapper,
                                                      CommunityCommentMapper commentMapper,
                                                      CommunityUserBlockMapper blockMapper) {
            return new CommunityAccessService(groupMapper, memberMapper, postMapper, commentMapper, blockMapper);
        }

        @Bean
        VenueAccessService venueAccessService(VenueAdminRelationMapper relationMapper) {
            return new VenueAccessService(relationMapper);
        }

        @Bean
        ReservationCommitmentService reservationCommitmentService(
            ReservationMapper reservationMapper,
            ReservationOccupancyMapper occupancyMapper,
            ReservationOccupancySeatMapper occupancySeatMapper,
            VenueAccessService venueAccessService,
            VenueSpaceMapper spaceMapper) {
            return new ReservationCommitmentService(
                reservationMapper, occupancyMapper, occupancySeatMapper, venueAccessService, spaceMapper);
        }

        @Bean
        ActivityLocationPolicy activityLocationPolicy(ReservationCommitmentService commitmentService,
                                                      CommunityTextPolicy textPolicy) {
            return new ActivityLocationPolicy(commitmentService, textPolicy);
        }

        @Bean
        ActivityCommandService activityCommandService(CommunityActivityCommandMapper mapper,
                                                      CommunityTextPolicy textPolicy) {
            return new ActivityCommandService(mapper, textPolicy);
        }

        @Bean
        ActivityStateEventService activityStateEventService(CommunityActivityStateEventMapper mapper,
                                                            ObjectMapper objectMapper) {
            return new ActivityStateEventService(mapper, objectMapper);
        }

        @Bean
        ActivityNotificationService activityNotificationService(
            CommunityActivityNotificationTaskMapper mapper,
            ApplicationEventPublisher eventPublisher) {
            return new ActivityNotificationService(mapper, eventPublisher);
        }

        @Bean
        CommunityActivityService communityActivityService(
            CommunityActivityMapper activityMapper,
            CommunityCompanionRequestMapper companionMapper,
            CommunityActivityParticipationMapper participationMapper,
            CommunityAccessService accessService,
            CommunityTextPolicy textPolicy,
            ActivityLocationPolicy locationPolicy,
            ActivityCommandService commandService,
            ActivityStateEventService eventService,
            ActivityNotificationService notificationService) {
            return new CommunityActivityService(activityMapper, companionMapper, participationMapper,
                accessService, textPolicy, locationPolicy, commandService, eventService, notificationService);
        }

        @Bean
        ActivityParticipationService activityParticipationService(
            CommunityActivityMapper activityMapper,
            CommunityCompanionRequestMapper companionMapper,
            CommunityActivityApplicationMapper applicationMapper,
            CommunityActivityParticipationMapper participationMapper,
            CommunityAccessService accessService,
            CommunityTextPolicy textPolicy,
            CommunityRateLimitService rateLimitService,
            ActivityCommandService commandService,
            ActivityStateEventService eventService,
            ActivityNotificationService notificationService) {
            return new ActivityParticipationService(activityMapper, companionMapper, applicationMapper,
                participationMapper, accessService, textPolicy, rateLimitService, commandService,
                eventService, notificationService);
        }

        @Bean
        CompanionMatchingService companionMatchingService(
            CommunityCompanionRequestMapper requestMapper,
            CommunityActivityMapper activityMapper,
            CommunityMatchDecisionMapper decisionMapper,
            CommunityAccessService accessService,
            CommunityTextPolicy textPolicy,
            ActivityCommandService commandService,
            ActivityStateEventService eventService,
            ObjectMapper objectMapper) {
            return new CompanionMatchingService(requestMapper, activityMapper, decisionMapper,
                accessService, textPolicy, commandService, eventService, objectMapper);
        }

        @Bean
        ActivityReservationPropagationBatchService activityReservationPropagationBatchService(
            CommunityActivityMapper activityMapper,
            CommunityCompanionRequestMapper companionMapper,
            CommunityActivityParticipationMapper participationMapper,
            ReservationCommitmentService commitmentService,
            ActivityStateEventService eventService,
            ActivityNotificationService notificationService) {
            return new ActivityReservationPropagationBatchService(activityMapper, companionMapper,
                participationMapper, commitmentService, eventService, notificationService);
        }

        @Bean
        ActivityReservationPropagationService activityReservationPropagationService(
            ActivityReservationPropagationBatchService batchService) {
            return new ActivityReservationPropagationService(batchService);
        }

        @Bean
        ActivityReservationReconciliationService activityReservationReconciliationService(
            CommunityActivityMapper activityMapper,
            CommunityCompanionRequestMapper companionMapper,
            ActivityReservationPropagationService propagationService) {
            return new ActivityReservationReconciliationService(
                activityMapper, companionMapper, propagationService);
        }

        @Bean
        ReservationCommitmentChangePublisher reservationCommitmentChangePublisher(
            ApplicationEventPublisher eventPublisher) {
            return new ReservationCommitmentChangePublisher(eventPublisher);
        }

        @Bean
        ActivityReservationChangeListener activityReservationChangeListener(
            ActivityReservationPropagationService propagationService) {
            return new ActivityReservationChangeListener(propagationService);
        }

        @Bean
        ActivityNotificationTaskStateService activityNotificationTaskStateService(
            CommunityActivityNotificationTaskMapper mapper) {
            return new ActivityNotificationTaskStateService(mapper);
        }
    }
}
