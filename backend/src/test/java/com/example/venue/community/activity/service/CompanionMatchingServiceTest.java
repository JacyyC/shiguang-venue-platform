package com.example.venue.community.activity.service;

import com.example.venue.common.CurrentUser;
import com.example.venue.community.activity.entity.CommunityActivity;
import com.example.venue.community.activity.entity.CommunityCompanionRequest;
import com.example.venue.community.activity.entity.CommunityMatchDecision;
import com.example.venue.community.activity.mapper.CommunityActivityMapper;
import com.example.venue.community.activity.mapper.CommunityCompanionRequestMapper;
import com.example.venue.community.activity.mapper.CommunityMatchDecisionMapper;
import com.example.venue.community.service.CommunityAccessService;
import com.example.venue.community.service.CommunityTextPolicy;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CompanionMatchingServiceTest {
    @Mock
    private CommunityCompanionRequestMapper requestMapper;
    @Mock
    private CommunityActivityMapper activityMapper;
    @Mock
    private CommunityMatchDecisionMapper decisionMapper;
    @Mock
    private CommunityAccessService accessService;
    @Mock
    private CommunityTextPolicy textPolicy;
    @Mock
    private ActivityCommandService commandService;
    @Mock
    private ActivityStateEventService eventService;

    private CompanionMatchingService service;

    @BeforeEach
    void setUp() {
        service = new CompanionMatchingService(
            requestMapper, activityMapper, decisionMapper, accessService, textPolicy,
            commandService, eventService, new ObjectMapper());
        lenient().when(accessService.hiddenResource())
            .thenReturn(com.example.venue.common.BusinessException.notFound("社区资源不存在"));
        authenticate(7L);
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void matchingIsDeterministicAndEvidenceContainsOnlyStructuredNonSensitiveFacts() {
        CommunityCompanionRequest source = request(71L, 7L, 4);
        CommunityCompanionRequest candidate = request(72L, 9L, 4);

        CompanionMatchingService.MatchEvaluation first = service.evaluate(source, candidate);
        CompanionMatchingService.MatchEvaluation replay = service.evaluate(source, candidate);

        assertThat(first).isEqualTo(replay);
        assertThat(first.outcome()).isEqualTo("MATCHED");
        assertThat(first.reasonCodes()).containsExactly(
            "ACTIVITY_TYPE_MATCH", "TIME_WINDOW_OVERLAP", "LOCATION_EXACT_SPACE", "CAPACITY_AVAILABLE");
        assertThat(first.evidence()).containsOnlyKeys(
            "overlapMinutes", "locationMatch", "availableSlots", "sameLinkedActivity");
        assertThat(first.evidence().toString())
            .doesNotContain(source.title, source.description, candidate.title, candidate.description, "phone", "email");
    }

    @Test
    void sameOwnerRequestsAreDefensivelyExcludedEvenIfSqlReturnsThem() {
        CommunityCompanionRequest source = request(71L, 7L, 4);
        CommunityCompanionRequest sameOwner = request(73L, 7L, 4);

        CompanionMatchingService.MatchEvaluation result = service.evaluate(source, sameOwner);

        assertThat(result.outcome()).isEqualTo("EXCLUDED");
        assertThat(result.reasonCodes()).containsExactly("SAME_OWNER");
        assertThat(result.score()).isZero();
    }

    @Test
    void mismatchReasonsRemainStableForTypeTimeLocationAndCapacity() {
        CommunityCompanionRequest source = request(71L, 7L, 4);

        CommunityCompanionRequest typeMismatch = request(72L, 9L, 4);
        typeMismatch.activityType = "SPORTS";
        assertThat(service.evaluate(source, typeMismatch).reasonCodes())
            .containsExactly("ACTIVITY_TYPE_MISMATCH");

        CommunityCompanionRequest timeMismatch = request(73L, 10L, 4);
        timeMismatch.windowStart = source.windowEnd.plusHours(1);
        timeMismatch.windowEnd = source.windowEnd.plusHours(2);
        assertThat(service.evaluate(source, timeMismatch).reasonCodes())
            .containsExactly("TIME_WINDOW_NO_OVERLAP");

        CommunityCompanionRequest locationMismatch = request(74L, 11L, 4);
        locationMismatch.spaceId = 99L;
        assertThat(service.evaluate(source, locationMismatch).reasonCodes())
            .containsExactly("LOCATION_MISMATCH");

        CommunityCompanionRequest full = request(75L, 12L, 1);
        assertThat(service.evaluate(source, full).reasonCodes())
            .containsExactly("CAPACITY_UNAVAILABLE");
    }

    @Test
    void persistedReasonsAreParsedToAWhitelistArrayAndUnknownValuesNeverReachResponse() {
        CommunityCompanionRequest source = request(71L, 7L, 4);
        CommunityCompanionRequest candidate = request(72L, 9L, 4);
        CommunityMatchDecision decision = new CommunityMatchDecision();
        decision.id = 91L;
        decision.candidateRequestId = candidate.id;
        decision.candidateVersion = candidate.versionNo;
        decision.score = 90;
        decision.ruleVersion = CompanionMatchingService.RULE_VERSION;
        decision.reasonCodesJson = "[\"ACTIVITY_TYPE_MATCH\",\"PHONE_MATCH\",\"TIME_WINDOW_OVERLAP\"]";
        when(requestMapper.selectById(source.id)).thenReturn(source);
        when(decisionMapper.listMatches(
            source.id, source.versionNo, CompanionMatchingService.RULE_VERSION, 500)).thenReturn(List.of(decision));
        when(requestMapper.selectById(candidate.id)).thenReturn(candidate);

        var result = service.matches(source.id, 20);

        assertThat(result.records()).hasSize(1);
        assertThat(result.records().get(0).reasonCodes)
            .containsExactly("ACTIVITY_TYPE_MATCH", "TIME_WINDOW_OVERLAP");
    }

    @Test
    void linkedActivityMustRemainPublishedVisibleAndUnblockedWhenReadingStoredMatches() {
        CommunityCompanionRequest source = request(71L, 7L, 4);
        CommunityCompanionRequest candidate = request(72L, 9L, 4);
        candidate.activityId = 81L;
        CommunityActivity linked = new CommunityActivity();
        linked.id = 81L;
        linked.groupId = candidate.groupId;
        linked.organizerUserId = 12L;
        linked.status = "CLOSED";
        linked.endTime = candidate.windowEnd;
        CommunityMatchDecision decision = new CommunityMatchDecision();
        decision.id = 91L;
        decision.candidateRequestId = candidate.id;
        decision.candidateVersion = candidate.versionNo;
        decision.score = 90;
        decision.ruleVersion = CompanionMatchingService.RULE_VERSION;
        decision.reasonCodesJson = "[\"ACTIVITY_TYPE_MATCH\"]";
        when(requestMapper.selectById(source.id)).thenReturn(source);
        when(decisionMapper.listMatches(
            source.id, source.versionNo, CompanionMatchingService.RULE_VERSION, 500)).thenReturn(List.of(decision));
        when(requestMapper.selectById(candidate.id)).thenReturn(candidate);
        when(activityMapper.selectById(linked.id)).thenReturn(linked);

        assertThat(service.matches(source.id, 20).records()).isEmpty();
    }

    private CommunityCompanionRequest request(Long id, Long ownerId, int capacity) {
        CommunityCompanionRequest request = new CommunityCompanionRequest();
        request.id = id;
        request.groupId = 11L;
        request.ownerUserId = ownerId;
        request.activityType = "STUDY";
        request.title = "title-" + id;
        request.description = "description-" + id;
        request.windowStart = LocalDateTime.of(2099, 8, 1, 9, 0);
        request.windowEnd = LocalDateTime.of(2099, 8, 1, 11, 0);
        request.locationType = "VENUE_SPACE";
        request.venueId = 3L;
        request.spaceId = 5L;
        request.capacityTotal = capacity;
        request.participantCount = 1;
        request.status = "PUBLISHED";
        request.versionNo = 2L;
        return request;
    }

    private void authenticate(Long userId) {
        CurrentUser user = new CurrentUser(userId, "user-" + userId, Set.of("USER"), Set.of());
        lenient().when(accessService.requireCurrentUser()).thenReturn(user);
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(user, null, List.of()));
    }
}
