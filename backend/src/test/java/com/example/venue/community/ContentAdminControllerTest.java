package com.example.venue.community;

import com.example.venue.community.controller.ContentAdminController;
import com.example.venue.community.dto.CommunityQueryRows;
import com.example.venue.community.dto.CommunityRequests;
import com.example.venue.community.dto.CommunityResponses;
import com.example.venue.community.dto.CursorPage;
import com.example.venue.community.entity.CommunityReport;
import com.example.venue.community.service.CommunityGovernanceService;
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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;

import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ContentAdminController.class)
@ContextConfiguration(classes = {
    ContentAdminController.class,
    ContentAdminControllerTest.MethodSecurityConfig.class
})
class ContentAdminControllerTest {
    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private CommunityGovernanceService governanceService;

    @Test
    void endpointsDeclareTheExactSplitContentOperationsAuthorities() throws Exception {
        Method inspect = ContentAdminController.class.getMethod(
            "inspectContent", String.class, Long.class, Long.class);
        assertThat(inspect.getAnnotation(GetMapping.class).value())
            .containsExactly("/content/{targetType}/{targetId}");
        assertThat(inspect.getAnnotation(PreAuthorize.class).value()).isEqualTo(
            "hasAnyAuthority('community:report:review','community:content:moderate','community:content:restore')");

        assertEndpoint("reports", new Class<?>[]{CommunityRequests.AdminReportQuery.class},
            GetMapping.class, "/reports", "community:report:review");
        assertEndpoint("decideReport",
            new Class<?>[]{Long.class, CommunityRequests.ReportDecision.class, String.class},
            PostMapping.class, "/reports/{reportId}/decision", "community:report:review");
        assertEndpoint("hideContent",
            new Class<?>[]{String.class, Long.class, CommunityRequests.ModerationAction.class, String.class},
            PostMapping.class, "/content/{targetType}/{targetId}/hide", "community:content:moderate");
        assertEndpoint("restoreContent",
            new Class<?>[]{String.class, Long.class, CommunityRequests.ModerationAction.class, String.class},
            PostMapping.class, "/content/{targetType}/{targetId}/restore", "community:content:restore");
        assertEndpoint("events", new Class<?>[]{CommunityRequests.AdminEventQuery.class},
            GetMapping.class, "/audit/events", "community:audit:view");
    }

