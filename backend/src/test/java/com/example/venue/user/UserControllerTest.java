package com.example.venue.user;

import com.example.venue.common.CurrentUser;
import com.example.venue.common.GlobalExceptionHandler;
import com.example.venue.notice.dto.TransactionalEmailPreferenceRequest;
import com.example.venue.notice.dto.TransactionalEmailPreferenceResponse;
import com.example.venue.notice.service.TransactionalEmailPreferenceService;
import com.example.venue.security.JwtAuthenticationFilter;
import com.example.venue.security.RateLimitFilter;
import com.example.venue.system.service.SystemService;
import com.example.venue.user.controller.UserController;
import com.example.venue.user.dto.AuthorizationSnapshot;
import com.example.venue.user.service.UserService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.context.annotation.FilterType;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.context.ContextConfiguration;

import java.util.Set;
import java.util.List;

import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.stringContainsInOrder;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(
    controllers = UserController.class,
    excludeFilters = @ComponentScan.Filter(
        type = FilterType.ASSIGNABLE_TYPE,
        classes = {JwtAuthenticationFilter.class, RateLimitFilter.class}))
@ContextConfiguration(classes = {
    UserController.class,
    GlobalExceptionHandler.class,
    UserControllerTest.TestSecurityConfig.class
})
class UserControllerTest {
    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private UserService userService;
    @MockBean
    private SystemService systemService;
    @MockBean
    private TransactionalEmailPreferenceService preferenceService;

    @Test
    void unauthenticatedPreferenceRequestsAreRejected() throws Exception {
        mockMvc.perform(get("/api/users/me/notification-preferences"))
            .andExpect(status().isUnauthorized());
        mockMvc.perform(put("/api/users/me/notification-preferences")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"transactionalEmailEnabled\":true}"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void authorizationSnapshotRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/users/me/authorization"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void authorizationSnapshotReturnsTheServerProjectionForTheCurrentUser() throws Exception {
        when(userService.currentAuthorization()).thenReturn(new AuthorizationSnapshot(
            List.of("USER", "VENUE_ADMIN"), List.of("user:view", "venue-admin:view")
        ));

        mockMvc.perform(get("/api/users/me/authorization")
                .with(authentication(currentUser())))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.roles[0]").value("USER"))
            .andExpect(jsonPath("$.data.roles[1]").value("VENUE_ADMIN"))
            .andExpect(jsonPath("$.data.permissions[1]").value("venue-admin:view"));

        verify(userService).currentAuthorization();
    }

    @Test
    void onlyTheSelfPreferenceRouteExists() throws Exception {
        mockMvc.perform(get("/api/users/7/notification-preferences")
                .with(authentication(currentUser())))
            .andExpect(status().isNotFound());
        mockMvc.perform(put("/api/users/7/notification-preferences")
                .with(authentication(currentUser()))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"transactionalEmailEnabled\":true}"))
            .andExpect(status().isNotFound());
    }

    @Test
    void getReturnsOnlyTheMaskedSelfProjection() throws Exception {
        when(preferenceService.current()).thenReturn(response(true));

        mockMvc.perform(get("/api/users/me/notification-preferences")
                .with(authentication(currentUser())))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.transactionalEmailEnabled").value(true))
            .andExpect(jsonPath("$.data.emailConfigured").value(true))
            .andExpect(jsonPath("$.data.emailEligible").value(true))
            .andExpect(jsonPath("$.data.maskedEmail").value("a***e@e***e.test"))
            .andExpect(jsonPath("$.data.addressValidated").value(true))
            .andExpect(jsonPath("$.data.addressVerified").value(false))
            .andExpect(jsonPath("$.data.exclusionCode").isEmpty())
            .andExpect(content().string(not(stringContainsInOrder("alice", "example.test"))));
    }

    @Test
    void putAcceptsOnlyTheTransactionalBooleanAndDelegatesWithoutIdentityFields() throws Exception {
        when(preferenceService.update(any())).thenReturn(response(true));

        mockMvc.perform(put("/api/users/me/notification-preferences")
                .with(authentication(currentUser()))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"transactionalEmailEnabled\":true}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.transactionalEmailEnabled").value(true));

