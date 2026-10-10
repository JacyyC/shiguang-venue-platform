package com.example.venue.payment.dto;

import java.util.List;
import java.util.Map;

public record PaymentCallbackEnvelope(
    String contentType,
    String characterEncoding,
    Map<String, List<String>> formParameters
) {
}
