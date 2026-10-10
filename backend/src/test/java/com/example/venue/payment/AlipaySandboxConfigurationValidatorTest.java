package com.example.venue.payment;

import com.example.venue.payment.provider.PaymentProviderProperties;
import com.example.venue.payment.provider.alipay.AlipaySandboxConfigurationValidator;
import com.example.venue.payment.provider.alipay.AlipaySandboxProperties;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AlipaySandboxConfigurationValidatorTest {
    private static final String SANDBOX_GATEWAY_URL = "https://openapi-sandbox.dl.alipaydev.com/gateway.do";
    private static final String NOTIFY_URL = "https://payments.example.test/api/payments/callback/alipay/sandbox";
    private static final String RETURN_URL = "http://127.0.0.1/me/payments/alipay/sandbox/return";

    @Test
    void disabledAllowsBlankSecrets() {
        AlipaySandboxProperties properties = new AlipaySandboxProperties();
        properties.setEnabled(false);

        assertThatCode(() -> validator(properties, defaultDev()).validateOrThrow())
            .doesNotThrowAnyException();
    }

    @Test
    void enabledRequiresEveryCredentialAndExactSandboxGateway() {
        assertConfigInvalid(properties -> properties.setAppId(""));
        assertConfigInvalid(properties -> properties.setAppPrivateKey(""));
        assertConfigInvalid(properties -> properties.setAlipayPublicKey(""));
        assertConfigInvalid(properties -> properties.setGatewayUrl("https://openapi.alipay.com/gateway.do"));
    }

    @Test
    void rejectsNonUtf8NotifyEnvelopeTargetAndInvalidReturnPath() {
        assertConfigInvalid(properties -> properties.setNotifyUrl(
            "http://payments.example.test/api/payments/callback/alipay/sandbox"));
        assertConfigInvalid(properties -> properties.setNotifyUrl(
            "https://user@payments.example.test/api/payments/callback/alipay/sandbox"));
        assertConfigInvalid(properties -> properties.setNotifyUrl(
            "https://payments.example.test/api/payments/callback/alipay/sandbox#fragment"));
        assertConfigInvalid(properties -> properties.setNotifyUrl(
            "https://payments.example.test/api/payments/callback/alipay/not-sandbox"));
        assertConfigInvalid(properties -> properties.setReturnUrl(
            "https://payments.example.test/me/payments/alipay/sandbox/not-return"));
        assertConfigInvalid(properties -> properties.setReturnUrl(
            "http://payments.example.test/me/payments/alipay/sandbox/return"));
    }

    @Test
    void rejectsDisabledDefaultSandboxKeyWithoutDevFallback() {
        AlipaySandboxProperties properties = new AlipaySandboxProperties();
        properties.setEnabled(false);
        PaymentProviderProperties payment = new PaymentProviderProperties();
        payment.setDefaultProviderKey("payment.alipay.sandbox");

        assertThatThrownBy(() -> validator(properties, payment).validateOrThrow())
            .hasMessageContaining("PAYMENT_PROVIDER_NOT_CONFIGURED");
    }

    @Test
    void rejectsProdEnabledSandboxBeforeSecretValidation() {
        AlipaySandboxProperties properties = new AlipaySandboxProperties();
        properties.setEnabled(true);
        PaymentProviderProperties payment = new PaymentProviderProperties();
        payment.setDefaultProviderKey("payment.dev");

        assertThatThrownBy(() -> validator(properties, payment, "prod").validateOrThrow())
            .hasMessageContaining("ALIPAY_SANDBOX_PRODUCTION_FORBIDDEN");
    }

    @Test
    void rejectsProductionDefaultSandboxKeyBeforeSecretValidation() {
        AlipaySandboxProperties properties = new AlipaySandboxProperties();
        properties.setEnabled(false);
        PaymentProviderProperties payment = new PaymentProviderProperties();
        payment.setDefaultProviderKey("payment.alipay.sandbox");

        assertThatThrownBy(() -> validator(properties, payment, "production").validateOrThrow())
            .hasMessageContaining("ALIPAY_SANDBOX_PRODUCTION_FORBIDDEN");
    }

    private void assertConfigInvalid(Consumer<AlipaySandboxProperties> mutation) {
        AlipaySandboxProperties properties = validSandboxProperties();
        mutation.accept(properties);

        assertThatThrownBy(() -> validator(properties, defaultDev()).validateOrThrow())
            .hasMessageContaining("ALIPAY_SANDBOX_CONFIG_INVALID");
    }

    private AlipaySandboxConfigurationValidator validator(AlipaySandboxProperties properties,
                                                          PaymentProviderProperties payment,
                                                          String... profiles) {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles(profiles);
        return new AlipaySandboxConfigurationValidator(properties, payment, environment);
    }

    private PaymentProviderProperties defaultDev() {
        PaymentProviderProperties payment = new PaymentProviderProperties();
        payment.setDefaultProviderKey("payment.dev");
        return payment;
    }

    private AlipaySandboxProperties validSandboxProperties() {
        try {
            KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance("RSA");
            keyPairGenerator.initialize(2048);
            KeyPair keyPair = keyPairGenerator.generateKeyPair();

            AlipaySandboxProperties properties = new AlipaySandboxProperties();
            properties.setEnabled(true);
            properties.setAppId("2021000000000000");
            properties.setAppPrivateKey(Base64.getEncoder().encodeToString(keyPair.getPrivate().getEncoded()));
            properties.setAlipayPublicKey(Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded()));
            properties.setGatewayUrl(SANDBOX_GATEWAY_URL);
            properties.setNotifyUrl(NOTIFY_URL);
            properties.setReturnUrl(RETURN_URL);
            return properties;
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to generate test RSA material", ex);
        }
    }
}
