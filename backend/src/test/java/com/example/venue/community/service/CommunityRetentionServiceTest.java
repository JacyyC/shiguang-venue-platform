package com.example.venue.community.service;

import com.example.venue.community.config.CommunityRetentionProperties;
import com.example.venue.community.dto.CommunityQueryRows;
import com.example.venue.community.enums.CommunityActorScope;
import com.example.venue.community.enums.CommunitySourceType;
import com.example.venue.community.mapper.CommunityCommentMapper;
import com.example.venue.community.mapper.CommunityPostMapper;
import com.example.venue.community.mapper.CommunityRetentionMapper;
import com.example.venue.system.entity.SysDeletedUserSnapshot;
import com.example.venue.system.mapper.SysDeletedUserSnapshotMapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.lang.reflect.Method;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class CommunityRetentionServiceTest {
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 7, 30, 12, 0);
    private static final Clock FIXED_CLOCK = Clock.fixed(NOW.atZone(ZONE).toInstant(), ZONE);

    private CommunityRetentionProperties properties;
    private CommunityRetentionMapper retentionMapper;
    private SysDeletedUserSnapshotMapper snapshotMapper;
    private CommunityGovernanceEventService eventService;
    private PlatformTransactionManager transactionManager;

    @BeforeEach
    void setUp() {
        properties = new CommunityRetentionProperties();
        properties.setBatchSize(100);
        properties.setMaxBatchesPerRun(5);
        retentionMapper = mock(CommunityRetentionMapper.class);
        snapshotMapper = mock(SysDeletedUserSnapshotMapper.class);
        eventService = mock(CommunityGovernanceEventService.class);
        transactionManager = mock(PlatformTransactionManager.class);
        when(transactionManager.getTransaction(any())).thenAnswer(ignored -> new SimpleTransactionStatus());
    }

    @Test
    void persistedThirtyAndOneHundredEightyDayDeadlinesPurgeAtTheExactBoundary() {
        LocalDateTime ordinaryDeletedAt = NOW.minusDays(30);
        LocalDateTime governedClosedAt = NOW.minusDays(180);
        assertThat(ordinaryDeletedAt.plusDays(30)).isEqualTo(NOW);
        assertThat(governedClosedAt.plusDays(180)).isEqualTo(NOW);

        when(retentionMapper.selectDueCommentIds(NOW, 100))
            .thenReturn(candidates(candidate(11L, "DELETED"), candidate(12L, "HIDDEN")), List.of());
        when(retentionMapper.selectDuePostIds(NOW, 100))
            .thenReturn(candidates(candidate(21L, "DELETED")), List.of());
        when(retentionMapper.selectDueGroupIds(NOW, 100))
            .thenReturn(candidates(candidate(31L, "HIDDEN")), List.of());
        when(retentionMapper.selectDueReportReasonIds(NOW, 100))
            .thenReturn(candidates(candidate(41L, "RESOLVED")), List.of());
        when(snapshotMapper.selectDeidentificationCandidates(NOW, 0L, 100)).thenReturn(List.of());

        service().runOnce();

        verify(retentionMapper).purgeComments(11L, "DELETED", NOW);
        verify(retentionMapper).purgeComments(12L, "HIDDEN", NOW);
        verify(retentionMapper).purgePosts(21L, "DELETED", NOW);
        verify(retentionMapper).purgeGroups(31L, "HIDDEN", NOW);
        verify(retentionMapper).purgeReportReasons(41L, "RESOLVED", NOW);
    }

    @Test
    void boundedBatchesStopAtTheConfiguredMaximum() {
        properties.setBatchSize(2);
        properties.setMaxBatchesPerRun(2);
        when(retentionMapper.selectDueCommentIds(NOW, 2))
            .thenReturn(
                candidates(candidate(1L, "DELETED"), candidate(2L, "DELETED")),
                candidates(candidate(3L, "DELETED"), candidate(4L, "DELETED"))
            );

        service().runOnce();

        verify(retentionMapper, times(2)).selectDueCommentIds(NOW, 2);
        verify(retentionMapper).purgeComments(1L, "DELETED", NOW);
        verify(retentionMapper).purgeComments(2L, "DELETED", NOW);
        verify(retentionMapper).purgeComments(3L, "DELETED", NOW);
        verify(retentionMapper).purgeComments(4L, "DELETED", NOW);
    }

    @Test
    void recordsScheduledEvidenceOnlyForRowsActuallyPurged() {
        when(retentionMapper.selectDueCommentIds(NOW, 100))
            .thenReturn(candidates(candidate(11L, "DELETED"), candidate(12L, "HIDDEN")), List.of());
        when(retentionMapper.purgeComments(11L, "DELETED", NOW)).thenReturn(1);
        when(retentionMapper.purgeComments(12L, "HIDDEN", NOW)).thenReturn(0);

        service().runOnce();

        ArgumentCaptor<CommunityGovernanceEventService.EventCommand> eventCaptor =
            ArgumentCaptor.forClass(CommunityGovernanceEventService.EventCommand.class);
        verify(eventService).record(eventCaptor.capture());
        CommunityGovernanceEventService.EventCommand event = eventCaptor.getValue();
        assertThat(event.eventType).isEqualTo("COMMENT_PURGED");
        assertThat(event.subjectType).isEqualTo("COMMENT");
        assertThat(event.subjectId).isEqualTo(11L);
        assertThat(event.actorUserId).isNull();
        assertThat(event.actorScope).isEqualTo(CommunityActorScope.SYSTEM);
        assertThat(event.sourceType).isEqualTo(CommunitySourceType.SCHEDULED_JOB);
        assertThat(event.fromStatus).isEqualTo("DELETED");
        assertThat(event.toStatus).isEqualTo("PURGED");
        assertThat(event.reasonCode).isEqualTo("RETENTION_EXPIRED");
        assertThat(event.idempotencyKey).isEqualTo("RETENTION:COMMENT_PURGED:11");
        assertThat(event.metadata).isEmpty();
        assertThat(event.occurredAt).isEqualTo(NOW);
    }

    @Test
    void governanceEventFailureRollsBackTheWholeContentBatch() {
        when(retentionMapper.selectDueCommentIds(NOW, 100))
            .thenReturn(candidates(candidate(11L, "DELETED")));
        when(retentionMapper.purgeComments(11L, "DELETED", NOW)).thenReturn(1);
        org.mockito.Mockito.doThrow(new IllegalStateException("event insert failed"))
            .when(eventService).record(any());

        service().runOnce();

        verify(transactionManager).rollback(any());
        verify(retentionMapper, never()).selectDuePostIds(any(), anyInt());
    }

    @Test
    void deletedUserIsDeidentifiedAtThe365DayBoundaryAfterAllReferencesMature() {
        SysDeletedUserSnapshot snapshot = snapshot(7L, 70L, NOW.minusDays(365));
        when(snapshotMapper.selectDeidentificationCandidates(NOW, 0L, 100))
            .thenReturn(List.of(snapshot));
        when(snapshotMapper.selectDeidentificationCandidates(NOW, 7L, 100))
            .thenReturn(List.of());
        when(retentionMapper.countDeidentificationBlockers(70L, NOW)).thenReturn(0);
        when(retentionMapper.countRemainingUserReferences(70L)).thenReturn(0);
        when(snapshotMapper.deidentifyIfDue(7L, NOW)).thenReturn(1);

        service().runOnce();

        LocalDateTime ordinaryRetainUntil = snapshot.deletedTime.plusDays(30);
        verify(retentionMapper).purgeGroupsOwnedByUser(70L, snapshot.deletedTime, ordinaryRetainUntil, NOW);
        verify(retentionMapper).purgePostsAuthoredByUser(70L, snapshot.deletedTime, ordinaryRetainUntil, NOW);
        verify(retentionMapper).purgeCommentsAuthoredByUser(70L, snapshot.deletedTime, ordinaryRetainUntil, NOW);
        verify(retentionMapper).deidentifyMemberships(70L, NOW);
        verify(retentionMapper).deidentifyBlocks(70L, NOW);
        verify(retentionMapper).clearReportUserReferences(70L, NOW);
        verify(retentionMapper).clearMatureGovernanceActors(70L, NOW);
        verify(snapshotMapper).deidentifyIfDue(7L, NOW);
        ArgumentCaptor<CommunityGovernanceEventService.EventCommand> eventCaptor =
            ArgumentCaptor.forClass(CommunityGovernanceEventService.EventCommand.class);
        verify(eventService).record(eventCaptor.capture());
        assertThat(eventCaptor.getValue()).satisfies(event -> {
            assertThat(event.eventType).isEqualTo("USER_DEIDENTIFIED");
            assertThat(event.subjectType).isEqualTo("USER");
            assertThat(event.subjectId).isEqualTo(70L);
            assertThat(event.actorUserId).isNull();
            assertThat(event.actorScope).isEqualTo(CommunityActorScope.SYSTEM);
            assertThat(event.sourceType).isEqualTo(CommunitySourceType.SCHEDULED_JOB);
            assertThat(event.fromStatus).isEqualTo("DELETED");
            assertThat(event.toStatus).isEqualTo("DEIDENTIFIED");
            assertThat(event.idempotencyKey).isEqualTo("RETENTION:USER_DEIDENTIFIED:7");
            assertThat(event.metadata).isEmpty();
        });
    }

    @Test
    void deidentificationEventFailureRollsBackTheSnapshotTransaction() {
        SysDeletedUserSnapshot snapshot = snapshot(10L, 100L, NOW.minusDays(365));
        when(snapshotMapper.selectDeidentificationCandidates(NOW, 0L, 100))
            .thenReturn(List.of(snapshot));
        when(snapshotMapper.selectDeidentificationCandidates(NOW, 10L, 100))
            .thenReturn(List.of());
        when(retentionMapper.countDeidentificationBlockers(100L, NOW)).thenReturn(0);
        when(retentionMapper.countRemainingUserReferences(100L)).thenReturn(0);
        when(snapshotMapper.deidentifyIfDue(10L, NOW)).thenReturn(1);
        org.mockito.Mockito.doThrow(new IllegalStateException("event insert failed"))
            .when(eventService).record(any());

        service().runOnce();

        verify(snapshotMapper).deidentifyIfDue(10L, NOW);
        verify(transactionManager).rollback(any());
    }

    @Test
    void futureContentOrGovernanceRetentionBlocksSnapshotDeidentification() {
        SysDeletedUserSnapshot snapshot = snapshot(8L, 80L, NOW.minusDays(365));
        when(snapshotMapper.selectDeidentificationCandidates(NOW, 0L, 100))
            .thenReturn(List.of(snapshot));
        when(snapshotMapper.selectDeidentificationCandidates(NOW, 8L, 100))
            .thenReturn(List.of());
        when(retentionMapper.countDeidentificationBlockers(80L, NOW)).thenReturn(1);

        service().runOnce();

        verify(retentionMapper, never()).purgePostsAuthoredByUser(any(), any(), any(), any());
        verify(snapshotMapper, never()).deidentifyIfDue(any(), any());
    }

    @Test
    void remainingReferenceRollsBackAndDoesNotMarkTheSnapshot() {
        SysDeletedUserSnapshot snapshot = snapshot(9L, 90L, NOW.minusDays(365));
        when(snapshotMapper.selectDeidentificationCandidates(NOW, 0L, 100))
            .thenReturn(List.of(snapshot));
        when(snapshotMapper.selectDeidentificationCandidates(NOW, 9L, 100))
            .thenReturn(List.of());
        when(retentionMapper.countDeidentificationBlockers(90L, NOW)).thenReturn(0);
        when(retentionMapper.countRemainingUserReferences(90L)).thenReturn(1);

        service().runOnce();

        verify(snapshotMapper, never()).deidentifyIfDue(any(), any());
        ArgumentCaptor<TransactionStatus> statusCaptor = ArgumentCaptor.forClass(TransactionStatus.class);
        verify(transactionManager).commit(statusCaptor.capture());
        assertThat(statusCaptor.getValue().isRollbackOnly()).isTrue();
    }

    @Test
    void mapperSqlUsesInclusiveDeadlinesAndNeverPhysicallyDeletesGovernanceEvidence() throws Exception {
        assertThat(selectSql("selectDueGroupIds"))
            .contains("status IN ('DELETED', 'HIDDEN')", "retain_until <= #{now}");
        assertThat(selectSql("selectDuePostIds"))
            .contains("status IN ('DELETED', 'HIDDEN')", "retain_until <= #{now}");
        assertThat(selectSql("selectDueCommentIds"))
            .contains("status IN ('DELETED', 'HIDDEN')", "retain_until <= #{now}");
        assertThat(selectSql("selectDueReportReasonIds")).contains("reason_retain_until <= #{now}");
        assertThat(updateSql(method("purgePosts")))
            .contains("deletion_reason = COALESCE(deletion_reason, 'MODERATION_RETENTION_EXPIRED')");
        assertThat(updateSql(method("purgeComments")))
            .contains("deletion_reason = COALESCE(deletion_reason, 'MODERATION_RETENTION_EXPIRED')");

        String blockerSql = selectSql("countDeidentificationBlockers");
        assertThat(blockerSql).contains(
            "owned_group.retain_until > #{now}",
            "authored_post.retain_until > #{now}",
            "authored_comment.retain_until > #{now}",
            "protected_report.minimal_retain_until > #{now}",
            "protected_event.retain_until > #{now}");

        assertThat(Arrays.stream(CommunityRetentionMapper.class.getDeclaredMethods())
            .filter(method -> method.isAnnotationPresent(Update.class))
            .map(this::updateSql)
            .map(sql -> sql.toUpperCase(Locale.ROOT)))
            .allSatisfy(sql -> assertThat(sql).doesNotContain("DELETE FROM"));
        assertThat(Arrays.stream(CommunityRetentionMapper.class.getDeclaredMethods())
            .map(Method::getName))
            .doesNotContain("deleteGovernanceEvents", "purgeGovernanceEvents");
    }

    @Test
    void groupRetentionCascadesToPostBodiesWithoutShorteningLongerDeadlines() {
        assertDeadlineSafeCascade(
            cascadeUpdateSql("UPDATE COMMUNITY_POST", "GROUP_ID"), "group -> posts");
    }

    @Test
    void groupRetentionCascadesToCommentBodiesWithoutShorteningLongerDeadlines() {
        assertDeadlineSafeCascade(
            cascadeUpdateSql("UPDATE COMMUNITY_COMMENT", "GROUP_ID"), "group -> comments");
    }

    @Test
    void postRetentionCascadesToCommentBodiesWithoutShorteningLongerDeadlines() {
        assertDeadlineSafeCascade(
            cascadeUpdateSql("UPDATE COMMUNITY_COMMENT", "POST_ID"), "post -> comments");
    }

    @Test
    void snapshotMapperKeepsInternalIdButRemovesDirectIdentifiersOnlyWhen365DaysAreDue() throws Exception {
        Method selectMethod = SysDeletedUserSnapshotMapper.class.getMethod(
            "selectDeidentificationCandidates", LocalDateTime.class, long.class, int.class);
        String selectSql = String.join(" ", selectMethod.getAnnotation(Select.class).value());
        assertThat(selectSql).contains(
            "DATE_ADD(deleted_time, INTERVAL 365 DAY)",
            "<= #{now}",
            "deidentified_at IS NULL");

        Method updateMethod = SysDeletedUserSnapshotMapper.class.getMethod(
            "deidentifyIfDue", Long.class, LocalDateTime.class);
        String updateSql = String.join(" ", updateMethod.getAnnotation(Update.class).value());
        assertThat(updateSql).contains(
            "username = CONCAT('deidentified-', id)",
            "nickname = NULL",
            "phone = NULL",
            "email = NULL",
            "avatar = NULL",
            "deleted_by_username = NULL",
            "deidentified_at = #{now}");
        assertThat(updateSql).doesNotContain("user_id = NULL");
    }

    private CommunityRetentionService service() {
        return new CommunityRetentionService(
            properties, retentionMapper, snapshotMapper, eventService, transactionManager, FIXED_CLOCK);
    }

    private CommunityQueryRows.RetentionCandidateRow candidate(Long id, String status) {
        CommunityQueryRows.RetentionCandidateRow candidate = new CommunityQueryRows.RetentionCandidateRow();
        candidate.id = id;
        candidate.status = status;
        return candidate;
    }

    private List<CommunityQueryRows.RetentionCandidateRow> candidates(
        CommunityQueryRows.RetentionCandidateRow... candidates) {
        return List.of(candidates);
    }

    private SysDeletedUserSnapshot snapshot(Long id, Long userId, LocalDateTime deletedTime) {
        SysDeletedUserSnapshot snapshot = new SysDeletedUserSnapshot();
        snapshot.id = id;
        snapshot.userId = userId;
        snapshot.deletedTime = deletedTime;
        snapshot.deidentifyAfter = deletedTime.plusDays(365);
        return snapshot;
    }

    private String selectSql(String methodName) {
        Method method = method(methodName);
        return String.join(" ", method.getAnnotation(Select.class).value());
    }

    private Method method(String methodName) {
        return Arrays.stream(CommunityRetentionMapper.class.getDeclaredMethods())
            .filter(candidate -> candidate.getName().equals(methodName))
            .findFirst()
            .orElseThrow();
    }

    private String updateSql(Method method) {
        return String.join(" ", method.getAnnotation(Update.class).value());
    }

    private String cascadeUpdateSql(String targetTable, String parentKey) {
        return List.of(
                CommunityRetentionMapper.class,
                CommunityPostMapper.class,
                CommunityCommentMapper.class).stream()
            .flatMap(type -> Arrays.stream(type.getDeclaredMethods()))
            .filter(method -> method.isAnnotationPresent(Update.class))
            .map(this::updateSql)
            .map(sql -> sql.replaceAll("\\s+", " ").toUpperCase(Locale.ROOT))
            .filter(sql -> sql.contains(targetTable))
            .filter(sql -> sql.contains(parentKey))
            .filter(sql -> sql.contains("RETAIN_UNTIL"))
            .findFirst()
            .orElseThrow(() -> new AssertionError(
                "missing retention cascade SQL for " + targetTable + " by " + parentKey));
    }

    private void assertDeadlineSafeCascade(String sql, String relationship) {
        assertThat(sql)
            .as("%s must transition or purge child content", relationship)
            .contains("STATUS");
        assertThat(sql)
            .as("%s must preserve an existing report-driven longer deadline", relationship)
            .satisfiesAnyOf(
                candidate -> assertThat(candidate).contains("GREATEST"),
                candidate -> assertThat(candidate).contains("CASE"),
                candidate -> assertThat(candidate).contains("RETAIN_UNTIL &LT;"));
    }
}
