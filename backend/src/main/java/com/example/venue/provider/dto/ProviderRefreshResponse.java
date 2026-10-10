package com.example.venue.provider.dto;

import java.util.ArrayList;
import java.util.List;

public class ProviderRefreshResponse {
    public String status;
    public String providerKey;
    public Long snapshotId;
    public List<String> dataGaps = new ArrayList<>();
}
