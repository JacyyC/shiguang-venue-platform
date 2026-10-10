package com.example.venue.notice;

import com.example.venue.common.BusinessException;
import com.example.venue.notice.provider.NotificationDeliveryResult;
import com.example.venue.notice.provider.SmtpFailureClassifier;
import jakarta.mail.AuthenticationFailedException;
import jakarta.mail.internet.InternetAddress;
import org.eclipse.angus.mail.smtp.SMTPAddressFailedException;
import org.eclipse.angus.mail.smtp.SMTPSendFailedException;
import org.eclipse.angus.mail.util.MailConnectException;
import org.eclipse.angus.mail.util.SocketConnectException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mail.MailSendException;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.util.concurrent.TimeoutException;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SmtpFailureClassifierTest {
    private final SmtpFailureClassifier classifier = new SmtpFailureClassifier();

    @ParameterizedTest
    @CsvSource({
        "421,SMTP_4XX_421,true", "450,SMTP_4XX_450,true", "451,SMTP_4XX_451,true",
        "452,SMTP_4XX_452,true", "550,SMTP_5XX_550,false", "551,SMTP_5XX_551,false",
        "552,SMTP_5XX_552,false", "553,SMTP_5XX_553,false", "554,SMTP_5XX_554,false"
    })
    void smtpReturnCodeMapsToTheRequiredResult(int code, String failureCode, boolean retryable) {
        var result = classifier.classify(new SMTPSendFailedException(
            "MAIL", code, "hidden smtp response", null, null, null, null));

        assertThat(result.failureCode()).isEqualTo(failureCode);
        assertThat(result.retryable()).isEqualTo(retryable);
        assertThat(result.providerStatus()).isEqualTo(retryable
            ? NotificationDeliveryResult.FAILED_RETRYABLE
            : NotificationDeliveryResult.FAILED_PERMANENT);
        assertSafe(result.toString(), result.failureMessage());
    }

    @ParameterizedTest
    @CsvSource({"450,SMTP_4XX_450,true", "550,SMTP_5XX_550,false"})
    void smtpAddressReturnCodePreservesClassificationThroughSpringWrapper(
        int code, String failureCode, boolean retryable
    ) throws Exception {
        SMTPAddressFailedException addressFailure = new SMTPAddressFailedException(
            new InternetAddress("alice@example.test"), "RCPT TO", code, "hidden smtp response");

        var result = classifier.classify(new MailSendException(Map.of("message", addressFailure)));

        assertThat(result.failureCode()).isEqualTo(failureCode);
        assertThat(result.retryable()).isEqualTo(retryable);
        assertThat(result.providerStatus()).isEqualTo(retryable
            ? NotificationDeliveryResult.FAILED_RETRYABLE
            : NotificationDeliveryResult.FAILED_PERMANENT);
        assertSafe(result.toString(), result.failureMessage());
    }

    @Test
    void timeoutPhaseIsClassifiedFromCauseTypesWithoutUsingExceptionText() {
        SocketTimeoutException hiddenTimeout = new SocketTimeoutException(
            "alice@example.test smtp-password-secret hidden smtp response");
        MailConnectException connect = new MailConnectException(new SocketConnectException(
            "hidden smtp response", hiddenTimeout, "mailpit", 1025, 3000));

        assertThat(classifier.classify(connect).failureCode()).isEqualTo("SMTP_CONNECT_TIMEOUT");
        assertThat(classifier.classify(hiddenTimeout).failureCode()).isEqualTo("SMTP_READ_TIMEOUT");
        assertThat(classifier.classify(new IOException(new TimeoutException("hidden smtp response")))
            .failureCode()).isEqualTo("SMTP_WRITE_TIMEOUT");
        assertSafe(classifier.classify(connect).failureMessage());
        assertSafe(classifier.classify(hiddenTimeout).failureMessage());
    }

    @Test
    void connectionAuthenticationAndInvalidPayloadFailuresUseSafeStableCodes() {
        assertThat(classifier.classify(new ConnectException("smtp.example secret response")))
            .satisfies(result -> {
                assertThat(result.failureCode()).isEqualTo("SMTP_CONNECTION_FAILED");
                assertThat(result.retryable()).isTrue();
                assertSafe(result.failureMessage());
            });
        assertThat(classifier.classify(new AuthenticationFailedException(
            "smtp-password-secret hidden smtp response")))
            .satisfies(result -> {
                assertThat(result.failureCode()).isEqualTo("SMTP_AUTH_FAILED");
                assertThat(result.retryable()).isFalse();
                assertSafe(result.failureMessage());
            });
        assertThat(classifier.classify(BusinessException.badRequest(
            "EMAIL_SNAPSHOT_INVALID: alice@example.test secret subject body")))
            .satisfies(result -> {
                assertThat(result.failureCode()).isEqualTo("EMAIL_SNAPSHOT_INVALID");
                assertThat(result.retryable()).isFalse();
                assertSafe(result.failureMessage());
            });
        assertThat(classifier.classify(BusinessException.badRequest(
            "EMAIL_ADDRESS_INVALID: alice@example.test"))).extracting(result -> result.failureCode())
            .isEqualTo("EMAIL_ADDRESS_INVALID");
        assertThat(classifier.classify(BusinessException.badRequest(
            "EMAIL_HEADER_INVALID: hidden subject"))).extracting(result -> result.failureCode())
            .isEqualTo("EMAIL_HEADER_INVALID");
    }

    @Test
    void unknownFailureIsRetryableAndNeverCopiesRawFailureText() {
        var result = classifier.classify(new IllegalStateException(
            "alice@example.test smtp-password-secret hidden subject body smtp response"));

        assertThat(result.providerStatus()).isEqualTo(NotificationDeliveryResult.FAILED_RETRYABLE);
        assertThat(result.retryable()).isTrue();
        assertThat(result.failureCode()).isEqualTo("SMTP_UNCLASSIFIED_FAILURE");
        assertSafe(result.toString(), result.failureMessage());
    }

    @Test
    void springFailedMessageWrapperPreservesTheSmtpReturnCodeClassification() {
        SMTPSendFailedException smtp = new SMTPSendFailedException(
            "MAIL", 550, "alice@example.test hidden smtp response",
            null, null, null, null);

        var result = classifier.classify(new MailSendException(Map.of("message", smtp)));

        assertThat(result.failureCode()).isEqualTo("SMTP_5XX_550");
        assertThat(result.retryable()).isFalse();
        assertSafe(result.toString(), result.failureMessage());
    }

    private void assertSafe(String... values) {
        assertThat(values).allSatisfy(value -> assertThat(value)
            .doesNotContain("alice@example.test", "smtp-password-secret", "hidden smtp response",
                "secret subject", " subject ", " body ", "smtp.example"));
    }
}
