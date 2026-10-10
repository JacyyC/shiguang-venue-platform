package com.example.venue.usertime.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.example.venue.common.BusinessException;
import com.example.venue.common.PageQuery;
import com.example.venue.common.PageResult;
import com.example.venue.community.activity.entity.CommunityActivity;
import com.example.venue.community.activity.entity.CommunityActivityApplication;
import com.example.venue.community.activity.entity.CommunityActivityParticipation;
import com.example.venue.community.activity.entity.CommunityCompanionRequest;
import com.example.venue.community.activity.mapper.CommunityActivityApplicationMapper;
import com.example.venue.community.activity.mapper.CommunityActivityMapper;
import com.example.venue.community.activity.mapper.CommunityActivityParticipationMapper;
import com.example.venue.community.activity.mapper.CommunityCompanionRequestMapper;
import com.example.venue.reservation.entity.Reservation;
import com.example.venue.reservation.mapper.ReservationMapper;
import com.example.venue.security.SecurityContext;
import com.example.venue.usertime.dto.MeTimeCounts;
import com.example.venue.usertime.dto.MeTimeItem;
import com.example.venue.venue.entity.Venue;
import com.example.venue.venue.mapper.VenueMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 「我的时间」只读聚合：合并场地预约与活动/搭子参与、待确认申请两个权威域。
 * <p>逐源有界拉取（每源上限见常量），内存合并排序后分页；同一 target 的参与记录优先于申请记录，
 * 避免同一段时间重复计数。状态一律透传原始值，不做跨域归一。</p>
 */
@Service
public class MeTimeService {
    private static final int RESERVATION_CAP = 200;
    private static final int PARTICIPATION_CAP = 200;
    private static final int APPLICATION_CAP = 100;

    private final ReservationMapper reservationMapper;
    private final VenueMapper venueMapper;
    private final CommunityActivityParticipationMapper participationMapper;
    private final CommunityActivityApplicationMapper applicationMapper;
    private final CommunityActivityMapper activityMapper;
    private final CommunityCompanionRequestMapper companionMapper;

    public MeTimeService(ReservationMapper reservationMapper,
                         VenueMapper venueMapper,
                         CommunityActivityParticipationMapper participationMapper,
                         CommunityActivityApplicationMapper applicationMapper,
                         CommunityActivityMapper activityMapper,
                         CommunityCompanionRequestMapper companionMapper) {
        this.reservationMapper = reservationMapper;
        this.venueMapper = venueMapper;
        this.participationMapper = participationMapper;
        this.applicationMapper = applicationMapper;
        this.activityMapper = activityMapper;
        this.companionMapper = companionMapper;
    }

