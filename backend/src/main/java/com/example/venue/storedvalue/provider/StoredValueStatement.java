package com.example.venue.storedvalue.provider;

import java.util.List;

public record StoredValueStatement(
    String sourceType,
    String sourceRef,
    List<StoredValueStatementItem> items,
    String summary
) {
    public StoredValueStatement {
        items = items == null ? List.of() : List.copyOf(items);
    }
}
