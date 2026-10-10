package com.example.venue.payment.provider;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.payment")
public class PaymentProviderProperties {
    private String defaultProviderKey = "payment.dev";

    public String getDefaultProviderKey() {
        return defaultProviderKey;
    }

    public void setDefaultProviderKey(String defaultProviderKey) {
        this.defaultProviderKey = defaultProviderKey;
    }
}
