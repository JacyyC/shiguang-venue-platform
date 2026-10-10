package com.example.venue.notice.email;

public record EmailSnapshot(
    Long snapshotId,
    String recipientAddress,
    String maskedRecipient,
    String subjectSnapshot,
    String bodyTextSnapshot,
    String messageIdHeader,
    String templateKey,
    String templateVersion
) {
    @Override
    public String toString() {
        return "EmailSnapshot[snapshotId=" + snapshotId
            + ", maskedRecipient=" + maskedRecipient
            + ", messageIdPresent=" + (messageIdHeader != null)
            + ", templateKey=" + templateKey
            + ", templateVersion=" + templateVersion + "]";
    }
}
