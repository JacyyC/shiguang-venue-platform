package com.example.venue.notice.email;

import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import org.springframework.stereotype.Component;

@Component
public final class EmailAddressValidator {
    private static final String SANDBOX_RECIPIENT_DOMAIN = "example.test";

    public String normalizeStrict(String address) {
        if (address == null || address.indexOf('\r') >= 0 || address.indexOf('\n') >= 0) {
            return null;
        }
        String normalized = address.trim();
        if (normalized.isEmpty() || normalized.length() > 254 || !isAscii(normalized)) {
            return null;
        }
        try {
            InternetAddress parsed = new InternetAddress(normalized, true);
            parsed.validate();
            if (parsed.getPersonal() != null || !normalized.equals(parsed.getAddress())) {
                return null;
            }
            return normalized;
        } catch (AddressException exception) {
            return null;
        }
    }

    public String normalizeSandboxRecipient(String address) {
        String normalized = normalizeStrict(address);
        if (normalized == null) {
            return null;
        }
        int separator = normalized.lastIndexOf('@');
        String domain = separator < 0 ? "" : normalized.substring(separator + 1);
        return SANDBOX_RECIPIENT_DOMAIN.equalsIgnoreCase(domain) ? normalized : null;
    }

    private boolean isAscii(String value) {
        for (int index = 0; index < value.length(); index++) {
            if (value.charAt(index) > 0x7f) {
                return false;
            }
        }
        return true;
    }
}
