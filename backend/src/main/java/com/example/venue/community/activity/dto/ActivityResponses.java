package com.example.venue.community.activity.dto;

import com.example.venue.community.activity.entity.CommunityActivity;
import com.example.venue.community.activity.entity.CommunityActivityApplication;
import com.example.venue.community.activity.entity.CommunityActivityParticipation;
import com.example.venue.community.activity.entity.CommunityCompanionRequest;
import com.example.venue.community.activity.entity.CommunityMatchDecision;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

public final class ActivityResponses {
    private ActivityResponses() {
    }

    public static Activity activity(CommunityActivity source) {
        Activity target = new Activity();
        target.id = source.id;
        target.activityNo = source.activityNo;
        target.groupId = source.groupId;
        target.referencePostId = source.referencePostId;
        target.organizerUserId = source.organizerUserId;
        target.activityType = source.activityType;
        target.title = source.title;
        target.description = source.description;
        target.startTime = source.startTime;
        target.endTime = source.endTime;
        target.location = location(source.locationType, source.venueId, source.spaceId, source.areaCode,
            source.locationCommitmentStatus);
        target.capacityTotal = source.capacityTotal;
        target.participantCount = source.participantCount;
        target.status = source.status;
        target.versionNo = source.versionNo;
        target.publishedAt = source.publishedAt;
        target.createTime = source.createTime;
        target.updateTime = source.updateTime;
        return target;
    }

    public static CompanionRequest companion(CommunityCompanionRequest source) {
        CompanionRequest target = new CompanionRequest();
        target.id = source.id;
        target.requestNo = source.requestNo;
        target.groupId = source.groupId;
        target.activityId = source.activityId;
        target.referencePostId = source.referencePostId;
        target.ownerUserId = source.ownerUserId;
        target.activityType = source.activityType;
        target.title = source.title;
        target.description = source.description;
        target.windowStart = source.windowStart;
        target.windowEnd = source.windowEnd;
        target.location = location(source.locationType, source.venueId, source.spaceId, source.areaCode,
            source.locationCommitmentStatus);
        target.capacityTotal = source.capacityTotal;
        target.participantCount = source.participantCount;
        target.status = source.status;
        target.versionNo = source.versionNo;
        target.publishedAt = source.publishedAt;
        target.createTime = source.createTime;
        target.updateTime = source.updateTime;
        return target;
    }

    public static Application application(CommunityActivityApplication source) {
        Application target = new Application();
        target.id = source.id;
        target.applicationNo = source.applicationNo;
        target.targetType = source.targetType;
        target.targetId = source.targetId;
        target.applicantUserId = source.applicantUserId;
        target.status = source.status;
        target.reasonCode = source.reasonCode;
        target.appliedAt = source.appliedAt;
        target.decidedAt = source.decidedAt;
        target.withdrawnAt = source.withdrawnAt;
        target.versionNo = source.versionNo;
        return target;
    }

    public static Participation participation(CommunityActivityParticipation source) {
        if (source == null) {
            return null;
        }
        Participation target = new Participation();
        target.id = source.id;
        target.participationNo = source.participationNo;
        target.targetType = source.targetType;
        target.targetId = source.targetId;
        target.userId = source.userId;
        target.roleType = source.roleType;
        target.status = source.status;
        target.joinedAt = source.joinedAt;
        target.leftAt = source.leftAt;
        return target;
    }

    public static ViewerState viewerState(CommunityActivityApplication application,
                                          CommunityActivityParticipation participation) {
        return new ViewerState(
            application == null ? null : application(application),
            participation(participation)
        );
    }

    public static Match match(CommunityMatchDecision decision,
                              CommunityCompanionRequest candidate,
                              List<String> reasonCodes) {
        Match target = new Match();
        target.decisionId = decision.id;
        target.candidate = companion(candidate);
        target.ruleVersion = decision.ruleVersion;
        target.score = decision.score;
        target.reasonCodes = List.copyOf(reasonCodes);
        target.decidedAt = decision.decidedAt;
        return target;
    }

