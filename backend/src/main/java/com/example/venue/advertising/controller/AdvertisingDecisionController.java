package com.example.venue.advertising.controller;

import com.example.venue.advertising.dto.AdvertisingDecisionRequests;
import com.example.venue.advertising.dto.AdvertisingDecisionResponses;
import com.example.venue.advertising.service.AdvertisingDecisionService;
import com.example.venue.advertising.service.AdvertisingEventService;
import com.example.venue.advertising.service.AdvertisingPrivacyPreferenceService;
import com.example.venue.common.Result;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 商业广告用户端窄接口。决策/事件只依赖当前认证用户和服务端令牌，不接受 userId、创意或目标覆盖。
 */
@RestController
@RequestMapping("/api/advertising")
public class AdvertisingDecisionController {
    private final AdvertisingPrivacyPreferenceService privacyService;
    private final AdvertisingDecisionService decisionService;
    private final AdvertisingEventService eventService;

    public AdvertisingDecisionController(AdvertisingPrivacyPreferenceService privacyService,
                                         AdvertisingDecisionService decisionService,
                                         AdvertisingEventService eventService) {
        this.privacyService = privacyService;
        this.decisionService = decisionService;
        this.eventService = eventService;
    }

    @GetMapping("/preferences")
    public Result<AdvertisingDecisionResponses.PrivacyPreferenceResponse> preferences() {
        return Result.ok(privacyService.current());
    }

    @PutMapping("/preferences")
    public Result<AdvertisingDecisionResponses.PrivacyPreferenceResponse> updatePreferences(
        @Valid @RequestBody AdvertisingDecisionRequests.UpdatePrivacyRequest request
    ) {
        return Result.ok(privacyService.update(request));
    }

    @GetMapping("/HOME_COMMERCIAL/decision")
    public Result<AdvertisingDecisionResponses.HomeCommercialDecision> decision() {
        return Result.ok(decisionService.decideHomeCommercial());
    }

    @PostMapping("/decisions/{decisionId}/events")
    public Result<AdvertisingDecisionResponses.RecordEventResult> event(
        @PathVariable Long decisionId,
        @Valid @RequestBody AdvertisingDecisionRequests.RecordEventRequest request,
        @RequestHeader("Idempotency-Key") String idempotencyKey
    ) {
        return Result.ok(eventService.recordEvent(decisionId, request, idempotencyKey));
    }
}
