package com.example.venue.community.activity.service;

import com.example.venue.community.activity.entity.CommunityActivity;
import com.example.venue.community.activity.entity.CommunityActivityParticipation;
import com.example.venue.community.activity.entity.CommunityCompanionRequest;
import com.example.venue.community.activity.mapper.CommunityActivityMapper;
import com.example.venue.community.activity.mapper.CommunityActivityParticipationMapper;
import com.example.venue.community.activity.mapper.CommunityCompanionRequestMapper;
import com.example.venue.reservation.service.ReservationCommitmentService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Service
public class ActivityReservationPropagationBatchService {
    private final CommunityActivityMapper activityMapper;
    private final CommunityCompanionRequestMapper companionMapper;
    private final CommunityActivityParticipationMapper participationMapper;
    private final ReservationCommitmentService commitmentService;
    private final ActivityStateEventService eventService;
    private final ActivityNotificationService notificationService;

    public ActivityReservationPropagationBatchService(
        CommunityActivityMapper activityMapper,
        CommunityCompanionRequestMapper companionMapper,
        CommunityActivityParticipationMapper participationMapper,
        ReservationCommitmentService commitmentService,
        ActivityStateEventService eventService,
        ActivityNotificationService notificationService
    ) {
        this.activityMapper = activityMapper;
        this.companionMapper = companionMapper;
        this.participationMapper = participationMapper;
        this.commitmentService = commitmentService;
        this.eventService = eventService;
        this.notificationService = notificationService;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public BatchResult activities(Long reservationId, String reasonCode, long afterId, int batchSize) {
        List<CommunityActivity> rows = activityMapper.findConfirmedByReservationForUpdate(
            reservationId, afterId, batchSize);
        ReservationCommitmentService.CommitmentSnapshot snapshot = commitmentService.inspect(reservationId);
        for (CommunityActivity activity : rows) {
            if (!snapshot.covers(activity.venueId, activity.spaceId, activity.startTime,
                activity.endTime, activity.capacityTotal)) {
                degradeActivity(activity, reasonCode, snapshot.reasonCode());
            }
        }
        return batch(rows.isEmpty() ? afterId : rows.get(rows.size() - 1).id, rows.size());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public BatchResult companions(Long reservationId, String reasonCode, long afterId, int batchSize) {
        List<CommunityCompanionRequest> rows = companionMapper.findConfirmedByReservationForUpdate(
            reservationId, afterId, batchSize);
        ReservationCommitmentService.CommitmentSnapshot snapshot = commitmentService.inspect(reservationId);
        for (CommunityCompanionRequest request : rows) {
            if (!snapshot.covers(request.venueId, request.spaceId, request.windowStart,
                request.windowEnd, request.capacityTotal)) {
                degradeCompanion(request, reasonCode, snapshot.reasonCode());
            }
        }
        return batch(rows.isEmpty() ? afterId : rows.get(rows.size() - 1).id, rows.size());
    }

    private void degradeActivity(CommunityActivity activity, String reasonCode, String snapshotReason) {
        LocalDateTime now = LocalDateTime.now();
        if (activityMapper.degradeCommitment(activity.id, activity.commitmentVersion, now) != 1) {
            return;
        }
        long nextCommitmentVersion = activity.commitmentVersion + 1;
        String stableReason = reasonCode == null ? "RESERVATION_CHANGED" : reasonCode;
        eventService.record("ACTIVITY_LOCATION_DEGRADED", "LOCATION_COMMITMENT", activity.id,
            null, "RESERVATION", "CONFIRMED", "DEGRADED", stableReason,
            commitmentEventKey("ACTIVITY", activity.id, nextCommitmentVersion, stableReason),
            Map.of("reservationId", activity.reservationId,
                "commitmentVersion", nextCommitmentVersion,
                "reservationReason", snapshotReason == null ? "COMMITMENT_NO_LONGER_COVERS" : snapshotReason));
        notifyParticipants("ACTIVITY", activity.id, activity.capacityTotal,
            "活动地点确认已失效", "关联预约发生变化，活动地点已降级为待确认。",
            "ACTIVITY_LOCATION_DEGRADED", nextCommitmentVersion);
    }

    private void degradeCompanion(CommunityCompanionRequest request,
                                  String reasonCode,
                                  String snapshotReason) {
        LocalDateTime now = LocalDateTime.now();
        if (companionMapper.degradeCommitment(request.id, request.commitmentVersion, now) != 1) {
            return;
        }
        long nextCommitmentVersion = request.commitmentVersion + 1;
        String stableReason = reasonCode == null ? "RESERVATION_CHANGED" : reasonCode;
        eventService.record("COMPANION_LOCATION_DEGRADED", "LOCATION_COMMITMENT", request.id,
            null, "RESERVATION", "CONFIRMED", "DEGRADED", stableReason,
            commitmentEventKey("COMPANION_REQUEST", request.id, nextCommitmentVersion, stableReason),
            Map.of("reservationId", request.reservationId,
                "commitmentVersion", nextCommitmentVersion,
                "reservationReason", snapshotReason == null ? "COMMITMENT_NO_LONGER_COVERS" : snapshotReason));
        notifyParticipants("COMPANION_REQUEST", request.id, request.capacityTotal,
            "找搭子地点确认已失效", "关联预约发生变化，找搭子地点已降级为待确认。",
            "COMPANION_LOCATION_DEGRADED", nextCommitmentVersion);
    }

    private void notifyParticipants(String targetType,
                                    Long targetId,
                                    int capacity,
                                    String title,
                                    String content,
                                    String eventType,
                                    long commitmentVersion) {
        List<CommunityActivityParticipation> participants = participationMapper.listActive(
            targetType, targetId, null, capacity + 1);
        for (CommunityActivityParticipation participant : participants) {
            notificationService.enqueue(eventType,
                eventType + ":" + targetId + ":" + commitmentVersion + ":" + participant.userId,
                participant.userId, title, content, targetType, targetId);
        }
    }

    private String commitmentEventKey(String targetType, Long targetId, long version, String reason) {
        return targetType + ":LOCATION_DEGRADED:" + targetId + ":" + version + ":" + reason;
    }

    private BatchResult batch(long lastId, int size) {
        return new BatchResult(lastId, size);
    }

    public record BatchResult(long lastId, int size) {
    }
}
