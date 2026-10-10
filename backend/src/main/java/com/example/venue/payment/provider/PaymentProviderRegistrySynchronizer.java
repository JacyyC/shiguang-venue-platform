package com.example.venue.payment.provider;

import com.example.venue.provider.dto.ProviderRegistryRegistration;
import com.example.venue.provider.service.ProviderObservabilityService;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.stereotype.Component;

@Component
public class PaymentProviderRegistrySynchronizer implements SmartInitializingSingleton {
    private static final String ALIPAY_SANDBOX_PROVIDER_KEY = "payment.alipay.sandbox";
    private static final String ALIPAY_SANDBOX_PROVIDER_ENV = "SANDBOX";
    private static final String ALIPAY_SANDBOX_PROVIDER_NAME = "ALIPAY";
    private static final String ALIPAY_SANDBOX_CONFIG_VERSION = "ALIPAY_SANDBOX_V1";
    private static final String ALIPAY_SANDBOX_DISPLAY_NAME = "Alipay Sandbox";
    private static final String SAFE_METADATA =
        "{\"sandbox\":true,\"capabilities\":[\"PAGE_PAY\",\"RAW_CALLBACK\",\"STATEMENT\"]}";

    private final PaymentProviderRouter paymentProviderRouter;
    private final ProviderObservabilityService observabilityService;

    public PaymentProviderRegistrySynchronizer(PaymentProviderRouter paymentProviderRouter,
                                                ProviderObservabilityService observabilityService) {
        this.paymentProviderRouter = paymentProviderRouter;
        this.observabilityService = observabilityService;
    }

    @Override
    public void afterSingletonsInstantiated() {
        paymentProviderRouter.catalog().stream()
            .filter(this::isExactSandboxDescriptor)
            .findFirst()
            .ifPresent(descriptor -> observabilityService.upsertRegistry(new ProviderRegistryRegistration(
                ALIPAY_SANDBOX_PROVIDER_KEY,
                "PAYMENT",
                ALIPAY_SANDBOX_PROVIDER_ENV,
                ALIPAY_SANDBOX_PROVIDER_NAME,
                ALIPAY_SANDBOX_DISPLAY_NAME,
                ALIPAY_SANDBOX_CONFIG_VERSION,
                descriptor.enabled(),
                SAFE_METADATA)));
    }

    private boolean isExactSandboxDescriptor(PaymentProviderDescriptor descriptor) {
        return descriptor != null
            && ALIPAY_SANDBOX_PROVIDER_KEY.equals(descriptor.providerKey())
            && ALIPAY_SANDBOX_PROVIDER_ENV.equals(descriptor.providerEnv())
            && ALIPAY_SANDBOX_PROVIDER_NAME.equals(descriptor.providerName())
            && ALIPAY_SANDBOX_CONFIG_VERSION.equals(descriptor.configVersion());
    }
}
