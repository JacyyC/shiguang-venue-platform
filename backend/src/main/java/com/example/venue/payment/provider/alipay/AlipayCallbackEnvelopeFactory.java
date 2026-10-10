package com.example.venue.payment.provider.alipay;

import com.example.venue.payment.dto.PaymentCallbackEnvelope;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class AlipayCallbackEnvelopeFactory {
    private static final String INVALID_ENVELOPE = "ALIPAY_CALLBACK_ENVELOPE_INVALID";
    private static final String FORM_CONTENT_TYPE = "application/x-www-form-urlencoded";
    private static final String UTF_8 = StandardCharsets.UTF_8.name();
    private static final int MAX_PARAMETER_COUNT = 64;
    private static final int MAX_AGGREGATE_CHARACTERS = 16 * 1024;
    private static final Set<String> REQUIRED_SINGLE_VALUE_FIELDS = Set.of(
        "sign", "sign_type", "charset", "app_id", "seller_id", "out_trade_no", "trade_no", "trade_status",
        "total_amount"
    );

    public PaymentCallbackEnvelope from(HttpServletRequest request) {
        if (request == null || !isFormContentType(request.getContentType())
            || !UTF_8.equals(request.getCharacterEncoding())) {
            throw invalidEnvelope();
        }
        Map<String, String[]> rawParameters = request.getParameterMap();
        if (rawParameters == null || rawParameters.size() > MAX_PARAMETER_COUNT) {
            throw invalidEnvelope();
        }

        long aggregateCharacters = 0;
        Map<String, List<String>> copiedParameters = new LinkedHashMap<>();
        for (Map.Entry<String, String[]> entry : rawParameters.entrySet()) {
            String key = entry.getKey();
            String[] rawValues = entry.getValue();
            if (key == null || key.isEmpty() || rawValues == null || rawValues.length == 0
                || (REQUIRED_SINGLE_VALUE_FIELDS.contains(key) && rawValues.length != 1)) {
                throw invalidEnvelope();
            }
            aggregateCharacters += key.length();
            List<String> copiedValues = new ArrayList<>(rawValues.length);
            for (String rawValue : rawValues) {
                if (rawValue == null) {
                    throw invalidEnvelope();
                }
                aggregateCharacters += rawValue.length();
                if (aggregateCharacters > MAX_AGGREGATE_CHARACTERS) {
                    throw invalidEnvelope();
                }
                copiedValues.add(rawValue);
            }
            copiedParameters.put(key, List.copyOf(copiedValues));
        }
        return new PaymentCallbackEnvelope(
            request.getContentType(), request.getCharacterEncoding(), Collections.unmodifiableMap(copiedParameters));
    }

    public static String safeSummary(PaymentCallbackEnvelope envelope) {
        Map<String, List<String>> values = envelope == null || envelope.formParameters() == null
            ? Map.of()
            : envelope.formParameters();
        String paymentNo = singleValue(values, "out_trade_no");
        String tradeNo = singleValue(values, "trade_no");
        return "{\"paymentNo\":" + json(paymentNo)
            + ",\"tradeSuffix\":" + json(maskedSuffix(tradeNo))
            + ",\"amountPresent\":" + hasSingleValue(values, "total_amount")
            + ",\"statusPresent\":" + hasSingleValue(values, "trade_status")
            + ",\"formEncoded\":" + (envelope != null && isFormContentType(envelope.contentType()))
            + ",\"utf8\":" + (envelope != null && UTF_8.equals(envelope.characterEncoding()))
            + ",\"requiredValuesSingle\":" + hasRequiredSingleValues(values)
            + ",\"rsa2\":" + "RSA2".equals(singleValue(values, "sign_type"))
            + "}";
    }

    private static boolean isFormContentType(String contentType) {
        if (contentType == null) {
            return false;
        }
        int separator = contentType.indexOf(';');
        String mediaType = (separator < 0 ? contentType : contentType.substring(0, separator)).trim();
        return FORM_CONTENT_TYPE.equalsIgnoreCase(mediaType);
    }

    private static boolean hasRequiredSingleValues(Map<String, List<String>> values) {
        return REQUIRED_SINGLE_VALUE_FIELDS.stream().allMatch(field -> hasSingleValue(values, field));
    }

    private static boolean hasSingleValue(Map<String, List<String>> values, String key) {
        List<String> value = values.get(key);
        return value != null && value.size() == 1 && value.get(0) != null && !value.get(0).isEmpty();
    }

    private static String singleValue(Map<String, List<String>> values, String key) {
        return hasSingleValue(values, key) ? values.get(key).get(0) : null;
    }

    private static String maskedSuffix(String value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        int start = Math.max(0, value.length() - 4);
        return "***" + value.substring(start);
    }

    private static String json(String value) {
        if (value == null) {
            return "null";
        }
        StringBuilder escaped = new StringBuilder("\"");
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            switch (current) {
                case '\\' -> escaped.append("\\\\");
                case '"' -> escaped.append("\\\"");
                case '\b' -> escaped.append("\\b");
                case '\f' -> escaped.append("\\f");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (current < 0x20) {
                        escaped.append(String.format("\\u%04x", (int) current));
                    } else {
                        escaped.append(current);
                    }
                }
            }
        }
        return escaped.append('"').toString();
    }

    private AlipaySandboxException invalidEnvelope() {
        return AlipaySandboxException.config(INVALID_ENVELOPE);
    }
}
