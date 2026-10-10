package com.example.venue.community.activity.service;

import com.example.venue.common.CurrentUser;
import com.example.venue.community.activity.dto.ActivityRequests;
import com.example.venue.community.activity.mapper.CommunityActivityMapper;
import com.example.venue.community.activity.mapper.CommunityActivityParticipationMapper;
import com.example.venue.community.activity.mapper.CommunityCompanionRequestMapper;
import com.example.venue.community.service.CommunityAccessService;
import com.example.venue.community.service.CommunityTextPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CommunityActivityServiceTest {
    @Mock
    private CommunityActivityMapper activityMapper;
    @Mock
    private CommunityCompanionRequestMapper companionMapper;
    @Mock
    private CommunityActivityParticipationMapper participationMapper;
    @Mock
    private CommunityAccessService accessService;
    @Mock
    private CommunityTextPolicy textPolicy;
    @Mock
    private ActivityLocationPolicy locationPolicy;
    @Mock
    private ActivityCommandService commandService;
    @Mock
    private ActivityStateEventService eventService;
    @Mock
    private ActivityNotificationService notificationService;

    private CommunityActivityService service;

    @BeforeEach
    void setUp() {
        service = new CommunityActivityService(
            activityMapper,
            companionMapper,
            participationMapper,
            accessService,
            textPolicy,
            locationPolicy,
            commandService,
            eventService,
            notificationService
        );
        when(accessService.requireCurrentUser())
            .thenReturn(new CurrentUser(7L, "user", Set.of(), Set.of("community:access")));
    }

    @Test
    void activityDiscoveryTrimsAndForwardsKeyword() {
        ActivityRequests.DiscoveryQuery query = new ActivityRequests.DiscoveryQuery();
        query.keyword = "  期末复习  ";

        service.discoverActivities(query);

        verify(activityMapper).discover(
            eq(7L), eq("期末复习"), isNull(), isNull(), isNull(), isNull(), isNull(), isNull(),
            any(LocalDateTime.class), eq(21)
        );
    }

    @Test
    void companionDiscoveryTrimsAndForwardsKeyword() {
        ActivityRequests.DiscoveryQuery query = new ActivityRequests.DiscoveryQuery();
        query.keyword = "  自习搭子  ";

        service.discoverCompanions(query);

        verify(companionMapper).discover(
            eq(7L), isNull(), eq("自习搭子"), isNull(), isNull(), isNull(), isNull(), isNull(),
            isNull(), isNull(), any(LocalDateTime.class), eq(21)
        );
    }
}
