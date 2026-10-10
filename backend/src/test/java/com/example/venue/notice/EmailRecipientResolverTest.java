package com.example.venue.notice;

import com.example.venue.notice.email.EmailAddressMasker;
import com.example.venue.notice.email.EmailRecipientEligibility;
import com.example.venue.notice.email.EmailRecipientResolver;
import com.example.venue.notice.entity.NoticeEmailPreference;
import com.example.venue.notice.mapper.NoticeEmailPreferenceMapper;
import com.example.venue.notice.routing.NotificationRecipientKind;
import com.example.venue.user.entity.SysUser;
import com.example.venue.user.mapper.SysUserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EmailRecipientResolverTest {
    @Mock
    private SysUserMapper userMapper;
    @Mock
    private NoticeEmailPreferenceMapper preferenceMapper;

    private EmailRecipientResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new EmailRecipientResolver.Database(userMapper, preferenceMapper, new EmailAddressMasker());
    }

    @Test
    void eligibleAddressIsNormalizedValidatedMaskedAndNeverClaimedVerified() {
        when(userMapper.selectById(7L)).thenReturn(activeUser(" alice@example.test "));
        when(preferenceMapper.selectOne(any())).thenReturn(preference(1));

        EmailRecipientEligibility eligibility = resolver.resolve(7L, NotificationRecipientKind.USER);

        assertThat(eligibility.userId()).isEqualTo(7L);
        assertThat(eligibility.recipientKind()).isEqualTo(NotificationRecipientKind.USER);
        assertThat(eligibility.active()).isTrue();
        assertThat(eligibility.emailEligible()).isTrue();
        assertThat(eligibility.normalizedAddress()).isEqualTo("alice@example.test");
        assertThat(eligibility.maskedAddress()).isEqualTo("a***e@e***e.test");
        assertThat(eligibility.preferenceEnabled()).isTrue();
        assertThat(eligibility.exclusionCode()).isNull();
        assertThat(eligibility.addressValidated()).isTrue();
        assertThat(eligibility.addressVerified()).isFalse();
        assertThat(eligibility.toString())
            .contains("a***e@e***e.test")
            .doesNotContain("alice@example.test");
    }

    @Test
    void eligibilityToStringDoesNotExposeUntrustedExclusionCode() {
        EmailRecipientEligibility eligibility = EmailRecipientEligibility.inAppOnly("alice@example.com");

        assertThat(eligibility.toString())
            .doesNotContain("alice@example.com", "@");
    }

    @Test
    void missingPreferenceKeepsRecipientInAppOnly() {
        when(userMapper.selectById(7L)).thenReturn(activeUser("alice@example.com"));
        when(preferenceMapper.selectOne(any())).thenReturn(null);

        EmailRecipientEligibility eligibility = resolver.resolve(7L, NotificationRecipientKind.USER);

        assertThat(eligibility.emailEligible()).isFalse();
        assertThat(eligibility.preferenceEnabled()).isFalse();
        assertThat(eligibility.exclusionCode()).isEqualTo("EMAIL_PREFERENCE_DISABLED");
        assertThat(eligibility.addressValidated()).isFalse();
        assertThat(eligibility.addressVerified()).isFalse();
    }

    @Test
    void explicitlyDisabledPreferenceKeepsRecipientInAppOnly() {
        when(userMapper.selectById(7L)).thenReturn(activeUser("alice@example.com"));
        when(preferenceMapper.selectOne(any())).thenReturn(preference(0));

        EmailRecipientEligibility eligibility = resolver.resolve(7L, NotificationRecipientKind.USER);

        assertThat(eligibility.emailEligible()).isFalse();
        assertThat(eligibility.preferenceEnabled()).isFalse();
        assertThat(eligibility.exclusionCode()).isEqualTo("EMAIL_PREFERENCE_DISABLED");
    }

    @Test
    void inactiveUserIsIneligible() {
        SysUser user = activeUser("alice@example.com");
        user.status = "DISABLED";
        when(userMapper.selectById(7L)).thenReturn(user);

        EmailRecipientEligibility eligibility = resolver.resolve(7L, NotificationRecipientKind.USER);

        assertThat(eligibility.active()).isFalse();
        assertThat(eligibility.emailEligible()).isFalse();
        assertThat(eligibility.exclusionCode()).isEqualTo("RECIPIENT_INACTIVE");
    }

    @Test
    void deletedUserIsIneligible() {
        SysUser user = activeUser("alice@example.com");
        user.deleted = 1;
        when(userMapper.selectById(7L)).thenReturn(user);

        EmailRecipientEligibility eligibility = resolver.resolve(7L, NotificationRecipientKind.USER);

        assertThat(eligibility.active()).isFalse();
        assertThat(eligibility.emailEligible()).isFalse();
        assertThat(eligibility.exclusionCode()).isEqualTo("RECIPIENT_INACTIVE");
    }

    @Test
    void missingUserIsIneligible() {
        when(userMapper.selectById(7L)).thenReturn(null);

        EmailRecipientEligibility eligibility = resolver.resolve(7L, NotificationRecipientKind.USER);

        assertThat(eligibility.emailEligible()).isFalse();
        assertThat(eligibility.exclusionCode()).isEqualTo("RECIPIENT_NOT_FOUND");
    }

    @Test
    void nullRecipientKindIsIneligible() {
        EmailRecipientEligibility eligibility = resolver.resolve(7L, null);

        assertThat(eligibility.emailEligible()).isFalse();
        assertThat(eligibility.exclusionCode()).isEqualTo("RECIPIENT_INVALID");
    }

    @Test
    void syntacticallyValidNonSandboxDomainIsIneligible() {
        when(userMapper.selectById(7L)).thenReturn(activeUser("alice@example.com"));
        when(preferenceMapper.selectOne(any())).thenReturn(preference(1));

        EmailRecipientEligibility eligibility = resolver.resolve(7L, NotificationRecipientKind.USER);

        assertThat(eligibility.emailEligible()).isFalse();
        assertThat(eligibility.normalizedAddress()).isNull();
        assertThat(eligibility.maskedAddress()).isNull();
        assertThat(eligibility.exclusionCode()).isEqualTo("EMAIL_ADDRESS_INVALID");
    }

    @ParameterizedTest
    @MethodSource("invalidAddresses")
    void malformedOrUnsafeAddressIsIneligible(String address) {
        when(userMapper.selectById(7L)).thenReturn(activeUser(address));
        when(preferenceMapper.selectOne(any())).thenReturn(preference(1));

        EmailRecipientEligibility eligibility = resolver.resolve(7L, NotificationRecipientKind.USER);

        assertThat(eligibility.active()).isTrue();
        assertThat(eligibility.preferenceEnabled()).isTrue();
        assertThat(eligibility.emailEligible()).isFalse();
        assertThat(eligibility.normalizedAddress()).isNull();
        assertThat(eligibility.maskedAddress()).isNull();
        assertThat(eligibility.exclusionCode()).isEqualTo("EMAIL_ADDRESS_INVALID");
        assertThat(eligibility.addressValidated()).isFalse();
        assertThat(eligibility.addressVerified()).isFalse();
    }

    private static Stream<String> invalidAddresses() {
        return Stream.of(
            " ",
            "a".repeat(244) + "@example.com",
            "alice@example.com\r\nBcc:mallory@example.com",
            "alíce@example.com",
            "alice@@example.com"
        );
    }

    private SysUser activeUser(String address) {
        SysUser user = new SysUser();
        user.id = 7L;
        user.status = "ACTIVE";
        user.deleted = 0;
        user.email = address;
        return user;
    }

    private NoticeEmailPreference preference(int enabled) {
        NoticeEmailPreference preference = new NoticeEmailPreference();
        preference.userId = 7L;
        preference.transactionalEmailEnabled = enabled;
        return preference;
    }
}
