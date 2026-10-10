package com.example.venue.reservation;

import com.example.venue.reservation.controller.ReservationController;
import com.example.venue.reservation.dto.CreateReservationRequest;
import com.example.venue.reservation.dto.ReservationSubmissionResult;
import com.example.venue.reservation.entity.Reservation;
import com.example.venue.reservation.service.ReservationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 预约提交的新增合同在 HTTP 层的验证：请求头绑定与只读核对端点的路由。
 *
 * <p>重点是 {@code /api/reservations/submission-result} 必须优先于 {@code /api/reservations/{id}} 匹配，
 * 否则核对入口会被当成「按 ID 查详情」，用户永远拿不回丢失的提交结果。</p>
 */
@WebMvcTest(ReservationController.class)
@ContextConfiguration(classes = {
    ReservationController.class,
    ReservationSubmissionResultWebMvcTest.SecurityConfig.class
})
class ReservationSubmissionResultWebMvcTest {
    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ReservationService reservationService;

    @Test
    void readOnlySubmissionResultWinsOverTheReservationDetailPathVariable() throws Exception {
        Reservation reservation = new Reservation();
        reservation.id = 88L;
        reservation.status = "PENDING_REVIEW";
        when(reservationService.submissionResult("intent-key-0001"))
            .thenReturn(ReservationSubmissionResult.completed(reservation));

        mockMvc.perform(get("/api/reservations/submission-result")
                .with(user("owner"))
                .param("idempotencyKey", "intent-key-0001"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("COMPLETED"))
            .andExpect(jsonPath("$.data.reservationId").value(88))
            .andExpect(jsonPath("$.data.reservation.status").value("PENDING_REVIEW"));

        verify(reservationService).submissionResult("intent-key-0001");
        verify(reservationService, org.mockito.Mockito.never()).myReservation(any());
    }

    @Test
    void createForwardsTheIdempotencyKeyHeaderToTheService() throws Exception {
        Reservation reservation = new Reservation();
        reservation.id = 77L;
        when(reservationService.create(any(CreateReservationRequest.class), eq("intent-key-0002")))
            .thenReturn(reservation);

        mockMvc.perform(post("/api/reservations")
                .with(user("owner"))
                .header("Idempotency-Key", "intent-key-0002")
                .contentType(MediaType.APPLICATION_JSON)
                .content(createBody()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.id").value(77));

        verify(reservationService).create(any(CreateReservationRequest.class), eq("intent-key-0002"));
    }

    private String createBody() {
        LocalDateTime start = LocalDate.now(ZoneId.of("Asia/Shanghai")).plusDays(1).atTime(10, 0);
        LocalDateTime end = start.plusHours(2);
        return """
            {
              "spaceId": 10,
              "startTime": "%s",
              "endTime": "%s",
              "participantCount": 1,
              "seatIds": [],
              "contactName": "王小明",
              "contactPhone": "13800000000"
            }
            """.formatted(start, end);
    }

    @TestConfiguration
    static class SecurityConfig {
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
