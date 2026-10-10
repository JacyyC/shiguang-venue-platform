package com.example.venue.notice;

import com.example.venue.common.BusinessException;
import com.example.venue.common.CurrentUser;
import com.example.venue.member.mapper.MemberAccountMapper;
import com.example.venue.notice.dto.AudiencePreviewRequest;
import com.example.venue.notice.entity.NoticeAudienceSnapshot;
import com.example.venue.notice.mapper.NoticeAudienceSnapshotMapper;
import com.example.venue.notice.service.NoticeAudienceService;
import com.example.venue.promotion.mapper.CouponTemplateMapper;
import com.example.venue.promotion.mapper.UserCouponMapper;
import com.example.venue.reservation.entity.Reservation;
import com.example.venue.reservation.mapper.ReservationMapper;
import com.example.venue.security.SecurityContext;
import com.example.venue.user.entity.SysUser;
import com.example.venue.user.mapper.SysUserMapper;
import com.example.venue.venue.service.VenueAccessService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NoticeAudienceServiceTest {
    @Mock
    private SysUserMapper userMapper;
    @Mock
    private MemberAccountMapper memberAccountMapper;
    @Mock
    private ReservationMapper reservationMapper;
    @Mock
    private UserCouponMapper userCouponMapper;
    @Mock
    private CouponTemplateMapper couponTemplateMapper;
    @Mock
    private NoticeAudienceSnapshotMapper audienceMapper;
    @Mock
    private VenueAccessService venueAccessService;

    private NoticeAudienceService service;

    @BeforeEach
    void setUp() {
        service = new NoticeAudienceService(userMapper, memberAccountMapper, reservationMapper,
            userCouponMapper, couponTemplateMapper, audienceMapper, venueAccessService);
        authenticate(new CurrentUser(1L, "system-admin", Set.of("SYSTEM_ADMIN"), Set.of()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void allUsersPreviewUsesOrdinaryUserRoleBoundary() {
        when(userMapper.findActiveUsersByRoleKey("USER")).thenReturn(List.of(
            user(1L, "ACTIVE", 0)
        ));

        AudiencePreviewRequest request = new AudiencePreviewRequest();
        request.targetScope = "ALL_USERS";

        var response = service.preview(request);

        assertThat(response.targetScope).isEqualTo("ALL_USERS");
        assertThat(response.audienceCount).isEqualTo(1);
        assertThat(response.deliverableCount).isEqualTo(1);
        assertThat(response.excludedCount).isZero();
        assertThat(response.samples).extracting(sample -> sample.userId)
            .containsExactly(1L);
        verify(userMapper).findActiveUsersByRoleKey("USER");
    }

    @Test
    void snapshotAudiencePersistsEveryCandidateAndMarksExcludedUsers() {
        when(userMapper.findActiveUsersByIdsAndRoleKey(any(), eq("USER"))).thenReturn(List.of(
            user(1L, "ACTIVE", 0)
        ));
        when(audienceMapper.insert(any(NoticeAudienceSnapshot.class))).thenAnswer(invocation -> {
            NoticeAudienceSnapshot snapshot = invocation.getArgument(0);
            snapshot.id = snapshot.userId + 70;
            return 1;
        });

        AudiencePreviewRequest request = new AudiencePreviewRequest();
        request.targetScope = "SPECIFIC_USERS";
        request.userIds = List.of(1L, 2L);

        List<NoticeAudienceSnapshot> snapshots = service.snapshotAudience(61L, 51L, request);

        assertThat(snapshots).extracting(snapshot -> snapshot.userId)
            .containsExactly(1L, 2L);

        ArgumentCaptor<NoticeAudienceSnapshot> captor = ArgumentCaptor.forClass(NoticeAudienceSnapshot.class);
        verify(audienceMapper, times(2)).insert(captor.capture());
        assertThat(captor.getAllValues()).allSatisfy(snapshot -> {
            assertThat(snapshot.batchId).isEqualTo(61L);
            assertThat(snapshot.noticeId).isEqualTo(51L);
            assertThat(snapshot.readStatus).isEqualTo("UNREAD");
            assertThat(snapshot.deleted).isZero();
        });
        assertThat(captor.getAllValues().get(0).deliveryStatus).isEqualTo("PENDING");
        assertThat(captor.getAllValues().get(0).excludeReason).isNull();
        assertThat(captor.getAllValues().get(1).deliveryStatus).isEqualTo("EXCLUDED");
        assertThat(captor.getAllValues().get(1).excludeReason).isEqualTo("USER_NOT_FOUND");
    }

    @Test
    void specificUsersDeduplicatesIdsPreservingOrder() {
        when(userMapper.findActiveUsersByIdsAndRoleKey(any(), eq("USER"))).thenReturn(List.of(
            user(3L, "ACTIVE", 0),
            user(1L, "ACTIVE", 0)
        ));

        AudiencePreviewRequest request = new AudiencePreviewRequest();
        request.targetScope = "SPECIFIC_USERS";
        request.userIds = List.of(3L, 3L, 1L);

        var response = service.preview(request);

        assertThat(response.samples).extracting(sample -> sample.userId)
            .containsExactly(3L, 1L);
        assertThat(response.audienceCount).isEqualTo(2);
        assertThat(response.deliverableCount).isEqualTo(2);
    }

    @Test
    void specificUsersExcludesActiveAccountsWithoutOrdinaryUserRole() {
        when(userMapper.findActiveUsersByIdsAndRoleKey(any(), eq("USER"))).thenReturn(List.of(
            user(3L, "ACTIVE", 0)
        ));

        AudiencePreviewRequest request = new AudiencePreviewRequest();
        request.targetScope = "SPECIFIC_USERS";
        request.userIds = List.of(3L, 9L);

        var response = service.preview(request);

        assertThat(response.samples).extracting(sample -> sample.userId)
            .containsExactly(3L, 9L);
        assertThat(response.deliverableCount).isEqualTo(1);
        assertThat(response.excludedCount).isEqualTo(1);
        assertThat(response.samples.get(1).deliverable).isFalse();
        assertThat(response.samples.get(1).excludeReason).isEqualTo("USER_NOT_FOUND");
    }

    @Test
    void specificUsersClassifiesDisabledUserAsNotActive() {
        when(userMapper.findActiveUsersByIdsAndRoleKey(any(), eq("USER"))).thenReturn(List.of(
            user(3L, "ACTIVE", 0),
            user(9L, "DISABLED", 0)
        ));

        AudiencePreviewRequest request = new AudiencePreviewRequest();
        request.targetScope = "SPECIFIC_USERS";
        request.userIds = List.of(3L, 9L);

        var response = service.preview(request);

        assertThat(response.samples).extracting(sample -> sample.userId)
            .containsExactly(3L, 9L);
        assertThat(response.deliverableCount).isEqualTo(1);
        assertThat(response.excludedCount).isEqualTo(1);
        assertThat(response.samples.get(0).deliverable).isTrue();
        assertThat(response.samples.get(1).deliverable).isFalse();
        assertThat(response.samples.get(1).excludeReason).isEqualTo("USER_NOT_ACTIVE");
    }

    @Test
    void specificUsersClassifiesDeletedUserAsUserDeleted() {
        when(userMapper.findActiveUsersByIdsAndRoleKey(any(), eq("USER"))).thenReturn(List.of(
            user(3L, "ACTIVE", 0),
            user(9L, "ACTIVE", 1)
        ));

        AudiencePreviewRequest request = new AudiencePreviewRequest();
        request.targetScope = "SPECIFIC_USERS";
        request.userIds = List.of(3L, 9L);

        var response = service.preview(request);

        assertThat(response.samples.get(1).deliverable).isFalse();
        assertThat(response.samples.get(1).excludeReason).isEqualTo("USER_DELETED");
    }

    @Test
    void venueAdminPreviewsOnlyRecentReserversForAnAuthorizedVenue() {
        authenticate(new CurrentUser(8L, "venue-admin", Set.of("VENUE_ADMIN"), Set.of()));
        Reservation reservation = new Reservation();
        reservation.userId = 3L;
        reservation.venueId = 20L;
        reservation.deleted = 0;
        when(reservationMapper.selectList(any())).thenReturn(List.of(reservation));
        when(userMapper.findActiveUsersByIdsAndRoleKey(any(), eq("USER"))).thenReturn(List.of(
            user(3L, "ACTIVE", 0)
        ));
        AudiencePreviewRequest request = new AudiencePreviewRequest();
        request.targetScope = "VENUE_RECENT_RESERVERS";
        request.targetVenueId = 20L;

        var response = service.preview(request);

        assertThat(response.samples).extracting(sample -> sample.userId).containsExactly(3L);
        assertThat(response.samples).extracting(sample -> sample.venueId).containsExactly(20L);
        verify(venueAccessService).requireVenueManageAccess(20L);
    }

    @Test
    void venueAdminCannotPreviewGlobalMemberOrSpecificUserAudiences() {
        authenticate(new CurrentUser(8L, "venue-admin", Set.of("VENUE_ADMIN"), Set.of()));
        for (String scope : List.of("ALL_USERS", "MEMBER_LEVEL", "SPECIFIC_USERS")) {
            AudiencePreviewRequest request = new AudiencePreviewRequest();
            request.targetScope = scope;
            request.targetVenueId = 20L;
            request.memberLevelId = 3L;
            request.userIds = List.of(1L);

            assertThatThrownBy(() -> service.preview(request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("只能预览已授权场馆的近期预约用户");
        }

        verify(venueAccessService, never()).requireVenueManageAccess(any());
        verify(userMapper, never()).findActiveUsersByRoleKey(any());
        verify(userMapper, never()).findActiveUsersByIdsAndRoleKey(any(), any());
    }

    @Test
    void venueAdminCannotPreviewAnUnauthorizedVenue() {
        authenticate(new CurrentUser(8L, "venue-admin", Set.of("VENUE_ADMIN"), Set.of()));
        doThrow(BusinessException.forbidden("无权管理该场馆数据"))
            .when(venueAccessService).requireVenueManageAccess(21L);
        AudiencePreviewRequest request = new AudiencePreviewRequest();
        request.targetScope = "VENUE_RECENT_RESERVERS";
        request.targetVenueId = 21L;

        assertThatThrownBy(() -> service.preview(request))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("无权管理该场馆数据");

        verify(venueAccessService).requireVenueManageAccess(21L);
        verify(reservationMapper, never()).selectList(any());
    }

    private SysUser user(Long id, String status, int deleted) {
        SysUser user = new SysUser();
        user.id = id;
        user.status = status;
        user.deleted = deleted;
        return user;
    }

    private void authenticate(CurrentUser user) {
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(user, null, List.of()));
    }
}
