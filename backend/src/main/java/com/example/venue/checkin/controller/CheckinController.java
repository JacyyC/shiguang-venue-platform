package com.example.venue.checkin.controller;

import com.example.venue.checkin.dto.CheckinAdminQuery;
import com.example.venue.checkin.dto.CheckinVerifyRequest;
import com.example.venue.checkin.dto.ManualAttendanceRequest;
import com.example.venue.checkin.dto.NoShowDecisionRequest;
import com.example.venue.checkin.dto.ReservationLifecycleResponse;
import com.example.venue.checkin.service.ReservationLifecycleService;
import com.example.venue.common.PageResult;
import com.example.venue.common.Result;
import com.example.venue.security.SecurityContext;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class CheckinController {
    private final ReservationLifecycleService lifecycleService;

    public CheckinController(ReservationLifecycleService lifecycleService) {
        this.lifecycleService = lifecycleService;
    }

    @GetMapping("/api/checkins/my")
    @PreAuthorize("hasAuthority('reservation:checkin:me')")
    public Result<List<ReservationLifecycleResponse>> myCheckins() {
        return Result.ok(lifecycleService.myCheckins());
    }

    @GetMapping("/api/reservations/{id}/lifecycle")
    @PreAuthorize("hasAuthority('reservation:checkin:me')")
    public Result<ReservationLifecycleResponse> myLifecycle(@PathVariable Long id) {
        return Result.ok(lifecycleService.myLifecycle(id, SecurityContext.currentUserId()));
    }

    @PostMapping("/api/reservations/{id}/checkin-credential")
    @PreAuthorize("hasAuthority('reservation:checkin:me')")
    public Result<ReservationLifecycleResponse> reissueMyCredential(@PathVariable Long id) {
        return Result.ok(lifecycleService.reissueMyCredential(id, SecurityContext.currentUserId()));
    }

    @GetMapping("/api/admin/checkins")
    @PreAuthorize("hasAuthority('reservation:checkin:manage')")
    public Result<PageResult<ReservationLifecycleResponse>> adminCheckins(@Valid CheckinAdminQuery query) {
        return Result.ok(lifecycleService.adminCheckins(query));
    }

    @PostMapping("/api/admin/checkins/verify")
    @PreAuthorize("hasAuthority('reservation:checkin:manage')")
    public Result<ReservationLifecycleResponse> verify(@Valid @RequestBody CheckinVerifyRequest request) {
        return Result.ok(lifecycleService.toResponse(lifecycleService.verify(request)));
    }

    @PostMapping("/api/admin/checkins/manual-pass/{reservationId}")
    @PreAuthorize("hasAuthority('reservation:checkin:manage')")
    public Result<ReservationLifecycleResponse> manualPass(@PathVariable Long reservationId,
                                                           @Valid @RequestBody ManualAttendanceRequest request) {
        return Result.ok(lifecycleService.toResponse(lifecycleService.manualPass(reservationId, request)));
    }

    @PostMapping("/api/admin/checkins/no-show/{reservationId}/confirm")
    @PreAuthorize("hasAuthority('reservation:checkin:manage')")
    public Result<ReservationLifecycleResponse> confirmNoShow(@PathVariable Long reservationId,
                                                              @Valid @RequestBody NoShowDecisionRequest request) {
        return Result.ok(lifecycleService.toResponse(lifecycleService.confirmNoShow(reservationId, request)));
    }

    @PostMapping("/api/admin/checkins/no-show/{reservationId}/revoke")
    @PreAuthorize("hasAuthority('reservation:checkin:manage')")
    public Result<ReservationLifecycleResponse> revokeNoShow(@PathVariable Long reservationId,
                                                             @Valid @RequestBody NoShowDecisionRequest request) {
        return Result.ok(lifecycleService.toResponse(lifecycleService.revokeNoShow(reservationId, request)));
    }

    @GetMapping("/api/admin/reservations/{id}/lifecycle")
    @PreAuthorize("hasAuthority('reservation:lifecycle:view')")
    public Result<ReservationLifecycleResponse> adminLifecycle(@PathVariable Long id) {
        return Result.ok(lifecycleService.adminLifecycle(id));
    }
}
