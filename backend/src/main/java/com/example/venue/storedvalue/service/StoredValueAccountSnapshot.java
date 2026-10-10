package com.example.venue.storedvalue.service;

import java.math.BigDecimal;

public record StoredValueAccountSnapshot(
    Long id,
    Long userId,
    Long issuerId,
    Long productId,
    String currency,
    String status,
    BigDecimal principalAvailable,
    BigDecimal principalFrozen,
    BigDecimal bonusAvailable,
    BigDecimal bonusFrozen,
    long version
) {
    public BigDecimal availableTotal() {
        return principalAvailable.add(bonusAvailable);
    }

    public BigDecimal totalBalance() {
        return principalAvailable.add(principalFrozen).add(bonusAvailable).add(bonusFrozen);
    }
}
