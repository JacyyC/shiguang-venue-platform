package com.example.venue.usertime;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.example.venue.common.BusinessException;
import com.example.venue.common.CurrentUser;
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
import com.example.venue.usertime.service.MeTimeService;
import com.example.venue.venue.entity.Venue;
import com.example.venue.venue.mapper.VenueMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MeTimeServiceTest {
    @Mock
    private ReservationMapper reservationMapper;
    @Mock
    private VenueMapper venueMapper;
    @Mock
    private CommunityActivityParticipationMapper participationMapper;
    @Mock
    private CommunityActivityApplicationMapper applicationMapper;
    @Mock
    private CommunityActivityMapper activityMapper;
    @Mock
    private CommunityCompanionRequestMapper companionMapper;

    private MeTimeService meTimeService;

    @BeforeEach
    void setUp() {
        meTimeService = new MeTimeService(reservationMapper, venueMapper, participationMapper,
            applicationMapper, activityMapper, companionMapper);
        setCurrentUser(7L);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void mergesReservationParticipationAndPendingApplicationSortedByStartTimeDesc() {
        when(reservationMapper.selectList(any())).thenReturn(List.of(
            reservation(1L, "PAID", LocalDateTime.of(2026, 9, 10, 18, 0))));
        CommunityActivity activity = activity(50L, "城市夜跑", LocalDateTime.of(2026, 9, 12, 7, 0));
        when(participationMapper.selectList(any())).thenReturn(List.of(
            participation("ACTIVITY", 50L)));
        when(applicationMapper.selectList(any())).thenReturn(List.of());
        when(activityMapper.selectBatchIds(any())).thenReturn(List.of(activity));
        when(venueMapper.selectBatchIds(any())).thenReturn(List.of(venue(20L, "西岸运动公园")));

        PageResult<MeTimeItem> result = meTimeService.myTime(pageQuery());

        assertThat(result.getTotal()).isEqualTo(2);
        assertThat(result.getRecords().get(0).sourceType).isEqualTo("ACTIVITY");
        assertThat(result.getRecords().get(0).startTime).isEqualTo(LocalDateTime.of(2026, 9, 12, 7, 0));
        assertThat(result.getRecords().get(1).sourceType).isEqualTo("RESERVATION");
        assertThat(result.getRecords().get(1).detailPath).isEqualTo("/me/reservations");
    }

    @Test
    void pendingApplicationForJoinedTargetIsNotDoubleCounted() {
        when(reservationMapper.selectList(any())).thenReturn(List.of());
        when(participationMapper.selectList(any())).thenReturn(List.of(
            participation("COMPANION_REQUEST", 60L)));
        when(applicationMapper.selectList(any())).thenReturn(List.of(
            application("COMPANION_REQUEST", 60L)));
        when(companionMapper.selectBatchIds(any())).thenReturn(List.of(companion(60L, "双打缺一", "PUBLISHED")));

        PageResult<MeTimeItem> result = meTimeService.myTime(pageQuery());

        assertThat(result.getTotal()).isEqualTo(1);
        MeTimeItem item = result.getRecords().get(0);
        assertThat(item.sourceType).isEqualTo("COMPANION_REQUEST");
        assertThat(item.pending).isFalse();
        assertThat(item.status).isEqualTo("PUBLISHED");
    }

    @Test
    void pendingApplicationOnCanceledCompanionIsExcluded() {
        when(reservationMapper.selectList(any())).thenReturn(List.of());
        when(participationMapper.selectList(any())).thenReturn(List.of());
        when(applicationMapper.selectList(any())).thenReturn(List.of(
            application("COMPANION_REQUEST", 61L)));
        when(companionMapper.selectBatchIds(any())).thenReturn(List.of(companion(61L, "已取消邀约", "CANCELED")));

        PageResult<MeTimeItem> result = meTimeService.myTime(pageQuery());

        assertThat(result.getTotal()).isZero();
        assertThat(result.getRecords()).isEmpty();
    }

    @Test
    void standalonePendingApplicationIsMarkedPending() {
        when(reservationMapper.selectList(any())).thenReturn(List.of());
        when(participationMapper.selectList(any())).thenReturn(List.of());
        when(applicationMapper.selectList(any())).thenReturn(List.of(
            application("ACTIVITY", 50L)));
        when(activityMapper.selectBatchIds(any())).thenReturn(List.of(
            activity(50L, "读书夜", LocalDateTime.of(2026, 9, 11, 19, 0))));

        PageResult<MeTimeItem> result = meTimeService.myTime(pageQuery());

        assertThat(result.getTotal()).isEqualTo(1);
        MeTimeItem item = result.getRecords().get(0);
        assertThat(item.pending).isTrue();
        assertThat(item.status).isEqualTo("PENDING");
        assertThat(item.detailPath).isEqualTo("/activities/50");
    }

    @Test
    void queriesAreScopedToCurrentUser() {
        when(reservationMapper.selectList(any())).thenReturn(List.of());
        when(participationMapper.selectList(any())).thenReturn(List.of());
        when(applicationMapper.selectList(any())).thenReturn(List.of());

        meTimeService.myTime(pageQuery());

        ArgumentCaptor<QueryWrapper<Reservation>> captor = ArgumentCaptor.forClass(QueryWrapper.class);
        org.mockito.Mockito.verify(reservationMapper).selectList(captor.capture());
        System.out.println("DBG-ALLVALUES=" + captor.getAllValues().size());
        System.out.println("DBG-SEG=" + captor.getValue().getSqlSegment());
        System.out.println("DBG-PARAMS=" + captor.getValue().getParamNameValuePairs());
        System.out.println("DBG-CLASS=" + captor.getValue().getClass());
        assertThat(captor.getValue().getParamNameValuePairs().values()).contains(7L);
    }

    @Test
    void anonymousUserIsRejected() {
        SecurityContextHolder.clearContext();
        assertThatThrownBy(() -> meTimeService.myTime(pageQuery()))
            .isInstanceOf(BusinessException.class);
    }

    private void setCurrentUser(Long id) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
            new CurrentUser(id, "tester" + id, Set.of("USER"), Set.of()), null, List.of()));
    }

    private PageQuery pageQuery() {
        PageQuery query = new PageQuery();
        query.setPageNum(1);
        query.setPageSize(20);
        return query;
    }

    private Reservation reservation(Long id, String status, LocalDateTime startTime) {
        Reservation reservation = new Reservation();
        reservation.id = id;
        reservation.userId = 7L;
        reservation.venueId = 20L;
        reservation.status = status;
        reservation.startTime = startTime;
        reservation.endTime = startTime.plusHours(1);
        reservation.amount = BigDecimal.valueOf(60);
        reservation.deleted = 0;
        return reservation;
    }

    private CommunityActivity activity(Long id, String title, LocalDateTime startTime) {
        CommunityActivity activity = new CommunityActivity();
        activity.id = id;
        activity.title = title;
        activity.startTime = startTime;
        activity.endTime = startTime.plusHours(2);
        activity.status = "PUBLISHED";
        return activity;
    }

    private CommunityCompanionRequest companion(Long id, String title, String status) {
        CommunityCompanionRequest companion = new CommunityCompanionRequest();
        companion.id = id;
        companion.title = title;
        companion.status = status;
        companion.windowStart = LocalDateTime.of(2026, 9, 13, 18, 30);
        companion.windowEnd = LocalDateTime.of(2026, 9, 13, 20, 0);
        return companion;
    }

    private CommunityActivityParticipation participation(String targetType, Long targetId) {
        CommunityActivityParticipation participation = new CommunityActivityParticipation();
        participation.id = targetId * 10;
        participation.targetType = targetType;
        participation.targetId = targetId;
        participation.userId = 7L;
        participation.status = "ACTIVE";
        return participation;
    }

    private CommunityActivityApplication application(String targetType, Long targetId) {
        CommunityActivityApplication application = new CommunityActivityApplication();
        application.id = targetId * 10 + 1;
        application.targetType = targetType;
        application.targetId = targetId;
        application.applicantUserId = 7L;
        application.status = "PENDING";
        return application;
    }

    private Venue venue(Long id, String name) {
        Venue venue = new Venue();
        venue.id = id;
        venue.name = name;
        return venue;
    }

    @Test
    void countsUseExactServerSideCounts() {
        when(applicationMapper.selectCount(any())).thenReturn(2L);
        when(participationMapper.selectCount(any())).thenReturn(3L);
        when(activityMapper.selectCount(any())).thenReturn(1L);
        when(companionMapper.selectCount(any())).thenReturn(4L);

        assertThat(meTimeService.counts())
            .isEqualTo(new MeTimeCounts(2, 3, 5));
    }
}
