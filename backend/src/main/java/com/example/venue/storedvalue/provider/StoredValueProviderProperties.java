package com.example.venue.storedvalue.provider;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.stored-value.provider")
public class StoredValueProviderProperties {
    private String defaultProviderKey = "stored-value.dev";
    private boolean rechargeEnabled = true;
    private boolean refundEnabled = true;
    private boolean alipaySandboxEnabled = false;
    private boolean alipaySandboxManualConfirmation = false;

    public String getDefaultProviderKey() {
        return defaultProviderKey;
    }

    public void setDefaultProviderKey(String defaultProviderKey) {
        this.defaultProviderKey = defaultProviderKey;
    }

    public boolean isRechargeEnabled() {
        return rechargeEnabled;
    }

    public void setRechargeEnabled(boolean rechargeEnabled) {
        this.rechargeEnabled = rechargeEnabled;
    }

    public boolean isRefundEnabled() {
        return refundEnabled;
    }

    public void setRefundEnabled(boolean refundEnabled) {
        this.refundEnabled = refundEnabled;
    }

    public boolean isAlipaySandboxEnabled() {
        return alipaySandboxEnabled;
    }

    public void setAlipaySandboxEnabled(boolean alipaySandboxEnabled) {
        this.alipaySandboxEnabled = alipaySandboxEnabled;
    }

    public boolean isAlipaySandboxManualConfirmation() {
        return alipaySandboxManualConfirmation;
    }

    public void setAlipaySandboxManualConfirmation(boolean alipaySandboxManualConfirmation) {
        this.alipaySandboxManualConfirmation = alipaySandboxManualConfirmation;
    }
}
