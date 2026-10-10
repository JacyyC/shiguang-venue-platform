package com.example.venue.funnel.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.example.venue.common.BusinessException;
import com.example.venue.funnel.dto.FunnelEventRequest;
import com.example.venue.funnel.dto.FunnelFavoriteResponse;
import com.example.venue.funnel.entity.UserVenueFavorite;
import com.example.venue.funnel.mapper.UserVenueFavoriteMapper;
import com.example.venue.security.SecurityContext;
import com.example.venue.venue.entity.Venue;
import com.example.venue.venue.mapper.VenueMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class FunnelFavoriteService {
    private static final String ACTIVE = "ACTIVE";
    private static final String CANCELED = "CANCELED";
    private static final String DEFAULT_SOURCE_CHANNEL = "WEB";

    private final UserVenueFavoriteMapper favoriteMapper;
    private final VenueMapper venueMapper;
    private final FunnelEventService eventService;

    public FunnelFavoriteService(UserVenueFavoriteMapper favoriteMapper,
                                 VenueMapper venueMapper,
                                 FunnelEventService eventService) {
        this.favoriteMapper = favoriteMapper;
        this.venueMapper = venueMapper;
        this.eventService = eventService;
    }

    @Transactional
    public FunnelFavoriteResponse favoriteVenue(Long venueId, FunnelEventRequest context) {
        Long userId = requireCurrentUser();
        verifyVenue(venueId);
        UserVenueFavorite existing = findAnyFavorite(userId, venueId);
        if (isActive(existing)) {
            return toResponse(existing);
        }

        LocalDateTime now = LocalDateTime.now();
        UserVenueFavorite favorite;
        // newlyActivated 标记本次调用是否实际触发 ACTIVE 转换；并发场景下若已被其他事务激活，
        // 则不重复记录 FAVORITE_ADDED 事件（去重），避免收藏转化重复计数。
        boolean newlyActivated = false;
        if (existing == null) {
            favorite = new UserVenueFavorite();
            favorite.userId = userId;
            favorite.venueId = venueId;
            favorite.createTime = now;
            favorite.sourceChannel = sourceChannel(context);
            favorite.campaignId = campaignId(context);
            favorite.status = ACTIVE;
            favorite.updateTime = now;
            favorite.deleted = 0;
            try {
                favoriteMapper.insert(favorite);
                newlyActivated = true;
            } catch (DuplicateKeyException ex) {
                // A26-P2-22：schema 有 uk_user_venue_favorite(user_id, venue_id)，并发首次收藏
                // 会撞唯一索引。原代码无兜底直接 500（整事务含已记漏斗事件回滚）。修复后重读已存在行：
                // - 并发已写入 ACTIVE → 直接返回不记事件（去重）
                // - 并发已写入 CANCELED → 走 CAS update 恢复为 ACTIVE，与下方 else 分支同路径
                UserVenueFavorite concurrent = favoriteMapper.findByUserAndVenue(userId, venueId);
                if (isActive(concurrent)) {
                    return toResponse(concurrent);
                }
                if (concurrent == null) {
                    // 极端情况：唯一索引冲突但重读查不到行（如并发事务已回滚），重抛让上层处理
                    throw ex;
                }
                favorite = concurrent;
                favorite.sourceChannel = sourceChannel(context);
                favorite.campaignId = campaignId(context);
                favorite.status = ACTIVE;
                favorite.updateTime = now;
                favorite.deleted = 0;
                newlyActivated = reactivateFavoriteWithCas(favorite);
            }
        } else {
            favorite = existing;
            favorite.sourceChannel = sourceChannel(context);
            favorite.campaignId = campaignId(context);
            favorite.status = ACTIVE;
            favorite.updateTime = now;
            favorite.deleted = 0;
            // A26-P2-22：CANCELED → ACTIVE 用 CAS update（where status='CANCELED'），
            // 影响行数=0 表示并发已激活，不记 FAVORITE_ADDED 事件（去重）。
            newlyActivated = reactivateFavoriteWithCas(favorite);
        }
        if (newlyActivated) {
            recordFavoriteMilestone("FAVORITE_ADDED", userId, venueId, context);
        }
        return toResponse(favorite);
    }

    /**
     * A26-P2-22：CANCELED → ACTIVE 的 CAS update。条件 where status='CANCELED' 保证
     * 并发场景下只有第一个事务能实际改写状态，第二个事务看到 status 已是 ACTIVE 时
     * 影响 0 行，从而避免重复记 FAVORITE_ADDED 事件导致收藏转化重复计数。
     * 显式 set 各字段（不用 updateById 全字段更新），与原 updateById 语义对齐。
     */
    private boolean reactivateFavoriteWithCas(UserVenueFavorite favorite) {
        int affected = favoriteMapper.update(null,
            new UpdateWrapper<UserVenueFavorite>()
                .eq("id", favorite.id)
                .eq("status", CANCELED)
                .set("source_channel", favorite.sourceChannel)
                .set("campaign_id", favorite.campaignId)
                .set("status", ACTIVE)
                .set("update_time", favorite.updateTime)
                .set("deleted", 0));
        return affected > 0;
    }

    @Transactional
    public void cancelFavorite(Long venueId, FunnelEventRequest context) {
        Long userId = requireCurrentUser();
        verifyVenue(venueId);
        UserVenueFavorite existing = favoriteMapper.findByUserAndVenue(userId, venueId);
        if (!isActive(existing)) {
            return;
        }
        existing.status = CANCELED;
        existing.updateTime = LocalDateTime.now();
        favoriteMapper.updateById(existing);
        recordFavoriteMilestone("FAVORITE_REMOVED", userId, venueId, context);
    }

    public boolean isFavorite(Long venueId) {
        Long userId = requireCurrentUser();
        verifyVenue(venueId);
        return isActive(favoriteMapper.findByUserAndVenue(userId, venueId));
    }

    public List<FunnelFavoriteResponse> myFavorites() {
        Long userId = requireCurrentUser();
        List<UserVenueFavorite> favorites = favoriteMapper.selectList(new QueryWrapper<UserVenueFavorite>()
            .eq("user_id", userId)
            .eq("status", ACTIVE)
            .eq("deleted", 0)
            .orderByDesc("update_time")
            .orderByDesc("id"));
        List<FunnelFavoriteResponse> responses = new ArrayList<>();
        for (UserVenueFavorite favorite : favorites) {
            if (isAvailableVenue(favorite.venueId)) {
                responses.add(toResponse(favorite));
            }
        }
        return responses;
    }

    private UserVenueFavorite findAnyFavorite(Long userId, Long venueId) {
        return favoriteMapper.selectOne(new QueryWrapper<UserVenueFavorite>()
            .eq("user_id", userId)
            .eq("venue_id", venueId)
            .last("LIMIT 1"));
    }

    private void recordFavoriteMilestone(String eventName, Long userId, Long venueId, FunnelEventRequest context) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (context != null && context.metadata != null) {
            metadata.putAll(context.metadata);
        }
        metadata.put("action", eventName);
        metadata.put("venueId", venueId);
        if (context != null) {
            metadata.put("sourceChannel", sourceChannel(context));
            if (context.pagePath != null && !context.pagePath.isBlank()) {
                metadata.put("pagePath", context.pagePath.trim());
            }
        }
        eventService.recordMilestone(
            eventName,
            "FAVORITE",
            userId,
            venueId,
            null,
            null,
            null,
            "SUCCESS",
            null,
            null,
            metadata,
            // A26-P1-03：收藏事件由浏览器 FunnelEventRequest 直接传入 context.sessionKey，
            // 与前序浏览器事件共享同一 sessionKey，避免漏斗收藏事件断链。
            context == null ? null : context.sessionKey);
    }

    private Long requireCurrentUser() {
        Long userId = SecurityContext.currentUserId();
        if (userId == null) {
            throw BusinessException.forbidden("请先登录");
        }
        return userId;
    }

    private Venue verifyVenue(Long venueId) {
        if (venueId == null) {
            throw BusinessException.badRequest("场馆ID不能为空");
        }
        Venue venue = venueMapper.selectById(venueId);
        if (venue == null || Integer.valueOf(1).equals(venue.deleted)) {
            throw BusinessException.notFound("场馆不存在");
        }
        return venue;
    }

    private boolean isAvailableVenue(Long venueId) {
        if (venueId == null) {
            return false;
        }
        Venue venue = venueMapper.selectById(venueId);
        return venue != null && !Integer.valueOf(1).equals(venue.deleted);
    }

    private boolean isActive(UserVenueFavorite favorite) {
        return favorite != null && ACTIVE.equals(favorite.status) && !Integer.valueOf(1).equals(favorite.deleted);
    }

    private String sourceChannel(FunnelEventRequest context) {
        if (context == null || context.sourceChannel == null || context.sourceChannel.isBlank()) {
            return DEFAULT_SOURCE_CHANNEL;
        }
        return context.sourceChannel.trim().toUpperCase(Locale.ROOT);
    }

    private String campaignId(FunnelEventRequest context) {
        if (context == null || context.campaignId == null || context.campaignId.isBlank()) {
            return null;
        }
        return context.campaignId.trim();
    }

    private FunnelFavoriteResponse toResponse(UserVenueFavorite favorite) {
        if (favorite == null) {
            return null;
        }
        FunnelFavoriteResponse response = new FunnelFavoriteResponse();
        response.id = favorite.id;
        response.userId = favorite.userId;
        response.venueId = favorite.venueId;
        response.sourceChannel = favorite.sourceChannel;
        response.campaignId = favorite.campaignId;
        response.status = favorite.status;
        response.createTime = favorite.createTime;
        response.updateTime = favorite.updateTime;
        return response;
    }
}
