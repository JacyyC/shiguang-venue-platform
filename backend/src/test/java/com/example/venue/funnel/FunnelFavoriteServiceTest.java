package com.example.venue.funnel;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.example.venue.common.CurrentUser;
import com.example.venue.funnel.dto.FunnelEventRequest;
import com.example.venue.funnel.dto.FunnelFavoriteResponse;
import com.example.venue.funnel.entity.FunnelEvent;
import com.example.venue.funnel.entity.UserVenueFavorite;
import com.example.venue.funnel.mapper.UserVenueFavoriteMapper;
import com.example.venue.funnel.service.FunnelEventService;
import com.example.venue.funnel.service.FunnelFavoriteService;
import com.example.venue.venue.entity.Venue;
import com.example.venue.venue.mapper.VenueMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FunnelFavoriteServiceTest {
    @Mock
    private UserVenueFavoriteMapper favoriteMapper;
    @Mock
    private VenueMapper venueMapper;
    @Mock
    private FunnelEventService eventService;

    private FunnelFavoriteService favoriteService;

    @BeforeEach
    void setUp() {
        favoriteService = new FunnelFavoriteService(favoriteMapper, venueMapper, eventService);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
            new CurrentUser(7L, "favorite-user", Set.of("USER"), Set.of("funnel:favorite:me")), null, List.of()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void favoriteStateAndMilestoneWritesShareOneTransaction() throws Exception {
        assertThat(FunnelFavoriteService.class.getMethod("favoriteVenue", Long.class, FunnelEventRequest.class)
            .getAnnotation(Transactional.class)).isNotNull();
        assertThat(FunnelFavoriteService.class.getMethod("cancelFavorite", Long.class, FunnelEventRequest.class)
            .getAnnotation(Transactional.class)).isNotNull();
    }

    @Test
    void favoriteVenueAddsFavoriteAndRecordsFavoriteAddedEvent() {
        when(venueMapper.selectById(20L)).thenReturn(activeVenue());
        when(favoriteMapper.selectOne(any(QueryWrapper.class))).thenReturn(null);
        when(favoriteMapper.insert(any(UserVenueFavorite.class))).thenAnswer(invocation -> {
            UserVenueFavorite favorite = invocation.getArgument(0);
            favorite.id = 301L;
            return 1;
        });
        FunnelFavoriteResponse response = favoriteService.favoriteVenue(20L, context());

        ArgumentCaptor<UserVenueFavorite> favoriteCaptor = ArgumentCaptor.forClass(UserVenueFavorite.class);
        verify(favoriteMapper).insert(favoriteCaptor.capture());
        UserVenueFavorite inserted = favoriteCaptor.getValue();
        ArgumentCaptor<Map> metadataCaptor = ArgumentCaptor.forClass(Map.class);
        assertThat(response.id).isEqualTo(301L);
        assertThat(response.userId).isEqualTo(7L);
        assertThat(response.venueId).isEqualTo(20L);
        assertThat(response.status).isEqualTo("ACTIVE");
        assertThat(inserted.userId).isEqualTo(7L);
        assertThat(inserted.venueId).isEqualTo(20L);
        assertThat(inserted.sourceChannel).isEqualTo("WEB");
        assertThat(inserted.campaignId).isEqualTo("camp-b5");
        assertThat(inserted.status).isEqualTo("ACTIVE");
        assertThat(inserted.deleted).isZero();
        verify(eventService).recordMilestone(
            eq("FAVORITE_ADDED"), eq("FAVORITE"), eq(7L), eq(20L), isNull(), isNull(), isNull(),
            eq("SUCCESS"), isNull(), isNull(), metadataCaptor.capture(), any());
        assertThat(metadataCaptor.getValue()).containsEntry("sourceChannel", "WEB");
    }

    @Test
    void favoriteVenuePassesH5SourceChannelToMilestoneMetadata() {
        FunnelEventRequest context = context();
        context.sourceChannel = "h5";
        context.metadata = Map.of("sourceChannel", "web", "safeKey", "kept");
        when(venueMapper.selectById(20L)).thenReturn(activeVenue());
        when(favoriteMapper.selectOne(any(QueryWrapper.class))).thenReturn(null);
        when(favoriteMapper.insert(any(UserVenueFavorite.class))).thenAnswer(invocation -> {
            UserVenueFavorite favorite = invocation.getArgument(0);
            favorite.id = 302L;
            return 1;
        });

        favoriteService.favoriteVenue(20L, context);

        ArgumentCaptor<UserVenueFavorite> favoriteCaptor = ArgumentCaptor.forClass(UserVenueFavorite.class);
        verify(favoriteMapper).insert(favoriteCaptor.capture());
        assertThat(favoriteCaptor.getValue().sourceChannel).isEqualTo("H5");
        ArgumentCaptor<Map> metadataCaptor = ArgumentCaptor.forClass(Map.class);
        verify(eventService).recordMilestone(
            eq("FAVORITE_ADDED"), eq("FAVORITE"), eq(7L), eq(20L), isNull(), isNull(), isNull(),
            eq("SUCCESS"), isNull(), isNull(), metadataCaptor.capture(), any());
        assertThat(metadataCaptor.getValue())
            .containsEntry("sourceChannel", "H5")
            .containsEntry("safeKey", "kept");
    }

    @Test
    void duplicateActiveFavoriteReturnsExistingResponseWithoutNewEvent() {
        UserVenueFavorite existing = favorite(301L, "ACTIVE", 0);
        when(venueMapper.selectById(20L)).thenReturn(activeVenue());
        when(favoriteMapper.selectOne(any(QueryWrapper.class))).thenReturn(existing);

        FunnelFavoriteResponse response = favoriteService.favoriteVenue(20L, context());

        assertThat(response.id).isEqualTo(301L);
        assertThat(response.status).isEqualTo("ACTIVE");
        verify(favoriteMapper, never()).insert(any(UserVenueFavorite.class));
        verify(favoriteMapper, never()).updateById(any(UserVenueFavorite.class));
        verify(eventService, never()).recordMilestone(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void cancelFavoriteMarksFavoriteCanceledAndRecordsFavoriteRemovedEvent() {
        UserVenueFavorite existing = favorite(301L, "ACTIVE", 0);
        when(venueMapper.selectById(20L)).thenReturn(activeVenue());
        when(favoriteMapper.findByUserAndVenue(7L, 20L)).thenReturn(existing);
        favoriteService.cancelFavorite(20L, context());

        ArgumentCaptor<UserVenueFavorite> favoriteCaptor = ArgumentCaptor.forClass(UserVenueFavorite.class);
        verify(favoriteMapper).updateById(favoriteCaptor.capture());
        UserVenueFavorite updated = favoriteCaptor.getValue();
        assertThat(updated.id).isEqualTo(301L);
        assertThat(updated.status).isEqualTo("CANCELED");
        assertThat(updated.deleted).isZero();
        assertThat(updated.updateTime).isNotNull();
        verify(eventService).recordMilestone(
            eq("FAVORITE_REMOVED"), eq("FAVORITE"), eq(7L), eq(20L), isNull(), isNull(), isNull(),
            eq("SUCCESS"), isNull(), isNull(), any(Map.class), any());
    }

    @Test
    void repeatedCancelOfCanceledFavoriteDoesNotWriteOrRecordAnotherRemoval() {
        UserVenueFavorite existing = favorite(301L, "CANCELED", 0);
        when(venueMapper.selectById(20L)).thenReturn(activeVenue());
        when(favoriteMapper.findByUserAndVenue(7L, 20L)).thenReturn(existing);

        favoriteService.cancelFavorite(20L, context());

        verify(favoriteMapper, never()).updateById(any(UserVenueFavorite.class));
        verify(eventService, never()).recordMilestone(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void favoriteVenueRestoresCanceledFavoriteAndRecordsFavoriteAddedEvent() {
        // A26-P2-22：CANCELED → ACTIVE 走 CAS update（where status='CANCELED'），不再用 updateById 全字段回写。
        UserVenueFavorite existing = favorite(301L, "CANCELED", 0);
        when(venueMapper.selectById(20L)).thenReturn(activeVenue());
        when(favoriteMapper.selectOne(any(QueryWrapper.class))).thenReturn(existing);
        when(favoriteMapper.update(any(), any(UpdateWrapper.class))).thenReturn(1);
        FunnelFavoriteResponse response = favoriteService.favoriteVenue(20L, context());

        verify(favoriteMapper).update(any(), any(UpdateWrapper.class));
        verify(favoriteMapper, never()).updateById(any(UserVenueFavorite.class));
        assertThat(response.id).isEqualTo(301L);
        assertThat(response.status).isEqualTo("ACTIVE");
        verify(eventService).recordMilestone(
            eq("FAVORITE_ADDED"), eq("FAVORITE"), eq(7L), eq(20L), isNull(), isNull(), isNull(),
            eq("SUCCESS"), isNull(), isNull(), any(Map.class), any());
    }

    // ===== A26-P2-22：收藏 check-then-insert 并发处理测试 =====

    /**
     * A26-P2-22：并发首次收藏撞 uk_user_venue_favorite 唯一索引时，DuplicateKeyException
     * 兜底重读发现并发已写入 ACTIVE 行 → 直接返回不记 FAVORITE_ADDED 事件（去重），
     * 避免收藏转化重复计数。
     */
    @Test
    void concurrentInsertDuplicateKeyFindsActiveFavoriteDoesNotRecordEvent() {
        UserVenueFavorite concurrentActive = favorite(301L, "ACTIVE", 0);
        when(venueMapper.selectById(20L)).thenReturn(activeVenue());
        when(favoriteMapper.selectOne(any(QueryWrapper.class))).thenReturn(null);
        org.mockito.Mockito.doThrow(new DuplicateKeyException("uk_user_venue_favorite"))
            .when(favoriteMapper).insert(any(UserVenueFavorite.class));
        when(favoriteMapper.findByUserAndVenue(7L, 20L)).thenReturn(concurrentActive);

        FunnelFavoriteResponse response = favoriteService.favoriteVenue(20L, context());

        assertThat(response.id).isEqualTo(301L);
        assertThat(response.status).isEqualTo("ACTIVE");
        verify(favoriteMapper, never()).update(any(), any(UpdateWrapper.class));
        verify(eventService, never()).recordMilestone(any(), any(), any(), any(), any(), any(), any(),
            any(), any(), any(), any(), any());
    }

    /**
     * A26-P2-22：并发首次收藏撞唯一索引，重读发现并发已写入 CANCELED 行 → 走 CAS
     * update（where status='CANCELED'）恢复为 ACTIVE，CAS 成功记 FAVORITE_ADDED 事件。
     */
    @Test
    void concurrentInsertDuplicateKeyReactivatesCanceledWithCasAndRecordsEvent() {
        UserVenueFavorite concurrentCanceled = favorite(302L, "CANCELED", 0);
        when(venueMapper.selectById(20L)).thenReturn(activeVenue());
        when(favoriteMapper.selectOne(any(QueryWrapper.class))).thenReturn(null);
        org.mockito.Mockito.doThrow(new DuplicateKeyException("uk_user_venue_favorite"))
            .when(favoriteMapper).insert(any(UserVenueFavorite.class));
        when(favoriteMapper.findByUserAndVenue(7L, 20L)).thenReturn(concurrentCanceled);
        when(favoriteMapper.update(any(), any(UpdateWrapper.class))).thenReturn(1);

        FunnelFavoriteResponse response = favoriteService.favoriteVenue(20L, context());

        assertThat(response.id).isEqualTo(302L);
        assertThat(response.status).isEqualTo("ACTIVE");
        verify(favoriteMapper).update(any(), any(UpdateWrapper.class));
        verify(eventService).recordMilestone(
            eq("FAVORITE_ADDED"), eq("FAVORITE"), eq(7L), eq(20L), isNull(), isNull(), isNull(),
            eq("SUCCESS"), isNull(), isNull(), any(Map.class), any());
    }

    /**
     * A26-P2-22：CANCELED 行并发收藏，两个事务同时 CAS update（where status='CANCELED'），
     * 第二个事务 affected=0（status 已被并发改为 ACTIVE），不记 FAVORITE_ADDED 事件（去重）。
     */
    @Test
    void concurrentCasUpdateReturnsZeroDoesNotRecordEvent() {
        UserVenueFavorite existing = favorite(303L, "CANCELED", 0);
        when(venueMapper.selectById(20L)).thenReturn(activeVenue());
        when(favoriteMapper.selectOne(any(QueryWrapper.class))).thenReturn(existing);
        when(favoriteMapper.update(any(), any(UpdateWrapper.class))).thenReturn(0);

        FunnelFavoriteResponse response = favoriteService.favoriteVenue(20L, context());

        verify(favoriteMapper).update(any(), any(UpdateWrapper.class));
        verify(eventService, never()).recordMilestone(any(), any(), any(), any(), any(), any(), any(),
            any(), any(), any(), any(), any());
    }

    @Test
    void myFavoritesReturnsActiveFavoritesAndIsFavoriteUsesActiveRecord() {
        UserVenueFavorite favorite = favorite(301L, "ACTIVE", 0);
        when(favoriteMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(favorite));
        when(venueMapper.selectById(20L)).thenReturn(activeVenue());
        when(favoriteMapper.findByUserAndVenue(7L, 20L)).thenReturn(favorite);

        List<FunnelFavoriteResponse> responses = favoriteService.myFavorites();
        boolean active = favoriteService.isFavorite(20L);

        assertThat(responses).hasSize(1);
        assertThat(responses.get(0).id).isEqualTo(301L);
        assertThat(responses.get(0).status).isEqualTo("ACTIVE");
        assertThat(active).isTrue();
        verify(eventService, never()).recordMilestone(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void myFavoritesFiltersStaleFavoritesWhoseVenuesAreMissingOrDeleted() {
        UserVenueFavorite activeFavorite = favorite(301L, "ACTIVE", 0);
        activeFavorite.venueId = 20L;
        UserVenueFavorite deletedVenueFavorite = favorite(302L, "ACTIVE", 0);
        deletedVenueFavorite.venueId = 21L;
        UserVenueFavorite missingVenueFavorite = favorite(303L, "ACTIVE", 0);
        missingVenueFavorite.venueId = 22L;
        Venue deletedVenue = activeVenue();
        deletedVenue.id = 21L;
        deletedVenue.deleted = 1;
        when(favoriteMapper.selectList(any(QueryWrapper.class)))
            .thenReturn(List.of(activeFavorite, deletedVenueFavorite, missingVenueFavorite));
        lenient().when(venueMapper.selectById(20L)).thenReturn(activeVenue());
        lenient().when(venueMapper.selectById(21L)).thenReturn(deletedVenue);
        lenient().when(venueMapper.selectById(22L)).thenReturn(null);

        List<FunnelFavoriteResponse> responses = favoriteService.myFavorites();

        assertThat(responses).hasSize(1);
        assertThat(responses.get(0).id).isEqualTo(301L);
        assertThat(responses.get(0).venueId).isEqualTo(20L);
        verify(eventService, never()).recordMilestone(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        verify(favoriteMapper, never()).updateById(any(UserVenueFavorite.class));
    }

    private FunnelEventRequest context() {
        FunnelEventRequest context = new FunnelEventRequest();
        context.sourceChannel = "web";
        context.campaignId = "camp-b5";
        context.pagePath = "/venues/20";
        return context;
    }

    private Venue activeVenue() {
        Venue venue = new Venue();
        venue.id = 20L;
        venue.name = "B5 羽毛球馆";
        venue.deleted = 0;
        return venue;
    }

    private UserVenueFavorite favorite(Long id, String status, Integer deleted) {
        UserVenueFavorite favorite = new UserVenueFavorite();
        favorite.id = id;
        favorite.userId = 7L;
        favorite.venueId = 20L;
        favorite.sourceChannel = "WEB";
        favorite.campaignId = "camp-b5";
        favorite.status = status;
        favorite.deleted = deleted;
        return favorite;
    }
}
