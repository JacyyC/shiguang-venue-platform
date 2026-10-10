package com.example.venue.reservation.controller;

import com.example.venue.checkin.dto.RescheduleRequest;
import com.example.venue.checkin.dto.RescheduleReviewRequest;
import com.example.venue.checkin.dto.ReservationRescheduleResponse;
import com.example.venue.common.AuditLog;
import com.example.venue.common.PageQuery;
import com.example.venue.common.PageResult;
import com.example.venue.common.Result;
import com.example.venue.member.dto.PricingPreviewResponse;
import com.example.venue.reservation.dto.AvailabilitySlot;
import com.example.venue.reservation.dto.CreateReservationRequest;
import com.example.venue.reservation.dto.ReservationCancelRequest;
import com.example.venue.reservation.dto.ReservationQuery;
import com.example.venue.reservation.dto.ReservationQuoteRequest;
import com.example.venue.reservation.dto.ReservationRejectRequest;
import com.example.venue.reservation.dto.ReservationRules;
import com.example.venue.reservation.dto.ReservationSubmissionResult;
import com.example.venue.reservation.dto.VenueAvailabilitySummary;
import com.example.venue.reservation.entity.Reservation;
import com.example.venue.reservation.service.ReservationService;
import com.example.venue.resource.dto.ResourceAvailabilityResponse;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@RestController
public class ReservationController {
    private final ReservationService reservationService;

    public ReservationController(ReservationService reservationService) {
        this.reservationService = reservationService;
    }

    @GetMapping("/api/reservations/availability")
    public Result<List<AvailabilitySlot>> availability(@RequestParam Long spaceId, @RequestParam String date) {
        return Result.ok(reservationService.availability(spaceId, LocalDate.parse(date)));
    }

    @GetMapping("/api/reservations/availability-summary")
    public Result<List<VenueAvailabilitySummary>> availabilitySummary(
        @RequestParam String date,
        @RequestParam("venueIds") List<Long> venueIds
    ) {
        return Result.ok(reservationService.venueAvailabilitySummary(LocalDate.parse(date), venueIds));
    }

    @GetMapping("/api/reservations/{id}/reschedule-slots")
    public Result<List<AvailabilitySlot>> rescheduleSlots(@PathVariable Long id, @RequestParam String date) {
        return Result.ok(reservationService.rescheduleSlots(id, LocalDate.parse(date)));
    }

    @GetMapping("/api/reservations/resource-availability")
    public Result<ResourceAvailabilityResponse> resourceAvailability(
        @RequestParam Long spaceId,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startTime,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endTime,
        @RequestParam(required = false) Integer participantCount,
        @RequestParam(required = false) List<Long> seatIds
    ) {
        return Result.ok(reservationService.resourceAvailability(
            spaceId, startTime, endTime, participantCount, seatIds));
    }

    @GetMapping("/api/reservations/rules")
    public Result<ReservationRules> rules() {
        return Result.ok(reservationService.rules());
    }

    @AuditLog(module = "预约管理", action = "提交预约")
    @PostMapping("/api/reservations")
    public Result<Reservation> create(@Valid @RequestBody CreateReservationRequest request,
                                      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        return Result.ok(reservationService.create(request, idempotencyKey));
    }

    /**
     * 只读核对一次提交意图的结果：响应丢失、刷新或返回后用它恢复，而不是换键重投。
     * 未带键时返回 {@code NONE}，与「从未提交」同义。
     */
    @GetMapping("/api/reservations/submission-result")
    public Result<ReservationSubmissionResult> submissionResult(
        @RequestParam(value = "idempotencyKey", required = false) String idempotencyKey) {
        return Result.ok(reservationService.submissionResult(idempotencyKey));
    }

    @PostMapping("/api/reservations/pricing-preview")
    public Result<PricingPreviewResponse> pricingPreview(@Valid @RequestBody CreateReservationRequest request) {
        return Result.ok(reservationService.pricingPreview(request));
    }

