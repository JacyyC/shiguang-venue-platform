package com.example.venue.feedback.service;

import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

@Component
public class FeedbackTextSanitizer {
    private static final Pattern PHONE = Pattern.compile("(1[3-9]\\d)[\\s-]?\\d{4}[\\s-]?(\\d{4})");
    private static final Pattern LANDLINE = Pattern.compile("(0\\d{2,3})[\\s-]?\\d{7,8}");
    // A26-P0-04：移除 \s 与 OD/RF 短前缀，对照 SensitiveDataMasker.BUSINESS_REFERENCE_PATTERN 的字符类写法，
    // 避免重复组跨越单词边界吞噬 "Ordinary / pay more / RFID" 等普通英文文本。
    private static final Pattern BUSINESS_ID = Pattern.compile(
        "\\b(?:ORD(?:ER)?|REF(?:UND)?|PAY)(?:[:_-]*[A-Z0-9]){6,}\\b",
        Pattern.CASE_INSENSITIVE);
    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");

    public SanitizedText sanitize(String raw) {
        if (raw == null || raw.isBlank()) {
            return new SanitizedText(null, false);
        }
        String result = raw.trim();
        boolean sensitive = PHONE.matcher(result).find() || LANDLINE.matcher(result).find()
            || BUSINESS_ID.matcher(result).find() || EMAIL.matcher(result).find();
        result = BUSINESS_ID.matcher(result).replaceAll("[业务单号已隐藏]");
        result = EMAIL.matcher(result).replaceAll("[邮箱已隐藏]");
        result = PHONE.matcher(result).replaceAll("$1****$2");
        result = LANDLINE.matcher(result).replaceAll("$1-****");
        return new SanitizedText(result, sensitive);
    }

    public record SanitizedText(String text, boolean sensitive) {
    }
}