    @Transactional(readOnly = true)
    public PageResult<MeTimeItem> myTime(PageQuery query) {
        Long userId = SecurityContext.currentUserId();
        if (userId == null) {
            throw BusinessException.forbidden("未登录");
        }
        List<Reservation> reservations = reservationMapper.selectList(new QueryWrapper<Reservation>()
            .eq("user_id", userId).eq("deleted", 0)
            .orderByDesc("start_time").orderByDesc("id")
            .last("limit " + RESERVATION_CAP));
        List<CommunityActivityParticipation> participations = participationMapper.selectList(
            new QueryWrapper<CommunityActivityParticipation>()
                .eq("user_id", userId).eq("status", "ACTIVE")
                .orderByDesc("id")
                .last("limit " + PARTICIPATION_CAP));
        List<CommunityActivityApplication> applications = applicationMapper.selectList(
            new QueryWrapper<CommunityActivityApplication>()
                .eq("applicant_user_id", userId).eq("status", "PENDING")
                .orderByDesc("id")
                .last("limit " + APPLICATION_CAP));

        Map<Long, CommunityActivity> activities = new HashMap<>();
        Map<Long, CommunityCompanionRequest> companions = new HashMap<>();
        Set<Long> venueIds = new HashSet<>();
        for (Reservation reservation : reservations) {
            if (reservation.venueId != null) venueIds.add(reservation.venueId);
        }
        for (CommunityActivityParticipation participation : participations) {
            if ("ACTIVITY".equals(participation.targetType)) {
                activities.put(participation.targetId, null);
            } else if ("COMPANION_REQUEST".equals(participation.targetType)) {
                companions.put(participation.targetId, null);
            }
        }
        for (CommunityActivityApplication application : applications) {
            if ("ACTIVITY".equals(application.targetType)) {
                activities.put(application.targetId, null);
            } else if ("COMPANION_REQUEST".equals(application.targetType)) {
                companions.put(application.targetId, null);
            }
        }
        if (!activities.isEmpty()) {
            activityMapper.selectBatchIds(activities.keySet()).forEach(activity -> {
                activities.put(activity.id, activity);
                if (activity.venueId != null) venueIds.add(activity.venueId);
            });
        }
        if (!companions.isEmpty()) {
            companionMapper.selectBatchIds(companions.keySet()).forEach(companion -> {
                companions.put(companion.id, companion);
                if (companion.venueId != null) venueIds.add(companion.venueId);
            });
        }
        Map<Long, String> venueNames = new HashMap<>();
        if (!venueIds.isEmpty()) {
            venueMapper.selectBatchIds(venueIds).forEach(venue -> venueNames.put(venue.id, venue.name));
        }

        List<MeTimeItem> items = new ArrayList<>(reservations.size() + participations.size() + applications.size());
        for (Reservation reservation : reservations) {
            items.add(reservationItem(reservation, venueNames));
        }
        Set<String> seenTargets = new HashSet<>();
        for (CommunityActivityParticipation participation : participations) {
            MeTimeItem item = participationItem(participation, activities, companions, venueNames);
            if (item == null) continue;
            seenTargets.add(participation.targetType + ":" + participation.targetId);
            items.add(item);
        }
        for (CommunityActivityApplication application : applications) {
            // 已有参与记录的目标不再以“待确认申请”重复出现
            if (seenTargets.contains(application.targetType + ":" + application.targetId)) continue;
            MeTimeItem item = applicationItem(application, activities, companions, venueNames);
            if (item == null) continue;
            items.add(item);
        }
        items.sort(Comparator
            .comparing((MeTimeItem item) -> item.startTime, Comparator.nullsLast(Comparator.reverseOrder()))
            .thenComparing(item -> item.sourceType)
            .thenComparing(item -> item.sourceId));

        long total = items.size();
        int fromIndex = (int) Math.min((query.getPageNum() - 1) * query.getPageSize(), total);
        int toIndex = (int) Math.min(fromIndex + query.getPageSize(), total);
        return PageResult.of(items.subList(fromIndex, toIndex), total, query.getPageNum(), query.getPageSize());
    }

    @Transactional(readOnly = true)
    public MeTimeCounts counts() {
        Long userId = SecurityContext.currentUserId();
        if (userId == null) {
            throw BusinessException.forbidden("未登录");
        }
        Long pendingApplications = applicationMapper.selectCount(new QueryWrapper<CommunityActivityApplication>()
            .eq("applicant_user_id", userId).eq("status", "PENDING"));
        Long activeParticipations = participationMapper.selectCount(new QueryWrapper<CommunityActivityParticipation>()
            .eq("user_id", userId).eq("status", "ACTIVE"));
        long hostedActivities = activityMapper.selectCount(
            new QueryWrapper<CommunityActivity>().eq("organizer_user_id", userId));
        long hostedCompanions = companionMapper.selectCount(
            new QueryWrapper<CommunityCompanionRequest>().eq("owner_user_id", userId));
        return new MeTimeCounts(
            pendingApplications == null ? 0 : pendingApplications,
            activeParticipations == null ? 0 : activeParticipations,
            hostedActivities + hostedCompanions);
    }

