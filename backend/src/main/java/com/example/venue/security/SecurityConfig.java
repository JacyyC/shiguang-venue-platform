package com.example.venue.security;

import com.example.venue.common.Result;
import com.example.venue.common.ResultCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.RequestDispatcher;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@EnableConfigurationProperties({JwtProperties.class, CorsProperties.class})
public class SecurityConfig {
    private static final String SSE_NOTIFICATIONS_PATH = "/api/notifications/sse";
    private static final RequestMatcher SSE_NOTIFICATIONS_MATCHER = new AntPathRequestMatcher(SSE_NOTIFICATIONS_PATH);
    private static final RequestMatcher ERROR_PATH_MATCHER = new AntPathRequestMatcher("/error");

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final FunnelSessionFilter funnelSessionFilter;
    private final ObjectMapper objectMapper;

    public SecurityConfig(JwtAuthenticationFilter jwtAuthenticationFilter,
                          FunnelSessionFilter funnelSessionFilter,
                          ObjectMapper objectMapper) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
        this.funnelSessionFilter = funnelSessionFilter;
        this.objectMapper = objectMapper;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, CorsConfigurationSource corsConfigurationSource) throws Exception {
        http.csrf(csrf -> csrf.disable())
            .cors(cors -> cors.configurationSource(corsConfigurationSource))
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(sseRedispatchRequestMatcher()).permitAll()
                .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                .requestMatchers("/api/auth/login", "/api/auth/register", "/api/auth/forgot-password",
                    "/api/auth/reset-password").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/payments/callback/**").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/funnel/events", "/api/funnel/events/batch").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/venues/**", "/api/notices/public").permitAll()
                .requestMatchers("/actuator/health").permitAll()
                .anyRequest().authenticated()
            )
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint((request, response, authException) -> {
                    response.setStatus(401);
                    response.setContentType("application/json;charset=UTF-8");
                    response.getWriter().write(objectMapper.writeValueAsString(Result.fail(ResultCode.UNAUTHORIZED, "未登录或登录已过期")));
                })
                .accessDeniedHandler((request, response, accessDeniedException) -> {
                    response.setStatus(403);
                    response.setContentType("application/json;charset=UTF-8");
                    response.getWriter().write(objectMapper.writeValueAsString(Result.fail(ResultCode.FORBIDDEN, "无权限访问")));
                })
            )
            .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
            // A26-P1-03：FunnelSessionFilter 必须在 JwtAuthenticationFilter 之前执行，
            // 保证 Service 层 recordMilestone 调用方在 SecurityContext 已就绪之前就能
            // 从 ThreadLocal 读到 X-Funnel-Session（鉴权失败路径不写入里程碑事件，
            // 但鉴权成功的业务接口需要 sessionKey 已绑定）。
            .addFilterBefore(funnelSessionFilter, JwtAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration configuration) throws Exception {
        return configuration.getAuthenticationManager();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource(CorsProperties properties) {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(Arrays.stream(properties.getAllowedOrigins().split(",")).map(String::trim).toList());
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        // A26-P1-03：允许浏览器在跨域请求中携带 X-Funnel-Session，使服务端权威里程碑事件
        // （RESERVATION_SUBMITTED/ORDER_CREATED/PAYMENT_INITIATED/PAYMENT_RESULT_RECORDED）
        // 能与前序浏览器事件共享同一 sessionKey，避免漏斗 cohort 第 6~9 步结构性断链。
        config.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-Requested-With",
            "X-Funnel-Session", "Idempotency-Key"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    private RequestMatcher sseRedispatchRequestMatcher() {
        return request -> switch (request.getDispatcherType()) {
            case ASYNC -> SSE_NOTIFICATIONS_MATCHER.matches(request);
            case ERROR -> SSE_NOTIFICATIONS_MATCHER.matches(request) || isSseErrorRedispatch(request);
            default -> false;
        };
    }

    private boolean isSseErrorRedispatch(jakarta.servlet.http.HttpServletRequest request) {
        Object originalRequestUri = request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI);
        return ERROR_PATH_MATCHER.matches(request) && SSE_NOTIFICATIONS_PATH.equals(originalRequestUri);
    }
}
