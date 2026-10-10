package com.example.venue.notice.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.ALWAYS)
public class TransactionalEmailPreferenceResponse {
    public boolean transactionalEmailEnabled;
    public boolean emailConfigured;
    public boolean emailEligible;
    public String maskedEmail;
    public boolean addressValidated;
    public boolean addressVerified;
    public String exclusionCode;
}
