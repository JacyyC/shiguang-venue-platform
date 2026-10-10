package com.example.venue.payment;

import com.example.venue.payment.dto.PaymentCallbackEnvelope;
import com.example.venue.payment.provider.alipay.AlipayCallbackEnvelopeFactory;
import com.example.venue.payment.provider.alipay.AlipaySandboxException;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.mock.web.MockHttpServletRequest;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AlipayCallbackEnvelopeFactoryTest {
    private final AlipayCallbackEnvelopeFactory factory = new AlipayCallbackEnvelopeFactory();

    @Test
    void copiesTheServletMultimapWithoutChangingRawValuesOrDuplicateOptionalFields() {
        MockHttpServletRequest request = validRequest();
        request.addParameter("out_trade_no", " PAY001 ");
        request.addParameter("opaque", "first%2Bvalue+kept");
        request.addParameter("opaque", "second");

        PaymentCallbackEnvelope envelope = factory.from(request);

        assertThat(envelope.contentType()).isEqualTo("application/x-www-form-urlencoded");
        assertThat(envelope.characterEncoding()).isEqualTo(StandardCharsets.UTF_8.name());
        assertThat(envelope.formParameters().keySet()).containsExactly("out_trade_no", "opaque");
        assertThat(envelope.formParameters().get("out_trade_no")).containsExactly(" PAY001 ");
        assertThat(envelope.formParameters().get("opaque")).containsExactly("first%2Bvalue+kept", "second");
        assertThatThrownBy(() -> envelope.formParameters().put("another", List.of("value")))
            .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> envelope.formParameters().get("opaque").add("third"))
            .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void makesADefensiveCopyOfServletParameterArrays() {
        String[] rawValues = {"PAY001"};
        Map<String, String[]> rawParameters = new LinkedHashMap<>();
        rawParameters.put("out_trade_no", rawValues);
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getContentType()).thenReturn("application/x-www-form-urlencoded");
        when(request.getCharacterEncoding()).thenReturn(StandardCharsets.UTF_8.name());
        when(request.getParameterMap()).thenReturn(rawParameters);

        PaymentCallbackEnvelope envelope = factory.from(request);
        rawValues[0] = "PAY002";

        assertThat(envelope.formParameters().get("out_trade_no")).containsExactly("PAY001");
    }

    @Test
    void acceptsAFormContentTypeWithUtf8Parameter() {
        MockHttpServletRequest request = validRequest();
        request.setContentType("application/x-www-form-urlencoded; charset=UTF-8");
        request.addParameter("out_trade_no", "PAY001");

        PaymentCallbackEnvelope envelope = factory.from(request);

        assertThat(envelope.formParameters().get("out_trade_no")).containsExactly("PAY001");
    }

    @ParameterizedTest
    @MethodSource("invalidRequests")
    void rejectsUnsafeOrAmbiguousRawEnvelopes(MockHttpServletRequest request) {
        assertThatThrownBy(() -> factory.from(request))
            .isInstanceOf(AlipaySandboxException.class)
            .hasMessage("ALIPAY_CALLBACK_ENVELOPE_INVALID");
    }

    @Test
    void emitsOnlyAnAllowlistedSummary() {
        String proofMarker = "proof-" + UUID.randomUUID();
        String merchantMarker = "merchant-" + UUID.randomUUID();
        Map<String, List<String>> values = new LinkedHashMap<>();
        values.put("out_trade_no", List.of("PAY001"));
        values.put("trade_no", List.of("202607120000001"));
        values.put("total_amount", List.of("100.00"));
        values.put("trade_status", List.of("TRADE_SUCCESS"));
        values.put("app_id", List.of("app-" + UUID.randomUUID()));
        values.put("seller_id", List.of(merchantMarker));
        values.put("sign_type", List.of("RSA2"));
        values.put("charset", List.of("UTF-8"));
        values.put("sign", List.of(proofMarker));
        PaymentCallbackEnvelope envelope = new PaymentCallbackEnvelope(
            "application/x-www-form-urlencoded", StandardCharsets.UTF_8.name(), Map.copyOf(values));

        String summary = factory.safeSummary(envelope);

        assertThat(summary)
            .contains("\"paymentNo\":\"PAY001\"")
            .contains("\"tradeSuffix\":\"***0001\"")
            .contains("\"amountPresent\":true", "\"statusPresent\":true")
            .contains("\"formEncoded\":true", "\"utf8\":true", "\"requiredValuesSingle\":true", "\"rsa2\":true")
            .doesNotContain("sign", proofMarker, merchantMarker, values.toString());
    }

    private static Stream<MockHttpServletRequest> invalidRequests() {
        MockHttpServletRequest emptyKey = validRequest();
        emptyKey.addParameter("", "value");

        MockHttpServletRequest tooManyParameters = validRequest();
        for (int index = 0; index <= 64; index++) {
            tooManyParameters.addParameter("parameter-" + index, "value");
        }

        MockHttpServletRequest tooLarge = validRequest();
        tooLarge.addParameter("payload", "x".repeat(16 * 1024 + 1));

        MockHttpServletRequest duplicateRequired = validRequest();
        duplicateRequired.addParameter("out_trade_no", "PAY001");
        duplicateRequired.addParameter("out_trade_no", "PAY002");

        MockHttpServletRequest duplicateProof = validRequest();
        duplicateProof.addParameter("sign", "one");
        duplicateProof.addParameter("sign", "two");

        MockHttpServletRequest nonForm = validRequest();
        nonForm.setContentType("application/json");

        MockHttpServletRequest nonUtf8 = validRequest();
        nonUtf8.setCharacterEncoding("ISO-8859-1");

        return Stream.of(emptyKey, tooManyParameters, tooLarge, duplicateRequired, duplicateProof, nonForm, nonUtf8);
    }

    private static MockHttpServletRequest validRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setMethod("POST");
        request.setContentType("application/x-www-form-urlencoded");
        request.setCharacterEncoding(StandardCharsets.UTF_8.name());
        return request;
    }
}
