package com.example.venue.provider.dto;

import java.time.LocalDateTime;

public class ProviderStatusResponse {
    public String providerKey;
    public String providerDomain;
    public String providerEnv;
    public String providerName;
    public String displayName;
    public boolean enabled;
    public String configVersion;
    public String status;
    public Integer freshnessSeconds;
    public String freshnessStatus;
    public LocalDateTime freshnessDeadlineAt;
    public Long evidenceAgeSeconds;
    public LocalDateTime lastSuccessAt;
    public LocalDateTime lastFailureAt;
    public Integer consecutiveFailures;
    public String lastFailureCode;
    public String lastFailureMessage;
}
