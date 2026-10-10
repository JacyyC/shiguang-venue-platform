package com.example.venue.payment.dto;

import java.util.Map;

public record PaymentAction(String type, String method, String url, Map<String, String> fields) {
    private static final String DEV_SIMULATION = "DEV_SIMULATION";
    private static final String SUBMIT_FORM = "SUBMIT_FORM";

    public PaymentAction {
        fields = immutableFields(fields);
        if (DEV_SIMULATION.equals(type)) {
            if (method != null || url != null || !fields.isEmpty()) {
                throw new IllegalArgumentException("DEV_SIMULATION action must not include form details");
            }
        } else if (SUBMIT_FORM.equals(type)) {
            if (!"POST".equals(method)) {
                throw new IllegalArgumentException("SUBMIT_FORM action method must be POST");
            }
            if (url == null || url.isBlank()) {
                throw new IllegalArgumentException("SUBMIT_FORM action URL must not be blank");
            }
            url = url.trim();
        } else {
            throw new IllegalArgumentException("Unsupported payment action type: " + type);
        }
    }

    private static Map<String, String> immutableFields(Map<String, String> fields) {
        if (fields == null) {
            return Map.of();
        }
        for (Map.Entry<String, String> entry : fields.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) {
                throw new IllegalArgumentException("Payment action fields must not contain null keys or values");
            }
        }
        return Map.copyOf(fields);
    }
}
