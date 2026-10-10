package com.example.venue.storedvalue;

import com.example.venue.storedvalue.domain.StoredValueCommands;
import com.example.venue.storedvalue.provider.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StoredValueProviderRouterTest {
    @Test
    void devIsDefaultAndProductionOrUnconfirmedSandboxIsClosed() {
        StoredValueProviderProperties properties = new StoredValueProviderProperties();
        StoredValueRechargeProvider dev = new Stub(new StoredValueProviderDescriptor("stored-value.dev", "DEV", "DEV_STORED_VALUE", "DEV_V1", true));
        StoredValueRechargeProvider prod = new Stub(new StoredValueProviderDescriptor("stored-value.prod", "PROD", "PROD", "PROD_V1", true));
        StoredValueProviderRouter router = new StoredValueProviderRouter(List.of(dev, prod), properties);
        assertThat(router.requireDefault()).isSameAs(dev);
        assertThatThrownBy(() -> router.require("stored-value.prod"))
            .hasMessageContaining("生产储值 Provider");

        properties.setDefaultProviderKey("stored-value.sandbox");
        StoredValueRechargeProvider sandbox = new Stub(new StoredValueProviderDescriptor("stored-value.sandbox", "SANDBOX", "ALIPAY", "S1", true));
        StoredValueProviderRouter sandboxRouter = new StoredValueProviderRouter(List.of(sandbox), properties);
        assertThatThrownBy(sandboxRouter::requireDefault).hasMessageContaining("人工确认");
        properties.setAlipaySandboxEnabled(true);
        properties.setAlipaySandboxManualConfirmation(true);
        assertThat(sandboxRouter.requireDefault()).isSameAs(sandbox);
    }

    @Test
    void productionProfileRejectsEvenTheDevProvider() {
        StoredValueProviderProperties properties = new StoredValueProviderProperties();
        StoredValueRechargeProvider dev = new Stub(new StoredValueProviderDescriptor(
            "stored-value.dev", "DEV", "DEV_STORED_VALUE", "DEV_V1", true));
        MockEnvironment environment = new MockEnvironment().withProperty("spring.profiles.active", "prod");
        StoredValueProviderRouter router = new StoredValueProviderRouter(List.of(dev), properties, environment);

        assertThatThrownBy(router::requireDefault)
            .hasMessageContaining("生产环境永久关闭储值资金入口");
        assertThatThrownBy(() -> router.requireForRefund("stored-value.dev"))
            .hasMessageContaining("生产环境永久关闭储值资金入口");
    }

    private static final class Stub implements StoredValueRechargeProvider {
        private final StoredValueProviderDescriptor descriptor;

        private Stub(StoredValueProviderDescriptor descriptor) {
            this.descriptor = descriptor;
        }

        @Override public StoredValueProviderDescriptor descriptor() { return descriptor; }
        @Override public StoredValueProviderCreateResult create(StoredValueProviderCreateRequest request) { return null; }
        @Override public StoredValueProviderVerification verify(StoredValueCommands.Callback callback) { return null; }
        @Override public StoredValueProviderRefundResult refund(StoredValueProviderRefundRequest request) { return null; }
    }
}
