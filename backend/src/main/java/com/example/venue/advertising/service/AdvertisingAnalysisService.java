package com.example.venue.advertising.service;

import com.example.venue.advertising.dto.AdvertisingAnalysisResponses;
import com.example.venue.advertising.mapper.AdvertisingAnalysisMapper;
import com.example.venue.advertising.mapper.AdvertisingAttributionMapper;
import com.example.venue.advertising.mapper.AdvertisingConversionCallbackMapper;
import com.example.venue.common.BusinessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
public class AdvertisingAnalysisService {
    private static final int DEFAULT_DAYS = 30;
    private static final int MAX_DAYS = 180;

    private final AdvertisingAnalysisMapper analysisMapper;
    private final AdvertisingAttributionMapper attributionMapper;
    private final AdvertisingConversionCallbackMapper callbackMapper;

    public AdvertisingAnalysisService(AdvertisingAnalysisMapper analysisMapper,
                                      AdvertisingAttributionMapper attributionMapper,
                                      AdvertisingConversionCallbackMapper callbackMapper) {
        this.analysisMapper = analysisMapper;
        this.attributionMapper = attributionMapper;
        this.callbackMapper = callbackMapper;
    }

    @Transactional(readOnly = true)
    public AdvertisingAnalysisResponses.Overview overview(LocalDateTime requestedFrom,
                                                          LocalDateTime requestedUntil) {
        LocalDateTime until = requestedUntil == null ? LocalDateTime.now() : requestedUntil;
        LocalDateTime from = requestedFrom == null ? until.minusDays(DEFAULT_DAYS) : requestedFrom;
        if (!from.isBefore(until)) {
            throw BusinessException.badRequest("分析开始时间必须早于结束时间");
        }
        if (from.isBefore(until.minusDays(MAX_DAYS))) {
            throw BusinessException.badRequest("分析时间范围不能超过 180 天");
        }
        return new AdvertisingAnalysisResponses.Overview(
            from,
            until,
            analysisMapper.countDecisions(from, until),
            analysisMapper.countViewableEvents(from, until),
            analysisMapper.countClickEvents(from, until),
            attributionMapper.countByStatus(AdvertisingConversionService.ATTRIBUTED, from, until),
            attributionMapper.countByStatus(AdvertisingConversionService.GAP, from, until),
            attributionMapper.countByStatus(AdvertisingConversionService.REVOKED, from, until),
            callbackMapper.countByResultCode("NO_VALID_CLICK", from, until),
            callbackMapper.countByResultCode("PRIVACY_DISABLED", from, until),
            callbackMapper.countPendingRetry(from, until),
            attributionMapper.countUnmatchedAcceptedClicks(from, until)
        );
    }
}
