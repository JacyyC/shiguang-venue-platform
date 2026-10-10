package com.example.venue.notice;

import com.example.venue.notice.email.EmailAddressMasker;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EmailAddressMaskerTest {
    private final EmailAddressMasker masker = new EmailAddressMasker();

    @Test
    void masksOrdinaryMailboxWithoutRevealingItsMiddleLabels() {
        assertThat(masker.mask("alice@example.com")).isEqualTo("a***e@e***e.com");
    }

    @Test
    void oneCharacterLabelsRevealNoOriginalCharacter() {
        assertThat(masker.mask("x@y.z")).isEqualTo("***@***.***");
    }

    @Test
    void twoCharacterIdentityLabelsDoNotRevealTheWholeOriginalValue() {
        assertThat(masker.mask("ab@cd.ef.com")).isEqualTo("***@***.***.com");
    }

    @Test
    void masksEveryIdentityLabelInAMultiLevelDomain() {
        assertThat(masker.mask("alice@mail.internal.example.test"))
            .isEqualTo("a***e@m***l.i***l.e***e.test");
    }
}
