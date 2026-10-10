package com.example.venue.feedback.controller;

import com.example.venue.common.AuditLog;
import com.example.venue.common.PageResult;
import com.example.venue.common.Result;
import com.example.venue.feedback.dto.CustomerServiceCaseActionRequest;
import com.example.venue.feedback.dto.CustomerServiceQuery;
import com.example.venue.feedback.dto.FeedbackResponses;
import com.example.venue.feedback.dto.ReviewModerationRequest;
import com.example.venue.feedback.service.CustomerFeedbackService;
import com.example.venue.feedback.service.FeedbackTextSanitizer;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/customer-service")
public class CustomerServiceAdminController {
    private final CustomerFeedbackService service;
    private final FeedbackTextSanitizer sanitizer;

    public CustomerServiceAdminController(CustomerFeedbackService service, FeedbackTextSanitizer sanitizer) {
        this.service = service;
        this.sanitizer = sanitizer;
    }

    @GetMapping("/complaints")
    @PreAuthorize("hasAuthority('customer-service:manage') or hasAuthority('complaint:manage')")
    public Result<PageResult<FeedbackResponses.AdminComplaint>> complaints(@Valid CustomerServiceQuery query) {
        return Result.ok(FeedbackResponses.page(service.adminComplaints(query), item -> FeedbackResponses.adminComplaint(item, sanitizer)));
    }

    @GetMapping("/cases")
    @PreAuthorize("hasAuthority('customer-service:manage')")
    public Result<PageResult<FeedbackResponses.AdminCase>> cases(@Valid CustomerServiceQuery query) {
        return Result.ok(FeedbackResponses.page(service.adminCases(query), item -> FeedbackResponses.adminCase(item, sanitizer)));
    }

    @GetMapping("/cases/{id}/events")
    @PreAuthorize("hasAuthority('customer-service:manage')")
    public Result<List<FeedbackResponses.AdminCaseEvent>> caseEvents(@PathVariable Long id) {
        return Result.ok(service.adminCaseEvents(id).stream()
            .map(item -> FeedbackResponses.adminCaseEvent(item, sanitizer))
            .toList());
    }

    @AuditLog(module = "客服工单", action = "分派工单")
    @PostMapping("/cases/{id}/assign")
    @PreAuthorize("hasAuthority('customer-service:manage')")
    public Result<FeedbackResponses.AdminCase> assign(@PathVariable Long id,
                                                      @Valid @RequestBody CustomerServiceCaseActionRequest request) {
        return Result.ok(FeedbackResponses.adminCase(service.assignCase(id, request), sanitizer));
    }

    @AuditLog(module = "客服工单", action = "变更工单状态")
    @PostMapping("/cases/{id}/status")
    @PreAuthorize("hasAuthority('customer-service:manage')")
    public Result<FeedbackResponses.AdminCase> status(@PathVariable Long id,
                                                      @Valid @RequestBody CustomerServiceCaseActionRequest request) {
        return Result.ok(FeedbackResponses.adminCase(service.changeCaseStatus(id, request), sanitizer));
    }

    @AuditLog(module = "客服工单", action = "添加客服回复")
    @PostMapping("/cases/{id}/reply")
    @PreAuthorize("hasAuthority('customer-service:manage')")
    public Result<FeedbackResponses.CaseEvent> reply(@PathVariable Long id,
                                                     @Valid @RequestBody CustomerServiceCaseActionRequest request) {
        return Result.ok(FeedbackResponses.caseEvent(service.addReply(id, request), sanitizer));
    }

    @AuditLog(module = "客服工单", action = "解决工单")
    @PostMapping("/cases/{id}/resolve")
    @PreAuthorize("hasAuthority('customer-service:manage')")
    public Result<FeedbackResponses.AdminCase> resolve(@PathVariable Long id,
                                                       @Valid @RequestBody CustomerServiceCaseActionRequest request) {
        return Result.ok(FeedbackResponses.adminCase(service.resolveCase(id, request), sanitizer));
    }

    @AuditLog(module = "客服工单", action = "关闭工单")
    @PostMapping("/cases/{id}/close")
    @PreAuthorize("hasAuthority('customer-service:manage')")
    public Result<FeedbackResponses.AdminCase> close(@PathVariable Long id,
                                                     @Valid @RequestBody CustomerServiceCaseActionRequest request) {
        return Result.ok(FeedbackResponses.adminCase(service.closeCase(id, request), sanitizer));
    }

    @AuditLog(module = "客服工单", action = "重开工单")
    @PostMapping("/cases/{id}/reopen")
    @PreAuthorize("hasAuthority('customer-service:manage')")
    public Result<FeedbackResponses.AdminCase> reopen(@PathVariable Long id,
                                                      @Valid @RequestBody CustomerServiceCaseActionRequest request) {
        return Result.ok(FeedbackResponses.adminCase(service.reopenCase(id, request), sanitizer));
    }

    @GetMapping("/reviews")
    @PreAuthorize("hasAuthority('review:manage')")
    public Result<PageResult<FeedbackResponses.AdminReview>> reviews(@Valid CustomerServiceQuery query) {
        return Result.ok(FeedbackResponses.page(service.adminReviews(query), item -> FeedbackResponses.adminReview(item, sanitizer)));
    }

    @AuditLog(module = "评价投诉", action = "评价审核")
    @PostMapping("/reviews/{id}/moderate")
    @PreAuthorize("hasAuthority('review:manage')")
    public Result<FeedbackResponses.AdminReview> moderate(@PathVariable Long id,
                                                         @Valid @RequestBody ReviewModerationRequest request) {
        return Result.ok(FeedbackResponses.adminReview(service.moderateReview(id, request), sanitizer));
    }

    @GetMapping("/context")
    @PreAuthorize("hasAuthority('customer-service:analytics')")
    public Result<Map<String, Object>> context(@Valid CustomerServiceQuery query) {
        return Result.ok(service.adminContext(query));
    }
}
