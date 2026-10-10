package com.example.venue.community.activity.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.example.venue.common.BusinessException;
import com.example.venue.common.CurrentUser;
import com.example.venue.community.activity.dto.ActivityRequests;
import com.example.venue.community.activity.dto.ActivityResponses;
import com.example.venue.community.activity.entity.CommunityActivityApplication;
import com.example.venue.community.activity.entity.CommunityActivityParticipation;
import com.example.venue.community.activity.mapper.CommunityActivityApplicationMapper;
import com.example.venue.community.activity.mapper.CommunityActivityParticipationMapper;
import com.example.venue.community.activity.mapper.CommunityDirectoryMapper;
import com.example.venue.community.activity.mapper.CommunityDirectoryMapper.DateCountRow;
import com.example.venue.community.activity.mapper.CommunityDirectoryMapper.DirectoryRow;
import com.example.venue.community.activity.mapper.CommunityDirectoryMapper.TypeCountRow;
import com.example.venue.community.entity.CommunityGroup;
import com.example.venue.community.mapper.CommunityGroupMapper;
import com.example.venue.community.service.CommunityAccessService;
import com.example.venue.user.entity.SysUser;
import com.example.venue.user.mapper.SysUserMapper;
import com.example.venue.venue.entity.Venue;
import com.example.venue.venue.entity.VenueSpace;
import com.example.venue.venue.mapper.VenueMapper;
import com.example.venue.venue.mapper.VenueSpaceMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * V02 日期目录只读聚合：活动与找搭子按统一时间序有界分页，服务端提供真数计数；
 * 写命令、状态机与可见性谓词仍归既有领域，本服务不建立新的权限口径。
 */
@Service
public class CommunityDirectoryService {
    static final String TYPE_ACTIVITY = "ACTIVITY";
    static final String TYPE_COMPANION = "COMPANION";
    private static final Set<String> DIRECTORY_TYPES = Set.of(TYPE_ACTIVITY, TYPE_COMPANION);
    private static final Set<String> INTEREST_TYPES = Set.of(
        "STUDY", "SPORTS", "BOARD_GAME", "WORKSHOP", "OTHER");
    private static final int CURSOR_VERSION = 1;
    private static final Duration MAX_TIME_WINDOW = Duration.ofDays(62);
    private static final DateTimeFormatter CURSOR_TIME = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    private final CommunityDirectoryMapper directoryMapper;
    private final CommunityActivityApplicationMapper applicationMapper;
    private final CommunityActivityParticipationMapper participationMapper;
    private final CommunityGroupMapper groupMapper;
    private final SysUserMapper userMapper;
    private final VenueMapper venueMapper;
    private final VenueSpaceMapper spaceMapper;
    private final CommunityAccessService accessService;
    private final ObjectMapper objectMapper;

