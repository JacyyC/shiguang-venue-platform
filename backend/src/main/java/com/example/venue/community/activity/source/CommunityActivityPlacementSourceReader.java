package com.example.venue.community.activity.source;

import com.example.venue.common.BusinessException;
import com.example.venue.community.activity.entity.CommunityActivity;
import com.example.venue.community.activity.mapper.CommunityActivityMapper;
import com.example.venue.community.entity.CommunityPost;
import com.example.venue.community.enums.CommunityContentStatus;
import com.example.venue.community.mapper.CommunityPostMapper;
import com.example.venue.community.service.CommunityAccessService;
import com.example.venue.placement.source.PlacementSourceReader;
import com.example.venue.placement.source.PlacementSourceSnapshot;
import com.example.venue.placement.source.PlacementSourceType;
import com.example.venue.security.SecurityContext;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Set;

/**
 * 社区活动域 Source Reader。复用 CommunityAccessService 可见性合同。来源合同：
 * 状态为当前已发布态 PUBLISHED/FULL、end_time &gt; now；小组为 ACTIVE；引用帖子存在时仍为同组
 * PUBLISHED；MEMBERS_ONLY 要求当前用户为 ACTIVE 成员；当前用户与组织者、小组 owner、引用作者之间
 * 无双向屏蔽；用户具备 community:access。深链固定为 /activities/{id}。不返回参与者、申请、
 * 联系方式、内部事件或治理字段。
 */
@Component
public class CommunityActivityPlacementSourceReader implements PlacementSourceReader {

    private static final Set<String> OPEN_STATUSES = Set.of("PUBLISHED", "FULL");
    private static final int TITLE_MAX_LENGTH = 128;
    private static final int SUMMARY_MAX_LENGTH = 200;

    private final CommunityActivityMapper activityMapper;
    private final CommunityAccessService accessService;
    private final CommunityPostMapper postMapper;

    public CommunityActivityPlacementSourceReader(CommunityActivityMapper activityMapper,
                                                  CommunityAccessService accessService,
                                                  CommunityPostMapper postMapper) {
        this.activityMapper = activityMapper;
        this.accessService = accessService;
        this.postMapper = postMapper;
    }

    @Override
    public PlacementSourceType sourceType() {
        return PlacementSourceType.COMMUNITY_ACTIVITY;
    }

    @Override
    public PlacementSourceSnapshot readForPreview(Long sourceId) {
        CommunityActivity activity = loadOpenActivity(sourceId);
        if (activity == null) {
            return null;
        }
        if (!isGroupActive(activity.groupId)) {
            return null;
        }
        if (!isReferencePostValid(activity)) {
            return null;
        }
        return toSnapshot(activity);
    }

    @Override
    public PlacementSourceSnapshot readForUser(Long sourceId, Long userId) {
        if (userId == null) {
            return null;
        }
        if (!SecurityContext.hasAuthority("community:access")) {
            return null;
        }
        CommunityActivity activity = loadOpenActivity(sourceId);
        if (activity == null) {
            return null;
        }
        try {
            accessService.requireActivityContext(activity.groupId, activity.referencePostId, userId);
            accessService.requireNoBlock(userId, activity.organizerUserId);
        } catch (BusinessException exception) {
            return null;
        }
        return toSnapshot(activity);
    }

    private CommunityActivity loadOpenActivity(Long sourceId) {
        if (sourceId == null || sourceId <= 0) {
            return null;
        }
        CommunityActivity activity = activityMapper.selectById(sourceId);
        if (activity == null || !OPEN_STATUSES.contains(activity.status)) {
            return null;
        }
        LocalDateTime now = LocalDateTime.now();
        if (activity.endTime == null || !activity.endTime.isAfter(now)) {
            return null;
        }
        return activity;
    }

    private boolean isGroupActive(Long groupId) {
        if (groupId == null) {
            return false;
        }
        try {
            accessService.requireActiveGroup(groupId);
            return true;
        } catch (BusinessException exception) {
            return false;
        }
    }

    private boolean isReferencePostValid(CommunityActivity activity) {
        if (activity.referencePostId == null) {
            return true;
        }
        CommunityPost post = postMapper.selectById(activity.referencePostId);
        return post != null
            && activity.groupId.equals(post.groupId)
            && CommunityContentStatus.PUBLISHED.matches(post.status);
    }

    private PlacementSourceSnapshot toSnapshot(CommunityActivity activity) {
        return new PlacementSourceSnapshot(
            PlacementSourceType.COMMUNITY_ACTIVITY,
            activity.id,
            activity.versionNo == null ? null : activity.versionNo.toString(),
            truncate(activity.title, TITLE_MAX_LENGTH),
            truncate(activity.description, SUMMARY_MAX_LENGTH),
            null,
            activity.startTime,
            activity.endTime,
            "/activities/" + activity.id
        );
    }

    private String truncate(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        if (trimmed.length() <= maxLength) {
            return trimmed;
        }
        return trimmed.substring(0, maxLength);
    }
}
