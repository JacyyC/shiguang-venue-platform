package com.example.venue.notice.email;

public record RenderedEmail(
    String subject,
    String body,
    String contentType,
    String locale
) {
    @Override
    public String toString() {
        return "RenderedEmail[contentType=" + contentType + ", locale=" + locale
            + ", subjectLength=" + (subject == null ? null : subject.length())
            + ", bodyLength=" + (body == null ? null : body.length()) + "]";
    }
}
