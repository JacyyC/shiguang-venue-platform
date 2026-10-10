package com.example.venue.storedvalue.provider;

import com.example.venue.storedvalue.domain.StoredValueCommands;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

/** A deterministic DEV provider. It never calls a real payment channel. */
@Component
public class DevStoredValueProvider implements StoredValueRechargeProvider {
    public static final String MERCHANT_IDENTIFIER = "DEV_STORED_VALUE";
    private static final StoredValueProviderDescriptor DESCRIPTOR =
        new StoredValueProviderDescriptor("stored-value.dev", "DEV", "DEV_STORED_VALUE", "DEV_V1", true);

    @Override
    public StoredValueProviderDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public StoredValueProviderCreateResult create(StoredValueProviderCreateRequest request) {
        requireRequest(request);
        String businessNo = request.providerBusinessNo();
        return new StoredValueProviderCreateResult(
            "PENDING", businessNo, null, digest(businessNo + request.amount()),
            "DEV provider create accepted; status must be confirmed by callback", null, null, null);
    }

    @Override
    public StoredValueProviderVerification verify(StoredValueCommands.Callback callback) {
        if (callback == null || callback.amount() == null || callback.currency() == null) {
            return rejected("INVALID_CALLBACK");
        }
        if (!DESCRIPTOR.providerKey().equals(callback.providerKey())
            || !DESCRIPTOR.providerEnv().equals(callback.providerEnv())
            || !DESCRIPTOR.providerName().equals(callback.providerName())
            || !DESCRIPTOR.configVersion().equals(callback.configVersion())
            || !MERCHANT_IDENTIFIER.equals(callback.merchantIdentifier())
            || callback.signatureDigest() == null
            || !callback.signatureDigest().equals(signatureDigest(callback))) {
            return rejected("INVALID_DEV_ENVELOPE");
        }
        String status = callback.status() == null ? "" : callback.status().trim().toUpperCase();
        if (!"SUCCESS".equals(status) && !"FAILED".equals(status)) {
            return rejected("UNSUPPORTED_PROVIDER_STATUS");
        }
        return new StoredValueProviderVerification(
            true, status, null, callback.amount(), callback.currency(), callback.externalRef(),
            callback.signatureDigest(),
            "DEV callback verified");
    }

    @Override
    public String signCallback(StoredValueCommands.Callback callback) {
        return signatureDigest(callback);
    }

    public static String signatureDigest(StoredValueCommands.Callback callback) {
        if (callback == null || callback.amount() == null) {
            return "";
        }
        String canonical = String.join("|", value(callback.providerKey()), value(callback.providerEnv()),
            value(callback.providerName()), value(callback.configVersion()), value(callback.rechargeNo()),
            value(callback.externalEventId()), value(callback.externalRef()), value(callback.merchantIdentifier()),
            value(callback.status()).toUpperCase(Locale.ROOT), value(callback.currency()),
            callback.amount().toPlainString(), value(callback.minimalSummary()));
        return digest(canonical);
    }

    @Override
    public StoredValueProviderRefundResult refund(StoredValueProviderRefundRequest request) {
        requireRefund(request);
        return new StoredValueProviderRefundResult(
            "SUCCEEDED", "dev-refund-" + request.refundNo(),
            digest(request.refundNo() + request.amount()), "DEV refund accepted", null);
    }

    private void requireRequest(StoredValueProviderCreateRequest request) {
        if (request == null || request.amount() == null || request.amount().signum() <= 0
            || !"CNY".equals(request.currency())) {
            throw new IllegalArgumentException("invalid DEV stored value create request");
        }
    }

    private void requireRefund(StoredValueProviderRefundRequest request) {
        if (request == null || request.amount() == null || request.amount().signum() <= 0
            || !"CNY".equals(request.currency())) {
            throw new IllegalArgumentException("invalid DEV stored value refund request");
        }
    }

    private StoredValueProviderVerification rejected(String code) {
        return new StoredValueProviderVerification(false, "FAILED", code, null, null, null, null, null);
    }

    private static String digest(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(64);
            for (byte item : bytes) {
                result.append(String.format("%02x", item));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private static String value(String value) {
        return value == null ? "" : value;
    }
}
