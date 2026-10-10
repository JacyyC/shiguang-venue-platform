package com.example.venue.community.service;

import com.example.venue.community.config.CommunityRetentionProperties;
import com.example.venue.community.dto.CommunityQueryRows;
import com.example.venue.community.enums.CommunityActorScope;
import com.example.venue.community.enums.CommunityContentStatus;
import com.example.venue.community.enums.CommunityGroupStatus;
import com.example.venue.community.enums.CommunitySourceType;
import com.example.venue.community.mapper.CommunityRetentionMapper;
import com.example.venue.system.entity.SysDeletedUserSnapshot;
import com.example.venue.system.mapper.SysDeletedUserSnapshotMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class CommunityRetentionService {
    static final int ORDINARY_CONTENT_RETENTION_DAYS = 30;

    private static final Logger log = LoggerFactory.getLogger(CommunityRetentionService.class);

    private final CommunityRetentionProperties properties;
    private final CommunityRetentionMapper retentionMapper;
    private final SysDeletedUserSnapshotMapper deletedUserSnapshotMapper;
    private final CommunityGovernanceEventService eventService;
    private final Clock clock;
    private final TransactionTemplate batchTransactionTemplate;
    private final AtomicBoolean running = new AtomicBoolean();

    @Autowired
    public CommunityRetentionService(CommunityRetentionProperties properties,
                                     CommunityRetentionMapper retentionMapper,
                                     SysDeletedUserSnapshotMapper deletedUserSnapshotMapper,
                                     CommunityGovernanceEventService eventService,
                                     PlatformTransactionManager transactionManager) {
        this(properties, retentionMapper, deletedUserSnapshotMapper, eventService,
            transactionManager, Clock.systemDefaultZone());
    }

    CommunityRetentionService(CommunityRetentionProperties properties,
                              CommunityRetentionMapper retentionMapper,
                              SysDeletedUserSnapshotMapper deletedUserSnapshotMapper,
                              CommunityGovernanceEventService eventService,
                              PlatformTransactionManager transactionManager,
                              Clock clock) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.retentionMapper = Objects.requireNonNull(retentionMapper, "retentionMapper");
        this.deletedUserSnapshotMapper = Objects.requireNonNull(deletedUserSnapshotMapper,
            "deletedUserSnapshotMapper");
        this.eventService = Objects.requireNonNull(eventService, "eventService");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.batchTransactionTemplate = new TransactionTemplate(
            Objects.requireNonNull(transactionManager, "transactionManager"));
        this.batchTransactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public void runOnce() {
        if (!running.compareAndSet(false, true)) {
            log.info("Community retention run skipped because another run is active");
            return;
        }

        try {
            LocalDateTime now = LocalDateTime.now(clock);
            if (!processDueContent("community_comment", "COMMENT", "COMMENT_PURGED",
                null, CommunityContentStatus.PURGED.name(), now, retentionMapper::selectDueCommentIds,
                retentionMapper::purgeComments)) {
                return;
            }
            if (!processDueContent("community_post", "POST", "POST_PURGED",
                null, CommunityContentStatus.PURGED.name(), now, retentionMapper::selectDuePostIds,
                retentionMapper::purgePosts)) {
                return;
            }
            if (!processDueContent("community_group", "GROUP", "GROUP_PURGED",
                null, CommunityGroupStatus.PURGED.name(), now, retentionMapper::selectDueGroupIds,
                retentionMapper::purgeGroups)) {
                return;
            }
            if (!processDueContent("community_report_reason", "REPORT", "REPORT_REASON_PURGED",
                "REASON_RETAINED", "REASON_PURGED", now, retentionMapper::selectDueReportReasonIds,
                retentionMapper::purgeReportReasons)) {
                return;
            }
            processDeletedUserSnapshots(now);
        } finally {
            running.set(false);
        }
    }

    private boolean processDueContent(String contentType,
                                      String subjectType,
                                      String eventType,
                                      String eventFromStatus,
                                      String eventToStatus,
                                      LocalDateTime now,
                                      CandidateSelector selector,
                                      ContentPurger purger) {
        for (int batchNumber = 1; batchNumber <= properties.getMaxBatchesPerRun(); batchNumber++) {
            try {
                List<CommunityQueryRows.RetentionCandidateRow> candidates =
                    selector.select(now, properties.getBatchSize());
                if (candidates == null || candidates.isEmpty()) {
                    return true;
                }
                List<CommunityQueryRows.RetentionCandidateRow> batch = List.copyOf(candidates);
                batchTransactionTemplate.executeWithoutResult(status -> {
                    for (CommunityQueryRows.RetentionCandidateRow candidate : batch) {
                        if (candidate == null || candidate.id == null || candidate.status == null) {
                            continue;
                        }
                        int affected = purger.purge(candidate.id, candidate.status, now);
                        if (affected == 1) {
                            recordScheduledEvent(
                                eventType,
                                subjectType,
                                candidate.id,
                                eventFromStatus == null ? candidate.status : eventFromStatus,
                                eventToStatus,
                                "RETENTION:" + eventType + ":" + candidate.id,
                                now
                            );
                        } else if (affected != 0) {
                            throw new IllegalStateException("Unexpected retention update count: " + affected);
                        }
                    }
                });
            } catch (RuntimeException ex) {
                log.error("Community retention failed contentType={} batch={} exceptionClass={}",
                    contentType, batchNumber, ex.getClass().getName());
                return false;
            }
        }
        return true;
    }

    private void processDeletedUserSnapshots(LocalDateTime now) {
        long afterId = 0L;
        for (int batchNumber = 1; batchNumber <= properties.getMaxBatchesPerRun(); batchNumber++) {
            List<SysDeletedUserSnapshot> candidates;
            try {
                candidates = deletedUserSnapshotMapper.selectDeidentificationCandidates(
                    now, afterId, properties.getBatchSize());
            } catch (RuntimeException ex) {
                log.error("Deleted user deidentification selection failed batch={} exceptionClass={}",
                    batchNumber, ex.getClass().getName());
                return;
            }
            if (candidates == null || candidates.isEmpty()) {
                return;
            }
            afterId = candidates.get(candidates.size() - 1).id;
            for (SysDeletedUserSnapshot candidate : candidates) {
                if (!deidentifySnapshot(candidate, now)) {
                    continue;
                }
            }
        }
    }

    private boolean deidentifySnapshot(SysDeletedUserSnapshot snapshot, LocalDateTime now) {
        if (snapshot == null || snapshot.id == null || snapshot.userId == null || snapshot.deletedTime == null) {
            return false;
        }
        try {
            Boolean completed = batchTransactionTemplate.execute(status -> {
                if (retentionMapper.countDeidentificationBlockers(snapshot.userId, now) > 0) {
                    return false;
                }
                LocalDateTime ordinaryRetainUntil = snapshot.deletedTime.plusDays(ORDINARY_CONTENT_RETENTION_DAYS);
                retentionMapper.purgeGroupsOwnedByUser(
                    snapshot.userId, snapshot.deletedTime, ordinaryRetainUntil, now);
                retentionMapper.purgePostsAuthoredByUser(
                    snapshot.userId, snapshot.deletedTime, ordinaryRetainUntil, now);
                retentionMapper.purgeCommentsAuthoredByUser(
                    snapshot.userId, snapshot.deletedTime, ordinaryRetainUntil, now);
                retentionMapper.deidentifyMemberships(snapshot.userId, now);
                retentionMapper.clearMembershipReviewer(snapshot.userId, now);
                retentionMapper.clearPostActorReferences(snapshot.userId, now);
                retentionMapper.clearCommentActorReferences(snapshot.userId, now);
                retentionMapper.deidentifyBlocks(snapshot.userId, now);
                retentionMapper.clearReportUserReferences(snapshot.userId, now);
                retentionMapper.clearMatureGovernanceActors(snapshot.userId, now);

                if (retentionMapper.countRemainingUserReferences(snapshot.userId) > 0
                    || deletedUserSnapshotMapper.deidentifyIfDue(snapshot.id, now) != 1) {
                    status.setRollbackOnly();
                    return false;
                }
                recordScheduledEvent(
                    "USER_DEIDENTIFIED",
                    "USER",
                    snapshot.userId,
                    "DELETED",
                    "DEIDENTIFIED",
                    "RETENTION:USER_DEIDENTIFIED:" + snapshot.id,
                    now
                );
                return true;
            });
            return Boolean.TRUE.equals(completed);
        } catch (RuntimeException ex) {
            log.error("Deleted user deidentification failed exceptionClass={}", ex.getClass().getName());
            return false;
        }
    }

    private void recordScheduledEvent(String eventType,
                                      String subjectType,
                                      Long subjectId,
                                      String fromStatus,
                                      String toStatus,
                                      String idempotencyKey,
                                      LocalDateTime occurredAt) {
        CommunityGovernanceEventService.EventCommand command =
            new CommunityGovernanceEventService.EventCommand();
        command.eventType = eventType;
        command.subjectType = subjectType;
        command.subjectId = subjectId;
        command.actorScope = CommunityActorScope.SYSTEM;
        command.sourceType = CommunitySourceType.SCHEDULED_JOB;
        command.fromStatus = fromStatus;
        command.toStatus = toStatus;
        command.reasonCode = "RETENTION_EXPIRED";
        command.resultStatus = "SUCCEEDED";
        command.idempotencyKey = idempotencyKey;
        command.metadata = Map.of();
        command.occurredAt = occurredAt;
        eventService.record(command);
    }

    @FunctionalInterface
    private interface CandidateSelector {
        List<CommunityQueryRows.RetentionCandidateRow> select(LocalDateTime now, int limit);
    }

    @FunctionalInterface
    private interface ContentPurger {
        int purge(Long id, String expectedStatus, LocalDateTime now);
    }
}
