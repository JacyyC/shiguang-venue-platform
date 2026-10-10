package com.example.venue.community.activity;

import com.example.venue.community.activity.controller.CommunityActivityController;
import com.example.venue.community.activity.dto.ActivityRequests;
import com.example.venue.community.activity.dto.ActivityResponses;
import com.example.venue.community.activity.entity.CommunityActivity;
import com.example.venue.community.activity.entity.CommunityActivityApplication;
import com.example.venue.community.activity.entity.CommunityActivityParticipation;
import com.example.venue.community.activity.service.ActivityParticipationService;
import com.example.venue.community.activity.service.CompanionMatchingService;
import com.example.venue.community.activity.service.CommunityActivityService;
import com.example.venue.community.activity.service.CommunityDirectoryService;
import com.example.venue.community.dto.CursorPage;
import com.example.venue.community.mapper.CommunityGroupMapper;
import com.example.venue.user.mapper.SysUserMapper;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Size;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(CommunityActivityController.class)
@ContextConfiguration(classes = {
    CommunityActivityController.class,
    CommunityActivityControllerTest.MethodSecurityConfig.class
})
class CommunityActivityControllerTest {
    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private CommunityActivityService activityService;
    @MockBean
    private ActivityParticipationService participationService;
    @MockBean
    private CompanionMatchingService matchingService;
    /**
     * 卡片「来源」补全使用的小组与用户 Mapper 属于真实构造依赖。
     * 列表路径会调用它们批量补全名称，缺失时按根约定回落为空，不能靠伪造可选依赖绕过装配。
     */
    @MockBean
    private CommunityGroupMapper groupMapper;
    @MockBean
    private SysUserMapper userMapper;
    @MockBean
    private CommunityDirectoryService directoryService;

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"activities", "companion-requests"})
    void fiftySourceNamesPreservePageAndMissingFallbackWithBoundedQueries(String path) throws Exception {
        var activities = new java.util.ArrayList<CommunityActivity>();
        var companions = new java.util.ArrayList<com.example.venue.community.activity.entity.CommunityCompanionRequest>();
        var groups = new java.util.ArrayList<com.example.venue.community.entity.CommunityGroup>();
        for (long i = 1; i <= 50; i++) {
            CommunityActivity a = activity();
            a.id = i; a.groupId = i; a.organizerUserId = 99L; activities.add(a);
            var c = new com.example.venue.community.activity.entity.CommunityCompanionRequest();
            c.id = i; c.groupId = i; c.ownerUserId = 99L; companions.add(c);
            if (i < 50) {
                var group = new com.example.venue.community.entity.CommunityGroup();
                group.id = i; group.name = "来源小组" + i; groups.add(group);
            }
        }
        var sourceUser = new com.example.venue.user.entity.SysUser();
        sourceUser.id = 99L; sourceUser.username = "fallback-name"; sourceUser.nickname = " ";
        when(groupMapper.selectBatchIds(any())).thenReturn(groups);
        when(userMapper.selectBatchIds(any())).thenReturn(List.of(sourceUser));
        if (path.equals("activities")) {
            when(activityService.discoverActivities(any())).thenReturn(CursorPage.of(activities, 88L, true));
        } else {
            when(activityService.discoverCompanions(any())).thenReturn(CursorPage.of(companions, 88L, true));
        }
        mockMvc.perform(get("/api/community/" + path).param("limit", "50").with(communityUser()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.records.length()").value(50))
            .andExpect(jsonPath("$.data.records[0].groupName").value("来源小组1"))
            .andExpect(jsonPath("$.data.records[48].groupName").value("来源小组49"))
            .andExpect(jsonPath("$.data.records[49].groupName").doesNotExist())
            .andExpect(jsonPath("$.data.records[49].organizerName").value("fallback-name"))
            .andExpect(jsonPath("$.data.nextCursor").value(88))
            .andExpect(jsonPath("$.data.hasMore").value(true));
        verify(groupMapper).selectBatchIds(any());
        org.mockito.Mockito.verifyNoMoreInteractions(groupMapper);
    }

    @Test
    void everyActivityEndpointRequiresCommunityAccessAuthority() {
        PreAuthorize authorization = CommunityActivityController.class.getAnnotation(PreAuthorize.class);

        assertThat(authorization).isNotNull();
        assertThat(authorization.value()).isEqualTo("hasAuthority('community:access')");
        assertThat(CommunityActivityController.class.getDeclaredMethods())
            .filteredOn(method -> java.lang.reflect.Modifier.isPublic(method.getModifiers()))
            .allSatisfy(method -> assertThat(method.getAnnotation(PreAuthorize.class)).isNull());
    }

    @Test
    void anonymousAndUsersWithoutCommunityAuthorityCannotCallActivityApis() throws Exception {
        mockMvc.perform(get("/api/community/activities"))
            .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/community/activities")
                .with(user("venue-only").roles("VENUE_ADMIN")))
            .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/community/activities")
                .with(user("venue-only").roles("VENUE_ADMIN"))
                .header("Idempotency-Key", "forbidden-create")
                .contentType(MediaType.APPLICATION_JSON)
                .content(validActivityJson()))
            .andExpect(status().isForbidden());
    }

    @Test
    void directoryEndpointBindsQueryAndRequiresCommunityAccess() throws Exception {
        when(directoryService.directory(any(ActivityRequests.DirectoryQuery.class)))
            .thenReturn(new ActivityResponses.DirectoryPage(List.of(), null, false, 0, Map.of(), List.of()));

        mockMvc.perform(get("/api/community/directory").with(communityUser())
                .param("keyword", "夜跑")
                .param("types", "ACTIVITY,COMPANION")
                .param("onlyAvailable", "true")
                .param("limit", "50"))
            .andExpect(status().isOk());

        ArgumentCaptor<ActivityRequests.DirectoryQuery> captor =
            ArgumentCaptor.forClass(ActivityRequests.DirectoryQuery.class);
        verify(directoryService).directory(captor.capture());
        assertThat(captor.getValue().keyword).isEqualTo("夜跑");
        assertThat(captor.getValue().onlyAvailable).isTrue();

        mockMvc.perform(get("/api/community/directory"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void myHostedEndpointDefaultsLimitAndDeclaresTheBound() throws Exception {
        when(directoryService.myHosted(isNull(), eq(20)))
            .thenReturn(new ActivityResponses.DirectoryPage(List.of(), null, false, 0, Map.of(), List.of()));

        mockMvc.perform(get("/api/community/my/hosted").with(communityUser()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.total").value(0));

        verify(directoryService).myHosted(isNull(), eq(20));

        java.lang.reflect.Method method = CommunityActivityController.class
            .getDeclaredMethod("myHosted", String.class, int.class);
        boolean declaredMax = false;
        for (java.lang.annotation.Annotation annotation : method.getParameterAnnotations()[1]) {
            if (annotation instanceof Max max) {
                declaredMax = max.value() == 50;
            }
        }
        assertThat(declaredMax).isTrue();
    }

    @Test
    void discoveryUsesAWhiteListResponseAndDeclaresTheLimitBound() throws Exception {
        CommunityActivity activity = activity();
        when(activityService.discoverActivities(any(ActivityRequests.DiscoveryQuery.class)))
            .thenReturn(CursorPage.of(List.of(activity), null, false));

        LocalDateTime fromTime = LocalDateTime.of(2099, 8, 1, 8, 0);
        LocalDateTime toTime = LocalDateTime.of(2099, 8, 1, 12, 0);

        mockMvc.perform(get("/api/community/activities")
                .with(communityUser())
                .param("keyword", "期末")
                .param("activityType", "STUDY")
                .param("fromTime", fromTime.toString())
                .param("toTime", toTime.toString())
                .param("venueId", "3")
                .param("areaCode", "NORTH_CAMPUS")
                .param("cursorId", "81")
                .param("limit", "50"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.records[0].id").value(41))
            .andExpect(jsonPath("$.data.records[0].location.commitmentStatus").value("CONFIRMED"))
            .andExpect(jsonPath("$.data.records[0].location.reservationId").doesNotExist())
            .andExpect(jsonPath("$.data.records[0].reservationId").doesNotExist())
            .andExpect(jsonPath("$.data.records[0].commitmentVersion").doesNotExist())
            .andExpect(jsonPath("$.data.records[0].description").value("公开活动说明"));

        Max max = ActivityRequests.DiscoveryQuery.class.getField("limit").getAnnotation(Max.class);
        Size keywordSize = ActivityRequests.DiscoveryQuery.class.getField("keyword").getAnnotation(Size.class);
        assertThat(max).isNotNull();
        assertThat(max.value()).isEqualTo(50);
        assertThat(keywordSize).isNotNull();
        assertThat(keywordSize.max()).isEqualTo(80);

        ArgumentCaptor<ActivityRequests.DiscoveryQuery> queryCaptor =
            ArgumentCaptor.forClass(ActivityRequests.DiscoveryQuery.class);
        verify(activityService).discoverActivities(queryCaptor.capture());
        assertThat(queryCaptor.getValue().keyword).isEqualTo("期末");
        assertThat(queryCaptor.getValue().activityType).isEqualTo("STUDY");
        assertThat(queryCaptor.getValue().fromTime).isEqualTo(fromTime);
        assertThat(queryCaptor.getValue().toTime).isEqualTo(toTime);
        assertThat(queryCaptor.getValue().venueId).isEqualTo(3L);
        assertThat(queryCaptor.getValue().areaCode).isEqualTo("NORTH_CAMPUS");
        assertThat(queryCaptor.getValue().cursorId).isEqualTo(81L);
        assertThat(queryCaptor.getValue().limit).isEqualTo(50);
    }

    @Test
    void applicationQueryBindsStatusCursorAndLimit() throws Exception {
        when(participationService.applications(
            eq("ACTIVITY"), eq(41L), any(ActivityRequests.ApplicationQuery.class)))
            .thenReturn(CursorPage.of(List.of(), null, false));

        mockMvc.perform(get("/api/community/activities/41/applications")
                .with(communityUser())
                .param("status", "PENDING")
                .param("cursorId", "73")
                .param("limit", "37"))
            .andExpect(status().isOk());

        ArgumentCaptor<ActivityRequests.ApplicationQuery> queryCaptor =
            ArgumentCaptor.forClass(ActivityRequests.ApplicationQuery.class);
        verify(participationService).applications(eq("ACTIVITY"), eq(41L), queryCaptor.capture());
        assertThat(queryCaptor.getValue().status).isEqualTo("PENDING");
        assertThat(queryCaptor.getValue().cursorId).isEqualTo(73L);
        assertThat(queryCaptor.getValue().limit).isEqualTo(37);
    }

    @Test
    void viewerStateReturnsOnlyTheCurrentUsersApplicationAndParticipationContract() throws Exception {
        CommunityActivityApplication application = new CommunityActivityApplication();
        application.id = 51L;
        application.targetType = "ACTIVITY";
        application.targetId = 41L;
        application.applicantUserId = 9L;
        application.status = "ACCEPTED";
        application.decidedBy = 7L;
        CommunityActivityParticipation participation = new CommunityActivityParticipation();
        participation.id = 61L;
        participation.targetType = "ACTIVITY";
        participation.targetId = 41L;
        participation.userId = 9L;
        participation.roleType = "PARTICIPANT";
        participation.status = "ACTIVE";
        participation.applicationId = application.id;
        when(participationService.viewerState("ACTIVITY", 41L))
            .thenReturn(ActivityResponses.viewerState(application, participation));

        mockMvc.perform(get("/api/community/activities/41/my-state")
                .with(communityUser()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.application.id").value(51))
            .andExpect(jsonPath("$.data.application.status").value("ACCEPTED"))
            .andExpect(jsonPath("$.data.application.decidedBy").doesNotExist())
            .andExpect(jsonPath("$.data.participation.id").value(61))
            .andExpect(jsonPath("$.data.participation.status").value("ACTIVE"))
            .andExpect(jsonPath("$.data.participation.applicationId").doesNotExist());

        verify(participationService).viewerState("ACTIVITY", 41L);
    }

    @Test
    void writeEndpointsRequireAndForwardIdempotencyKey() throws Exception {
        when(activityService.createActivity(any(ActivityRequests.CreateActivity.class), eq("activity-create")))
            .thenReturn(activity());

        mockMvc.perform(post("/api/community/activities")
                .with(communityUser())
                .contentType(MediaType.APPLICATION_JSON)
                .content(validActivityJson()))
            .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/community/activities")
                .with(communityUser())
                .header("Idempotency-Key", "activity-create")
                .contentType(MediaType.APPLICATION_JSON)
                .content(validActivityJson()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.id").value(41))
            .andExpect(jsonPath("$.data.location.commitmentStatus").value("CONFIRMED"));

        verify(activityService).createActivity(any(ActivityRequests.CreateActivity.class), eq("activity-create"));
    }

    @Test
    void matchesExposeReasonCodeArrayButNoPersistedJsonEvidence() throws Exception {
        ActivityResponses.CompanionRequest candidate = new ActivityResponses.CompanionRequest();
        candidate.id = 72L;
        candidate.ownerUserId = 9L;
        candidate.title = "图书馆复习搭子";
        candidate.status = "PUBLISHED";
        ActivityResponses.Match match = new ActivityResponses.Match();
        match.decisionId = 91L;
        match.candidate = candidate;
        match.ruleVersion = CompanionMatchingService.RULE_VERSION;
        match.score = 90;
        match.reasonCodes = List.of("ACTIVITY_TYPE_MATCH", "TIME_WINDOW_OVERLAP");
        when(matchingService.matches(71L, 20))
            .thenReturn(new ActivityResponses.MatchPage(List.of(match), CompanionMatchingService.RULE_VERSION));

        mockMvc.perform(get("/api/community/companion-requests/71/matches")
                .with(communityUser()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.ruleVersion").value("COMPANION_MATCH_V1"))
            .andExpect(jsonPath("$.data.records[0].reasonCodes[0]").value("ACTIVITY_TYPE_MATCH"))
            .andExpect(jsonPath("$.data.records[0].reasonCodesJson").doesNotExist())
            .andExpect(jsonPath("$.data.records[0].evidenceJson").doesNotExist());
    }

    private org.springframework.test.web.servlet.request.RequestPostProcessor communityUser() {
        return user("community-user")
            .authorities(new SimpleGrantedAuthority("community:access"));
    }

    private CommunityActivity activity() {
        CommunityActivity activity = new CommunityActivity();
        activity.id = 41L;
        activity.activityNo = "ACT-41";
        activity.groupId = 11L;
        activity.organizerUserId = 7L;
        activity.activityType = "STUDY";
        activity.title = "期末复习";
        activity.description = "公开活动说明";
        activity.startTime = LocalDateTime.of(2099, 8, 1, 9, 0);
        activity.endTime = LocalDateTime.of(2099, 8, 1, 11, 0);
        activity.locationType = "VENUE_SPACE";
        activity.venueId = 3L;
        activity.spaceId = 5L;
        activity.reservationId = 101L;
        activity.locationCommitmentStatus = "CONFIRMED";
        activity.commitmentVersion = 4L;
        activity.capacityTotal = 8;
        activity.participantCount = 1;
        activity.status = "PUBLISHED";
        activity.versionNo = 2L;
        return activity;
    }

    private String validActivityJson() {
        return """
            {
              "groupId": 11,
              "activityType": "STUDY",
              "title": "期末复习",
              "description": "公开活动说明",
              "startTime": "2099-08-01T09:00:00",
              "endTime": "2099-08-01T11:00:00",
              "capacityTotal": 8,
              "location": {
                "type": "VENUE_SPACE",
                "venueId": 3,
                "spaceId": 5,
                "reservationId": 101
              }
            }
            """;
    }

    @TestConfiguration
    @EnableMethodSecurity
    static class MethodSecurityConfig {
        @Bean
        SecurityFilterChain testSecurityFilterChain(HttpSecurity http) throws Exception {
            return http
                .csrf(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .httpBasic(Customizer.withDefaults())
                .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                .build();
        }
    }
}