    private MeTimeItem reservationItem(Reservation reservation, Map<Long, String> venueNames) {
        MeTimeItem item = new MeTimeItem();
        item.sourceType = "RESERVATION";
        item.sourceId = reservation.id;
        String venueName = venueNames.get(reservation.venueId);
        item.title = venueName == null ? "场地预约" : venueName;
        item.location = venueName;
        item.startTime = reservation.startTime;
        item.endTime = reservation.endTime;
        item.status = reservation.status;
        item.pending = false;
        item.detailPath = "/me/reservations";
        return item;
    }

    private MeTimeItem participationItem(CommunityActivityParticipation participation,
                                         Map<Long, CommunityActivity> activities,
                                         Map<Long, CommunityCompanionRequest> companions,
                                         Map<Long, String> venueNames) {
        if ("ACTIVITY".equals(participation.targetType)) {
            CommunityActivity activity = activities.get(participation.targetId);
            if (activity == null) return null;
            MeTimeItem item = new MeTimeItem();
            item.sourceType = "ACTIVITY";
            item.sourceId = activity.id;
            item.title = activity.title;
            item.location = locationOf(activity.venueId, activity.areaCode, venueNames);
            item.startTime = activity.startTime;
            item.endTime = activity.endTime;
            item.status = activity.status;
            item.pending = false;
            item.detailPath = "/activities/" + activity.id;
            return item;
        }
        if ("COMPANION_REQUEST".equals(participation.targetType)) {
            CommunityCompanionRequest companion = companions.get(participation.targetId);
            if (companion == null) return null;
            MeTimeItem item = new MeTimeItem();
            item.sourceType = "COMPANION_REQUEST";
            item.sourceId = companion.id;
            item.title = companion.title;
            item.location = locationOf(companion.venueId, companion.areaCode, venueNames);
            item.startTime = companion.windowStart;
            item.endTime = companion.windowEnd;
            item.status = companion.status;
            item.pending = false;
            item.detailPath = "/companion-requests/" + companion.id;
            return item;
        }
        return null;
    }

    private MeTimeItem applicationItem(CommunityActivityApplication application,
                                       Map<Long, CommunityActivity> activities,
                                       Map<Long, CommunityCompanionRequest> companions,
                                       Map<Long, String> venueNames) {
        if ("ACTIVITY".equals(application.targetType)) {
            CommunityActivity activity = activities.get(application.targetId);
            if (activity == null) return null;
            MeTimeItem item = new MeTimeItem();
            item.sourceType = "ACTIVITY";
            item.sourceId = activity.id;
            item.title = activity.title;
            item.location = locationOf(activity.venueId, activity.areaCode, venueNames);
            item.startTime = activity.startTime;
            item.endTime = activity.endTime;
            item.status = application.status;
            item.pending = true;
            item.detailPath = "/activities/" + activity.id;
            return item;
        }
        if ("COMPANION_REQUEST".equals(application.targetType)) {
            CommunityCompanionRequest companion = companions.get(application.targetId);
            // 目标已取消/过期的待确认申请不再进入时间线
            if (companion == null || "CANCELED".equals(companion.status) || "EXPIRED".equals(companion.status)) {
                return null;
            }
            MeTimeItem item = new MeTimeItem();
            item.sourceType = "COMPANION_REQUEST";
            item.sourceId = companion.id;
            item.title = companion.title;
            item.location = locationOf(companion.venueId, companion.areaCode, venueNames);
            item.startTime = companion.windowStart;
            item.endTime = companion.windowEnd;
            item.status = application.status;
            item.pending = true;
            item.detailPath = "/companion-requests/" + companion.id;
            return item;
        }
        return null;
    }

    private String locationOf(Long venueId, String areaCode, Map<Long, String> venueNames) {
        if (venueId != null) return venueNames.get(venueId);
        return areaCode;
    }
}
