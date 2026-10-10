package com.example.venue.notice.email;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.HexFormat;
import java.util.List;

public final class EmailIdempotencyKeyFactory {
    public String forRoute(
        String eventType,
        String eventKey,
        Long recipientUserId,
        String providerKey,
        String configVersion
    ) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(
                canonicalTuple(eventType, eventKey, recipientUserId, providerKey, configVersion));
            return "EMAIL:" + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private byte[] canonicalTuple(
        String eventType,
        String eventKey,
        Long recipientUserId,
        String providerKey,
        String configVersion
    ) {
        List<String> fields = List.of(
            requireUpperEventType(eventType),
            Normalizer.normalize(requireEventKey(eventKey), Normalizer.Form.NFC),
            requirePositiveId(recipientUserId).toString(),
            requireAscii(providerKey, "providerKey"),
            requireAscii(configVersion, "configVersion"));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        for (String field : fields) {
            byte[] bytes = field.getBytes(StandardCharsets.UTF_8);
            output.writeBytes(Integer.toString(bytes.length).getBytes(StandardCharsets.US_ASCII));
            output.write(':');
            output.writeBytes(bytes);
            output.write(',');
        }
        return output.toByteArray();
    }

    private String requireUpperEventType(String eventType) {
        if (eventType == null || !eventType.matches("[A-Z][A-Z0-9_]{0,63}")) {
            throw new IllegalArgumentException("eventType must be an uppercase event identifier");
        }
        return eventType;
    }

    private String requireEventKey(String eventKey) {
        if (eventKey == null || eventKey.isBlank() || eventKey.length() > 128) {
            throw new IllegalArgumentException("eventKey must contain 1 to 128 characters");
        }
        return eventKey;
    }

    private Long requirePositiveId(Long recipientUserId) {
        if (recipientUserId == null || recipientUserId <= 0) {
            throw new IllegalArgumentException("recipientUserId must be positive");
        }
        return recipientUserId;
    }

    private String requireAscii(String value, String fieldName) {
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException(fieldName + " must not be empty");
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character < 0x21 || character > 0x7e) {
                throw new IllegalArgumentException(fieldName + " must contain printable ASCII only");
            }
        }
        return value;
    }
}
