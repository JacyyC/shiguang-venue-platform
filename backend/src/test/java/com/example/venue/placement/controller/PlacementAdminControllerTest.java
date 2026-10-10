package com.example.venue.placement.controller;

import com.example.venue.common.BusinessException;
import com.example.venue.common.CurrentUser;
import com.example.venue.common.GlobalExceptionHandler;
import com.example.venue.placement.dto.PlacementRequests;
import com.example.venue.placement.dto.PlacementResponses;
import com.example.venue.placement.service.PlacementAdminService;
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

@WebMvcTest(PlacementAdminController.class)
@ContextConfiguration(classes = {
    PlacementAdminController.class,
    GlobalExceptionHandler.class,
    PlacementAdminControllerTest.MethodSecurityConfig.class
})
class PlacementAdminControllerTest {

    @Autowired private MockMvc mockMvc;
    @MockBean private PlacementAdminService adminService;

    @Test
    void slotsAccessibleByAnyPlacementLeafAuthority() throws Exception {
        when(adminService.listSlots()).thenReturn(List.of());

        mockMvc.perform(get("/api/content-admin/placements/slots").with(user(1L, "placement:manage")))
            .andExpect(status().isOk());
        mockMvc.perform(get("/api/content-admin/placements/slots").with(user(1L, "placement:publish")))
            .andExpect(status().isOk());
        mockMvc.perform(get("/api/content-admin/placements/slots").with(user(1L, "placement:audit:view")))
            .andExpect(status().isOk());
    }

    @Test
    void contentAdminViewAloneCannotAccessPlacementApi() throws Exception {
        mockMvc.perform(get("/api/content-admin/placements/slots").with(user(1L, "content-admin:view")))
            .andExpect(status().isForbidden());
    }

    @Test
    void venueAdminRoleCannotAccessPlacementApi() throws Exception {
        mockMvc.perform(get("/api/content-admin/placements/slots")
                .with(userWithRoles(1L, "VENUE_ADMIN")))
            .andExpect(status().isForbidden());
    }

    @Test
    void communityAuthoritiesCannotAccessPlacementApi() throws Exception {
        mockMvc.perform(get("/api/content-admin/placements/slots")
                .with(user(1L, "community:content:moderate")))
            .andExpect(status().isForbidden());
    }

