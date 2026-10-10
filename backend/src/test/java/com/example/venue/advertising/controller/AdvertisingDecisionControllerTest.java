package com.example.venue.advertising.controller;

import com.example.venue.advertising.dto.AdvertisingDecisionResponses;
import com.example.venue.advertising.service.AdvertisingDecisionService;
import com.example.venue.advertising.service.AdvertisingEventService;
import com.example.venue.advertising.service.AdvertisingPrivacyPreferenceService;
import com.example.venue.common.CurrentUser;
import com.example.venue.common.GlobalExceptionHandler;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AdvertisingDecisionController.class)
@ContextConfiguration(classes = {
    AdvertisingDecisionController.class,
    GlobalExceptionHandler.class,
    AdvertisingDecisionControllerTest.MethodSecurityConfig.class
})
class AdvertisingDecisionControllerTest {
    @Autowired private MockMvc mockMvc;
    @MockBean private AdvertisingPrivacyPreferenceService privacyService;
    @MockBean private AdvertisingDecisionService decisionService;
    @MockBean private AdvertisingEventService eventService;

    @Test
    void preferenceEndpointReturnsClosedByDefault() throws Exception {
        when(privacyService.current()).thenReturn(
            new AdvertisingDecisionResponses.PrivacyPreferenceResponse(false, false,
                AdvertisingPrivacyPreferenceService.POLICY_VERSION, null));

        mockMvc.perform(get("/api/advertising/preferences").with(user(501L)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.commercialEnabled").value(false))
            .andExpect(jsonPath("$.data.measurementEnabled").value(false));
    }

    @Test
    void privacyUpdatePassesOnlyTheNarrowRequestContract() throws Exception {
        when(privacyService.update(any())).thenReturn(
            new AdvertisingDecisionResponses.PrivacyPreferenceResponse(true, true,
                AdvertisingPrivacyPreferenceService.POLICY_VERSION, LocalDateTime.now()));

        mockMvc.perform(put("/api/advertising/preferences")
                .with(user(501L))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"commercialEnabled\":true,\"measurementEnabled\":true}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.commercialEnabled").value(true))
            .andExpect(jsonPath("$.data.measurementEnabled").value(true));

        verify(privacyService).update(any());
    }

    @Test
    void decisionEndpointReturnsFixedAdLabelAndServerTarget() throws Exception {
        when(decisionService.decideHomeCommercial()).thenReturn(
            new AdvertisingDecisionResponses.HomeCommercialDecision(
                601L, 701L, "raw-token", false, "广告", "广告内容",
                "标题", "正文", "VIEW", "VENUE_DETAIL", 9L, "/venues/9",
                AdvertisingPrivacyPreferenceService.POLICY_VERSION, LocalDateTime.now().plusMinutes(5)));

        mockMvc.perform(get("/api/advertising/HOME_COMMERCIAL/decision").with(user(501L)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.adLabel").value("广告"))
            .andExpect(jsonPath("$.data.accessibleAdLabel").value("广告内容"))
            .andExpect(jsonPath("$.data.deepLink").value("/venues/9"));
    }

    @Test
    void eventEndpointRequiresAndForwardsIdempotencyKey() throws Exception {
        when(eventService.recordEvent(eq(601L), any(), eq("event-key-1"))).thenReturn(
            AdvertisingDecisionResponses.RecordEventResult.accepted("CLICK"));

        mockMvc.perform(post("/api/advertising/decisions/601/events")
                .with(user(501L))
                .header("Idempotency-Key", "event-key-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"eventType\":\"CLICK\",\"token\":\"raw-token\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.resultStatus").value("ACCEPTED"));
    }

    @Test
    void unauthenticatedDecisionRequestIsRejectedBeforeService() throws Exception {
        mockMvc.perform(get("/api/advertising/HOME_COMMERCIAL/decision"))
            .andExpect(status().isUnauthorized());
    }

    private RequestPostProcessor user(Long userId) {
        CurrentUser principal = new CurrentUser(userId, "user-" + userId, Set.of("USER"), Set.of());
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
