package com.example.venue.notice.email;

import java.util.Objects;
import java.util.Set;

public record EmailTemplate(
    String eventType,
    String templateKey,
    String templateVersion,
    String subjectPattern,
    String textBodyPattern,
    Set<String> allowedModelKeys,
    Set<String> requiredModelKeys,
    int maxRenderedSubjectLength,
    int maxRenderedBodyLength,
    String locale,
    String contentType
) {
    public EmailTemplate {
        allowedModelKeys = Set.copyOf(Objects.requireNonNull(
            allowedModelKeys, "allowedModelKeys"));
        requiredModelKeys = Set.copyOf(Objects.requireNonNull(
            requiredModelKeys, "requiredModelKeys"));
    }
}
