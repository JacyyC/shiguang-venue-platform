package com.example.venue.notice.controller;

import com.example.venue.common.AuditLog;
import com.example.venue.common.PageQuery;
import com.example.venue.common.PageResult;
import com.example.venue.common.Result;
import com.example.venue.notice.dto.AudiencePreviewRequest;
import com.example.venue.notice.dto.AudiencePreviewResponse;
import com.example.venue.notice.dto.NoticeAudienceResponse;
import com.example.venue.notice.dto.NoticeDeliveryBatchResponse;
import com.example.venue.notice.dto.NoticeDeliveryOutboxResponse;
import com.example.venue.notice.dto.NoticeDeliveryOverviewResponse;
import com.example.venue.notice.dto.NoticeDeliveryQuery;
import com.example.venue.notice.dto.NoticeExperimentResponse;
import com.example.venue.notice.dto.NoticeExperimentResultResponse;
import com.example.venue.notice.dto.NoticeReadRequest;
import com.example.venue.notice.dto.NoticeRequest;
import com.example.venue.notice.dto.NotificationProviderOptionResponse;
import com.example.venue.notice.dto.PublicNoticeResponse;
import com.example.venue.notice.dto.SaveNoticeExperimentRequest;
import com.example.venue.notice.entity.Notice;
import com.example.venue.notice.entity.NoticeMessage;
import com.example.venue.notice.service.NoticeAnalyticsService;
import com.example.venue.notice.service.NoticeAudienceService;
import com.example.venue.notice.service.NoticeDeliveryService;
import com.example.venue.notice.service.NoticeExperimentService;
import com.example.venue.notice.service.NoticeService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
public class NoticeController {
    private final NoticeService noticeService;
    private final NoticeAudienceService audienceService;
    private final NoticeDeliveryService deliveryService;
    private final NoticeExperimentService experimentService;
    private final NoticeAnalyticsService analyticsService;

    public NoticeController(NoticeService noticeService, NoticeAudienceService audienceService,
                            NoticeDeliveryService deliveryService, NoticeExperimentService experimentService,
                            NoticeAnalyticsService analyticsService) {
        this.noticeService = noticeService;
        this.audienceService = audienceService;
        this.deliveryService = deliveryService;
        this.experimentService = experimentService;
        this.analyticsService = analyticsService;
    }

    @GetMapping("/api/notices/public")
    public Result<PageResult<PublicNoticeResponse>> publicNotices(@Valid PageQuery query) {
        return Result.ok(noticeService.publicNotices(query));
    }

    @GetMapping("/api/messages")
    public Result<PageResult<NoticeMessage>> messages(@Valid PageQuery query, @RequestParam(required = false) Boolean unreadOnly) {
        return Result.ok(noticeService.myMessages(query, unreadOnly));
    }

    @GetMapping("/api/messages/unread-count")
    public Result<Long> unreadCount() {
        return Result.ok(noticeService.unreadCount());
    }

    @PutMapping("/api/messages/{id}/read")
    public Result<Void> markOneRead(@PathVariable Long id,
                                    @RequestBody(required = false) NoticeReadRequest request) {
        noticeService.markRead(id, request == null ? null : request.entry);
        return Result.ok();
    }

    @PutMapping("/api/messages/read")
    public Result<Void> markRead(@RequestBody(required = false) NoticeReadRequest request) {
        noticeService.markAllRead(request == null ? null : request.entry);
        return Result.ok();
    }

    @AuditLog(module = "公告管理", action = "发布公告")
    @PostMapping("/api/admin/notices")
    @PreAuthorize("hasAuthority('notice:manage')")
    public Result<Notice> publish(@Valid @RequestBody NoticeRequest request) {
        return Result.ok(noticeService.publish(request));
    }

    @PostMapping("/api/admin/notices/audience/preview")
    @PreAuthorize("hasAuthority('notice:manage')")
    public Result<AudiencePreviewResponse> previewAudience(@Valid @RequestBody AudiencePreviewRequest request) {
        return Result.ok(audienceService.preview(request));
    }

    @AuditLog(module = "公告投递", action = "重试公告投递")
    @PostMapping("/api/admin/notices/delivery/outbox/{id}/retry")
    @PreAuthorize("hasAuthority('notice:manage')")
    public Result<Void> retryOutbox(@PathVariable Long id) {
        deliveryService.retryOutbox(id);
        return Result.ok();
    }

    @GetMapping("/api/admin/notices/delivery/providers")
    @PreAuthorize("hasAuthority('notice:analytics')")
    public Result<List<NotificationProviderOptionResponse>> deliveryProviders() {
        return Result.ok(analyticsService.providerOptions());
    }

    @GetMapping("/api/admin/notices/delivery/overview")
    @PreAuthorize("hasAuthority('notice:analytics')")
    public Result<NoticeDeliveryOverviewResponse> deliveryOverview(@Valid NoticeDeliveryQuery query) {
        return Result.ok(analyticsService.overview(query));
    }

    @GetMapping("/api/admin/notices/delivery/outboxes")
    @PreAuthorize("hasAuthority('notice:analytics')")
    public Result<PageResult<NoticeDeliveryOutboxResponse>> deliveryOutboxes(@Valid NoticeDeliveryQuery query) {
        return Result.ok(analyticsService.outboxes(query));
    }

    @GetMapping("/api/admin/notices/delivery/batches")
    @PreAuthorize("hasAuthority('notice:analytics')")
    public Result<PageResult<NoticeDeliveryBatchResponse>> deliveryBatches(@Valid NoticeDeliveryQuery query) {
        return Result.ok(analyticsService.batches(query));
    }

    @GetMapping("/api/admin/notices/delivery/batches/{id}")
    @PreAuthorize("hasAuthority('notice:analytics')")
    public Result<NoticeDeliveryBatchResponse> deliveryBatch(@PathVariable Long id) {
        return Result.ok(analyticsService.batch(id));
    }

    @GetMapping("/api/admin/notices/delivery/batches/{id}/audiences")
    @PreAuthorize("hasAuthority('notice:analytics')")
    public Result<PageResult<NoticeAudienceResponse>> deliveryAudiences(@PathVariable Long id,
                                                                        @Valid NoticeDeliveryQuery query) {
        return Result.ok(analyticsService.audiences(id, query));
    }

    @AuditLog(module = "公告实验", action = "创建公告实验")
    @PostMapping("/api/admin/notices/experiments")
    @PreAuthorize("hasAuthority('notice:analytics')")
    public Result<NoticeExperimentResponse> createExperiment(@Valid @RequestBody SaveNoticeExperimentRequest request) {
        return Result.ok(experimentService.create(request));
    }

    @GetMapping("/api/admin/notices/experiments")
    @PreAuthorize("hasAuthority('notice:analytics')")
    public Result<PageResult<NoticeExperimentResponse>> experiments(@Valid NoticeDeliveryQuery query) {
        return Result.ok(experimentService.experiments(query));
    }

    @GetMapping("/api/admin/notices/experiments/{id}/results")
    @PreAuthorize("hasAuthority('notice:analytics')")
    public Result<NoticeExperimentResultResponse> experimentResults(@PathVariable Long id,
                                                                     @Valid NoticeDeliveryQuery query) {
        return Result.ok(analyticsService.experimentResults(id, query));
    }
}
