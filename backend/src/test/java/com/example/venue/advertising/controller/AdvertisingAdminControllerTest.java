package com.example.venue.advertising.controller;

import com.example.venue.advertising.dto.AdvertisingResponses;
import com.example.venue.advertising.dto.AdvertisingAnalysisResponses;
import com.example.venue.advertising.service.AdvertisingAdminService;
import com.example.venue.advertising.service.AdvertisingAnalysisService;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AdvertisingAdminController.class)
@ContextConfiguration(classes = {
    AdvertisingAdminController.class,
    GlobalExceptionHandler.class,
    AdvertisingAdminControllerTest.MethodSecurityConfig.class
})
class AdvertisingAdminControllerTest {

    @Autowired private MockMvc mockMvc;
    @MockBean private AdvertisingAdminService adminService;
    @MockBean private AdvertisingAnalysisService analysisService;

    @Test
    void manageAuthorityCanCreateAdvertiser() throws Exception {
        when(adminService.createAdvertiser(any())).thenReturn(advertiserResponse());

        mockMvc.perform(post("/api/content-admin/advertising/advertisers")
                .with(user(10L, "advertising:manage"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"advertiserName\":\"平台广告主\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("ACTIVE"));
    }

    @Test
    void contentAdminViewAloneCannotAccessAdvertisingWriteApi() throws Exception {
        mockMvc.perform(post("/api/content-admin/advertising/advertisers")
                .with(user(10L, "content-admin:view"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"advertiserName\":\"平台广告主\"}"))
            .andExpect(status().isForbidden());

        verify(adminService, never()).createAdvertiser(any());
    }

    @Test
    void reviewAuthorityCanApproveButManageAuthorityCannot() throws Exception {
        when(adminService.approveCampaign(5L)).thenReturn(campaignResponse("APPROVED"));

        mockMvc.perform(post("/api/content-admin/advertising/campaigns/5/approve")
                .with(user(11L, "advertising:review")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("APPROVED"));

        mockMvc.perform(post("/api/content-admin/advertising/campaigns/5/approve")
                .with(user(10L, "advertising:manage")))
            .andExpect(status().isForbidden());

        verify(adminService).approveCampaign(5L);
    }

    @Test
    void publishAuthorityCanPublishButReviewAuthorityCannot() throws Exception {
        when(adminService.publishCampaign(5L)).thenReturn(campaignResponse("PUBLISHED"));

        mockMvc.perform(post("/api/content-admin/advertising/campaigns/5/publish")
                .with(user(11L, "advertising:publish")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("PUBLISHED"));

        mockMvc.perform(post("/api/content-admin/advertising/campaigns/5/publish")
                .with(user(11L, "advertising:review")))
            .andExpect(status().isForbidden());
    }

    @Test
    void auditAuthorityCanReadAuditsButManageAuthorityCannot() throws Exception {
        when(adminService.listAudits(null, null, null, null)).thenReturn(List.of());

        mockMvc.perform(get("/api/content-admin/advertising/audits")
                .with(user(12L, "advertising:audit:view")))
            .andExpect(status().isOk());

        mockMvc.perform(get("/api/content-admin/advertising/audits")
                .with(user(10L, "advertising:manage")))
            .andExpect(status().isForbidden());
    }

    @Test
    void analysisAuthorityCanReadBoundedOverviewButAuditAuthorityCannot() throws Exception {
        when(analysisService.overview(any(), any())).thenReturn(new AdvertisingAnalysisResponses.Overview(
            LocalDateTime.of(2026, 8, 1, 0, 0), LocalDateTime.of(2026, 8, 3, 0, 0),
            8, 6, 3, 1, 2, 1, 2, 1, 1, 1));

        mockMvc.perform(get("/api/content-admin/advertising/analysis/overview")
                .with(user(12L, "advertising:analysis:view")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.decisions").value(8))
            .andExpect(jsonPath("$.data.gapReservations").value(2));

        mockMvc.perform(get("/api/content-admin/advertising/analysis/overview")
                .with(user(12L, "advertising:audit:view")))
            .andExpect(status().isForbidden());
    }

    @Test
    void anonymousRequestIsRejected() throws Exception {
        mockMvc.perform(get("/api/content-admin/advertising/campaigns"))
            .andExpect(status().isUnauthorized());
    }

    private AdvertisingResponses.AdvertiserResponse advertiserResponse() {
        LocalDateTime now = LocalDateTime.now();
        return new AdvertisingResponses.AdvertiserResponse(
            1L, "ADV-ADVER-1", "平台广告主", "ACTIVE", now, now);
    }

    private AdvertisingResponses.CampaignResponse campaignResponse(String status) {
        LocalDateTime now = LocalDateTime.now();
        return new AdvertisingResponses.CampaignResponse(
            5L, "ADV-CAMP-1", 1L, null, "计划", now, now.plusDays(1), status,
            now, now, now, null, null, now, now);
    }

    private RequestPostProcessor user(Long userId, String authority) {
        CurrentUser principal = new CurrentUser(userId, "operator-" + userId,
            Set.of("SYSTEM_ADMIN"), Set.of(authority));
        return authentication(new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
            principal, null, List.of(new SimpleGrantedAuthority(authority))));
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
