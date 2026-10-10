package com.example.venue.notice.service;

import com.example.venue.notice.entity.NoticeOutbox;
import com.example.venue.notice.provider.NotificationDeliveryCommand;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public record EmailDeliveryEvidenceContext(
    Long outboxId,
    Long userId,
    String eventType,
    String templateKey,
    String templateVersion,
    Long snapshotId,
    String maskedRecipient,
    String messageIdHash
) {
    public static final class SafeSnapshotProjection {
        public Long snapshotId;
        public Long outboxId;
        public String maskedRecipient;
        public String templateKey;
        public String templateVersion;
        public String messageIdHeader;
    }

    static EmailDeliveryEvidenceContext from(NoticeOutbox outbox) {
        if (outbox == null || !"EMAIL".equals(outbox.channel)) {
            return null;
        }
        return new EmailDeliveryEvidenceContext(
            outbox.id, outbox.userId, outbox.eventType, outbox.templateKey, outbox.templateVersion,
            null, null, null);
    }

    EmailDeliveryEvidenceContext withSafeSnapshot(SafeSnapshotProjection projection) {
        if (projection == null || projection.snapshotId == null || projection.snapshotId <= 0
            || !same(outboxId, projection.outboxId)
            || !same(templateKey, projection.templateKey)
            || !same(templateVersion, projection.templateVersion)
            || !isSafeMaskedRecipient(projection.maskedRecipient)
            || !isCanonicalMessageId(projection.messageIdHeader)) {
            return this;
        }
        return new EmailDeliveryEvidenceContext(
            outboxId, userId, eventType, templateKey, templateVersion,
            projection.snapshotId, projection.maskedRecipient, hash(projection.messageIdHeader));
    }

    EmailDeliveryEvidenceContext withCommand(NotificationDeliveryCommand command) {
        if (command == null || command.provider() == null
            || !"EMAIL".equals(command.provider().channel())) {
            return this;
        }
        return new EmailDeliveryEvidenceContext(
            outboxId, command.userId(), command.eventType(), command.templateKey(), command.templateVersion(),
            command.emailSnapshotId(), command.maskedRecipient(), hash(command.messageIdHeader()));
    }

    private static boolean same(Object first, Object second) {
        return first != null && first.equals(second);
    }

    private static boolean isSafeMaskedRecipient(String value) {
        if (value == null || value.isBlank() || value.length() > 255 || !value.contains("***")) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character < 0x20 || character > 0x7e) {
                return false;
            }
        }
        return true;
    }

    private static boolean isCanonicalMessageId(String value) {
        return value != null && value.matches("<[0-9a-f]{64}@venue\\.local>");
    }

    private static String hash(String value) {
        if (value == null) {
            return null;
        }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
