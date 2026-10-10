package com.example.venue.storedvalue.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

/**
 * Stable, non-reversible references used by the finance projection boundary.
 * Domain services continue to use database identifiers internally.
 */
public final class StoredValueReference {
    private static final int REFERENCE_DIGEST_LENGTH = 24;

    private StoredValueReference() {
    }

    public static String account(Long accountId) {
        return accountId == null ? null : "SVA-" + digest("stored-value:account:" + accountId);
    }

    public static String recharge(String rechargeNo) {
        return blank(rechargeNo) ? null : "SVR-REF-" + digest("stored-value:recharge:" + rechargeNo);
    }

    public static String user(Long userId) {
        return userId == null ? null : "USR-" + digest("stored-value:user:" + userId);
    }

    public static String transaction(Long transactionId) {
        return transactionId == null ? null : "SVT-" + digest("stored-value:transaction:" + transactionId);
    }

    public static String difference(Long differenceId) {
        return differenceId == null ? null : "SVD-" + digest("stored-value:difference:" + differenceId);
    }

    public static String suffix(String value) {
        if (blank(value)) {
            return null;
        }
        String normalized = value.trim();
        int length = Math.min(8, normalized.length());
        return "..." + normalized.substring(normalized.length() - length).toUpperCase(Locale.ROOT);
    }

    public static String summary(String value) {
        if (blank(value)) {
            return null;
        }
        String normalized = value.trim();
        return normalized.length() <= 24 ? normalized : normalized.substring(0, 21) + "...";
    }

    private static String digest(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(REFERENCE_DIGEST_LENGTH);
            for (byte item : bytes) {
                result.append(String.format("%02x", item));
                if (result.length() == REFERENCE_DIGEST_LENGTH) {
                    break;
                }
            }
            return result.toString().toUpperCase(Locale.ROOT);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
