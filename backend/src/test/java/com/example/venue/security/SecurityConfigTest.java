package com.example.venue.security;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.RequestDispatcher;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(SecurityConfigTest.DispatcherProbeController.class)
@ContextConfiguration(classes = {
    SecurityConfigTest.DispatcherProbeController.class,
    SecurityConfig.class,
    SecurityConfigTest.SecurityTestConfig.class
})
@TestPropertySource(properties = "app.cors.allowed-origins=http://127.0.0.1")
class SecurityConfigTest {
    @Autowired
    private MockMvc mockMvc;

    @Test
    void sseRequestStillRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/notifications/sse"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void sseAsyncRedispatchIsPermitted() throws Exception {
        mockMvc.perform(get("/api/notifications/sse")
                .with(request -> {
                    request.setDispatcherType(DispatcherType.ASYNC);
                    return request;
                }))
            .andExpect(status().isOk())
            .andExpect(content().string("ok"));
    }

    @Test
    void sseErrorRedispatchOnSameUriIsPermitted() throws Exception {
        mockMvc.perform(get("/api/notifications/sse")
                .with(request -> {
                    request.setDispatcherType(DispatcherType.ERROR);
                    return request;
                }))
            .andExpect(status().isOk())
            .andExpect(content().string("ok"));
    }

    @Test
    void sseErrorRedispatchViaServletErrorPathIsPermitted() throws Exception {
        mockMvc.perform(get("/error")
                .with(request -> {
                    request.setDispatcherType(DispatcherType.ERROR);
                    request.setAttribute(RequestDispatcher.ERROR_REQUEST_URI, "/api/notifications/sse");
                    return request;
                }))
            .andExpect(status().isOk())
            .andExpect(content().string("ok"));
    }

    @Test
    void protectedProbeRequestStillRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/protected-dispatch-probe"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void protectedProbeAsyncRedispatchStillRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/protected-dispatch-probe")
                .with(request -> {
                    request.setDispatcherType(DispatcherType.ASYNC);
                    return request;
                }))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void protectedProbeErrorRedispatchStillRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/protected-dispatch-probe")
                .with(request -> {
                    request.setDispatcherType(DispatcherType.ERROR);
                    return request;
                }))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void browserFunnelReportingIsPublicButFavoriteWritesRemainProtected() throws Exception {
        mockMvc.perform(post("/api/funnel/events"))
            .andExpect(status().isOk())
            .andExpect(content().string("funnel"));
        mockMvc.perform(post("/api/funnel/favorites/venues/1"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void protectedPostRoutesRemainProtected() throws Exception {
        mockMvc.perform(post("/api/protected-post-probe"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void communityIdempotencyHeaderIsAllowedByCorsPreflight() throws Exception {
        mockMvc.perform(options("/api/community/posts")
                .header("Origin", "http://127.0.0.1")
                .header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "authorization,content-type,idempotency-key"))
            .andExpect(status().isOk())
            .andExpect(header().string("Access-Control-Allow-Headers",
                org.hamcrest.Matchers.containsStringIgnoringCase("Idempotency-Key")));
    }

    @RestController
    static class DispatcherProbeController {
        @GetMapping("/api/notifications/sse")
        String sseProbe() {
            return "ok";
        }

        @GetMapping("/api/protected-dispatch-probe")
        String protectedProbe() {
            return "ok";
        }

        @GetMapping("/error")
        String errorProbe() {
            return "ok";
        }

        @PostMapping("/api/funnel/events")
        String funnelProbe() {
            return "funnel";
        }

        @PostMapping("/api/funnel/favorites/venues/1")
        String favoriteProbe() {
            return "favorite";
        }

        @PostMapping("/api/protected-post-probe")
        String protectedPostProbe() {
            return "protected";
        }
    }

    @TestConfiguration
    static class SecurityTestConfig {
        @Bean
        JwtAuthenticationFilter jwtAuthenticationFilter() {
            return new JwtAuthenticationFilter(mock(JwtTokenService.class), mock(SecurityUserService.class));
        }

        // A26-P1-03：SecurityConfig 构造注入 FunnelSessionFilter，@WebMvcTest 不扫描 @Component，
        // 需在此显式提供 bean。FunnelSessionFilter 无依赖，直接 new 即可。
        @Bean
        FunnelSessionFilter funnelSessionFilter() {
            return new FunnelSessionFilter();
        }
    }
}
