package com.example.venue.placement.controller;

import com.example.venue.common.Result;
import com.example.venue.placement.dto.PlacementRequests;
import com.example.venue.placement.dto.PlacementResponses;
import com.example.venue.placement.service.PlacementDecisionService;
import com.example.venue.placement.service.PlacementEventService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户端宣传接口。决策与事件只能为当前认证用户读取/写入，不接受 userId 参数。
 * 决策返回空、401/403、超时或 5xx 时首页不渲染空宣传壳，不影响 hero、空间预订、社区、活动和推荐场馆。
 */
@RestController
@RequestMapping("/api/placements")
public class PlacementController {

    private final PlacementDecisionService decisionService;
    private final PlacementEventService eventService;

    public PlacementController(PlacementDecisionService decisionService,
                               PlacementEventService eventService) {
        this.decisionService = decisionService;
        this.eventService = eventService;
    }

    @GetMapping("/{slotCode}/decision")
    public Result<PlacementResponses.HomePrimaryDecision> decision(@PathVariable String slotCode) {
        if (!PlacementDecisionService.HOME_PRIMARY_CODE.equals(slotCode)) {
            return Result.ok(null);
        }
        return Result.ok(decisionService.decideHomePrimary());
    }

    @PostMapping("/decisions/{decisionId}/events")
    public Result<PlacementResponses.RecordEventResult> recordEvent(
        @PathVariable Long decisionId,
        @Valid @RequestBody PlacementRequests.RecordEventRequest request,
        @RequestHeader("Idempotency-Key") String idempotencyKey
    ) {
        return Result.ok(eventService.recordEvent(decisionId, request, idempotencyKey));
    }
}
