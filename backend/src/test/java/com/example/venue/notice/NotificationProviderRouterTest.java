package com.example.venue.notice;

import com.example.venue.common.BusinessException;
import com.example.venue.notice.provider.NotificationProvider;
import com.example.venue.notice.provider.NotificationProviderDescriptor;
import com.example.venue.notice.provider.NotificationProviderRouter;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NotificationProviderRouterTest {
    @Test
    void requireSelectsOneProviderWithoutBroadcastingToOthers() {
        NotificationProvider inApp = provider(descriptor(
            "notice.in_app.dev", "DEV", "IN_APP_MESSAGE", "IN_APP", true));
        NotificationProvider fakeEmail = provider(descriptor(
            "notice.fake_email.test", "TEST", "FAKE_EMAIL", "EMAIL", true));
        NotificationProviderRouter router = new NotificationProviderRouter(List.of(inApp, fakeEmail));

        assertThat(router.require("notice.in_app.dev")).isSameAs(inApp);

        verify(fakeEmail, never()).send(any());
    }

    @Test
    void requireRejectsUnknownAndDisabledProvidersWithoutFallback() {
        NotificationProvider disabled = provider(descriptor(
            "notice.disabled", "TEST", "DISABLED_MESSAGE", "EMAIL", false));
        NotificationProviderRouter router = new NotificationProviderRouter(List.of(disabled));

        assertThatThrownBy(() -> router.require("notice.unknown"))
            .isInstanceOf(BusinessException.class)
            .hasMessage("NOTICE_PROVIDER_NOT_FOUND: 通知 Provider 不存在或未启用");
        assertThatThrownBy(() -> router.require("notice.disabled"))
            .isInstanceOf(BusinessException.class)
            .hasMessage("NOTICE_PROVIDER_NOT_FOUND: 通知 Provider 不存在或未启用");
    }

    @Test
    void constructorRejectsDuplicateProviderKey() {
        NotificationProvider first = provider(descriptor(
            "notice.duplicate", "DEV", "IN_APP_MESSAGE", "IN_APP", true));
        NotificationProvider second = provider(descriptor(
            "notice.duplicate", "TEST", "FAKE_EMAIL", "EMAIL", true));

        assertThatThrownBy(() -> new NotificationProviderRouter(List.of(first, second)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Duplicate notification provider key");
    }

    @Test
    void constructorRejectsDuplicateProviderEnvironmentAndName() {
        NotificationProvider first = provider(descriptor(
            "notice.first", "DEV", "IN_APP_MESSAGE", "IN_APP", true));
        NotificationProvider second = provider(descriptor(
            "notice.second", "DEV", "IN_APP_MESSAGE", "MESSAGE", true));

        assertThatThrownBy(() -> new NotificationProviderRouter(List.of(first, second)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Duplicate notification provider env/name");
    }

    @Test
    void disabledSmtpSandboxRemainsCatalogVisibleButCannotBeSelected() {
        NotificationProvider smtp = provider(new NotificationProviderDescriptor(
            "notice.smtp.sandbox", "MESSAGE", "SANDBOX", "SMTP_EMAIL", "EMAIL",
            "SMTP 邮件沙箱", "SMTP_SANDBOX_V1", false));
        NotificationProviderRouter router = new NotificationProviderRouter(List.of(smtp));

        assertThat(router.catalog()).singleElement().satisfies(descriptor -> {
            assertThat(descriptor.providerKey()).isEqualTo("notice.smtp.sandbox");
            assertThat(descriptor.providerEnv()).isEqualTo("SANDBOX");
            assertThat(descriptor.providerName()).isEqualTo("SMTP_EMAIL");
            assertThat(descriptor.channel()).isEqualTo("EMAIL");
            assertThat(descriptor.configVersion()).isEqualTo("SMTP_SANDBOX_V1");
            assertThat(descriptor.enabled()).isFalse();
        });
        assertThatThrownBy(() -> router.require("notice.smtp.sandbox"))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("NOTICE_PROVIDER_NOT_FOUND");
    }

    private NotificationProvider provider(NotificationProviderDescriptor descriptor) {
        NotificationProvider provider = mock(NotificationProvider.class);
        when(provider.descriptor()).thenReturn(descriptor);
        return provider;
    }

    private NotificationProviderDescriptor descriptor(String key, String env, String name,
                                                      String channel, boolean enabled) {
        return new NotificationProviderDescriptor(
            key, "MESSAGE", env, name, channel, name, name + "_V1", enabled);
    }
}
