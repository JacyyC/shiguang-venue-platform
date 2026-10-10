package com.example.venue.notice.provider;

import com.example.venue.common.BusinessException;
import jakarta.mail.AuthenticationFailedException;
import jakarta.mail.MessagingException;
import org.eclipse.angus.mail.smtp.SMTPSendFailedException;
import org.eclipse.angus.mail.smtp.SMTPAddressFailedException;
import org.eclipse.angus.mail.util.MailConnectException;
import org.eclipse.angus.mail.util.SocketConnectException;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailSendException;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.concurrent.TimeoutException;

@Component
public class SmtpFailureClassifier {
    private static final Set<String> PERMANENT_PAYLOAD_CODES = Set.of(
        "EMAIL_SNAPSHOT_INVALID", "EMAIL_ADDRESS_INVALID", "EMAIL_HEADER_INVALID");

    public SmtpFailure classify(Throwable failure) {
        String payloadCode = payloadCode(failure);
        if (payloadCode != null) {
            return permanent(payloadCode, "Email payload validation failed");
        }

        SMTPSendFailedException smtpFailure = find(failure, SMTPSendFailedException.class);
        if (smtpFailure != null) {
            int code = smtpFailure.getReturnCode();
            if (code >= 400 && code <= 499) {
                return retryable("SMTP_4XX_" + code, "SMTP server temporarily rejected the message");
            }
            if (code >= 500 && code <= 599) {
                return permanent("SMTP_5XX_" + code, "SMTP server permanently rejected the message");
            }
        }

        SMTPAddressFailedException addressFailure = find(failure, SMTPAddressFailedException.class);
        if (addressFailure != null) {
            int code = addressFailure.getReturnCode();
            if (code >= 400 && code <= 499) {
                return retryable("SMTP_4XX_" + code, "SMTP server temporarily rejected the recipient");
            }
            if (code >= 500 && code <= 599) {
                return permanent("SMTP_5XX_" + code, "SMTP server permanently rejected the recipient");
            }
        }

        if (contains(failure, AuthenticationFailedException.class)
            || contains(failure, MailAuthenticationException.class)) {
            return permanent("SMTP_AUTH_FAILED", "SMTP authentication failed");
        }
        if (contains(failure, MailConnectException.class)
            || contains(failure, SocketConnectException.class)) {
            if (contains(failure, SocketTimeoutException.class)) {
                return retryable("SMTP_CONNECT_TIMEOUT", "SMTP connection timed out");
            }
            return retryable("SMTP_CONNECTION_FAILED", "SMTP connection failed");
        }
        if (contains(failure, TimeoutException.class) && contains(failure, IOException.class)) {
            return retryable("SMTP_WRITE_TIMEOUT", "SMTP write timed out");
        }
        if (contains(failure, SocketTimeoutException.class)) {
            return retryable("SMTP_READ_TIMEOUT", "SMTP read timed out");
        }
        if (contains(failure, UnknownHostException.class)
            || contains(failure, NoRouteToHostException.class)
            || contains(failure, ConnectException.class)
            || contains(failure, SocketException.class)
            || contains(failure, IOException.class)) {
            return retryable("SMTP_CONNECTION_FAILED", "SMTP connection failed");
        }
        return retryable("SMTP_UNCLASSIFIED_FAILURE", "SMTP operation failed");
    }

    private String payloadCode(Throwable failure) {
        for (Throwable item : throwableChain(failure)) {
            if (!(item instanceof BusinessException) || item.getMessage() == null) {
                continue;
            }
            int separator = item.getMessage().indexOf(':');
            String candidate = separator < 0
                ? item.getMessage()
                : item.getMessage().substring(0, separator);
            if (PERMANENT_PAYLOAD_CODES.contains(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private boolean contains(Throwable failure, Class<? extends Throwable> type) {
        return find(failure, type) != null;
    }

    private <T extends Throwable> T find(Throwable failure, Class<T> type) {
        for (Throwable item : throwableChain(failure)) {
            if (type.isInstance(item)) {
                return type.cast(item);
            }
        }
        return null;
    }

    private Iterable<Throwable> throwableChain(Throwable failure) {
        if (failure == null) {
            return Collections.emptyList();
        }
        ArrayDeque<Throwable> queue = new ArrayDeque<>();
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        java.util.List<Throwable> ordered = new java.util.ArrayList<>();
        queue.add(failure);
        while (!queue.isEmpty()) {
            Throwable current = queue.removeFirst();
            if (!visited.add(current)) {
                continue;
            }
            ordered.add(current);
            if (current.getCause() != null) {
                queue.addLast(current.getCause());
            }
            if (current instanceof MessagingException messaging
                && messaging.getNextException() != null) {
                queue.addLast(messaging.getNextException());
            }
            if (current instanceof MailSendException mailSendException) {
                for (Exception messageException : mailSendException.getMessageExceptions()) {
                    if (messageException != null) {
                        queue.addLast(messageException);
                    }
                }
            }
        }
        return ordered;
    }

    private SmtpFailure retryable(String code, String message) {
        return new SmtpFailure(
            NotificationDeliveryResult.FAILED_RETRYABLE, true, code, message);
    }

    private SmtpFailure permanent(String code, String message) {
        return new SmtpFailure(
            NotificationDeliveryResult.FAILED_PERMANENT, false, code, message);
    }

    public record SmtpFailure(
        String providerStatus,
        boolean retryable,
        String failureCode,
        String failureMessage
    ) {
    }
}
