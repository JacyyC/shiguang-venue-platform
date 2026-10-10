package com.example.venue.payment;

import com.alipay.api.AlipayClient;
import com.example.venue.payment.provider.PaymentProviderProperties;
import com.example.venue.payment.provider.alipay.AlipaySandboxClientFactory;
import com.example.venue.payment.provider.alipay.AlipaySandboxConfigurationValidator;
import com.example.venue.payment.provider.alipay.AlipaySandboxProperties;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.lang.reflect.Field;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AlipaySandboxClientFactoryTest {
    private static final String NOTIFY_URL = "https://payments.example.test/api/payments/callback/alipay/sandbox";
    private static final String RETURN_URL = "http://127.0.0.1/me/payments/alipay/sandbox/return";

    @Test
    void disabledFactoryRejectsBeforeClientConstructionAndDoesNotCache() {
        AlipaySandboxProperties properties = new AlipaySandboxProperties();
        properties.setEnabled(false);
        AlipaySandboxClientFactory factory = factory(properties);

        assertThatThrownBy(factory::client)
            .hasMessageContaining("PAYMENT_PROVIDER_NOT_CONFIGURED");
        assertThat(cachedClient(factory)).isNull();

        applyValidEnabledConfiguration(properties);

        assertThat(factory.client()).isNotNull();
    }

    @Test
    void enabledFactoryCreatesAndReusesOneClient() {
        AlipaySandboxClientFactory factory = factory(validSandboxProperties());

        AlipayClient first = factory.client();
        AlipayClient second = factory.client();

        assertThat(first).isNotNull();
        assertThat(second).isSameAs(first);
    }

    private AlipaySandboxClientFactory factory(AlipaySandboxProperties properties) {
        PaymentProviderProperties payment = new PaymentProviderProperties();
        payment.setDefaultProviderKey("payment.dev");
        AlipaySandboxConfigurationValidator validator = new AlipaySandboxConfigurationValidator(
            properties, payment, new MockEnvironment());
        return new AlipaySandboxClientFactory(properties, validator);
    }

    private AlipayClient cachedClient(AlipaySandboxClientFactory factory) {
        try {
            Field field = AlipaySandboxClientFactory.class.getDeclaredField("client");
            field.setAccessible(true);
            return (AlipayClient) field.get(factory);
        } catch (ReflectiveOperationException ex) {
            throw new AssertionError("Unable to inspect factory cache", ex);
        }
    }

    private AlipaySandboxProperties validSandboxProperties() {
        AlipaySandboxProperties properties = new AlipaySandboxProperties();
        applyValidEnabledConfiguration(properties);
        return properties;
    }

    private void applyValidEnabledConfiguration(AlipaySandboxProperties properties) {
        try {
            KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance("RSA");
            keyPairGenerator.initialize(2048);
            KeyPair keyPair = keyPairGenerator.generateKeyPair();

            properties.setEnabled(true);
            properties.setAppId("2021000000000000");
            properties.setAppPrivateKey(Base64.getEncoder().encodeToString(keyPair.getPrivate().getEncoded()));
            properties.setAlipayPublicKey(Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded()));
            properties.setGatewayUrl(AlipaySandboxProperties.SANDBOX_GATEWAY_URL);
            properties.setNotifyUrl(NOTIFY_URL);
            properties.setReturnUrl(RETURN_URL);
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to generate test RSA material", ex);
        }
    }
}
