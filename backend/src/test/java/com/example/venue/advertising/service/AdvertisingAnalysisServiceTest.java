package com.example.venue.advertising.service;

import com.example.venue.advertising.dto.AdvertisingAnalysisResponses;
import com.example.venue.advertising.mapper.AdvertisingAnalysisMapper;
import com.example.venue.advertising.mapper.AdvertisingAttributionMapper;
import com.example.venue.advertising.mapper.AdvertisingConversionCallbackMapper;
import com.example.venue.common.BusinessException;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AdvertisingAnalysisServiceTest {
    private final AdvertisingAnalysisMapper analysisMapper = mock(AdvertisingAnalysisMapper.class);
    private final AdvertisingAttributionMapper attributionMapper = mock(AdvertisingAttributionMapper.class);
    private final AdvertisingConversionCallbackMapper callbackMapper = mock(AdvertisingConversionCallbackMapper.class);
    private final AdvertisingAnalysisService service = new AdvertisingAnalysisService(
        analysisMapper, attributionMapper, callbackMapper);

    @Test
    void allowsTheFullOneHundredEightyDayAttributionHistoryWindow() {
        LocalDateTime until = LocalDateTime.of(2026, 8, 3, 12, 0);
        LocalDateTime from = until.minusDays(180);
        when(analysisMapper.countDecisions(from, until)).thenReturn(1L);

        AdvertisingAnalysisResponses.Overview overview = service.overview(from, until);

        assertThat(overview.from()).isEqualTo(from);
        assertThat(overview.until()).isEqualTo(until);
        assertThat(overview.decisions()).isEqualTo(1L);
    }

    @Test
    void rejectsRangesLongerThanThePolicyWindow() {
        LocalDateTime until = LocalDateTime.of(2026, 8, 3, 12, 0);

        assertThatThrownBy(() -> service.overview(until.minusDays(180).minusSeconds(1), until))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("180 天");
    }
}
