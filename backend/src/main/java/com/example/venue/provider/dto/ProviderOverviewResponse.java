package com.example.venue.provider.dto;

import java.util.ArrayList;
import java.util.List;

public class ProviderOverviewResponse {
    public List<ProviderStatusResponse> providers = new ArrayList<>();
    public List<String> dataGaps = new ArrayList<>();
}
