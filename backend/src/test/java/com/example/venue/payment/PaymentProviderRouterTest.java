package com.example.venue.payment;

import com.example.venue.payment.dto.PaymentCallbackCommand;
import com.example.venue.payment.dto.PaymentCallbackEnvelope;
import com.example.venue.payment.dto.PaymentCallbackResult;
import com.example.venue.payment.dto.PaymentAction;
import com.example.venue.payment.dto.PaymentCreateCommand;
import com.example.venue.payment.dto.PaymentCreateResult;
import com.example.venue.payment.dto.ProviderStatementItem;
import com.example.venue.payment.dto.ProviderStatementQuery;
import com.example.venue.payment.provider.PaymentProvider;
import com.example.venue.payment.provider.PaymentProviderDescriptor;
import com.example.venue.payment.provider.PaymentProviderProperties;
import com.example.venue.payment.provider.PaymentProviderRouter;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaymentProviderRouterTest {
    @Test
    void selectsConfiguredDefaultAndExposesStableCatalog() {
        PaymentProvider dev = provider("payment.dev", "DEV", "DEV_PAYMENT", true);
        PaymentProvider sandbox = provider("payment.sandbox", "SANDBOX", "SANDBOX_PAYMENT", true);
        PaymentProviderProperties properties = properties("payment.sandbox");

        PaymentProviderRouter router = new PaymentProviderRouter(List.of(sandbox, dev), properties);

        assertThat(router.requireDefault()).isSameAs(sandbox);
        assertThat(router.require("dev", "dev_payment")).isSameAs(dev);
        assertThat(router.catalog())
            .extracting(PaymentProviderDescriptor::providerKey)
            .containsExactly("payment.dev", "payment.sandbox");
    }

    @Test
    void rejectsUnknownDefaultAndDisabledProviderWithoutDevFallback() {
        PaymentProvider disabled = provider("payment.disabled", "SANDBOX", "DISABLED", false);
        PaymentProviderRouter router = new PaymentProviderRouter(
            List.of(disabled), properties("payment.missing"));

        assertThatThrownBy(router::requireDefault)
            .hasMessageContaining("PAYMENT_PROVIDER_NOT_CONFIGURED");
        assertThatThrownBy(() -> router.require("SANDBOX", "DISABLED"))
            .hasMessageContaining("PAYMENT_PROVIDER_NOT_FOUND");
        assertThat(router.catalog()).containsExactly(disabled.descriptor());
    }

    @Test
    void rejectsDuplicateProviderKeyAndEnvironmentName() {
        assertThatThrownBy(() -> new PaymentProviderRouter(List.of(
            provider("payment.dev", "DEV", "ONE", true),
            provider("payment.dev", "DEV", "TWO", true)), properties("payment.dev")))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("payment.dev");

        assertThatThrownBy(() -> new PaymentProviderRouter(List.of(
            provider("payment.one", "DEV", "DUPLICATE", true),
            provider("payment.two", "dev", "duplicate", true)), properties("payment.one")))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("DEV:DUPLICATE");
    }

    @Test
    void requiresProviderNameToResolveToOneEnabledProvider() {
        PaymentProvider dev = provider("payment.dev", "DEV", "SHARED", true);
        PaymentProvider sandbox = provider("payment.sandbox", "SANDBOX", "SHARED", true);
        PaymentProvider disabled = provider("payment.disabled", "TEST", "DISABLED", false);
        PaymentProviderRouter router = new PaymentProviderRouter(
            List.of(dev, sandbox, disabled), properties("payment.dev"));

        assertThatThrownBy(() -> router.requireByName("shared"))
            .hasMessageContaining("PAYMENT_PROVIDER_AMBIGUOUS");
        assertThatThrownBy(() -> router.requireByName("disabled"))
            .hasMessageContaining("PAYMENT_PROVIDER_NOT_FOUND");
        assertThatThrownBy(() -> router.requireByName("missing"))
            .hasMessageContaining("PAYMENT_PROVIDER_NOT_FOUND");
    }

    @Test
    void requireByKeyNeverFallsBackToTheConfiguredDefault() {
        PaymentProvider dev = provider("payment.dev", "DEV", "DEV_PAYMENT", true);
        PaymentProvider sandbox = provider("payment.alipay.sandbox", "SANDBOX", "ALIPAY", false);
        PaymentProviderRouter router = new PaymentProviderRouter(List.of(dev, sandbox), properties("payment.dev"));

        assertThat(router.requireByKey("payment.dev")).isSameAs(dev);
        assertThatThrownBy(() -> router.requireByKey("payment.alipay.sandbox"))
            .hasMessageContaining("PAYMENT_PROVIDER_NOT_CONFIGURED");
        assertThatThrownBy(() -> router.requireByKey("payment.missing"))
            .hasMessageContaining("PAYMENT_PROVIDER_NOT_CONFIGURED");
    }

    @Test
    void submitFormActionSnapshotsFieldsAndRejectsInvalidShapes() {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("a", "b");

        PaymentAction action = new PaymentAction("SUBMIT_FORM", "POST", "https://gateway.example", fields);
        fields.put("later", "changed");

        assertThat(action.fields()).isEqualTo(Map.of("a", "b"));
        assertThatThrownBy(() -> action.fields().put("mutated", "value"))
            .isInstanceOf(UnsupportedOperationException.class);

        Map<String, String> nullKey = new LinkedHashMap<>();
        nullKey.put(null, "value");
        Map<String, String> nullValue = new LinkedHashMap<>();
        nullValue.put("key", null);
        assertThatThrownBy(() -> new PaymentAction("SUBMIT_FORM", "POST", "https://gateway.example", nullKey))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PaymentAction("SUBMIT_FORM", "POST", "https://gateway.example", nullValue))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PaymentAction("SUBMIT_FORM", "GET", "https://gateway.example", Map.of()))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PaymentAction("SUBMIT_FORM", "POST", " ", Map.of()))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PaymentAction("DEV_SIMULATION", null, "https://gateway.example", Map.of()))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PaymentAction("DEV_SIMULATION", null, null, Map.of("a", "b")))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PaymentAction("DEV_SIMULATION", "POST", null, Map.of()))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PaymentAction("UNKNOWN", null, null, Map.of()))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void providerDefaultsRawCallbackVerificationToUnsupported() {
        PaymentCallbackEnvelope envelope = new PaymentCallbackEnvelope(
            "application/x-www-form-urlencoded", "UTF-8", Map.of("trade_status", List.of("TRADE_SUCCESS")));

        assertThatThrownBy(() -> provider("payment.dev", "DEV", "DEV_PAYMENT", true).verifyCallback(envelope))
            .isInstanceOf(UnsupportedOperationException.class)
            .hasMessage("raw callback is not supported");
    }

    private PaymentProviderProperties properties(String defaultProviderKey) {
        PaymentProviderProperties properties = new PaymentProviderProperties();
        properties.setDefaultProviderKey(defaultProviderKey);
        return properties;
    }

    private PaymentProvider provider(String key, String env, String name, boolean enabled) {
        PaymentProviderDescriptor descriptor = new PaymentProviderDescriptor(
            key, env, name, name, name + "_V1", enabled);
        return new PaymentProvider() {
            @Override
            public PaymentProviderDescriptor descriptor() {
                return descriptor;
            }

            @Override
            public PaymentCreateResult createPayment(PaymentCreateCommand command) {
                throw new UnsupportedOperationException("not used by router test");
            }

            @Override
            public PaymentCallbackResult handleCallback(PaymentCallbackCommand command) {
                throw new UnsupportedOperationException("not used by router test");
            }

            @Override
            public List<ProviderStatementItem> statement(ProviderStatementQuery query) {
                throw new UnsupportedOperationException("not used by router test");
            }
        };
    }
}
