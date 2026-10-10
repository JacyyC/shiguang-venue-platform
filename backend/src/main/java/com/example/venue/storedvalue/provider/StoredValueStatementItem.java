package com.example.venue.storedvalue.provider;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record StoredValueStatementItem(
    String recordType,
    String recordId,
    String businessRef,
    String externalRef,
    BigDecimal amount,
    String currency,
    String status,
    String providerCaseId,
    LocalDateTime occurredAt,
    String dataGap
) {
}
