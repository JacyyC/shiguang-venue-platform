package com.example.venue.member;

import com.example.venue.common.CurrentUser;
import com.example.venue.common.GlobalExceptionHandler;
import com.example.venue.common.PageResult;
import com.example.venue.member.controller.MemberRewardController;
import com.example.venue.member.dto.MemberRewardCatalogResponse;
import com.example.venue.member.dto.MemberRewardEventResponse;
import com.example.venue.member.dto.MemberRewardRedemptionResponse;
import com.example.venue.member.service.MemberRewardService;
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

import java.util.List;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(MemberRewardController.class)
@ContextConfiguration(classes = {
    MemberRewardController.class,
    GlobalExceptionHandler.class,
    MemberRewardControllerTest.MethodSecurityConfig.class
})
class MemberRewardControllerTest {
    @Autowired private MockMvc mockMvc;
    @MockBean private MemberRewardService service;

    @Test
    void selfApisRequireDedicatedAuthorityAndBindPreviewVersion() throws Exception {
        MemberRewardCatalogResponse catalog = new MemberRewardCatalogResponse();
        catalog.itemId = 5L;
        catalog.versionId = 11L;
        MemberRewardRedemptionResponse redemption = new MemberRewardRedemptionResponse();
        redemption.id = 88L;
        redemption.status = "SUCCEEDED";
        when(service.catalog()).thenReturn(List.of(catalog));
        when(service.redeem(5L, 11L, "idem-1")).thenReturn(redemption);
        when(service.history(any(), any())).thenReturn(PageResult.of(List.of(redemption), 1, 1, 10));

        mockMvc.perform(get("/api/member-rewards/catalog").with(user(7L, "member-reward:me")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[0].versionId").value(11));
        mockMvc.perform(post("/api/member-rewards/catalog/5/redemptions")
                .with(user(7L, "member-reward:me"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"catalogVersionId\":11,\"idempotencyKey\":\"idem-1\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.id").value(88));
        mockMvc.perform(get("/api/member-rewards/redemptions")
                .with(user(7L, "member-reward:me"))
                .param("userId", "999"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.records[0].id").value(88));
        mockMvc.perform(get("/api/member-rewards/redemptions")
                .with(user(7L, "member-reward:me"))
                .param("status", "PENDING"))
            .andExpect(status().isOk());

        verify(service).redeem(5L, 11L, "idem-1");
        verify(service).history(any(), org.mockito.ArgumentMatchers.isNull());
        verify(service).history(any(), org.mockito.ArgumentMatchers.eq("PENDING"));
    }

    @Test
    void anonymousAndPermissionlessUsersCannotReachSelfApi() throws Exception {
        mockMvc.perform(get("/api/member-rewards/catalog"))
            .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/member-rewards/catalog").with(user(7L, "member:me")))
            .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/member-rewards/admin/catalog")
                .with(user(7L, "member-reward:me"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"itemCode\":\"R1\",\"itemName\":\"权益\"}"))
            .andExpect(status().isForbidden());
    }

    @Test
    void managementAndAuditLeavesStayIndependent() throws Exception {
        MemberRewardEventResponse event = new MemberRewardEventResponse();
        event.id = 101L;
        event.eventType = "REDEMPTION_RETRY_RESULT";
        when(service.exceptions(any(), any())).thenReturn(PageResult.of(List.of(), 0, 1, 10));
        when(service.events(any())).thenReturn(PageResult.of(List.of(event), 1, 1, 10));

        mockMvc.perform(get("/api/member-rewards/admin/exceptions")
                .with(user(9L, "member-reward:exception:manage")))
            .andExpect(status().isOk());
        mockMvc.perform(get("/api/member-rewards/admin/events")
                .with(user(9L, "member-reward:exception:manage")))
            .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/member-rewards/admin/events")
                .with(user(9L, "member-reward:audit:view")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.records[0].eventType").value("REDEMPTION_RETRY_RESULT"))
            .andExpect(jsonPath("$.data.records[0].eventKey").doesNotExist())
            .andExpect(jsonPath("$.data.records[0].metadataJson").doesNotExist());
        mockMvc.perform(get("/api/member-rewards/admin/exceptions")
                .with(user(9L, "member-reward:audit:view")))
            .andExpect(status().isForbidden());
    }

    @Test
    void exceptionListBindsOptionalStatusParameterFromQueryString() throws Exception {
        when(service.exceptions(any(), any())).thenReturn(PageResult.of(List.of(), 0, 1, 10));

        // B4-M1：status 必须经查询串真正到达服务端；缺省时传 null 以保留原三态默认范围。
        mockMvc.perform(get("/api/member-rewards/admin/exceptions")
                .param("status", "SUCCEEDED")
                .with(user(9L, "member-reward:exception:manage")))
            .andExpect(status().isOk());
        verify(service).exceptions(any(), org.mockito.ArgumentMatchers.eq("SUCCEEDED"));

        mockMvc.perform(get("/api/member-rewards/admin/exceptions")
                .with(user(9L, "member-reward:exception:manage")))
            .andExpect(status().isOk());
        verify(service).exceptions(any(), org.mockito.ArgumentMatchers.isNull());
    }

    @Test
    void redemptionRejectsMissingPreviewVersionBeforeCallingService() throws Exception {
        mockMvc.perform(post("/api/member-rewards/catalog/5/redemptions")
                .with(user(7L, "member-reward:me"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"idempotencyKey\":\"idem-1\"}"))
            .andExpect(status().isUnprocessableEntity());

        verify(service, never()).redeem(any(), any(), any());
    }

    @Test
    void catalogManageEndpointsRequireDedicatedAuthority() throws Exception {
        MemberRewardCatalogResponse item = new MemberRewardCatalogResponse();
        item.itemId = 5L;
        item.itemCode = "COUPON20";
        item.itemName = "满 100 减 20 券";
        item.status = "ACTIVE";
        when(service.listCatalogItems()).thenReturn(List.of(item));
        when(service.setCatalogItemStatus(5L, "INACTIVE")).thenReturn(item);

        mockMvc.perform(get("/api/member-rewards/admin/catalog")
                .with(user(1L, "member-reward:catalog:manage")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[0].itemCode").value("COUPON20"));
        mockMvc.perform(put("/api/member-rewards/admin/catalog/5/status")
                .with(user(1L, "member-reward:catalog:manage"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"INACTIVE\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("ACTIVE"));

        verify(service).listCatalogItems();
        verify(service).setCatalogItemStatus(5L, "INACTIVE");
    }

    @Test
    void catalogManageEndpointsRejectWrongPermission() throws Exception {
        mockMvc.perform(get("/api/member-rewards/admin/catalog")
                .with(user(7L, "member-reward:me")))
            .andExpect(status().isForbidden());
        mockMvc.perform(put("/api/member-rewards/admin/catalog/5/status")
                .with(user(9L, "member-reward:exception:manage"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"INACTIVE\"}"))
            .andExpect(status().isForbidden());

        verify(service, never()).listCatalogItems();
        verify(service, never()).setCatalogItemStatus(any(), any());
    }

    private RequestPostProcessor user(Long userId, String authority) {
        CurrentUser principal = new CurrentUser(userId, "user-" + userId, Set.of("USER"), Set.of(authority));
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