    @PostMapping("/api/reservations/quote")
    public Result<PricingPreviewResponse> quote(@Valid @RequestBody ReservationQuoteRequest request) {
        return Result.ok(reservationService.quote(request));
    }

    @GetMapping("/api/reservations/{id}")
    public Result<Reservation> detail(@PathVariable Long id) {
        return Result.ok(reservationService.myReservation(id));
    }

    @GetMapping("/api/reservations/my")
    public Result<PageResult<Reservation>> my(@Valid ReservationQuery query) {
        return Result.ok(reservationService.myReservations(query));
    }

    @AuditLog(module = "预约管理", action = "用户取消预约")
    @PutMapping("/api/reservations/{id}/cancel")
    public Result<Void> cancel(@PathVariable Long id, @Valid @RequestBody ReservationCancelRequest request) {
        reservationService.cancel(id, request.reasonCode, request.reasonText, false);
        return Result.ok();
    }

    @PostMapping("/api/reservations/{id}/reschedule-requests")
    public Result<ReservationRescheduleResponse> requestReschedule(@PathVariable Long id,
                                                                   @Valid @RequestBody RescheduleRequest request) {
        return Result.ok(reservationService.requestReschedule(id, request));
    }

    @GetMapping("/api/reservations/{id}/reschedule-availability")
    public Result<ResourceAvailabilityResponse> rescheduleAvailability(
        @PathVariable Long id,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startTime,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endTime
    ) {
        return Result.ok(reservationService.rescheduleAvailability(id, startTime, endTime));
    }

    @GetMapping("/api/reservations/reschedule-requests/my")
    public Result<PageResult<ReservationRescheduleResponse>> myRescheduleRequests(@Valid PageQuery query) {
        return Result.ok(reservationService.myRescheduleRequests(query));
    }

    @GetMapping("/api/admin/reservations")
    @PreAuthorize("hasAuthority('reservation:audit')")
    public Result<PageResult<Reservation>> adminList(@Valid ReservationQuery query) {
        return Result.ok(reservationService.adminReservations(query));
    }

    @GetMapping("/api/admin/reschedule-requests")
    @PreAuthorize("hasAuthority('reservation:audit')")
    public Result<PageResult<ReservationRescheduleResponse>> adminRescheduleRequests(@Valid PageQuery query) {
        return Result.ok(reservationService.adminRescheduleRequests(query));
    }

    @PutMapping("/api/admin/reschedule-requests/{id}/review")
    @PreAuthorize("hasAuthority('reservation:audit')")
    public Result<Void> reviewReschedule(@PathVariable Long id, @Valid @RequestBody RescheduleReviewRequest request) {
        reservationService.reviewReschedule(id, request.approve, request.reviewReason);
        return Result.ok();
    }

    @AuditLog(module = "预约审核", action = "审核通过")
    @PutMapping("/api/admin/reservations/{id}/approve")
    @PreAuthorize("hasAuthority('reservation:audit')")
    public Result<Void> approve(@PathVariable Long id, @RequestBody(required = false) Map<String, String> body) {
        reservationService.approve(id, true, body == null ? "审核通过" : body.getOrDefault("reason", "审核通过"));
        return Result.ok();
    }

    @AuditLog(module = "预约审核", action = "审核拒绝")
    @PutMapping("/api/admin/reservations/{id}/reject")
    @PreAuthorize("hasAuthority('reservation:audit')")
    public Result<Void> reject(@PathVariable Long id, @Valid @RequestBody ReservationRejectRequest request) {
        reservationService.approve(id, false, request.reason);
        return Result.ok();
    }

    @AuditLog(module = "预约管理", action = "管理员取消预约")
    @PutMapping("/api/admin/reservations/{id}/cancel")
    @PreAuthorize("hasAuthority('reservation:audit')")
    public Result<Void> adminCancel(@PathVariable Long id, @Valid @RequestBody ReservationCancelRequest request) {
        reservationService.cancel(id, request.reasonCode, request.reasonText, true);
        return Result.ok();
    }
}
