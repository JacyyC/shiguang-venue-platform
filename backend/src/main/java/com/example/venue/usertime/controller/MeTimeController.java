package com.example.venue.usertime.controller;

import com.example.venue.common.PageQuery;
import com.example.venue.common.PageResult;
import com.example.venue.common.Result;
import com.example.venue.usertime.dto.MeTimeCounts;
import com.example.venue.usertime.dto.MeTimeItem;
import com.example.venue.usertime.service.MeTimeService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class MeTimeController {
    private final MeTimeService meTimeService;

    public MeTimeController(MeTimeService meTimeService) {
        this.meTimeService = meTimeService;
    }

    @GetMapping("/api/me/time")
    public Result<PageResult<MeTimeItem>> myTime(@Valid PageQuery query) {
        return Result.ok(meTimeService.myTime(query));
    }

    @GetMapping("/api/me/time/counts")
    public Result<MeTimeCounts> counts() {
        return Result.ok(meTimeService.counts());
    }
}
