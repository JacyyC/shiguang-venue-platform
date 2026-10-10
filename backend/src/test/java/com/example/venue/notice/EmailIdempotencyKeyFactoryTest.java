package com.example.venue.notice;

import com.example.venue.notice.email.EmailIdempotencyKeyFactory;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EmailIdempotencyKeyFactoryTest {
    private final EmailIdempotencyKeyFactory factory = new EmailIdempotencyKeyFactory();

    @Test
    void canonicalNetstringsKeepDelimiterAmbiguousValuesDistinct() {
        String left = factory.forRoute("A", "ab,c", 7L, "notice.smtp.sandbox", "SMTP_SANDBOX_V1");
        String right = factory.forRoute("A", "a,bc", 7L, "notice.smtp.sandbox", "SMTP_SANDBOX_V1");

        assertThat(left).matches("EMAIL:[0-9a-f]{64}").isNotEqualTo(right);
    }

    @Test
    void unicodeNfcEquivalentEventKeysProduceTheSameKeyWithoutTruncation() {
        String composed = factory.forRoute(
            "PAYMENT_SUCCEEDED", "\u00E9", 7L, "notice.smtp.sandbox", "SMTP_SANDBOX_V1");
        String decomposed = factory.forRoute(
            "PAYMENT_SUCCEEDED", "e\u0301", 7L, "notice.smtp.sandbox", "SMTP_SANDBOX_V1");

        assertThat(composed).isEqualTo(decomposed).hasSize(70);
    }

    @Test
    void utf8ByteLengthNetstringMatchesTheIndependentGoldenDigest() {
        assertThat(factory.forRoute(
            "PAYMENT_SUCCEEDED", "é", 7L, "notice.smtp.sandbox", "SMTP_SANDBOX_V1"))
            .isEqualTo("EMAIL:2cdd2f0b7402c252af22e140aa318592fa39741155ea4d4166f75e4aaa6f9090");
    }

    @Test
    void everyCanonicalTupleFieldChangesTheKey() {
        String base = factory.forRoute("A", "key", 7L, "provider", "CONFIG_V1");

        assertThat(new String[] {
            base,
            factory.forRoute("B", "key", 7L, "provider", "CONFIG_V1"),
            factory.forRoute("A", "other", 7L, "provider", "CONFIG_V1"),
            factory.forRoute("A", "key", 8L, "provider", "CONFIG_V1"),
            factory.forRoute("A", "key", 7L, "other", "CONFIG_V1"),
            factory.forRoute("A", "key", 7L, "provider", "CONFIG_V2")
        }).doesNotHaveDuplicates();
    }

    @Test
    void rejectsInvalidTupleFieldsInsteadOfTruncatingOrRewritingThem() {
        assertThatThrownBy(() -> factory.forRoute(
            "payment_succeeded", "key", 7L, "notice.smtp.sandbox", "SMTP_SANDBOX_V1"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> factory.forRoute(
            "PAYMENT_SUCCEEDED", "x".repeat(129), 7L, "notice.smtp.sandbox", "SMTP_SANDBOX_V1"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> factory.forRoute(
            "PAYMENT_SUCCEEDED", "key", 0L, "notice.smtp.sandbox", "SMTP_SANDBOX_V1"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> factory.forRoute(
            "PAYMENT_SUCCEEDED", "key", 7L, "notice.smtp.sändbox", "SMTP_SANDBOX_V1"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> factory.forRoute(
            "PAYMENT_SUCCEEDED", "key", 7L, "notice.smtp.sandbox", "邮件_V1"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> factory.forRoute(
            "PAYMENT_SUCCEEDED", null, 7L, "notice.smtp.sandbox", "SMTP_SANDBOX_V1"))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
