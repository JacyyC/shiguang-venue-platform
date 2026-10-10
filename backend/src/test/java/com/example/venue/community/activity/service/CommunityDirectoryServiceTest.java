package com.example.venue.community.activity.service;

import com.example.venue.common.BusinessException;
import com.example.venue.common.CurrentUser;
import com.example.venue.community.activity.dto.ActivityRequests;
import com.example.venue.community.activity.dto.ActivityResponses;
import com.example.venue.community.activity.mapper.CommunityActivityApplicationMapper;
import com.example.venue.community.activity.mapper.CommunityActivityParticipationMapper;
import com.example.venue.community.activity.mapper.CommunityDirectoryMapper;
import com.example.venue.community.activity.mapper.CommunityDirectoryMapper.DirectoryRow;
import com.example.venue.community.entity.CommunityGroup;
import com.example.venue.community.mapper.CommunityGroupMapper;
import com.example.venue.community.service.CommunityAccessService;
import com.example.venue.user.entity.SysUser;
import com.example.venue.user.mapper.SysUserMapper;
import com.example.venue.venue.entity.Venue;
import com.example.venue.venue.entity.VenueSpace;
import com.example.venue.venue.mapper.VenueMapper;
import com.example.venue.venue.mapper.VenueSpaceMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CommunityDirectoryServiceTest {
    private static final LocalDateTime START = LocalDateTime.of(2099, 8, 1, 10, 0);
    private static final LocalDateTime END = START.plusHours(2);

    private final CommunityDirectoryMapper directoryMapper = mock(CommunityDirectoryMapper.class);
    private final CommunityActivityApplicationMapper applicationMapper =
        mock(CommunityActivityApplicationMapper.class);
    private final CommunityActivityParticipationMapper participationMapper =
        mock(CommunityActivityParticipationMapper.class);
    private final CommunityGroupMapper groupMapper = mock(CommunityGroupMapper.class);
    private final SysUserMapper userMapper = mock(SysUserMapper.class);
    private final VenueMapper venueMapper = mock(VenueMapper.class);
    private final VenueSpaceMapper spaceMapper = mock(VenueSpaceMapper.class);
    private final CommunityAccessService accessService = mock(CommunityAccessService.class);

    private final CommunityDirectoryService service = new CommunityDirectoryService(
        directoryMapper, applicationMapper, participationMapper, groupMapper, userMapper,
        venueMapper, spaceMapper, accessService, new ObjectMapper().findAndRegisterModules());

    @BeforeEach
    void setUp() {
        when(accessService.requireCurrentUser()).thenReturn(
            new CurrentUser(7L, "user-7", Set.of(), Set.of("community:access")));
        when(applicationMapper.selectList(any())).thenReturn(List.of());
        when(participationMapper.selectList(any())).thenReturn(List.of());
    }

    @Test
    void firstPagePassesNoCursorAndNormalizesLimitAndTypes() {
        when(directoryMapper.selectPage(eq(7L), isNull(), isNull(), isNull(), isNull(), isNull(), isNull(),
            isNull(), isNull(), eq(false), any(), isNull(), isNull(), isNull(), any(), eq(21)))
            .thenReturn(List.of());
        when(directoryMapper.countByType(eq(7L), isNull(), isNull(), isNull(), isNull(), isNull(), isNull(),
            isNull(), isNull(), eq(false), any())).thenReturn(List.of());
        when(directoryMapper.countByDate(eq(7L), isNull(), isNull(), isNull(), isNull(), isNull(), isNull(),
            isNull(), isNull(), eq(false), any())).thenReturn(List.of());

        ActivityRequests.DirectoryQuery query = new ActivityRequests.DirectoryQuery();
        query.types = " companion , ACTIVITY ,COMPANION";
        query.limit = 0;
        service.directory(query);

        ArgumentCaptor<List<String>> types = ArgumentCaptor.captor();
        verify(directoryMapper).selectPage(eq(7L), isNull(), isNull(), isNull(), isNull(), isNull(), isNull(),
            isNull(), isNull(), eq(false), types.capture(), isNull(), isNull(), isNull(), any(),
            eq(21));
        assertThat(types.getValue()).containsExactly("ACTIVITY", "COMPANION");
    }

    @Test
    void overlargeLimitIsClampedToFifty() {
        when(directoryMapper.selectPage(eq(7L), isNull(), isNull(), isNull(), isNull(), isNull(), isNull(),
            isNull(), isNull(), eq(false), any(), isNull(), isNull(), isNull(), any(), eq(51)))
            .thenReturn(List.of());
        when(directoryMapper.countByType(eq(7L), isNull(), isNull(), isNull(), isNull(), isNull(), isNull(),
            isNull(), isNull(), eq(false), any())).thenReturn(List.of());
        when(directoryMapper.countByDate(eq(7L), isNull(), isNull(), isNull(), isNull(), isNull(), isNull(),
            isNull(), isNull(), eq(false), any())).thenReturn(List.of());

        ActivityRequests.DirectoryQuery query = new ActivityRequests.DirectoryQuery();
        query.limit = 99;
        service.directory(query);

        verify(directoryMapper).selectPage(eq(7L), isNull(), isNull(), isNull(), isNull(), isNull(), isNull(),
            isNull(), isNull(), eq(false), any(), isNull(), isNull(), isNull(), any(), eq(51));
    }

    @Test
    void cursorRoundTripCarriesSortAnchorAndFingerprint() {
        DirectoryRow row = row("ACTIVITY", 11L, START);
        when(directoryMapper.selectPage(eq(7L), isNull(), isNull(), isNull(), isNull(), isNull(), isNull(),
            isNull(), isNull(), eq(false), any(), isNull(), isNull(), isNull(), any(), eq(2)))
            .thenReturn(List.of(row, row));
        when(directoryMapper.countByType(any(), isNull(), isNull(), isNull(), isNull(), isNull(), isNull(),
            isNull(), isNull(), eq(false), any())).thenReturn(List.of());
        when(directoryMapper.countByDate(any(), isNull(), isNull(), isNull(), isNull(), isNull(), isNull(),
            isNull(), isNull(), eq(false), any())).thenReturn(List.of());

        ActivityRequests.DirectoryQuery first = new ActivityRequests.DirectoryQuery();
        first.limit = 1;
        ActivityResponses.DirectoryPage page = service.directory(first);
        assertThat(page.hasMore()).isTrue();
        assertThat(page.nextCursor()).isNotBlank();

        // 同一筛选条件携带游标再取下一页：游标解码为同一排序锚点。
        ActivityRequests.DirectoryQuery second = new ActivityRequests.DirectoryQuery();
        second.limit = 1;
        second.cursor = page.nextCursor();
        when(directoryMapper.selectPage(eq(7L), isNull(), isNull(), isNull(), isNull(), isNull(), isNull(),
            isNull(), isNull(), eq(false), any(), eq(START), eq("ACTIVITY"), eq(11L), any(), eq(2)))
            .thenReturn(List.of());
        ActivityResponses.DirectoryPage secondPage = service.directory(second);
        assertThat(secondPage.hasMore()).isFalse();
        assertThat(secondPage.nextCursor()).isNull();
    }

    @Test
    void cursorFromDifferentFiltersIsRejected() {
        DirectoryRow row = row("ACTIVITY", 11L, START);
        when(directoryMapper.selectPage(any(), isNull(), isNull(), isNull(), isNull(), isNull(), isNull(),
            isNull(), isNull(), eq(false), any(), isNull(), isNull(), isNull(), any(), eq(2)))
            .thenReturn(List.of(row, row));
        when(directoryMapper.countByType(any(), isNull(), isNull(), isNull(), isNull(), isNull(), isNull(),
            isNull(), isNull(), eq(false), any())).thenReturn(List.of());
        when(directoryMapper.countByDate(any(), isNull(), isNull(), isNull(), isNull(), isNull(), isNull(),
            isNull(), isNull(), eq(false), any())).thenReturn(List.of());

        ActivityRequests.DirectoryQuery first = new ActivityRequests.DirectoryQuery();
        first.limit = 1;
        String cursor = service.directory(first).nextCursor();

        ActivityRequests.DirectoryQuery changed = new ActivityRequests.DirectoryQuery();
        changed.limit = 1;
        changed.keyword = "夜跑";
        changed.cursor = cursor;
        assertThatThrownBy(() -> service.directory(changed))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("筛选条件已变化");
    }

    @Test
    void corruptCursorIsRejected() {
        ActivityRequests.DirectoryQuery query = new ActivityRequests.DirectoryQuery();
        query.cursor = "not-a-cursor";
        assertThatThrownBy(() -> service.directory(query))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("游标不合法");
    }

    @Test
    void invalidTypeAndAreaCodeAndTimeWindowAreRejected() {
        ActivityRequests.DirectoryQuery badType = new ActivityRequests.DirectoryQuery();
        badType.types = "RESERVATION";
        assertThatThrownBy(() -> service.directory(badType))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("目录类型不合法");

        ActivityRequests.DirectoryQuery badArea = new ActivityRequests.DirectoryQuery();
        badArea.areaCode = "north campus";
        assertThatThrownBy(() -> service.directory(badArea))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("校区区域代码不合法");

        ActivityRequests.DirectoryQuery reversed = new ActivityRequests.DirectoryQuery();
        reversed.fromTime = END;
        reversed.toTime = START;
        assertThatThrownBy(() -> service.directory(reversed))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("时间窗口不合法");

        ActivityRequests.DirectoryQuery tooWide = new ActivityRequests.DirectoryQuery();
        tooWide.fromTime = START;
        tooWide.toTime = START.plusDays(63);
        assertThatThrownBy(() -> service.directory(tooWide))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("时间窗口过长");
    }

    @Test
    void itemsCarryDisplayNamesAndViewerState() {
        DirectoryRow row = row("ACTIVITY", 11L, START);
        row.groupId = 21L;
        row.ownerUserId = 31L;
        row.venueId = 41L;
        row.spaceId = 51L;
        row.areaCode = "NORTH_CAMPUS";
        row.title = "夜跑";
        when(directoryMapper.selectPage(any(), any(), isNull(), isNull(), isNull(), isNull(), isNull(),
            isNull(), isNull(), eq(false), any(), isNull(), isNull(), isNull(), any(), eq(21)))
            .thenReturn(List.of(row));
        when(directoryMapper.countByType(any(), any(), isNull(), isNull(), isNull(), isNull(), isNull(),
            isNull(), isNull(), eq(false), any())).thenReturn(List.of());
        when(directoryMapper.countByDate(any(), any(), isNull(), isNull(), isNull(), isNull(), isNull(),
            isNull(), isNull(), eq(false), any())).thenReturn(List.of(
                dateCount(LocalDate.of(2099, 8, 1), 3)));

        CommunityGroup group = new CommunityGroup();
        group.id = 21L;
        group.name = "周末挥拍俱乐部";
        when(groupMapper.selectBatchIds(any())).thenReturn(List.of(group));
        SysUser user = new SysUser();
        user.id = 31L;
        user.username = "user-31";
        user.nickname = "小挥";
        when(userMapper.selectBatchIds(any())).thenReturn(List.of(user));
        Venue venue = new Venue();
        venue.id = 41L;
        venue.name = "西岸运动场";
        when(venueMapper.selectBatchIds(any())).thenReturn(List.of(venue));
        VenueSpace space = new VenueSpace();
        space.id = 51L;
        space.name = "日落网球场";
        when(spaceMapper.selectBatchIds(any())).thenReturn(List.of(space));

        com.example.venue.community.activity.entity.CommunityActivityApplication application =
            new com.example.venue.community.activity.entity.CommunityActivityApplication();
        application.targetType = "ACTIVITY";
        application.targetId = 11L;
        application.applicantUserId = 7L;
        application.status = "PENDING";
        when(applicationMapper.selectList(any())).thenReturn(List.of(application));
        com.example.venue.community.activity.entity.CommunityActivityParticipation participation =
            new com.example.venue.community.activity.entity.CommunityActivityParticipation();
        participation.targetType = "ACTIVITY";
        participation.targetId = 11L;
        participation.userId = 7L;
        participation.status = "ACTIVE";
        participation.roleType = "PARTICIPANT";
        when(participationMapper.selectList(any())).thenReturn(List.of(participation));

        ActivityRequests.DirectoryQuery query = new ActivityRequests.DirectoryQuery();
        query.keyword = "夜跑";
        ActivityResponses.DirectoryPage page = service.directory(query);

        assertThat(page.records()).hasSize(1);
        ActivityResponses.DirectoryItem item = page.records().get(0);
        assertThat(item.groupName).isEqualTo("周末挥拍俱乐部");
        assertThat(item.organizerName).isEqualTo("小挥");
        assertThat(item.venueName).isEqualTo("西岸运动场");
        assertThat(item.spaceName).isEqualTo("日落网球场");
        assertThat(item.viewerApplicationStatus).isEqualTo("PENDING");
        assertThat(item.viewerParticipationRole).isEqualTo("PARTICIPANT");
        assertThat(item.startTime).isEqualTo(START);
        assertThat(page.dateCounts()).containsExactly(new ActivityResponses.DateCount(LocalDate.of(2099, 8, 1), 3));
    }

    @Test
    void applicationMetricsCountsPendingAndRejectsUnknownTargets() {
        when(applicationMapper.selectCount(any())).thenReturn(3L);
        assertThat(service.applicationMetrics("ACTIVITY", 11L).pendingApplications()).isEqualTo(3L);
        assertThatThrownBy(() -> service.applicationMetrics("RESERVATION", 1L))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("目标不合法");
    }

    @Test
    void myHostedIncludesDraftsAndReportsTotal() {
        DirectoryRow draft = row("ACTIVITY", 12L, START);
        draft.updateTime = LocalDateTime.of(2099, 7, 31, 8, 0);
        draft.status = "DRAFT";
        when(directoryMapper.selectHostedPage(eq(7L), isNull(), isNull(), isNull(), eq(21)))
            .thenReturn(List.of(draft));
        when(directoryMapper.countHosted(7L)).thenReturn(1L);

        ActivityResponses.DirectoryPage page = service.myHosted(null, 20);
        assertThat(page.total()).isEqualTo(1L);
        assertThat(page.records()).hasSize(1);
        assertThat(page.records().get(0).status).isEqualTo("DRAFT");
    }

    @Test
    void myHostedCursorIsBoundToHostedFingerprint() {
        DirectoryRow row = row("ACTIVITY", 12L, START);
        row.updateTime = LocalDateTime.of(2099, 7, 31, 8, 0);
        when(directoryMapper.selectHostedPage(eq(7L), isNull(), isNull(), isNull(), eq(2)))
            .thenReturn(List.of(row, row));
        when(directoryMapper.countHosted(7L)).thenReturn(2L);

        ActivityResponses.DirectoryPage page = service.myHosted(null, 1);
        assertThat(page.hasMore()).isTrue();

        when(directoryMapper.selectHostedPage(eq(7L), eq(row.updateTime), eq("ACTIVITY"), eq(12L), eq(2)))
            .thenReturn(List.of());
        ActivityResponses.DirectoryPage second = service.myHosted(page.nextCursor(), 1);
        assertThat(second.hasMore()).isFalse();
    }

    private DirectoryRow row(String sourceType, long sourceId, LocalDateTime start) {
        DirectoryRow row = new DirectoryRow();
        row.sourceType = sourceType;
        row.sourceId = sourceId;
        row.sortStart = start;
        row.sortEnd = END;
        row.updateTime = start;
        row.status = "PUBLISHED";
        row.capacityTotal = 8;
        row.participantCount = 2;
        row.activityType = "SPORTS";
        row.title = "活动-" + sourceId;
        return row;
    }

    private CommunityDirectoryMapper.DateCountRow dateCount(LocalDate date, long total) {
        CommunityDirectoryMapper.DateCountRow row = new CommunityDirectoryMapper.DateCountRow();
        row.countDate = date;
        row.total = total;
        return row;
    }
}
