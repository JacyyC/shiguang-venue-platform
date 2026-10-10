package com.example.venue.checkin;

import com.example.venue.checkin.controller.CheckinController;
import com.example.venue.checkin.dto.CheckinAdminQuery;
import com.example.venue.checkin.dto.CheckinVerifyRequest;
import com.example.venue.checkin.dto.ManualAttendanceRequest;
import com.example.venue.checkin.dto.NoShowDecisionRequest;
import com.example.venue.checkin.dto.ReservationLifecycleResponse;
import com.example.venue.checkin.entity.ReservationLifecycle;
import com.example.venue.checkin.service.ReservationLifecycleService;
import com.example.venue.common.CurrentUser;
import com.example.venue.common.PageResult;
import com.example.venue.common.Result;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CheckinControllerTest {
    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void adminCheckinEndpointsUseCheckinManagePermission() throws Exception {
        Method adminCheckins = CheckinController.class.getDeclaredMethod("adminCheckins", CheckinAdminQuery.class);
        assertThat(adminCheckins.getAnnotation(PreAuthorize.class).value())
            .isEqualTo("hasAuthority('reservation:checkin:manage')");

        Method verify = CheckinController.class.getDeclaredMethod("verify", CheckinVerifyRequest.class);
        assertThat(verify.getAnnotation(PreAuthorize.class).value())
            .isEqualTo("hasAuthority('reservation:checkin:manage')");

        Method manualPass = CheckinController.class
            .getDeclaredMethod("manualPass", Long.class, ManualAttendanceRequest.class);
        assertThat(manualPass.getAnnotation(PreAuthorize.class).value())
            .isEqualTo("hasAuthority('reservation:checkin:manage')");

        Method confirmNoShow = CheckinController.class
            .getDeclaredMethod("confirmNoShow", Long.class, NoShowDecisionRequest.class);
        assertThat(confirmNoShow.getAnnotation(PreAuthorize.class).value())
            .isEqualTo("hasAuthority('reservation:checkin:manage')");

        Method revokeNoShow = CheckinController.class
            .getDeclaredMethod("revokeNoShow", Long.class, NoShowDecisionRequest.class);
        assertThat(revokeNoShow.getAnnotation(PreAuthorize.class).value())
            .isEqualTo("hasAuthority('reservation:checkin:manage')");
    }

    @Test
    void lifecycleViewEndpointUsesLifecyclePermission() throws Exception {
        Method adminLifecycle = CheckinController.class.getDeclaredMethod("adminLifecycle", Long.class);

        assertThat(adminLifecycle.getAnnotation(PreAuthorize.class).value())
            .isEqualTo("hasAuthority('reservation:lifecycle:view')");
    }

    @Test
    void userCheckinEndpointsUseMePermission() throws Exception {
        Method myCheckins = CheckinController.class.getDeclaredMethod("myCheckins");
        assertThat(myCheckins.getAnnotation(PreAuthorize.class).value())
            .isEqualTo("hasAuthority('reservation:checkin:me')");

        Method myLifecycle = CheckinController.class.getDeclaredMethod("myLifecycle", Long.class);
        assertThat(myLifecycle.getAnnotation(PreAuthorize.class).value())
            .isEqualTo("hasAuthority('reservation:checkin:me')");

        Method reissueMyCredential = CheckinController.class.getDeclaredMethod("reissueMyCredential", Long.class);
        assertThat(reissueMyCredential.getAnnotation(PreAuthorize.class).value())
            .isEqualTo("hasAuthority('reservation:checkin:me')");
    }

    @Test
    void myCheckinsReturnsCurrentUsersCredentials() {
        ReservationLifecycleService lifecycleService = mock(ReservationLifecycleService.class);
        CheckinController controller = new CheckinController(lifecycleService);
        ReservationLifecycleResponse response = response(99L);
        when(lifecycleService.myCheckins()).thenReturn(List.of(response));

        Result<List<ReservationLifecycleResponse>> result = controller.myCheckins();

        assertThat(result.getData()).containsExactly(response);
        verify(lifecycleService).myCheckins();
    }

    @Test
    void myLifecycleUsesCurrentUserId() {
        authenticate(7L);
        ReservationLifecycleService lifecycleService = mock(ReservationLifecycleService.class);
        CheckinController controller = new CheckinController(lifecycleService);
        ReservationLifecycleResponse response = response(99L);
        when(lifecycleService.myLifecycle(99L, 7L)).thenReturn(response);

        Result<ReservationLifecycleResponse> result = controller.myLifecycle(99L);

        assertThat(result.getData()).isSameAs(response);
        verify(lifecycleService).myLifecycle(99L, 7L);
    }

    @Test
    void reissueMyCredentialUsesCurrentUserId() {
        authenticate(7L);
        ReservationLifecycleService lifecycleService = mock(ReservationLifecycleService.class);
        CheckinController controller = new CheckinController(lifecycleService);
        ReservationLifecycleResponse response = response(99L);
        when(lifecycleService.reissueMyCredential(99L, 7L)).thenReturn(response);

        Result<ReservationLifecycleResponse> result = controller.reissueMyCredential(99L);

        assertThat(result.getData()).isSameAs(response);
        verify(lifecycleService).reissueMyCredential(99L, 7L);
    }

    @Test
    void adminCheckinsDelegatesQueryToService() {
        ReservationLifecycleService lifecycleService = mock(ReservationLifecycleService.class);
        CheckinController controller = new CheckinController(lifecycleService);
        CheckinAdminQuery query = new CheckinAdminQuery();
        PageResult<ReservationLifecycleResponse> page =
            PageResult.of(List.of(response(99L)), 1, 1, 10);
        when(lifecycleService.adminCheckins(query)).thenReturn(page);

        Result<PageResult<ReservationLifecycleResponse>> result = controller.adminCheckins(query);

        assertThat(result.getData()).isSameAs(page);
        verify(lifecycleService).adminCheckins(query);
    }

    @Test
    void verifyDelegatesToServiceAndMapsLifecycleResponse() {
        ReservationLifecycleService lifecycleService = mock(ReservationLifecycleService.class);
        CheckinController controller = new CheckinController(lifecycleService);
        CheckinVerifyRequest request = new CheckinVerifyRequest();
        request.credential = "CHECKIN:99:123456";
        ReservationLifecycle lifecycle = lifecycle(99L);
        ReservationLifecycleResponse response = response(99L);
        when(lifecycleService.verify(request)).thenReturn(lifecycle);
        when(lifecycleService.toResponse(lifecycle)).thenReturn(response);

        Result<ReservationLifecycleResponse> result = controller.verify(request);

        assertThat(result.getData()).isSameAs(response);
        verify(lifecycleService).verify(request);
        verify(lifecycleService).toResponse(lifecycle);
    }

    @Test
    void manualPassDelegatesToServiceAndMapsLifecycleResponse() {
        ReservationLifecycleService lifecycleService = mock(ReservationLifecycleService.class);
        CheckinController controller = new CheckinController(lifecycleService);
        ManualAttendanceRequest request = new ManualAttendanceRequest();
        request.reasonCode = "OFFLINE_CHECKIN";
        request.reasonText = "线下登记";
        ReservationLifecycle lifecycle = lifecycle(99L);
        ReservationLifecycleResponse response = response(99L);
        when(lifecycleService.manualPass(99L, request)).thenReturn(lifecycle);
        when(lifecycleService.toResponse(lifecycle)).thenReturn(response);

        Result<ReservationLifecycleResponse> result = controller.manualPass(99L, request);

        assertThat(result.getData()).isSameAs(response);
        verify(lifecycleService).manualPass(99L, request);
        verify(lifecycleService).toResponse(lifecycle);
    }

    @Test
    void confirmNoShowDelegatesToServiceAndMapsLifecycleResponse() {
        ReservationLifecycleService lifecycleService = mock(ReservationLifecycleService.class);
        CheckinController controller = new CheckinController(lifecycleService);
        NoShowDecisionRequest request = noShowRequest();
        ReservationLifecycle lifecycle = lifecycle(99L);
        ReservationLifecycleResponse response = response(99L);
        when(lifecycleService.confirmNoShow(99L, request)).thenReturn(lifecycle);
        when(lifecycleService.toResponse(lifecycle)).thenReturn(response);

        Result<ReservationLifecycleResponse> result = controller.confirmNoShow(99L, request);

        assertThat(result.getData()).isSameAs(response);
        verify(lifecycleService).confirmNoShow(99L, request);
        verify(lifecycleService).toResponse(lifecycle);
    }

    @Test
    void revokeNoShowDelegatesToServiceAndMapsLifecycleResponse() {
        ReservationLifecycleService lifecycleService = mock(ReservationLifecycleService.class);
        CheckinController controller = new CheckinController(lifecycleService);
        NoShowDecisionRequest request = noShowRequest();
        ReservationLifecycle lifecycle = lifecycle(99L);
        ReservationLifecycleResponse response = response(99L);
        when(lifecycleService.revokeNoShow(99L, request)).thenReturn(lifecycle);
        when(lifecycleService.toResponse(lifecycle)).thenReturn(response);

        Result<ReservationLifecycleResponse> result = controller.revokeNoShow(99L, request);

        assertThat(result.getData()).isSameAs(response);
        verify(lifecycleService).revokeNoShow(99L, request);
        verify(lifecycleService).toResponse(lifecycle);
    }

    @Test
    void adminLifecycleDelegatesToService() {
        ReservationLifecycleService lifecycleService = mock(ReservationLifecycleService.class);
        CheckinController controller = new CheckinController(lifecycleService);
        ReservationLifecycleResponse response = response(99L);
        when(lifecycleService.adminLifecycle(99L)).thenReturn(response);

        Result<ReservationLifecycleResponse> result = controller.adminLifecycle(99L);

        assertThat(result.getData()).isSameAs(response);
        verify(lifecycleService).adminLifecycle(99L);
    }

    private void authenticate(Long userId) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
            new CurrentUser(userId, "checkin-user", Set.of("USER"), Set.of("reservation:checkin:me")),
            null,
            List.of()));
    }

    private ReservationLifecycle lifecycle(Long reservationId) {
        ReservationLifecycle lifecycle = new ReservationLifecycle();
        lifecycle.reservationId = reservationId;
        lifecycle.orderId = 77L;
        lifecycle.venueId = 20L;
        lifecycle.spaceId = 10L;
        return lifecycle;
    }

    private ReservationLifecycleResponse response(Long reservationId) {
        ReservationLifecycleResponse response = new ReservationLifecycleResponse();
        response.reservationId = reservationId;
        response.orderId = 77L;
        response.venueId = 20L;
        response.spaceId = 10L;
        return response;
    }

    private NoShowDecisionRequest noShowRequest() {
        NoShowDecisionRequest request = new NoShowDecisionRequest();
        request.reasonCode = "NO_ARRIVAL";
        request.reasonText = "核销窗口关闭仍未到场";
        return request;
    }
}
