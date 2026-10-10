package com.example.venue.funnel.controller;

import com.example.venue.common.Result;
import com.example.venue.funnel.dto.FunnelEventBatchRequest;
import com.example.venue.funnel.dto.FunnelEventRequest;
import com.example.venue.funnel.dto.FunnelEventResponse;
import com.example.venue.funnel.dto.FunnelFavoriteResponse;
import com.example.venue.funnel.service.FunnelEventService;
import com.example.venue.funnel.service.FunnelFavoriteService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
public class FunnelController {
    private final FunnelEventService eventService;
    private final FunnelFavoriteService favoriteService;

    public FunnelController(FunnelEventService eventService, FunnelFavoriteService favoriteService) {
        this.eventService = eventService;
        this.favoriteService = favoriteService;
    }

    @PostMapping("/api/funnel/events")
    public Result<FunnelEventResponse> record(@Valid @RequestBody FunnelEventRequest request) {
        return Result.ok(eventService.record(request));
    }

    @PostMapping("/api/funnel/events/batch")
    public Result<List<FunnelEventResponse>> recordBatch(@Valid @RequestBody FunnelEventBatchRequest request) {
        return Result.ok(eventService.recordBatch(request));
    }

    @GetMapping("/api/funnel/favorites/my")
    @PreAuthorize("hasAuthority('funnel:favorite:me')")
    public Result<List<FunnelFavoriteResponse>> myFavorites() {
        return Result.ok(favoriteService.myFavorites());
    }

    @PostMapping("/api/funnel/favorites/venues/{venueId}")
    @PreAuthorize("hasAuthority('funnel:favorite:me')")
    public Result<FunnelFavoriteResponse> favorite(@PathVariable Long venueId,
                                                   @RequestBody(required = false) FunnelEventRequest context) {
        return Result.ok(favoriteService.favoriteVenue(venueId, context));
    }

    @DeleteMapping("/api/funnel/favorites/venues/{venueId}")
    @PreAuthorize("hasAuthority('funnel:favorite:me')")
    public Result<Void> cancelFavorite(@PathVariable Long venueId,
                                       @RequestBody(required = false) FunnelEventRequest context) {
        favoriteService.cancelFavorite(venueId, context);
        return Result.ok();
    }

    @GetMapping("/api/funnel/favorites/venues/{venueId}/status")
    @PreAuthorize("hasAuthority('funnel:favorite:me')")
    public Result<Map<String, Boolean>> favoriteStatus(@PathVariable Long venueId) {
        return Result.ok(Map.of("favorite", favoriteService.isFavorite(venueId)));
    }
}
