package com.example.venue.storedvalue.provider;

import java.time.LocalDate;
import java.time.LocalDateTime;

public record StoredValueStatementQuery(
    LocalDate statementDate,
    LocalDateTime periodStart,
    LocalDateTime periodEnd,
    StoredValueProviderDescriptor descriptor
) {
}
