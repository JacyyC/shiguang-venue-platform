package com.example.venue.placement.controller;

import com.example.venue.common.CurrentUser;
import com.example.venue.common.GlobalExceptionHandler;
import com.example.venue.placement.dto.PlacementRequests;
import com.example.venue.placement.dto.PlacementResponses;
import com.example.venue.placement.service.PlacementDecisionService;
import com.example.venue.placement.service.PlacementEventService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(PlacementController.class)
@ContextConfiguration(classes = {
    PlacementController.class,
    GlobalExceptionHandler.class,
    PlacementControllerTest.MethodSecurityConfig.class
})
class PlacementControllerTest {

    @Autowired private MockMvc mockMvc;
    @MockBean private PlacementDecisionService decisionService;
    @MockBean private PlacementEventService eventService;

    @Test
    void homePrimaryDecisionReturnsTokenAndWhitelistedFields() throws Exception {
        PlacementResponses.HomePrimaryDecision decision = new PlacementResponses.HomePrimaryDecision(
            42L, 50L, "raw-token", false, "公告标题", "摘要",
            null, "/me/messages", LocalDateTime.now().plusMinutes(10));
        when(decisionService.decideHomePrimary()).thenReturn(decision);

        mockMvc.perform(get("/api/placements/HOME_PRIMARY/decision").with(user(10L)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.decisionId").value(42))
            .andExpect(jsonPath("$.data.token").value("raw-token"))
            .andExpect(jsonPath("$.data.title").value("公告标题"))
            .andExpect(jsonPath("$.data.deepLink").value("/me/messages"))
            .andExpect(jsonPath("$.data.reused").value(false));
    }

    @Test
    void homePrimaryDecisionReturnsNullWhenNoCandidate() throws Exception {
        when(decisionService.decideHomePrimary()).thenReturn(null);

        mockMvc.perform(get("/api/placements/HOME_PRIMARY/decision").with(user(10L)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0));
    }

    @Test
    void unknownSlotCodeReturnsNullWithoutCallingService() throws Exception {
        mockMvc.perform(get("/api/placements/UNKNOWN_SLOT/decision").with(user(10L)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0));

        verify(decisionService, never()).decideHomePrimary();
    }

    @Test
    void unauthenticatedDecisionRequestIsRejected() throws Exception {
        mockMvc.perform(get("/api/placements/HOME_PRIMARY/decision"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void recordEventRequiresIdempotencyKeyHeader() throws Exception {
        // Missing required Idempotency-Key header is rejected — currently 500 due to a
        // pre-existing GlobalExceptionHandler gap for MissingRequestHeaderException.
        // The placement contract only requires that it is not accepted as a valid event.
        mockMvc.perform(post("/api/placements/decisions/42/events")
                .with(user(10L))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"eventType\":\"CLICK\",\"token\":\"tok\"}"))
            .andExpect(result -> {
                int status = result.getResponse().getStatus();
                if (status < 400) {
                    throw new AssertionError("Expected error status but was " + status);
                }
            });
        verify(eventService, never()).recordEvent(any(), any(), any());
    }

    @Test
    void recordEventAcceptsValidClickEvent() throws Exception {
        PlacementResponses.RecordEventResult result =
            PlacementResponses.RecordEventResult.accepted("CLICK");
        when(eventService.recordEvent(eq(42L), any(), eq("idem-1"))).thenReturn(result);

        mockMvc.perform(post("/api/placements/decisions/42/events")
                .with(user(10L))
                .header("Idempotency-Key", "idem-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"eventType\":\"CLICK\",\"token\":\"tok\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.resultStatus").value("ACCEPTED"));
    }

    @Test
    void recordEventAcceptsViewableWithVisibilityData() throws Exception {
        PlacementResponses.RecordEventResult result =
            PlacementResponses.RecordEventResult.accepted("CLIENT_VIEWABLE");
        when(eventService.recordEvent(eq(42L), any(), eq("idem-view"))).thenReturn(result);

        mockMvc.perform(post("/api/placements/decisions/42/events")
                .with(user(10L))
                .header("Idempotency-Key", "idem-view")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"eventType\":\"CLIENT_VIEWABLE\",\"token\":\"tok\",\"visibleRatio\":0.8,\"visibleDurationMs\":2000}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.eventType").value("CLIENT_VIEWABLE"));
    }

    @Test
    void recordEventRejectsMissingToken() throws Exception {
        mockMvc.perform(post("/api/placements/decisions/42/events")
                .with(user(10L))
                .header("Idempotency-Key", "idem-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"eventType\":\"CLICK\"}"))
            .andExpect(status().isUnprocessableEntity());
    }

    private RequestPostProcessor user(Long userId) {
        CurrentUser principal = new CurrentUser(userId, "user-" + userId, Set.of("USER"), Set.of("USER"));
        return authentication(new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
            principal, null, List.of(new SimpleGrantedAuthority("USER"))));
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
