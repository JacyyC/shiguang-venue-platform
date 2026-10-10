package com.example.venue.order.service;

import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ThreadLocalRandom;

@Component
public class OrderNumberGenerator {
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS");

    public String orderNo() {
        return "ORD" + FORMATTER.format(LocalDateTime.now()) + ThreadLocalRandom.current().nextInt(1000, 9999);
    }

    public String refundNo() {
        return "REF" + FORMATTER.format(LocalDateTime.now()) + ThreadLocalRandom.current().nextInt(1000, 9999);
    }
}
