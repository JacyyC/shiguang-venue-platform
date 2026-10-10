package com.example.venue.notice.email;

import com.example.venue.notice.routing.NotificationRecipientKind;

public record EmailRecipientEligibility(
    Long userId,
    NotificationRecipientKind recipientKind,
    boolean active,
    boolean emailEligible,
    String normalizedAddress,
    String maskedAddress,
    boolean preferenceEnabled,
    String exclusionCode
) {
    public static EmailRecipientEligibility inAppOnly(String exclusionCode) {
        return new EmailRecipientEligibility(
            null, null, false, false, null, null, false, exclusionCode);
    }

    public boolean addressValidated() {
        return emailEligible;
    }

    public boolean addressVerified() {
        return false;
    }

    @Override
    public String toString() {
        return "EmailRecipientEligibility[userId=" + userId
            + ", recipientKind=" + recipientKind
            + ", active=" + active
            + ", emailEligible=" + emailEligible
            + ", maskedAddress=" + maskedAddress
            + ", preferenceEnabled=" + preferenceEnabled
            + ", exclusionPresent=" + (exclusionCode != null) + "]";
    }
}
