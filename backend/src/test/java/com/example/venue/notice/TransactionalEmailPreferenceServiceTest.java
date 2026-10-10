package com.example.venue.notice;

import com.example.venue.common.CurrentUser;
import com.example.venue.notice.dto.TransactionalEmailPreferenceRequest;
import com.example.venue.notice.dto.TransactionalEmailPreferenceResponse;
import com.example.venue.notice.email.EmailAddressMasker;
import com.example.venue.notice.email.EmailAddressValidator;
import com.example.venue.notice.email.EmailRecipientResolver;
import com.example.venue.notice.entity.NoticeEmailPreference;
import com.example.venue.notice.mapper.NoticeEmailPreferenceMapper;
import com.example.venue.notice.service.TransactionalEmailPreferenceService;
import com.example.venue.user.entity.SysUser;
import com.example.venue.user.mapper.SysUserMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TransactionalEmailPreferenceServiceTest {
    @Mock
    private SysUserMapper userMapper;
    @Mock
    private NoticeEmailPreferenceMapper preferenceMapper;

    private TransactionalEmailPreferenceService service;

    @BeforeEach
    void setUp() {
        EmailAddressMasker addressMasker = new EmailAddressMasker();
        EmailAddressValidator addressValidator = new EmailAddressValidator();
        EmailRecipientResolver resolver = new EmailRecipientResolver.Database(
            userMapper, preferenceMapper, addressMasker, addressValidator);
        service = new TransactionalEmailPreferenceService(
            userMapper, preferenceMapper, resolver, addressValidator);
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(
                new CurrentUser(7L, "alice", Set.of("USER"), Set.of()), null, Set.of()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void absenceDefaultsToDisabledWithoutCreatingAPreferenceRow() {
        when(userMapper.selectById(7L)).thenReturn(user(7L, "alice@example.test", "ACTIVE", 0));
        when(preferenceMapper.selectOne(any())).thenReturn(null);

        TransactionalEmailPreferenceResponse response = service.current();

        assertThat(response.transactionalEmailEnabled).isFalse();
        assertThat(response.emailConfigured).isTrue();
        assertThat(response.emailEligible).isFalse();
        assertThat(response.maskedEmail).isNull();
        assertThat(response.addressValidated).isFalse();
        assertThat(response.addressVerified).isFalse();
        assertThat(response.exclusionCode).isEqualTo("EMAIL_PREFERENCE_DISABLED");
        verify(preferenceMapper, never()).insert(any(NoticeEmailPreference.class));
        verify(preferenceMapper, never()).updateById(any(NoticeEmailPreference.class));
    }

    @Test
    void enablingRequiresTheCurrentUsersStrictlyValidEmailAndDoesNotChangePreferenceOnFailure() {
        when(userMapper.selectById(7L)).thenReturn(user(7L, "bad\r\n@example.test", "ACTIVE", 0));

        assertThatThrownBy(() -> service.update(enabledRequest()))
            .hasMessageContaining("EMAIL_ADDRESS_INVALID");
        verify(preferenceMapper, never()).insert(any(NoticeEmailPreference.class));
        verify(preferenceMapper, never()).updateById(any(NoticeEmailPreference.class));
    }

    @Test
    void enablingNeverUsesAUserIdOrAddressFromTheRequest() {
        when(userMapper.selectById(7L)).thenReturn(user(7L, "alice@example.test", "ACTIVE", 0));
        NoticeEmailPreference persisted = preference(7L, 1);
        when(preferenceMapper.selectOne(any())).thenReturn(null, persisted);

        TransactionalEmailPreferenceResponse response = service.update(enabledRequest());

        ArgumentCaptor<NoticeEmailPreference> inserted = ArgumentCaptor.forClass(NoticeEmailPreference.class);
        verify(preferenceMapper).insert(inserted.capture());
        assertThat(inserted.getValue().userId).isEqualTo(7L);
        assertThat(inserted.getValue().transactionalEmailEnabled).isEqualTo(1);
        assertThat(response.transactionalEmailEnabled).isTrue();
        assertThat(response.maskedEmail).isEqualTo("a***e@e***e.test");
    }

    @Test
    void disablingUpdatesOnlyTheCurrentUsersFutureRoutingPreference() {
        NoticeEmailPreference persisted = preference(7L, 1);
        when(preferenceMapper.selectOne(any())).thenReturn(persisted);
        when(userMapper.selectById(7L)).thenReturn(user(7L, "alice@example.test", "ACTIVE", 0));

        TransactionalEmailPreferenceResponse response = service.update(disabledRequest());

        verify(preferenceMapper).updateById(persisted);
        assertThat(persisted.userId).isEqualTo(7L);
        assertThat(persisted.transactionalEmailEnabled).isZero();
        assertThat(response.transactionalEmailEnabled).isFalse();
        assertThat(response.exclusionCode).isEqualTo("EMAIL_PREFERENCE_DISABLED");
    }

    @Test
    void responseMasksAddressAndNeverClaimsVerification() {
        when(userMapper.selectById(7L)).thenReturn(user(7L, "alice@example.test", "ACTIVE", 0));
        when(preferenceMapper.selectOne(any())).thenReturn(preference(7L, 1));

        TransactionalEmailPreferenceResponse response = service.current();

        assertThat(response.maskedEmail).isEqualTo("a***e@e***e.test");
        assertThat(response.addressValidated).isTrue();
        assertThat(response.addressVerified).isFalse();
    }

    private TransactionalEmailPreferenceRequest enabledRequest() {
        TransactionalEmailPreferenceRequest request = new TransactionalEmailPreferenceRequest();
        request.transactionalEmailEnabled = true;
        return request;
    }

    private TransactionalEmailPreferenceRequest disabledRequest() {
        TransactionalEmailPreferenceRequest request = new TransactionalEmailPreferenceRequest();
        request.transactionalEmailEnabled = false;
        return request;
    }

    private SysUser user(long id, String address, String status, int deleted) {
        SysUser user = new SysUser();
        user.id = id;
        user.email = address;
        user.status = status;
        user.deleted = deleted;
        return user;
    }

    private NoticeEmailPreference preference(long userId, int enabled) {
        NoticeEmailPreference preference = new NoticeEmailPreference();
        preference.id = 41L;
        preference.userId = userId;
        preference.transactionalEmailEnabled = enabled;
        return preference;
    }
}
