package com.example.venue.payment.provider.alipay;

import com.alipay.api.AlipayClient;
import com.alipay.api.DefaultAlipayClient;
import org.springframework.stereotype.Component;

@Component
public class AlipaySandboxClientFactory {
    private static final String FORMAT = "json";
    private static final String CHARSET = "UTF-8";
    private static final String SIGN_TYPE = "RSA2";

    private final AlipaySandboxProperties properties;
    private final AlipaySandboxConfigurationValidator validator;
    private volatile AlipayClient client;

    public AlipaySandboxClientFactory(AlipaySandboxProperties properties,
                                     AlipaySandboxConfigurationValidator validator) {
        this.properties = properties;
        this.validator = validator;
    }

    public AlipayClient client() {
        if (!properties.isEnabled()) {
            throw AlipaySandboxException.config("PAYMENT_PROVIDER_NOT_CONFIGURED");
        }
        AlipayClient current = client;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            if (client == null) {
                validator.validateOrThrow();
                client = new DefaultAlipayClient(
                    properties.getGatewayUrl(),
                    properties.getAppId(),
                    properties.getAppPrivateKey(),
                    FORMAT,
                    CHARSET,
                    properties.getAlipayPublicKey(),
                    SIGN_TYPE
                );
            }
            return client;
        }
    }
}