    private static Location location(String type, Long venueId, Long spaceId, String areaCode,
                                     String commitmentStatus) {
        Location target = new Location();
        target.type = type;
        target.venueId = venueId;
        target.spaceId = spaceId;
        target.areaCode = areaCode;
        target.commitmentStatus = commitmentStatus;
        return target;
    }

    public static class Activity {
        public Long id;
        public String activityNo;
        public Long groupId;
        public Long referencePostId;
        public Long organizerUserId;
        public String groupName;
        public String organizerName;
        public String activityType;
        public String title;
        public String description;
        public LocalDateTime startTime;
        public LocalDateTime endTime;
        public Location location;
        public Integer capacityTotal;
        public Integer participantCount;
        public String status;
        public Long versionNo;
        public LocalDateTime publishedAt;
        public LocalDateTime createTime;
        public LocalDateTime updateTime;
    }

    public static class CompanionRequest {
        public Long id;
        public String requestNo;
        public Long groupId;
        public Long activityId;
        public Long referencePostId;
        public Long ownerUserId;
        public String groupName;
        public String organizerName;
        public String activityType;
        public String title;
        public String description;
        public LocalDateTime windowStart;
        public LocalDateTime windowEnd;
        public Location location;
        public Integer capacityTotal;
        public Integer participantCount;
        public String status;
        public Long versionNo;
        public LocalDateTime publishedAt;
        public LocalDateTime createTime;
        public LocalDateTime updateTime;
    }

    public static class Location {
        public String type;
        public Long venueId;
        public Long spaceId;
        public String areaCode;
        public String commitmentStatus;
    }

    public static class Application {
        public Long id;
        public String applicationNo;
        public String targetType;
        public Long targetId;
        public Long applicantUserId;
        public String status;
        public String reasonCode;
        public LocalDateTime appliedAt;
        public LocalDateTime decidedAt;
        public LocalDateTime withdrawnAt;
        public Long versionNo;
    }

    public static class Participation {
        public Long id;
        public String participationNo;
        public String targetType;
        public Long targetId;
        public Long userId;
        public String roleType;
        public String status;
        public LocalDateTime joinedAt;
        public LocalDateTime leftAt;
    }

    public static class Match {
        public Long decisionId;
        public CompanionRequest candidate;
        public String ruleVersion;
        public Integer score;
        public List<String> reasonCodes;
        public LocalDateTime decidedAt;
    }

    public record MatchPage(List<Match> records, String ruleVersion) {
    }

    public record ViewerState(Application application, Participation participation) {
    }

    /** 发起者管理页指标：等待回应的申请数（服务端精确 count，不取截断集合）。 */
    public record ApplicationMetrics(long pendingApplications) {
    }

    /** V02 日期目录条目：sourceType + sourceId 类型化定位，数字 ID 相同也不互相覆盖。 */
    public static class DirectoryItem {
        public String sourceType;
        public Long sourceId;
        public Long groupId;
        public String groupName;
        public Long referencePostId;
        public Long organizerUserId;
        public String organizerName;
        public String activityType;
        public String title;
        public String description;
        public LocalDateTime startTime;
        public LocalDateTime endTime;
        public String locationType;
        public Long venueId;
        public String venueName;
        public Long spaceId;
        public String spaceName;
        public String areaCode;
        public String commitmentStatus;
        public Integer capacityTotal;
        public Integer participantCount;
        public String status;
        public Long linkedActivityId;
        public String viewerApplicationStatus;
        public String viewerParticipationRole;
        public LocalDateTime updateTime;
    }

    public record DateCount(java.time.LocalDate date, long count) {
    }

    public record DirectoryPage(List<DirectoryItem> records,
                                String nextCursor,
                                boolean hasMore,
                                long total,
                                Map<String, Long> typeCounts,
                                List<DateCount> dateCounts) {
    }
}
