package com.example.venue.common;

import java.util.Set;
import java.util.regex.Pattern;

public final class SensitiveDataMasker {
    private static final Set<String> SENSITIVE_KEYS = Set.of(
        "password", "oldPassword", "newPassword", "token", "authorization",
        "secret", "sign", "signature", "privateKey", "captcha", "code",
        "email", "phone", "mobile", "contactPhone", "contactName",
        "orderNo", "refundNo", "paymentNo", "transactionId", "externalRef", "idempotencyKey"
    );
    private static final Pattern JWT_PATTERN = Pattern.compile("eyJ[\\w-]+\\.[\\w-]+\\.[\\w-]+");
    private static final Pattern EMAIL_PATTERN = Pattern.compile("(?i)[a-z0-9._%+-]+@[a-z0-9.-]+\\.[a-z]{2,}");
    private static final Pattern MOBILE_PATTERN = Pattern.compile("(?<!\\d)(1[3-9]\\d)\\d{4}(\\d{4})(?!\\d)");
    private static final Pattern LANDLINE_PATTERN = Pattern.compile("(?<!\\d)(0\\d{2,3}[- ]?)\\d{7,8}(?!\\d)");
    // A26-P3-32：原 (?i)\\b(?:ORD|PAY|REF|RES|TRADE)[-_A-Z0-9]{6,}\\b 在大小写不敏感下
    // 把 paymentFailureCount / resolution 等普通英文单词整体替换为 ***（键名被破坏）。
    // 修复点 1：字符类改为 (?:[:_-]*[A-Z0-9]){6,}，与 FeedbackTextSanitizer.BUSINESS_ID 对齐，
    //           避免连续小写字母被字符类无分隔地吞掉。
    // 修复点 2：去掉 (?i)，要求业务单号前缀全大写。本系统 OrderNumberGenerator/PaymentService 生成的
    //           ORD/REF/PAY 单号及外部 Provider 回传的 RES/TRADE 标识均为全大写+数字形式，
    //           去掉大小写不敏感不会漏脱敏真实业务单号，但能避免 paymentFailureCount/resolution/trade_id 等被吞。
    private static final Pattern BUSINESS_REFERENCE_PATTERN =
        Pattern.compile("\\b(?:ORD(?:ER)?|REF(?:UND)?|PAY|RES|TRADE)(?:[:_-]*[A-Z0-9]){6,}\\b");

    private SensitiveDataMasker() {
    }

    public static String mask(String text) {
        if (text == null || text.isBlank()) {
            return text;
        }
        String masked = JWT_PATTERN.matcher(text).replaceAll("eyJ***.***.***");
        for (String key : SENSITIVE_KEYS) {
            // 值捕获组按 JSON 字符串字面量语义匹配：非引号非反斜杠字符或任意转义序列（含 \"），
            // 防止密码含双引号（JSON 转义为 \"）时 ([^"]+) 提前终止，导致引号之后的明文片段落库（A26-P2-25）。
            masked = masked.replaceAll("(?i)(\"" + key + "\"\\s*:\\s*\")((?:[^\"\\\\]|\\\\.)+)(\")", "$1***$3");
            masked = masked.replaceAll("(?i)(" + key + "=)([^&\\s]+)", "$1***");
        }
        masked = EMAIL_PATTERN.matcher(masked).replaceAll("***@***");
        masked = MOBILE_PATTERN.matcher(masked).replaceAll("$1****$2");
        masked = LANDLINE_PATTERN.matcher(masked).replaceAll("$1****");
        masked = BUSINESS_REFERENCE_PATTERN.matcher(masked).replaceAll("***");
        return masked;
    }
}