    public CommunityDirectoryService(CommunityDirectoryMapper directoryMapper,
                                     CommunityActivityApplicationMapper applicationMapper,
                                     CommunityActivityParticipationMapper participationMapper,
                                     CommunityGroupMapper groupMapper,
                                     SysUserMapper userMapper,
                                     VenueMapper venueMapper,
                                     VenueSpaceMapper spaceMapper,
                                     CommunityAccessService accessService,
                                     ObjectMapper objectMapper) {
        this.directoryMapper = directoryMapper;
        this.applicationMapper = applicationMapper;
        this.participationMapper = participationMapper;
        this.groupMapper = groupMapper;
        this.userMapper = userMapper;
        this.venueMapper = venueMapper;
        this.spaceMapper = spaceMapper;
        this.accessService = accessService;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public ActivityResponses.DirectoryPage directory(ActivityRequests.DirectoryQuery query) {
        CurrentUser actor = accessService.requireCurrentUser();
        ActivityRequests.DirectoryQuery normalized = query == null ? new ActivityRequests.DirectoryQuery() : query;
        String keyword = normalizeKeyword(normalized.keyword);
        List<String> types = normalizeTypes(normalized.types);
        String areaCode = normalizeAreaCode(normalized.areaCode);
        int limit = normalizeLimit(normalized.limit);
        validateTimeWindow(normalized.fromTime, normalized.toTime);
        boolean onlyAvailable = Boolean.TRUE.equals(normalized.onlyAvailable);
        List<String> interestTypes = normalizeInterestTypes(normalized.activityTypes);
        String fingerprint = fingerprint(keyword, types, interestTypes, normalized.fromTime, normalized.toTime,
            normalized.venueId, areaCode, normalized.groupId, normalized.referencePostId, onlyAvailable);

        DirectoryCursor cursor = decodeCursor(normalized.cursor, fingerprint);
        LocalDateTime now = LocalDateTime.now();
        List<DirectoryRow> rows = directoryMapper.selectPage(
            actor.id(), keyword, interestTypes.isEmpty() ? null : interestTypes, normalized.fromTime, normalized.toTime, normalized.venueId,
            areaCode, normalized.groupId, normalized.referencePostId, onlyAvailable, types,
            cursor == null ? null : cursor.start, cursor == null ? null : cursor.type,
            cursor == null ? null : cursor.id, now, limit + 1);

        boolean hasMore = rows.size() > limit;
        List<DirectoryRow> records = hasMore ? rows.subList(0, limit) : rows;
        String nextCursor = null;
        if (hasMore && !records.isEmpty()) {
            DirectoryRow last = records.get(records.size() - 1);
            nextCursor = encodeCursor(last.sortStart, last.sourceType, last.sourceId, fingerprint);
        }

        Map<String, Long> typeCounts = new LinkedHashMap<>();
        typeCounts.put(TYPE_ACTIVITY, 0L);
        typeCounts.put(TYPE_COMPANION, 0L);
        for (TypeCountRow row : directoryMapper.countByType(actor.id(), keyword, interestTypes.isEmpty() ? null : interestTypes,
            normalized.fromTime, normalized.toTime, normalized.venueId, areaCode, normalized.groupId,
            normalized.referencePostId, onlyAvailable, now)) {
            typeCounts.put(row.sourceType, row.total == null ? 0L : row.total);
        }
        long total = typeCounts.values().stream().mapToLong(Long::longValue).sum();

        List<ActivityResponses.DateCount> dateCounts = new ArrayList<>();
        for (DateCountRow row : directoryMapper.countByDate(actor.id(), keyword, interestTypes.isEmpty() ? null : interestTypes,
            normalized.fromTime, normalized.toTime, normalized.venueId, areaCode, normalized.groupId,
            normalized.referencePostId, onlyAvailable, now)) {
            dateCounts.add(new ActivityResponses.DateCount(row.countDate, row.total == null ? 0L : row.total));
        }

        List<ActivityResponses.DirectoryItem> items = items(records, actor.id());
        return new ActivityResponses.DirectoryPage(items, nextCursor, hasMore, total, typeCounts, dateCounts);
    }

    /** 本人发起（含草稿）：按所有者权威读取，供“我发起的”与发起后导航使用。 */
    @Transactional(readOnly = true)
    public ActivityResponses.DirectoryPage myHosted(String cursor, int rawLimit) {
        CurrentUser actor = accessService.requireCurrentUser();
        int limit = normalizeLimit(rawLimit);
        DirectoryCursor anchor = decodeHostedCursor(cursor);
        List<DirectoryRow> rows = directoryMapper.selectHostedPage(
            actor.id(), anchor == null ? null : anchor.start, anchor == null ? null : anchor.type,
            anchor == null ? null : anchor.id, limit + 1);
        boolean hasMore = rows.size() > limit;
        List<DirectoryRow> records = hasMore ? rows.subList(0, limit) : rows;
        String nextCursor = null;
        if (hasMore && !records.isEmpty()) {
            DirectoryRow last = records.get(records.size() - 1);
            nextCursor = encodeCursor(last.updateTime, last.sourceType, last.sourceId,
                hostedFingerprint());
        }
        long total = directoryMapper.countHosted(actor.id());
        return new ActivityResponses.DirectoryPage(items(records, actor.id()), nextCursor, hasMore,
            total, Map.of(), List.of());
    }

    /** 发起者管理页指标：待处理申请数（服务端精确 count）。 */
    @Transactional(readOnly = true)
    public ActivityResponses.ApplicationMetrics applicationMetrics(String targetType, Long targetId) {
        accessService.requireCurrentUser();
        if (!Set.of("ACTIVITY", "COMPANION_REQUEST").contains(targetType) || targetId == null) {
            throw BusinessException.badRequest("目标不合法");
        }
        Long pending = applicationMapper.selectCount(new QueryWrapper<CommunityActivityApplication>()
            .eq("target_type", targetType).eq("target_id", targetId).eq("status", "PENDING"));
        return new ActivityResponses.ApplicationMetrics(pending == null ? 0 : pending);
    }

    private List<ActivityResponses.DirectoryItem> items(List<DirectoryRow> rows, Long viewerId) {
        if (rows.isEmpty()) {
            return List.of();
        }
        Set<Long> groupIds = new HashSet<>();
        Set<Long> userIds = new HashSet<>();
        Set<Long> venueIds = new HashSet<>();
        Set<Long> spaceIds = new HashSet<>();
        List<Long> activityIds = new ArrayList<>();
        List<Long> companionIds = new ArrayList<>();
        for (DirectoryRow row : rows) {
            groupIds.add(row.groupId);
            userIds.add(row.ownerUserId);
            if (row.venueId != null) venueIds.add(row.venueId);
            if (row.spaceId != null) spaceIds.add(row.spaceId);
            if (TYPE_ACTIVITY.equals(row.sourceType)) activityIds.add(row.sourceId);
            else companionIds.add(row.sourceId);
        }
        Map<Long, String> groupNames = new HashMap<>();
        for (CommunityGroup group : groupMapper.selectBatchIds(groupIds)) {
            groupNames.put(group.id, group.name);
        }
        Map<Long, String> nicknames = new HashMap<>();
        for (SysUser user : userMapper.selectBatchIds(userIds)) {
            nicknames.put(user.id, user.nickname == null || user.nickname.isBlank()
                ? user.username : user.nickname);
        }
        Map<Long, String> venueNames = new HashMap<>();
        if (!venueIds.isEmpty()) {
            for (Venue venue : venueMapper.selectBatchIds(venueIds)) {
                venueNames.put(venue.id, venue.name);
            }
        }
        Map<Long, String> spaceNames = new HashMap<>();
        if (!spaceIds.isEmpty()) {
            for (VenueSpace space : spaceMapper.selectBatchIds(spaceIds)) {
                spaceNames.put(space.id, space.name);
            }
        }

        Map<String, String> viewerApplications = viewerApplications(viewerId, rows);
        Map<String, String> viewerRoles = viewerParticipationRoles(viewerId, rows);

        List<ActivityResponses.DirectoryItem> items = new ArrayList<>(rows.size());
        for (DirectoryRow row : rows) {
            ActivityResponses.DirectoryItem item = new ActivityResponses.DirectoryItem();
            item.sourceType = row.sourceType;
            item.sourceId = row.sourceId;
            item.groupId = row.groupId;
            item.groupName = groupNames.get(row.groupId);
            item.referencePostId = row.referencePostId;
            item.organizerUserId = row.ownerUserId;
            item.organizerName = nicknames.get(row.ownerUserId);
            item.activityType = row.activityType;
            item.title = row.title;
            item.description = row.description;
            item.startTime = row.sortStart;
            item.endTime = row.sortEnd;
            item.locationType = row.locationType;
            item.venueId = row.venueId;
            item.venueName = venueNames.get(row.venueId);
            item.spaceId = row.spaceId;
            item.spaceName = spaceNames.get(row.spaceId);
            item.areaCode = row.areaCode;
            item.commitmentStatus = row.locationCommitmentStatus;
            item.capacityTotal = row.capacityTotal;
            item.participantCount = row.participantCount;
            item.status = row.status;
            item.linkedActivityId = row.linkedActivityId;
            item.viewerApplicationStatus = viewerApplications.get(key(row));
            item.viewerParticipationRole = viewerRoles.get(key(row));
            item.updateTime = row.updateTime;
            items.add(item);
        }
        return items;
    }

    /** 同一 target 每人至多一条申请记录（唯一键约束），状态直读即可得本人最新申请。 */
    private Map<String, String> viewerApplications(Long viewerId, List<DirectoryRow> rows) {
        List<String> targetTypes = typesIn(rows);
        if (targetTypes.isEmpty()) {
            return Map.of();
        }
        Map<String, String> result = new HashMap<>();
        for (String targetType : targetTypes) {
            List<Long> ids = idsOfType(rows, targetType);
            for (CommunityActivityApplication application : applicationMapper.selectList(
                new QueryWrapper<CommunityActivityApplication>()
                    .eq("applicant_user_id", viewerId)
                    .eq("target_type", targetType)
                    .in("target_id", ids))) {
                result.put(targetType + ":" + application.targetId, application.status);
            }
        }
        return result;
    }

    private Map<String, String> viewerParticipationRoles(Long viewerId, List<DirectoryRow> rows) {
        List<String> targetTypes = typesIn(rows);
        if (targetTypes.isEmpty()) {
            return Map.of();
        }
        Map<String, String> result = new HashMap<>();
        for (String targetType : targetTypes) {
            List<Long> ids = idsOfType(rows, targetType);
            for (CommunityActivityParticipation participation : participationMapper.selectList(
                new QueryWrapper<CommunityActivityParticipation>()
                    .eq("user_id", viewerId)
                    .eq("target_type", targetType)
                    .eq("status", "ACTIVE")
                    .in("target_id", ids))) {
                result.put(targetType + ":" + participation.targetId, participation.roleType);
            }
        }
        return result;
    }

    private List<String> typesIn(List<DirectoryRow> rows) {
        Set<String> types = new HashSet<>();
        for (DirectoryRow row : rows) {
            types.add(row.sourceType);
        }
        return new ArrayList<>(types);
    }

    private List<Long> idsOfType(List<DirectoryRow> rows, String targetType) {
        List<Long> ids = new ArrayList<>();
        for (DirectoryRow row : rows) {
            if (targetType.equals(row.sourceType)) {
                ids.add(row.sourceId);
            }
        }
        return ids;
    }

    private String key(DirectoryRow row) {
        return row.sourceType + ":" + row.sourceId;
    }

    private List<String> normalizeTypes(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        Set<String> types = new HashSet<>();
        for (String part : raw.split(",")) {
            String value = part.trim().toUpperCase(Locale.ROOT);
            if (value.isEmpty()) {
                continue;
            }
            if (!DIRECTORY_TYPES.contains(value)) {
                throw BusinessException.badRequest("目录类型不合法");
            }
            types.add(value);
        }
        List<String> sorted = new ArrayList<>(types);
        sorted.sort(String::compareTo);
        return sorted;
    }

    /** 兴趣类型多选（组内 OR），与类型 tab（ACTIVITY/COMPANION）互不替代。 */
    private List<String> normalizeInterestTypes(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        Set<String> types = new HashSet<>();
        for (String part : raw.split(",")) {
            String value = part.trim().toUpperCase(Locale.ROOT);
            if (value.isEmpty()) {
                continue;
            }
            if (!INTEREST_TYPES.contains(value)) {
                throw BusinessException.badRequest("活动类型不合法");
            }
            types.add(value);
        }
        List<String> sorted = new ArrayList<>(types);
        sorted.sort(String::compareTo);
        return sorted;
    }

    private String normalizeKeyword(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String value = raw.trim();
        if (value.length() > 80) {
            throw BusinessException.badRequest("搜索关键词长度不能超过80个字符");
        }
        return value;
    }

    private String normalizeAreaCode(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String value = raw.trim().toUpperCase(Locale.ROOT);
        if (!value.matches("[A-Z0-9_]{2,64}")) {
            throw BusinessException.badRequest("校区区域代码不合法");
        }
        return value;
    }

    private int normalizeLimit(int limit) {
        return limit <= 0 ? 20 : Math.min(limit, 50);
    }

    private void validateTimeWindow(LocalDateTime fromTime, LocalDateTime toTime) {
        if (fromTime == null || toTime == null) {
            return;
        }
        if (!fromTime.isBefore(toTime)) {
            throw BusinessException.badRequest("时间窗口不合法");
        }
        if (Duration.between(fromTime, toTime).compareTo(MAX_TIME_WINDOW) > 0) {
            throw BusinessException.badRequest("时间窗口过长，请缩小范围后查询");
        }
    }

    private String fingerprint(String keyword, List<String> types, List<String> interestTypes,
                               LocalDateTime fromTime,
                               LocalDateTime toTime, Long venueId, String areaCode, Long groupId,
                               Long referencePostId, boolean onlyAvailable) {
        return sha256(String.join("|",
            List.of(nullToEmpty(keyword), String.join(",", types), String.join(",", interestTypes),
                nullToEmpty(fromTime == null ? null : fromTime.format(CURSOR_TIME)),
                nullToEmpty(toTime == null ? null : toTime.format(CURSOR_TIME)),
                nullToEmpty(venueId), nullToEmpty(areaCode), nullToEmpty(groupId),
                nullToEmpty(referencePostId), String.valueOf(onlyAvailable))));
    }

    private String hostedFingerprint() {
        return sha256("hosted");
    }

    private record DirectoryCursor(LocalDateTime start, String type, Long id, String fingerprint) {
    }

    private DirectoryCursor decodeCursor(String raw, String expectedFingerprint) {
        DirectoryCursor cursor = decode(raw);
        if (cursor == null) {
            return null;
        }
        if (!cursor.fingerprint.equals(expectedFingerprint)) {
            throw BusinessException.badRequest("筛选条件已变化，请从第一页重新加载");
        }
        return cursor;
    }

    private DirectoryCursor decodeHostedCursor(String raw) {
        DirectoryCursor cursor = decode(raw);
        if (cursor == null) {
            return null;
        }
        if (!cursor.fingerprint.equals(hostedFingerprint())) {
            throw BusinessException.badRequest("游标不合法，请从第一页重新加载");
        }
        return cursor;
    }

    private DirectoryCursor decode(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            Map<?, ?> payload = objectMapper.readValue(Base64.getUrlDecoder().decode(raw), Map.class);
            if (!Integer.valueOf(CURSOR_VERSION).equals(payload.get("v"))) {
                throw BusinessException.badRequest("游标版本不合法，请从第一页重新加载");
            }
            LocalDateTime start = LocalDateTime.parse((String) payload.get("s"), CURSOR_TIME);
            String type = (String) payload.get("t");
            long id = ((Number) payload.get("i")).longValue();
            String fingerprint = (String) payload.get("f");
            if (type == null || fingerprint == null) {
                throw BusinessException.badRequest("游标不合法，请从第一页重新加载");
            }
            return new DirectoryCursor(start, type, id, fingerprint);
        } catch (BusinessException exception) {
            throw exception;
        } catch (IllegalArgumentException | java.io.IOException | java.time.format.DateTimeParseException
                 | ClassCastException | NullPointerException exception) {
            throw BusinessException.badRequest("游标不合法，请从第一页重新加载");
        }
    }

    private String encodeCursor(LocalDateTime anchorTime, String anchorType, Long anchorId,
                                String fingerprint) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("v", CURSOR_VERSION);
        payload.put("s", anchorTime.format(CURSOR_TIME));
        payload.put("t", anchorType);
        payload.put("i", anchorId);
        payload.put("f", fingerprint);
        try {
            byte[] json = objectMapper.writeValueAsBytes(payload);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(json);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("目录游标编码失败", exception);
        }
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder();
            for (byte b : hash) {
                builder.append(String.format("%02x", b));
            }
            return builder.substring(0, 32);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }

    private String nullToEmpty(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
