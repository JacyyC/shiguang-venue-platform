package com.example.venue.feedback.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A26-P0-04：客服脱敏正则修复后的覆盖测试。
 *
 * <p>原正则 {@code \b(?:ORD(?:ER)?|REF(?:UND)?|PAY|OD|RF)(?:[\s:_-]*[A-Z0-9]){6,}\b} 把空白字符放进可重复组，
 * 匹配链可无限跨越单词边界，外加 OD/RF 短前缀，导致 "Ordinary people like the venue 5 stars" 整句被吞。
 * 修复后正则 {@code \b(?:ORD(?:ER)?|REF(?:UND)?|PAY)(?:[:_-]*[A-Z0-9]){6,}\b} 对照
 * {@link com.example.venue.common.SensitiveDataMasker#BUSINESS_REFERENCE_PATTERN} 的字符类写法，
 * 字符类不含 {@code \s}、不含 OD/RF 短前缀。
 */
class FeedbackTextSanitizerTest {

    private final FeedbackTextSanitizer sanitizer = new FeedbackTextSanitizer();

    // === evidence 实测复现用例：修复后不被吞噬 ===

    @Test
    void ordinaryEnglishSentenceIsNotSwallowedByBusinessIdPattern() {
        FeedbackTextSanitizer.SanitizedText sanitized = sanitizer.sanitize(
            "Ordinary people like the venue 5 stars");

        assertThat(sanitized.sensitive()).isFalse();
        assertThat(sanitized.text()).isEqualTo("Ordinary people like the venue 5 stars");
    }

    @Test
    void payWordInsideSentenceIsNotSwallowedByBusinessIdPattern() {
        FeedbackTextSanitizer.SanitizedText sanitized = sanitizer.sanitize(
            "I will pay more attention next time.");

        assertThat(sanitized.sensitive()).isFalse();
        assertThat(sanitized.text()).isEqualTo("I will pay more attention next time.");
    }

    @Test
    void rfidKeywordIsNotSwallowedAfterDroppingRfPrefix() {
        FeedbackTextSanitizer.SanitizedText sanitized = sanitizer.sanitize(
            "RFID reader was broken yesterday");

        assertThat(sanitized.sensitive()).isFalse();
        assertThat(sanitized.text()).isEqualTo("RFID reader was broken yesterday");
    }

    // === 真实业务单号仍被脱敏 ===

    @Test
    void realOrderNumberIsMasked() {
        FeedbackTextSanitizer.SanitizedText sanitized = sanitizer.sanitize("ORD1234567");

        assertThat(sanitized.sensitive()).isTrue();
        assertThat(sanitized.text()).isEqualTo("[业务单号已隐藏]");
    }

    @Test
    void realPaymentNumberWithSeparatorIsMasked() {
        FeedbackTextSanitizer.SanitizedText sanitized = sanitizer.sanitize("PAY-ABC123456");

        assertThat(sanitized.sensitive()).isTrue();
        assertThat(sanitized.text()).isEqualTo("[业务单号已隐藏]");
    }

    @Test
    void realRefundNumberIsMasked() {
        FeedbackTextSanitizer.SanitizedText sanitized = sanitizer.sanitize("REF202607060002");

        assertThat(sanitized.sensitive()).isTrue();
        assertThat(sanitized.text()).isEqualTo("[业务单号已隐藏]");
    }

    @Test
    void fullRefundPrefixNumberIsMasked() {
        FeedbackTextSanitizer.SanitizedText sanitized = sanitizer.sanitize("REFUND:20260706:0004");

        assertThat(sanitized.sensitive()).isTrue();
        assertThat(sanitized.text()).isEqualTo("[业务单号已隐藏]");
    }

    @Test
    void multipleBusinessIdsInMixedTextAreAllMasked() {
        FeedbackTextSanitizer.SanitizedText sanitized = sanitizer.sanitize(
            "订单 ORD-20260706-0001，退款 REF202607060002，支付 Pay_20260706_0003，REFUND:20260706:0004");

        assertThat(sanitized.sensitive()).isTrue();
        assertThat(sanitized.text())
            .contains("[业务单号已隐藏]")
            .doesNotContain("ORD-20260706-0001")
            .doesNotContain("REF202607060002")
            .doesNotContain("Pay_20260706_0003")
            .doesNotContain("REFUND:20260706:0004");
    }

    // === 其他脱敏通道保持原行为 ===

    @Test
    void phoneLandlineAndEmailAreMaskedAndFlaggedSensitive() {
        FeedbackTextSanitizer.SanitizedText sanitized = sanitizer.sanitize(
            "联系 138-1234-5678、010-12345678 或 user@example.test");

        assertThat(sanitized.sensitive()).isTrue();
        assertThat(sanitized.text())
            .doesNotContain("138-1234-5678", "010-12345678", "user@example.test")
            .contains("138****5678", "010-****", "[邮箱已隐藏]");
    }

    @Test
    void mobileNumberWithoutSeparatorIsMasked() {
        FeedbackTextSanitizer.SanitizedText sanitized = sanitizer.sanitize("电话 13812345678");

        assertThat(sanitized.sensitive()).isTrue();
        assertThat(sanitized.text()).isEqualTo("电话 138****5678");
    }

    // === null/blank 入参 ===

    @Test
    void nullInputReturnsNullTextAndNotSensitive() {
        FeedbackTextSanitizer.SanitizedText sanitized = sanitizer.sanitize(null);

        assertThat(sanitized.text()).isNull();
        assertThat(sanitized.sensitive()).isFalse();
    }

    @Test
    void blankInputReturnsNullTextAndNotSensitive() {
        FeedbackTextSanitizer.SanitizedText sanitized = sanitizer.sanitize("   ");

        assertThat(sanitized.text()).isNull();
        assertThat(sanitized.sensitive()).isFalse();
    }

    @Test
    void ordinaryChineseTextIsNotFlaggedSensitive() {
        FeedbackTextSanitizer.SanitizedText sanitized = sanitizer.sanitize("体验不错，场馆干净");

        assertThat(sanitized.sensitive()).isFalse();
        assertThat(sanitized.text()).isEqualTo("体验不错，场馆干净");
    }
}
