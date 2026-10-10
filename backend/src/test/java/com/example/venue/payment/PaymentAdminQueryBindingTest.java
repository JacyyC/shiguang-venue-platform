package com.example.venue.payment;

import com.example.venue.common.GlobalExceptionHandler;
import com.example.venue.common.PageResult;
import com.example.venue.payment.controller.PaymentAdminController;
import com.example.venue.payment.dto.PaymentAdminQuery;
import com.example.venue.payment.entity.PaymentAttempt;
import com.example.venue.payment.service.FinanceReconciliationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * B5-H23 反例：PaymentAdminQuery 过滤参数必须经 @ModelAttribute 真实绑定到服务层。
 *
 * S04 隔离栈 general log 实测：纯 public 字段（无 setter）被 BeanWrapper 静默跳过，
 * `status=FAILED&venueId=2` 落到 mapper 的 SQL 只剩 provider 条件——过滤从未到达服务端。
 * DTO 补 setter 后，这里锁「查询串 → DTO 字段 → 服务层入参」整条链路。
 */
@WebMvcTest(PaymentAdminController.class)
@ContextConfiguration(classes = {
    PaymentAdminController.class,
    GlobalExceptionHandler.class,
    PaymentAdminQueryBindingTest.MethodSecurityConfig.class
})
class PaymentAdminQueryBindingTest {
    @Autowired private MockMvc mockMvc;
    @MockBean private FinanceReconciliationService service;

    @TestConfiguration
    @EnableMethodSecurity
    static class MethodSecurityConfig {
        @Bean
        SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
            http.csrf(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(request -> request.anyRequest().permitAll())
                .httpBasic(Customizer.withDefaults());
            return http.build();
        }
    }

    private PaymentAttempt attemptRow() {
        PaymentAttempt attempt = new PaymentAttempt();
        attempt.id = 1L;
        attempt.paymentNo = "PAY-TEST-001";
        attempt.status = "FAILED";
        return attempt;
    }

    @Test
    @WithMockUser(authorities = "payment:admin")
    void filterParamsReachTheServiceThroughModelAttributeBinding() throws Exception {
        when(service.attempts(any())).thenReturn(PageResult.of(List.of(attemptRow()), 1, 1, 10));

        mockMvc.perform(get("/api/admin/payments/attempts")
                .param("providerEnv", "DEV")
                .param("providerName", "DEV_PAYMENT")
                .param("status", "FAILED")
                .param("orderNo", "PAY20260914")
                .param("venueId", "21")
                .param("startTime", "2026-09-14T00:00:00")
                .param("endTime", "2026-09-15T00:00:00")
                .accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk());

        verify(service).attempts(org.mockito.ArgumentMatchers.argThat(query -> {
            assertThat(query.providerEnv).isEqualTo("DEV");
            assertThat(query.providerName).isEqualTo("DEV_PAYMENT");
            assertThat(query.status).isEqualTo("FAILED");
            assertThat(query.orderNo).isEqualTo("PAY20260914");
            assertThat(query.venueId).isEqualTo(21L);
            assertThat(query.startTime).isNotNull();
            assertThat(query.endTime).isNotNull();
            return true;
        }));
    }

    @Test
    @WithMockUser(authorities = "payment:admin")
    void malformedVenueIdIsRejectedInsteadOfSilentlyDropped() throws Exception {
        // 绑定生效的另一面：非法类型必须被拒绝（本仓全局异常处理器把绑定失败映射为 422），而不是被静默丢成 null 放大全量
        mockMvc.perform(get("/api/admin/payments/attempts")
                .param("venueId", "abc")
                .accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isUnprocessableEntity());
    }
}
