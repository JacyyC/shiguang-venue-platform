package com.example.venue.feedback.controller;

import com.example.venue.common.AuditLog;
import com.example.venue.common.PageResult;
import com.example.venue.common.Result;
import com.example.venue.feedback.dto.ComplaintSubmitRequest;
import com.example.venue.feedback.dto.FeedbackResponses;
import com.example.venue.feedback.dto.FeedbackQuery;
import com.example.venue.feedback.dto.PublicReviewResponse;
import com.example.venue.feedback.dto.ReviewSubmitRequest;
import com.example.venue.feedback.service.CustomerFeedbackService;
import com.example.venue.feedback.service.FeedbackTextSanitizer;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
public class CustomerFeedbackController {
    private final CustomerFeedbackService service;
    private final FeedbackTextSanitizer sanitizer;

    public CustomerFeedbackController(CustomerFeedbackService service, FeedbackTextSanitizer sanitizer) {
        this.service = service;
        this.sanitizer = sanitizer;
    }

    @GetMapping("/api/me/feedback/reviews")
    @PreAuthorize("hasAuthority('reservation:my')")
    public Result<PageResult<FeedbackResponses.UserReview>> myReviews(@Valid FeedbackQuery query) {
        return Result.ok(FeedbackResponses.page(service.myReviews(query), item -> FeedbackResponses.userReview(item, sanitizer)));
    }

    @AuditLog(module = "评价投诉", action = "提交评价")
    @PostMapping("/api/me/feedback/reviews")
    @PreAuthorize("hasAuthority('reservation:my')")
    public Result<FeedbackResponses.UserReview> submitReview(@Valid @RequestBody ReviewSubmitRequest request) {
        return Result.ok(FeedbackResponses.userReview(service.submitReview(request), sanitizer));
    }

    @GetMapping("/api/me/feedback/complaints")
    @PreAuthorize("hasAuthority('reservation:my')")
    public Result<PageResult<FeedbackResponses.UserComplaint>> myComplaints(@Valid FeedbackQuery query) {
        return Result.ok(FeedbackResponses.page(service.myComplaints(query), item -> FeedbackResponses.userComplaint(item, sanitizer)));
    }

    @AuditLog(module = "评价投诉", action = "提交投诉")
    @PostMapping("/api/me/feedback/complaints")
    @PreAuthorize("hasAuthority('reservation:my')")
    public Result<FeedbackResponses.UserComplaint> submitComplaint(@Valid @RequestBody ComplaintSubmitRequest request) {
        return Result.ok(FeedbackResponses.userComplaint(service.submitComplaint(request), sanitizer));
    }

    @GetMapping("/api/me/feedback/cases")
    @PreAuthorize("hasAuthority('reservation:my')")
    public Result<PageResult<FeedbackResponses.UserCase>> myCases(@Valid FeedbackQuery query) {
        return Result.ok(FeedbackResponses.page(service.myCases(query), item -> FeedbackResponses.userCase(item, sanitizer)));
    }

    @GetMapping("/api/me/feedback/cases/{id}/events")
    @PreAuthorize("hasAuthority('reservation:my')")
    public Result<List<FeedbackResponses.UserCaseEvent>> myCaseEvents(@PathVariable Long id) {
        return Result.ok(service.myCaseEvents(id).stream()
            .map(item -> FeedbackResponses.userCaseEvent(item, sanitizer))
            .toList());
    }

    @GetMapping("/api/venues/{venueId}/reviews/summary")
    public Result<Map<String, Object>> reviewSummary(@PathVariable Long venueId) {
        return Result.ok(service.publicReviewSummary(venueId));
    }

    @GetMapping("/api/venues/{venueId}/reviews")
    public Result<PageResult<PublicReviewResponse>> publicReviews(@PathVariable Long venueId,
                                                                  @Valid FeedbackQuery query) {
        FeedbackQuery safeQuery = query == null ? new FeedbackQuery() : query;
        safeQuery.venueId = venueId;
        return Result.ok(service.publicReviews(safeQuery));
    }
}