        verify(preferenceService).update(any(TransactionalEmailPreferenceRequest.class));
    }

    @Test
    void putRejectsUserIdAndAddressFields() throws Exception {
        mockMvc.perform(put("/api/users/me/notification-preferences")
                .with(authentication(currentUser()))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"transactionalEmailEnabled\":true,\"userId\":8}"))
            .andExpect(status().isBadRequest());
        mockMvc.perform(put("/api/users/me/notification-preferences")
                .with(authentication(currentUser()))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"transactionalEmailEnabled\":true,\"address\":\"alice@example.test\"}"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void putRejectsAnAddressShapedFieldNameWithoutReflectingItAnywhere() throws Exception {
        String body = "{\"alice@example.test\":true,\"transactionalEmailEnabled\":true}";

        mockMvc.perform(put("/api/users/me/notification-preferences")
                .with(authentication(currentUser()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isBadRequest())
            .andExpect(content().string(not(containsString("alice@example.test"))))
            .andExpect(content().string(not(containsString("@"))));

        assertThatThrownBy(() -> objectMapper.readValue(
                body, TransactionalEmailPreferenceRequest.class))
            .hasRootCauseMessage("Unsupported notification preference field");
    }

    @Test
    void profileUpdateRejectsFieldsBeyondTheUserColumnLengths() throws Exception {
        String oversizedValidEmail = "a".repeat(60) + "@" + "b".repeat(63) + ".example";

        mockMvc.perform(put("/api/users/me")
                .with(authentication(currentUser()))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"nickname\":\"" + "n".repeat(65) + "\"}"))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.data.nickname").value("昵称长度不能超过 64 位"));
        mockMvc.perform(put("/api/users/me")
                .with(authentication(currentUser()))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"phone\":\"" + "1".repeat(33) + "\"}"))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.data.phone").value("请输入 11 位有效手机号"));
        mockMvc.perform(put("/api/users/me")
                .with(authentication(currentUser()))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + oversizedValidEmail + "\"}"))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.data.email").value("邮箱长度不能超过 128 位"));
        mockMvc.perform(put("/api/users/me")
                .with(authentication(currentUser()))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"avatar\":\"" + "a".repeat(513) + "\"}"))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.data.avatar").value("头像长度不能超过 512 位"));

        verify(userService, never()).updateProfile(any());
    }

    @Test
    void profilePhoneFollowsTheSharedOptionalMobileContract() throws Exception {
        // 未填写（缺字段/空字符串）与合法 11 位手机号都放行到服务层
        mockMvc.perform(put("/api/users/me")
                .with(authentication(currentUser()))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"nickname\":\"球员\"}"))
            .andExpect(status().isOk());
        mockMvc.perform(put("/api/users/me")
                .with(authentication(currentUser()))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"phone\":\"\"}"))
            .andExpect(status().isOk());
        mockMvc.perform(put("/api/users/me")
                .with(authentication(currentUser()))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"phone\":\"13800138000\"}"))
            .andExpect(status().isOk());
        verify(userService, times(3)).updateProfile(any());

        // 非法格式在数据边界被拒，且不触发任何写入
        for (String invalid : List.of("1", "12345", "abc123", "12800138000", "1380013800",
            "138001380001", "+8613800138000")) {
            mockMvc.perform(put("/api/users/me")
                    .with(authentication(currentUser()))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"phone\":\"" + invalid + "\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.data.phone").value("请输入 11 位有效手机号"));
        }
        verify(userService, times(3)).updateProfile(any());
    }

    private Authentication currentUser() {
        return new UsernamePasswordAuthenticationToken(
            new CurrentUser(7L, "alice", Set.of("USER"), Set.of()), null, Set.of());
    }

    private TransactionalEmailPreferenceResponse response(boolean enabled) {
        TransactionalEmailPreferenceResponse response = new TransactionalEmailPreferenceResponse();
        response.transactionalEmailEnabled = enabled;
        response.emailConfigured = true;
        response.emailEligible = enabled;
        response.maskedEmail = enabled ? "a***e@e***e.test" : null;
        response.addressValidated = enabled;
        response.addressVerified = false;
        response.exclusionCode = enabled ? null : "EMAIL_PREFERENCE_DISABLED";
        return response;
    }

    @TestConfiguration
    static class TestSecurityConfig {
        @Bean
        SecurityFilterChain testSecurityFilterChain(HttpSecurity http) throws Exception {
            return http
                .csrf(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .httpBasic(Customizer.withDefaults())
                .authorizeHttpRequests(auth -> auth
                    .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                    .anyRequest().authenticated())
                .build();
        }
    }
}
