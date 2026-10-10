package com.example.venue.notice.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.example.venue.common.BusinessException;
import com.example.venue.common.Constants;
import com.example.venue.common.CurrentUser;
import com.example.venue.member.entity.MemberAccount;
import com.example.venue.member.mapper.MemberAccountMapper;
import com.example.venue.notice.dto.AudiencePreviewRequest;
import com.example.venue.notice.dto.AudiencePreviewResponse;
import com.example.venue.notice.dto.AudienceUser;
import com.example.venue.notice.entity.NoticeAudienceSnapshot;
import com.example.venue.notice.mapper.NoticeAudienceSnapshotMapper;
import com.example.venue.promotion.entity.CouponTemplate;
import com.example.venue.promotion.entity.UserCoupon;
import com.example.venue.promotion.mapper.CouponTemplateMapper;
import com.example.venue.promotion.mapper.UserCouponMapper;
import com.example.venue.reservation.entity.Reservation;
import com.example.venue.reservation.mapper.ReservationMapper;
import com.example.venue.user.entity.SysUser;
import com.example.venue.user.mapper.SysUserMapper;
import com.example.venue.security.SecurityContext;
import com.example.venue.venue.service.VenueAccessService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class NoticeAudienceService {
    private static final Set<String> SUPPORTED_SCOPES = Set.of(
        "ALL_USERS",
        "MEMBER_LEVEL",
        "VENUE_RECENT_RESERVERS",
        "CAMPAIGN_PARTICIPANTS",
        "SPECIFIC_USERS"
    );

    private final SysUserMapper userMapper;
    private final MemberAccountMapper memberAccountMapper;
    private final ReservationMapper reservationMapper;
    private final UserCouponMapper userCouponMapper;
    private final CouponTemplateMapper couponTemplateMapper;
    private final NoticeAudienceSnapshotMapper audienceMapper;
    private final VenueAccessService venueAccessService;

    public NoticeAudienceService(SysUserMapper userMapper, MemberAccountMapper memberAccountMapper,
                                 ReservationMapper reservationMapper, UserCouponMapper userCouponMapper,
                                 CouponTemplateMapper couponTemplateMapper,
                                 NoticeAudienceSnapshotMapper audienceMapper,
                                 VenueAccessService venueAccessService) {
        this.userMapper = userMapper;
        this.memberAccountMapper = memberAccountMapper;
        this.reservationMapper = reservationMapper;
        this.userCouponMapper = userCouponMapper;
        this.couponTemplateMapper = couponTemplateMapper;
        this.audienceMapper = audienceMapper;
        this.venueAccessService = venueAccessService;
    }

    public AudiencePreviewResponse preview(AudiencePreviewRequest request) {
        String scope = normalizeScope(request == null ? null : request.targetScope);
        requirePreviewAccess(scope, request);
        List<AudienceUser> audience = resolveAudience(scope, request);
        AudiencePreviewResponse response = new AudiencePreviewResponse();
        response.targetScope = scope;
        response.audienceCount = audience.size();
        response.deliverableCount = (int) audience.stream().filter(user -> user.deliverable).count();
        response.excludedCount = response.audienceCount - response.deliverableCount;
        response.excludeReasonCounts = audience.stream()
            .filter(user -> !user.deliverable)
            .collect(Collectors.groupingBy(
                user -> user.excludeReason == null ? "UNKNOWN" : user.excludeReason,
                LinkedHashMap::new,
                Collectors.counting()
            ));
        response.samples.addAll(audience);
        return response;
    }

    @Transactional
    public List<NoticeAudienceSnapshot> snapshotAudience(Long batchId, Long noticeId, AudiencePreviewRequest request) {
        if (batchId == null || noticeId == null) {
            throw BusinessException.badRequest("公告投递批次参数不完整");
        }
        String scope = normalizeScope(request == null ? null : request.targetScope);
        List<AudienceUser> audience = resolveAudience(scope, request);
        LocalDateTime now = LocalDateTime.now();
        List<NoticeAudienceSnapshot> snapshots = new ArrayList<>();
        for (AudienceUser user : audience) {
            NoticeAudienceSnapshot snapshot = new NoticeAudienceSnapshot();
            snapshot.batchId = batchId;
            snapshot.noticeId = noticeId;
            snapshot.userId = user.userId;
            snapshot.venueId = user.venueId;
            snapshot.memberLevelId = user.memberLevelId;
            snapshot.segmentCode = user.segmentCode;
            snapshot.deliveryStatus = user.deliverable ? "PENDING" : "EXCLUDED";
            snapshot.readStatus = "UNREAD";
            snapshot.excludeReason = user.deliverable ? null : user.excludeReason;
            snapshot.createTime = now;
            snapshot.updateTime = now;
            snapshot.deleted = 0;
            audienceMapper.insert(snapshot);
            snapshots.add(snapshot);
        }
        return snapshots;
    }

    private void requirePreviewAccess(String scope, AudiencePreviewRequest request) {
        CurrentUser user = SecurityContext.currentUser();
        if (user == null) {
            throw BusinessException.forbidden("未登录");
        }
        if (user.hasRole(Constants.ROLE_SYSTEM_ADMIN)) {
            return;
        }
        if (!user.hasRole(Constants.ROLE_VENUE_ADMIN)
            || !"VENUE_RECENT_RESERVERS".equals(scope)
            || request == null
            || request.targetVenueId == null) {
            throw BusinessException.forbidden("场馆管理员只能预览已授权场馆的近期预约用户");
        }
        venueAccessService.requireVenueManageAccess(request.targetVenueId);
    }

    private List<AudienceUser> resolveAudience(String scope, AudiencePreviewRequest request) {
        return switch (scope) {
            case "ALL_USERS" -> allUsers();
            case "MEMBER_LEVEL" -> memberLevelUsers(request);
            case "VENUE_RECENT_RESERVERS" -> recentVenueReservers(request);
            case "CAMPAIGN_PARTICIPANTS" -> campaignParticipants(request);
            case "SPECIFIC_USERS" -> specificUsers(request);
            default -> throw BusinessException.badRequest("不支持的公告目标人群");
        };
    }

    private List<AudienceUser> allUsers() {
        return safeList(userMapper.findActiveUsersByRoleKey(Constants.ROLE_USER)).stream()
            .map(user -> toAudienceUser(user, user.id, null, null, null))
            .toList();
    }

    private List<AudienceUser> memberLevelUsers(AudiencePreviewRequest request) {
        if (request == null || request.memberLevelId == null) {
            throw BusinessException.badRequest("目标人群参数不完整");
        }
        List<MemberAccount> accounts = safeList(memberAccountMapper.selectList(new QueryWrapper<MemberAccount>()
            .eq("level_id", request.memberLevelId)
            .eq("deleted", 0)));
        Map<Long, MemberAccount> accountByUser = accounts.stream()
            .filter(account -> account.userId != null)
            .collect(Collectors.toMap(account -> account.userId, Function.identity(), (left, right) -> left, LinkedHashMap::new));
        Map<Long, SysUser> users = loadUsersById(accountByUser.keySet());
        return accountByUser.entrySet().stream()
            .map(entry -> {
                MemberAccount account = entry.getValue();
                return toAudienceUser(users.get(entry.getKey()), entry.getKey(), null,
                    account.levelId == null ? request.memberLevelId : account.levelId, account.levelCode);
            })
            .toList();
    }

    private List<AudienceUser> recentVenueReservers(AudiencePreviewRequest request) {
        if (request == null || request.targetVenueId == null) {
            throw BusinessException.badRequest("目标人群参数不完整");
        }
        LocalDateTime cutoff = LocalDateTime.now().minusDays(30);
        List<Reservation> reservations = safeList(reservationMapper.selectList(new QueryWrapper<Reservation>()
            .eq("venue_id", request.targetVenueId)
            .ge("create_time", cutoff)
            .eq("deleted", 0)));
        Map<Long, Reservation> reservationByUser = reservations.stream()
            .filter(reservation -> reservation.userId != null)
            .collect(Collectors.toMap(reservation -> reservation.userId, Function.identity(), (left, right) -> left, LinkedHashMap::new));
        Map<Long, SysUser> users = loadUsersById(reservationByUser.keySet());
        return reservationByUser.entrySet().stream()
            .map(entry -> toAudienceUser(users.get(entry.getKey()), entry.getKey(),
                entry.getValue().venueId == null ? request.targetVenueId : entry.getValue().venueId, null, null))
            .toList();
    }

    private List<AudienceUser> campaignParticipants(AudiencePreviewRequest request) {
        if (request == null || request.campaignId == null) {
            throw BusinessException.badRequest("目标人群参数不完整");
        }
        List<CouponTemplate> templates = safeList(couponTemplateMapper.selectList(new QueryWrapper<CouponTemplate>()
            .eq("campaign_id", request.campaignId)
            .eq("deleted", 0)));
        List<Long> templateIds = templates.stream()
            .map(template -> template.id)
            .filter(Objects::nonNull)
            .toList();
        if (templateIds.isEmpty()) {
            return List.of();
        }
        List<UserCoupon> coupons = safeList(userCouponMapper.selectList(new QueryWrapper<UserCoupon>()
            .in("template_id", templateIds)
            .eq("deleted", 0)));
        LinkedHashSet<Long> userIds = coupons.stream()
            .map(coupon -> coupon.userId)
            .filter(Objects::nonNull)
            .collect(Collectors.toCollection(LinkedHashSet::new));
        Map<Long, SysUser> users = loadUsersById(userIds);
        return userIds.stream()
            .map(userId -> toAudienceUser(users.get(userId), userId, null, null, "CAMPAIGN_" + request.campaignId))
            .toList();
    }

    private List<AudienceUser> specificUsers(AudiencePreviewRequest request) {
        if (request == null || request.userIds == null || request.userIds.isEmpty()) {
            throw BusinessException.badRequest("目标人群参数不完整");
        }
        List<Long> userIds = request.userIds.stream()
            .filter(Objects::nonNull)
            .collect(Collectors.toCollection(LinkedHashSet::new))
            .stream()
            .toList();
        if (userIds.isEmpty()) {
            throw BusinessException.badRequest("目标人群参数不完整");
        }
        Map<Long, SysUser> users = loadUsersById(userIds);
        return userIds.stream()
            .map(userId -> toAudienceUser(users.get(userId), userId, null, null, null))
            .toList();
    }

    private Map<Long, SysUser> loadUsersById(Collection<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return Map.of();
        }
        List<SysUser> users = safeList(userMapper.findActiveUsersByIdsAndRoleKey(userIds, Constants.ROLE_USER));
        return users.stream()
            .filter(user -> user.id != null)
            .collect(Collectors.toMap(user -> user.id, Function.identity(), (left, right) -> left, LinkedHashMap::new));
    }

    private AudienceUser toAudienceUser(SysUser user, Long requestedUserId, Long venueId, Long memberLevelId, String segmentCode) {
        AudienceUser audienceUser = new AudienceUser();
        audienceUser.userId = user == null ? requestedUserId : user.id;
        audienceUser.venueId = venueId;
        audienceUser.memberLevelId = memberLevelId;
        audienceUser.segmentCode = segmentCode;
        if (user == null) {
            audienceUser.deliverable = false;
            audienceUser.excludeReason = "USER_NOT_FOUND";
            return audienceUser;
        }
        if (!Integer.valueOf(0).equals(user.deleted)) {
            audienceUser.deliverable = false;
            audienceUser.excludeReason = "USER_DELETED";
            return audienceUser;
        }
        if (!Constants.ACTIVE.equals(user.status)) {
            audienceUser.deliverable = false;
            audienceUser.excludeReason = "USER_NOT_ACTIVE";
            return audienceUser;
        }
        audienceUser.deliverable = true;
        return audienceUser;
    }

    private String normalizeScope(String scope) {
        if (scope == null || scope.isBlank()) {
            return "ALL_USERS";
        }
        String normalized = scope.trim().toUpperCase(Locale.ROOT);
        if (!SUPPORTED_SCOPES.contains(normalized)) {
            throw BusinessException.badRequest("不支持的公告目标人群");
        }
        return normalized;
    }

    private <T> List<T> safeList(List<T> values) {
        return values == null ? List.of() : values;
    }
}
