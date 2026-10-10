package com.example.venue.notice.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.NotNull;

public class TransactionalEmailPreferenceRequest {
    @NotNull
    public Boolean transactionalEmailEnabled;

    @JsonAnySetter
    public void rejectUnknownField(String ignoredField, Object ignoredValue) {
        throw new IllegalArgumentException("Unsupported notification preference field");
    }
}
