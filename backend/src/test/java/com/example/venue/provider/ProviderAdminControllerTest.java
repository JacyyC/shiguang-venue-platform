package com.example.venue.provider;

import com.example.venue.common.AuditLog;
import com.example.venue.common.GlobalExceptionHandler;
import com.example.venue.common.PageResult;
import com.example.venue.common.Result;
import com.example.venue.common.ResultCode;
import com.example.venue.provider.controller.ProviderAdminController;
import com.example.venue.provider.dto.ProviderQuery;
import com.example.venue.provider.dto.VenueObservationLocationRequest;
import com.example.venue.provider.service.ProviderAdminService;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ProviderAdminControllerTest {
    @Test
    void observeEndpointsRequireProviderObservePermission() throws Exception {
        assertGet("overview", "/api/admin/providers/overview");
        assertGet("calls", ProviderQuery.class, "/api/admin/providers/calls");
        assertGet("weatherSnapshots", ProviderQuery.class, "/api/admin/providers/weather/snapshots");
        assertGet("riskSnapshots", ProviderQuery.class, "/api/admin/providers/risk/snapshots");
        assertGet("metricSnapshots", ProviderQuery.class, "/api/admin/providers/metrics/snapshots");
        assertGet("locations", "/api/admin/providers/venues/locations");
    }

    @Test
    void refreshAndLocationEndpointsRequireProviderRefreshAndAudit() throws Exception {
        Method weather = ProviderAdminController.class.getMethod("refreshWeather", Long.class);
        assertThat(weather.getAnnotation(PreAuthorize.class).value()).isEqualTo("hasAuthority('provider:refresh')");
        assertThat(weather.getAnnotation(PostMapping.class).value()).containsExactly("/api/admin/providers/weather/refresh");
        assertThat(weather.getAnnotation(AuditLog.class).module()).isEqualTo("Provider观测");
        assertThat(weather.getAnnotation(AuditLog.class).action()).isEqualTo("刷新天气provider");
        assertThat(weather.getReturnType()).isEqualTo(Result.class);

        Method risk = ProviderAdminController.class.getMethod("refreshRisk", Long.class);
        assertThat(risk.getAnnotation(PreAuthorize.class).value()).isEqualTo("hasAuthority('provider:refresh')");
        assertThat(risk.getAnnotation(PostMapping.class).value()).containsExactly("/api/admin/providers/risk/refresh");
        assertThat(risk.getAnnotation(AuditLog.class).module()).isEqualTo("Provider观测");
        assertThat(risk.getAnnotation(AuditLog.class).action()).isEqualTo("刷新风险provider");
        assertThat(risk.getParameters()[0].getAnnotation(RequestParam.class).required()).isFalse();
        assertThat(risk.getReturnType()).isEqualTo(Result.class);

        Method metrics = ProviderAdminController.class.getMethod("refreshMetrics");
        assertThat(metrics.getAnnotation(PreAuthorize.class).value()).isEqualTo("hasAuthority('provider:refresh')");
        assertThat(metrics.getAnnotation(PostMapping.class).value()).containsExactly("/api/admin/providers/metrics/refresh");
        assertThat(metrics.getAnnotation(AuditLog.class).module()).isEqualTo("Provider观测");
        assertThat(metrics.getAnnotation(AuditLog.class).action()).isEqualTo("刷新指标provider");
        assertThat(metrics.getReturnType()).isEqualTo(Result.class);

        Method location = ProviderAdminController.class.getMethod("saveLocation", Long.class, VenueObservationLocationRequest.class);
        assertThat(location.getAnnotation(PreAuthorize.class).value()).isEqualTo("hasAuthority('provider:refresh')");
        assertThat(location.getAnnotation(PutMapping.class).value()).containsExactly("/api/admin/providers/venues/{venueId}/location");
        assertThat(location.getAnnotation(AuditLog.class).module()).isEqualTo("Provider观测");
        assertThat(location.getAnnotation(AuditLog.class).action()).isEqualTo("维护场馆观测位置");
    }

    @Test
    void invalidCallSourceTypeUsesTheProviderBadRequestResponse() throws Exception {
        ProviderAdminService service = mock(ProviderAdminService.class);
        when(service.callLogs(any(ProviderQuery.class))).thenAnswer(invocation -> {
            ProviderQuery query = invocation.getArgument(0);
            query.normalizedSourceType();
            return PageResult.of(List.of(), 0, 1, 20);
        });
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new ProviderAdminController(service))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

        mockMvc.perform(get("/api/admin/providers/calls").param("sourceType", " batch "))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value(ResultCode.BAD_REQUEST.getCode()))
            .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("sourceType")));
    }

    @Test
    void callsBindsEveryProviderQueryFieldFromHttpRequest() throws Exception {
        ProviderAdminService service = mock(ProviderAdminService.class);
        when(service.callLogs(any(ProviderQuery.class))).thenReturn(PageResult.of(List.of(), 0, 2, 25));
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new ProviderAdminController(service))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

        mockMvc.perform(get("/api/admin/providers/calls")
                .param("providerKey", "weather.open_meteo.dev")
                .param("providerDomain", "FUTURE_DOMAIN")
                .param("providerEnv", "DEV")
                .param("providerName", "OPEN_METEO")
                .param("operationType", "COLLECT")
                .param("status", "SUCCESS")
                .param("snapshotType", "WEATHER")
                .param("sourceType", "MANUAL")
                .param("venueId", "7")
                .param("metricKey", "redis_hit_rate")
                .param("snapshotId", "11")
                .param("startTime", "2026-07-13T10:15:00")
                .param("endTime", "2026-07-13T11:15:00")
                .param("pageNum", "2")
                .param("pageSize", "25"))
            .andExpect(status().isOk());

        org.mockito.ArgumentCaptor<ProviderQuery> queryCaptor =
            org.mockito.ArgumentCaptor.forClass(ProviderQuery.class);
        verify(service).callLogs(queryCaptor.capture());
        ProviderQuery query = queryCaptor.getValue();
        assertThat(query.providerKey).isEqualTo("weather.open_meteo.dev");
        assertThat(query.providerDomain).isEqualTo("FUTURE_DOMAIN");
        assertThat(query.providerEnv).isEqualTo("DEV");
        assertThat(query.providerName).isEqualTo("OPEN_METEO");
        assertThat(query.operationType).isEqualTo("COLLECT");
        assertThat(query.status).isEqualTo("SUCCESS");
        assertThat(query.snapshotType).isEqualTo("WEATHER");
        assertThat(query.sourceType).isEqualTo("MANUAL");
        assertThat(query.venueId).isEqualTo(7L);
        assertThat(query.metricKey).isEqualTo("redis_hit_rate");
        assertThat(query.snapshotId).isEqualTo(11L);
        assertThat(query.startTime).isEqualTo(LocalDateTime.of(2026, 7, 13, 10, 15));
        assertThat(query.endTime).isEqualTo(LocalDateTime.of(2026, 7, 13, 11, 15));
        assertThat(query.getPageNum()).isEqualTo(2L);
        assertThat(query.getPageSize()).isEqualTo(25L);
    }

    private void assertGet(String methodName, String path) throws Exception {
        Method method = ProviderAdminController.class.getMethod(methodName);
        assertThat(method.getAnnotation(PreAuthorize.class).value()).isEqualTo("hasAuthority('provider:observe')");
        assertThat(method.getAnnotation(GetMapping.class).value()).containsExactly(path);
    }

    private void assertGet(String methodName, Class<?> arg, String path) throws Exception {
        Method method = ProviderAdminController.class.getMethod(methodName, arg);
        assertThat(method.getAnnotation(PreAuthorize.class).value()).isEqualTo("hasAuthority('provider:observe')");
        assertThat(method.getAnnotation(GetMapping.class).value()).containsExactly(path);
    }
}
