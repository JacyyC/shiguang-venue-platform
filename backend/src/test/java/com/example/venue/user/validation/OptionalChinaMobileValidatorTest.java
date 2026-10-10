package com.example.venue.user.validation;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OptionalChinaMobileValidatorTest {
    private final OptionalChinaMobileValidator validator = new OptionalChinaMobileValidator();

    @Test
    void blankValuesMeanNotFilledIn() {
        assertThat(validator.isValid(null, null)).isTrue();
        assertThat(validator.isValid("", null)).isTrue();
        assertThat(validator.isValid("   ", null)).isTrue();
    }

    @Test
    void elevenDigitMainlandNumbersPass() {
        assertThat(validator.isValid("13800138000", null)).isTrue();
        assertThat(validator.isValid("13000000000", null)).isTrue();
        assertThat(validator.isValid("19999999999", null)).isTrue();
        assertThat(validator.isValid("  13800138000  ", null)).isTrue();
    }

    @Test
    void wrongPrefixLengthOrCharactersFail() {
        assertThat(validator.isValid("12800138000", null)).as("第二位 2 不合法").isFalse();
        assertThat(validator.isValid("10800138000", null)).as("第二位 0 不合法").isFalse();
        assertThat(validator.isValid("23800138000", null)).as("首位不是 1").isFalse();
        assertThat(validator.isValid("1380013800", null)).as("10 位").isFalse();
        assertThat(validator.isValid("138001380001", null)).as("12 位").isFalse();
        assertThat(validator.isValid("1380013800a", null)).as("含字母").isFalse();
        assertThat(validator.isValid("+8613800138000", null)).as("带国际前缀").isFalse();
        assertThat(validator.isValid("138 0013 8000", null)).as("含空格").isFalse();
    }
}