    @Test
    void reportReviewerInspectionPassesTheRequiredReportContextToService() throws Exception {
        CommunityResponses.AdminContent content = new CommunityResponses.AdminContent();
        content.targetType = "POST";
        content.id = 21L;
        content.content = "待检查正文";
        content.status = "PUBLISHED";
        when(governanceService.inspect("POST", 21L, 41L)).thenReturn(content);

        mockMvc.perform(get("/api/content-admin/content/POST/21")
                .with(authority("community:report:review"))
                .param("reportId", "41"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.id").value(21))
            .andExpect(jsonPath("$.data.content").value("待检查正文"));

        verify(governanceService).inspect("POST", 21L, 41L);
    }

    @Test
    void listQueryParametersAreBoundAndForwardedToGovernanceService() throws Exception {
        when(governanceService.reports(any(CommunityRequests.AdminReportQuery.class)))
            .thenReturn(CursorPage.of(List.of(), null, false));
        when(governanceService.events(any(CommunityRequests.AdminEventQuery.class)))
            .thenReturn(CursorPage.of(List.of(), null, false));

        mockMvc.perform(get("/api/content-admin/reports")
                .with(authority("community:report:review"))
                .param("status", "OPEN")
                .param("targetType", "POST")
                .param("cursorId", "93")
                .param("limit", "15"))
            .andExpect(status().isOk());
        mockMvc.perform(get("/api/content-admin/audit/events")
                .with(authority("community:audit:view"))
                .param("subjectType", "COMMENT")
                .param("subjectId", "31")
                .param("reportId", "41")
                .param("cursorId", "94")
                .param("limit", "16"))
            .andExpect(status().isOk());

        ArgumentCaptor<CommunityRequests.AdminReportQuery> reportQueryCaptor =
            ArgumentCaptor.forClass(CommunityRequests.AdminReportQuery.class);
        verify(governanceService).reports(reportQueryCaptor.capture());
        CommunityRequests.AdminReportQuery reportQuery = reportQueryCaptor.getValue();
        assertThat(reportQuery.status).isEqualTo("OPEN");
        assertThat(reportQuery.targetType).isEqualTo("POST");
        assertThat(reportQuery.cursorId).isEqualTo(93L);
        assertThat(reportQuery.limit).isEqualTo(15);

        ArgumentCaptor<CommunityRequests.AdminEventQuery> eventQueryCaptor =
            ArgumentCaptor.forClass(CommunityRequests.AdminEventQuery.class);
        verify(governanceService).events(eventQueryCaptor.capture());
        CommunityRequests.AdminEventQuery eventQuery = eventQueryCaptor.getValue();
        assertThat(eventQuery.subjectType).isEqualTo("COMMENT");
        assertThat(eventQuery.subjectId).isEqualTo(31L);
        assertThat(eventQuery.reportId).isEqualTo(41L);
        assertThat(eventQuery.cursorId).isEqualTo(94L);
        assertThat(eventQuery.limit).isEqualTo(16);
    }

    @Test
    void eachDedicatedAuthorityCanReachOnlyItsOwnOperation() throws Exception {
        when(governanceService.reports(any(CommunityRequests.AdminReportQuery.class)))
            .thenReturn(CursorPage.of(List.of(reportRow()), null, false));
        when(governanceService.events(any(CommunityRequests.AdminEventQuery.class)))
            .thenReturn(CursorPage.of(List.of(eventRow()), null, false));

        CommunityReport decided = new CommunityReport();
        decided.id = 41L;
        decided.reportNo = "CR-41";
        decided.targetType = "POST";
        decided.targetId = 21L;
        decided.status = "RESOLVED";
        decided.decisionCode = "VIOLATION";
        when(governanceService.decideReport(
            eq(41L), any(CommunityRequests.ReportDecision.class), eq("decision-key")))
            .thenReturn(decided);

        mockMvc.perform(get("/api/content-admin/reports")
                .with(authority("community:report:review")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.records[0].reporter.nickname").value("举报用户"))
            .andExpect(jsonPath("$.data.records[0].reporter.email").doesNotExist());

        mockMvc.perform(post("/api/content-admin/reports/41/decision")
                .with(authority("community:report:review"))
                .header("Idempotency-Key", "decision-key")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"decision\":\"RESOLVE\",\"decisionCode\":\"VIOLATION\",\"internalNote\":\"内部\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("RESOLVED"))
            .andExpect(jsonPath("$.data.internalNote").doesNotExist());

        mockMvc.perform(post("/api/content-admin/content/POST/21/hide")
                .with(authority("community:content:moderate"))
                .header("Idempotency-Key", "hide-key")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reasonCode\":\"SPAM\"}"))
            .andExpect(status().isOk());

        mockMvc.perform(post("/api/content-admin/content/POST/21/restore")
                .with(authority("community:content:restore"))
                .header("Idempotency-Key", "restore-key")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reasonCode\":\"REVIEWED_SAFE\"}"))
            .andExpect(status().isOk());

        mockMvc.perform(get("/api/content-admin/audit/events")
                .with(authority("community:audit:view")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.records[0].eventType").value("POST_HIDDEN"));

        mockMvc.perform(post("/api/content-admin/content/POST/21/restore")
                .with(authority("community:content:moderate"))
                .header("Idempotency-Key", "wrong-permission")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reasonCode\":\"REVIEWED_SAFE\"}"))
            .andExpect(status().isForbidden());

        verify(governanceService).moderate(
            eq("POST"), eq(21L), eq(false), any(CommunityRequests.ModerationAction.class), eq("hide-key"));
        verify(governanceService).moderate(
            eq("POST"), eq(21L), eq(true), any(CommunityRequests.ModerationAction.class), eq("restore-key"));
    }

    @Test
    void systemAdministratorWithoutExplicitModerationAuthorityReceivesForbidden() throws Exception {
        mockMvc.perform(post("/api/content-admin/content/POST/21/hide")
                .with(user("system-admin").roles("SYSTEM_ADMIN"))
                .header("Idempotency-Key", "admin-hide")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reasonCode\":\"SPAM\"}"))
            .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/content-admin/reports/41/decision")
                .with(user("system-admin").roles("SYSTEM_ADMIN"))
                .header("Idempotency-Key", "admin-decision")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"decision\":\"DISMISS\",\"decisionCode\":\"NO_VIOLATION\"}"))
            .andExpect(status().isForbidden());
    }

    private void assertEndpoint(String name, Class<?>[] argumentTypes,
                                Class<? extends java.lang.annotation.Annotation> mappingType,
                                String path, String permission) throws Exception {
        Method method = ContentAdminController.class.getMethod(name, argumentTypes);
        if (mappingType == GetMapping.class) {
            assertThat(method.getAnnotation(GetMapping.class).value()).containsExactly(path);
        } else {
            assertThat(method.getAnnotation(PostMapping.class).value()).containsExactly(path);
        }
        assertThat(method.getAnnotation(PreAuthorize.class).value())
            .isEqualTo("hasAuthority('" + permission + "')");
    }

    private org.springframework.test.web.servlet.request.RequestPostProcessor authority(String permission) {
        return user("content-operator")
            .authorities(new SimpleGrantedAuthority(permission));
    }

    private CommunityQueryRows.ReportRow reportRow() {
        CommunityQueryRows.ReportRow row = new CommunityQueryRows.ReportRow();
        row.id = 41L;
        row.reportNo = "CR-41";
        row.reporterUserId = 7L;
        row.reporterNickname = "举报用户";
        row.targetType = "POST";
        row.targetId = 21L;
        row.reasonText = "违规内容";
        row.status = "OPEN";
        return row;
    }

    private CommunityQueryRows.EventRow eventRow() {
        CommunityQueryRows.EventRow row = new CommunityQueryRows.EventRow();
        row.id = 51L;
        row.eventNo = "CGE-51";
        row.eventType = "POST_HIDDEN";
        row.subjectType = "POST";
        row.subjectId = 21L;
        row.actorScope = "CONTENT";
        row.sourceType = "CONTENT_OPERATOR";
        row.resultStatus = "SUCCEEDED";
        return row;
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
