package com.example.venue.payment;

import com.example.venue.payment.provider.PaymentProviderDescriptor;
import com.example.venue.payment.provider.PaymentProviderRegistrySynchronizer;
import com.example.venue.payment.provider.PaymentProviderRouter;
import com.example.venue.provider.dto.ProviderRegistryRegistration;
import com.example.venue.provider.service.ProviderObservabilityService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentProviderRegistrySynchronizerTest {
    @Mock
    private PaymentProviderRouter paymentProviderRouter;
    @Mock
    private ProviderObservabilityService observabilityService;

    @Test
    void registersOnlyTheDisabledFixedAlipaySandboxDescriptorWithSafeMetadata() {
        PaymentProviderDescriptor dev = new PaymentProviderDescriptor(
            "payment.dev", "DEV", "DEV_PAYMENT", "Dev", "DEV_PAYMENT_V1", true);
        PaymentProviderDescriptor sandbox = new PaymentProviderDescriptor(
            "payment.alipay.sandbox", "SANDBOX", "ALIPAY", "Alipay Sandbox", "ALIPAY_SANDBOX_V1", false);
        when(paymentProviderRouter.catalog()).thenReturn(List.of(dev, sandbox));
        PaymentProviderRegistrySynchronizer synchronizer = new PaymentProviderRegistrySynchronizer(
            paymentProviderRouter, observabilityService);

        synchronizer.afterSingletonsInstantiated();

        ArgumentCaptor<ProviderRegistryRegistration> registration =
            ArgumentCaptor.forClass(ProviderRegistryRegistration.class);
        verify(observabilityService).upsertRegistry(registration.capture());
        verifyNoMoreInteractions(observabilityService);
        assertThat(registration.getValue().providerKey()).isEqualTo("payment.alipay.sandbox");
        assertThat(registration.getValue().providerDomain()).isEqualTo("PAYMENT");
        assertThat(registration.getValue().providerEnv()).isEqualTo("SANDBOX");
        assertThat(registration.getValue().providerName()).isEqualTo("ALIPAY");
        assertThat(registration.getValue().displayName()).isEqualTo("Alipay Sandbox");
        assertThat(registration.getValue().configVersion()).isEqualTo("ALIPAY_SANDBOX_V1");
        assertThat(registration.getValue().enabled()).isFalse();
        assertThat(registration.getValue().metadataJson())
            .isEqualTo("{\"sandbox\":true,\"capabilities\":[\"PAGE_PAY\",\"RAW_CALLBACK\",\"STATEMENT\"]}")
            .doesNotContain("appId", "seller", "gateway", "http", "key", "tunnel");
    }
}
