package com.example.venue.community.service;

import com.example.venue.community.entity.CommunityGovernanceEvent;
import com.example.venue.community.enums.CommunityActorScope;
import com.example.venue.community.enums.CommunitySourceType;
import com.example.venue.community.mapper.CommunityGovernanceEventMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CommunityGovernanceEventServiceTest {
    @Mock
    private CommunityGovernanceEventMapper mapper;

    private CommunityGovernanceEventService service;

    @BeforeEach
    void setUp() {
        service = new CommunityGovernanceEventService(mapper, new ObjectMapper());
    }

    @Test
    void recordedRequestReadsOnlyTheBoundFingerprintFromEventMetadata() {
        CommunityGovernanceEvent event = new CommunityGovernanceEvent();
        event.metadataJson = "{\"decision\":\"TAKE\",\"requestHash\":\"bound-hash\","
            + "\"baseVersion\":2,\"resultVersion\":3}";
        when(mapper.findRecorded(
            eq("CONTENT_OPERATOR"), eq("REPORT_DECIDED"), eq("REPORT"), eq(41L), anyString()))
            .thenReturn(event);

        CommunityGovernanceEventService.RecordedRequest result = service.findRecordedRequest(
            CommunitySourceType.CONTENT_OPERATOR, "REPORT_DECIDED", "REPORT", 41L, 7L,
            "REPORT_DECISION:41:decision-key");

        assertThat(result.recorded()).isTrue();
        assertThat(result.requestHash()).isEqualTo("bound-hash");
        assertThat(result.baseVersion()).isEqualTo(2L);
        assertThat(result.resultVersion()).isEqualTo(3L);
        ArgumentCaptor<String> persistedKey = ArgumentCaptor.forClass(String.class);
        verify(mapper).findRecorded(
            eq("CONTENT_OPERATOR"), eq("REPORT_DECIDED"), eq("REPORT"), eq(41L), persistedKey.capture());
        assertThat(persistedKey.getValue()).hasSize(64);
    }

    @Test
    void recordConvertsTypedCommunityScopeAndSourceAtThePersistenceBoundary() {
        CommunityGovernanceEventService.EventCommand command = new CommunityGovernanceEventService.EventCommand();
        command.eventType = "POST_CREATED";
        command.subjectType = "POST";
        command.subjectId = 41L;
        command.actorUserId = 7L;
        command.actorScope = CommunityActorScope.SELF;
        command.sourceType = CommunitySourceType.USER;
        command.toStatus = "PUBLISHED";
        command.idempotencyKey = "POST_CREATED:41:7";

        service.record(command);

        ArgumentCaptor<CommunityGovernanceEvent> event = ArgumentCaptor.forClass(CommunityGovernanceEvent.class);
        verify(mapper).insert(event.capture());
        assertThat(event.getValue().actorScope).isEqualTo("SELF");
        assertThat(event.getValue().sourceType).isEqualTo("USER");
    }

    @Test
    void recordRejectsCommandsWithoutTypedScopeOrSource() {
        CommunityGovernanceEventService.EventCommand command = new CommunityGovernanceEventService.EventCommand();
        command.eventType = "POST_CREATED";
        command.subjectType = "POST";
        command.idempotencyKey = "missing-scope";

        assertThatThrownBy(() -> service.record(command))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("主体范围和来源");
    }
}
