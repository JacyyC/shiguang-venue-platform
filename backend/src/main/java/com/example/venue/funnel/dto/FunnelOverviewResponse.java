package com.example.venue.funnel.dto;

import java.util.ArrayList;
import java.util.List;

public class FunnelOverviewResponse {
    public List<FunnelStepMetric> stepMetrics = new ArrayList<>();
    public List<FunnelDropoffMetric> dropoffReasons = new ArrayList<>();
    public List<FunnelSourceMetric> sourceChannels = new ArrayList<>();
    public List<String> topKeywords = new ArrayList<>();
    public List<String> dataGaps = new ArrayList<>();
    public List<FunnelEventResponse> sampleEvents = new ArrayList<>();
}
