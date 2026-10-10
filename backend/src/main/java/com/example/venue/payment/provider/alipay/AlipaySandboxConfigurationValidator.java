package com.example.venue.payment.provider.alipay;

import com.example.venue.payment.provider.PaymentProviderProperties;
import jakarta.annotation.PostConstruct;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.URI;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.Base64;
import java.util.Locale;
import java.util.regex.Pattern;

@Component
public class AlipaySandboxConfigurationValidator {
    private static final String CONFIG_INVALID = "ALIPAY_SANDBOX_CONFIG_INVALID";
    private static final String PROVIDER_NOT_CONFIGURED = "PAYMENT_PROVIDER_NOT_CONFIGURED";
    private static final String PRODUCTION_FORBIDDEN = "ALIPAY_SANDBOX_PRODUCTION_FORBIDDEN";
    private static final String SANDBOX_PROVIDER_KEY = "payment.alipay.sandbox";
    private static final String NOTIFY_PATH = "/api/payments/callback/alipay/sandbox";
    private static final String RETURN_PATH = "/me/payments/alipay/sandbox/return";
    private static final Pattern APP_ID_PATTERN = Pattern.compile("\\d{1,32}");
    private static final Pattern IPV4_LITERAL = Pattern.compile("\\d{1,3}(?:\\.\\d{1,3}){3}");

    private final AlipaySandboxProperties properties;
    private final PaymentProviderProperties paymentProperties;
    private final Environment environment;

    public AlipaySandboxConfigurationValidator(AlipaySandboxProperties properties,
                                               PaymentProviderProperties paymentProperties,
                                               Environment environment) {
        this.properties = properties;
        this.paymentProperties = paymentProperties;
        this.environment = environment;
    }

    public void validateOrThrow() {
        if (isProductionProfile() && (properties.isEnabled() || isSandboxDefault())) {
            throw AlipaySandboxException.config(PRODUCTION_FORBIDDEN);
        }
        if (!properties.isEnabled()) {
            if (isSandboxDefault()) {
                throw AlipaySandboxException.config(PROVIDER_NOT_CONFIGURED);
            }
            return;
        }
        validateAppIdAndKeys();
        validateGatewayNotifyAndReturnUrls();
    }

    @PostConstruct
    void validateAtStartup() {
        validateOrThrow();
    }

    private boolean isProductionProfile() {
        return Arrays.stream(environment.getActiveProfiles())
            .anyMatch(profile -> "prod".equalsIgnoreCase(profile) || "production".equalsIgnoreCase(profile));
    }

    private boolean isSandboxDefault() {
        String defaultProviderKey = paymentProperties.getDefaultProviderKey();
        return SANDBOX_PROVIDER_KEY.equals(defaultProviderKey == null ? "" : defaultProviderKey.trim());
    }

    private void validateAppIdAndKeys() {
        if (!hasValidAppId() || !isValidPrivateKey(properties.getAppPrivateKey())
            || !isValidPublicKey(properties.getAlipayPublicKey())) {
            throw AlipaySandboxException.config(CONFIG_INVALID);
        }
    }

    private boolean hasValidAppId() {
        String appId = properties.getAppId();
        return appId != null && APP_ID_PATTERN.matcher(appId).matches();
    }

    private boolean isValidPrivateKey(String value) {
        try {
            PrivateKey privateKey = KeyFactory.getInstance("RSA").generatePrivate(
                new PKCS8EncodedKeySpec(decode(value)));
            return privateKey instanceof RSAPrivateKey;
        } catch (Exception ex) {
            return false;
        }
    }

    private boolean isValidPublicKey(String value) {
        try {
            PublicKey publicKey = KeyFactory.getInstance("RSA").generatePublic(
                new X509EncodedKeySpec(decode(value)));
            return publicKey instanceof RSAPublicKey;
        } catch (Exception ex) {
            return false;
        }
    }

    private byte[] decode(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("RSA key is blank");
        }
        return Base64.getDecoder().decode(value);
    }

    private void validateGatewayNotifyAndReturnUrls() {
        if (!AlipaySandboxProperties.SANDBOX_GATEWAY_URL.equals(properties.getGatewayUrl())
            || !isValidNotifyUrl(properties.getNotifyUrl())
            || !isValidReturnUrl(properties.getReturnUrl())) {
            throw AlipaySandboxException.config(CONFIG_INVALID);
        }
    }

    private boolean isValidNotifyUrl(String value) {
        URI uri = parse(value);
        return uri != null
            && "https".equalsIgnoreCase(uri.getScheme())
            && uri.getHost() != null
            && uri.getRawUserInfo() == null
            && uri.getRawFragment() == null
            && NOTIFY_PATH.equals(uri.getRawPath());
    }

    private boolean isValidReturnUrl(String value) {
        URI uri = parse(value);
        if (uri == null || uri.getHost() == null || uri.getRawUserInfo() != null || uri.getRawFragment() != null
            || !RETURN_PATH.equals(uri.getRawPath())) {
            return false;
        }
        if ("https".equalsIgnoreCase(uri.getScheme())) {
            return true;
        }
        return "http".equalsIgnoreCase(uri.getScheme()) && isLoopbackHost(uri.getHost());
    }

    private URI parse(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return URI.create(value);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private boolean isLoopbackHost(String host) {
        String normalized = host == null ? "" : host.toLowerCase(Locale.ROOT);
        if ("localhost".equals(normalized)) {
            return true;
        }
        String address = normalized.startsWith("[") && normalized.endsWith("]")
            ? normalized.substring(1, normalized.length() - 1)
            : normalized;
        if (!address.contains(":") && !IPV4_LITERAL.matcher(address).matches()) {
            return false;
        }
        try {
            return InetAddress.getByName(address).isLoopbackAddress();
        } catch (Exception ex) {
            return false;
        }
    }
}
