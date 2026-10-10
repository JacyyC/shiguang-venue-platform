package com.example.venue.notice;

import com.example.venue.notice.email.EmailAddressValidator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class EmailAddressValidatorTest {
    private final EmailAddressValidator validator = new EmailAddressValidator();

    @Test
    void normalizesSurroundingWhitespaceForAStrictAsciiMailbox() {
        assertThat(validator.normalizeStrict("  alice@example.test  "))
            .isEqualTo("alice@example.test");
    }

    @Test
    void acceptsAnExactly254CharacterAsciiMailbox() {
        String address = "a".repeat(64) + "@" + "b".repeat(63) + "."
            + "c".repeat(63) + "." + "d".repeat(61);

        assertThat(address).hasSize(254);
        assertThat(validator.normalizeStrict(address)).isEqualTo(address);
    }

    @ParameterizedTest
    @MethodSource("invalidAddresses")
    void rejectsMalformedOrUnsafeAddresses(String address) {
        assertThat(validator.normalizeStrict(address)).isNull();
    }

    private static Stream<String> invalidAddresses() {
        return Stream.of(
            " ",
            "a".repeat(244) + "@example.com",
            "alice@example.com\r\nBcc:mallory@example.com",
            "alíce@example.com",
            "alice@@example.com",
            "Alice <alice@example.com>"
        );
    }
}