    @Test
    void createCandidateRequiresManageAuthority() throws Exception {
        PlacementResponses.CandidateVersionResponse response = new PlacementResponses.CandidateVersionResponse(
            1L, 1L, "PLC-1", "GLOBAL_NOTICE", 100L, "v1",
            LocalDateTime.now(), LocalDateTime.now().plusHours(1),
            "ALL_AUTHENTICATED", null, 0, 1, 24, "PLACEMENT_RULE_V1", "DRAFT", null);
        when(adminService.createCandidate(any())).thenReturn(response);

        mockMvc.perform(post("/api/content-admin/placements/candidates")
                .with(user(1L, "placement:manage"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(validCreateBody()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("DRAFT"));
    }

    @Test
    void publishAuthorityCannotCreateCandidate() throws Exception {
        mockMvc.perform(post("/api/content-admin/placements/candidates")
                .with(user(1L, "placement:publish"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(validCreateBody()))
            .andExpect(status().isForbidden());

        verify(adminService, never()).createCandidate(any());
    }

    @Test
    void auditAuthorityCannotCreateCandidate() throws Exception {
        mockMvc.perform(post("/api/content-admin/placements/candidates")
                .with(user(1L, "placement:audit:view"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(validCreateBody()))
            .andExpect(status().isForbidden());
    }

    @Test
    void publishCandidateRequiresPublishAuthority() throws Exception {
        PlacementResponses.CandidateVersionResponse response = new PlacementResponses.CandidateVersionResponse(
            1L, 1L, "PLC-1", "GLOBAL_NOTICE", 100L, "v1",
            LocalDateTime.now(), LocalDateTime.now().plusHours(1),
            "ALL_AUTHENTICATED", null, 0, 1, 24, "PLACEMENT_RULE_V1", "PUBLISHED", LocalDateTime.now());
        when(adminService.publishCandidate(1L)).thenReturn(response);

        mockMvc.perform(post("/api/content-admin/placements/candidates/1/publish")
                .with(user(1L, "placement:publish")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("PUBLISHED"));
    }

    @Test
    void manageAuthorityCannotPublish() throws Exception {
        mockMvc.perform(post("/api/content-admin/placements/candidates/1/publish")
                .with(user(1L, "placement:manage")))
            .andExpect(status().isForbidden());

        verify(adminService, never()).publishCandidate(any());
    }

    @Test
    void pauseAndRetireRequirePublishAuthority() throws Exception {
        PlacementResponses.CandidateVersionResponse paused = new PlacementResponses.CandidateVersionResponse(
            1L, 1L, "PLC-1", "GLOBAL_NOTICE", 100L, "v1",
            LocalDateTime.now(), LocalDateTime.now().plusHours(1),
            "ALL_AUTHENTICATED", null, 0, 1, 24, "PLACEMENT_RULE_V1", "PAUSED", null);
        when(adminService.pauseCandidate(1L)).thenReturn(paused);

        mockMvc.perform(post("/api/content-admin/placements/candidates/1/pause")
                .with(user(1L, "placement:publish")))
            .andExpect(status().isOk());

        mockMvc.perform(post("/api/content-admin/placements/candidates/1/retire")
                .with(user(1L, "placement:publish")))
            .andExpect(status().isOk());
    }

    @Test
    void sourcePreviewRequiresManageOrPublish() throws Exception {
        PlacementResponses.SourcePreviewResponse preview = new PlacementResponses.SourcePreviewResponse(
            "GLOBAL_NOTICE", 100L, "v1", "标题", "摘要", null, "/me/messages",
            LocalDateTime.now(), LocalDateTime.now().plusHours(1));
        when(adminService.previewSource("GLOBAL_NOTICE", 100L)).thenReturn(preview);

        mockMvc.perform(get("/api/content-admin/placements/sources/preview")
                .with(user(1L, "placement:manage"))
                .param("sourceType", "GLOBAL_NOTICE")
                .param("sourceId", "100"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.title").value("标题"));

        mockMvc.perform(get("/api/content-admin/placements/sources/preview")
                .with(user(1L, "placement:publish"))
                .param("sourceType", "GLOBAL_NOTICE")
                .param("sourceId", "100"))
            .andExpect(status().isOk());
    }

    @Test
    void auditAuthorityCannotPreviewSource() throws Exception {
        mockMvc.perform(get("/api/content-admin/placements/sources/preview")
                .with(user(1L, "placement:audit:view"))
                .param("sourceType", "GLOBAL_NOTICE")
                .param("sourceId", "100"))
            .andExpect(status().isForbidden());
    }

    @Test
    void decisionsAndEventsRequireAuditAuthority() throws Exception {
        when(adminService.listDecisions(eq(null), eq(null))).thenReturn(List.of());
        when(adminService.listEvents(eq(null), eq(null))).thenReturn(List.of());

        mockMvc.perform(get("/api/content-admin/placements/decisions")
                .with(user(1L, "placement:audit:view")))
            .andExpect(status().isOk());

        mockMvc.perform(get("/api/content-admin/placements/events")
                .with(user(1L, "placement:audit:view")))
            .andExpect(status().isOk());
    }

    @Test
    void manageAuthorityCannotViewDecisions() throws Exception {
        mockMvc.perform(get("/api/content-admin/placements/decisions")
                .with(user(1L, "placement:manage")))
            .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/content-admin/placements/events")
                .with(user(1L, "placement:manage")))
            .andExpect(status().isForbidden());
    }

    @Test
    void decisionEventsRequireAuditAuthority() throws Exception {
        when(adminService.listDecisionEvents(eq(5L), eq(null))).thenReturn(List.of());

        mockMvc.perform(get("/api/content-admin/placements/decisions/5/events")
                .with(user(1L, "placement:audit:view")))
            .andExpect(status().isOk());
    }

    @Test
    void anonymousRequestIsRejected() throws Exception {
        mockMvc.perform(get("/api/content-admin/placements/slots"))
            .andExpect(status().isUnauthorized());
    }

    private String validCreateBody() {
        return """
            {
              "slotId": 1,
              "sourceType": "GLOBAL_NOTICE",
              "sourceId": 100,
              "effectiveFrom": "2030-01-01T00:01:00",
              "effectiveUntil": "2030-01-01T01:00:00",
              "audienceType": "ALL_AUTHENTICATED",
              "priority": 0,
              "frequencyCapPerUser": 1,
              "frequencyCapWindowHours": 24
            }
            """;
    }

    private RequestPostProcessor user(Long userId, String authority) {
        CurrentUser principal = new CurrentUser(userId, "user-" + userId, Set.of("USER"), Set.of(authority));
        return authentication(new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
            principal, null, List.of(new SimpleGrantedAuthority(authority))));
    }

    private RequestPostProcessor userWithRoles(Long userId, String... roles) {
        Set<String> roleSet = Set.of(roles);
        List<SimpleGrantedAuthority> authorities = java.util.Arrays.stream(roles)
            .map(r -> new SimpleGrantedAuthority("ROLE_" + r))
            .toList();
        CurrentUser principal = new CurrentUser(userId, "user-" + userId, roleSet, Set.of());
        return authentication(new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
            principal, null, authorities));
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
