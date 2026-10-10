package com.example.venue.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

class SensitiveDataMaskerTest {
    @Test
    void masksSensitiveKeysAndFreeTextContactAndBusinessReferences() {
        String raw = "{\"email\":\"alice@example.test\",\"password\":\"secret\","
            + "\"reason\":\"联系 13912345678、010-12345678，订单 ORD20260717000001\"}";

        String masked = SensitiveDataMasker.mask(raw);

        assertThat(masked).doesNotContain("alice@example.test", "secret", "13912345678", "010-12345678", "ORD20260717000001");
    }

    // === A26-P2-25：脱敏正则遇到含双引号的密码时只掩盖前半段，密码明文片段落库到操作日志 ===

    @Test
    void masksPasswordWithEscapedQuoteInJsonValue() {
        // 输入 JSON：{"oldPassword":"a\"b","newPassword":"x\"yz"}
        // 即两个密码值都含 JSON 转义引号 \"（密码原文分别是 a"b 与 x"yz）。
        // 修复前 ([^"]+) 在第一个 \" 处提前终止，掩码结果为 {"oldPassword":"***"b","newPassword":"***"yz"}，
        // 引号之后的明文 b / yz 落库到 sys_operation_log.params。
        String raw = "{\"oldPassword\":\"a\\\"b\",\"newPassword\":\"x\\\"yz\"}";

        String masked = SensitiveDataMasker.mask(raw);

        assertThat(masked).isEqualTo("{\"oldPassword\":\"***\",\"newPassword\":\"***\"}");
        assertThat(masked).doesNotContain("a\\", "b\"", "x\\", "yz\"");
    }

    @Test
    void masksAllSensitiveKeysEvenWhenValuesContainEscapedQuotes() {
        // 同一 JSON 中多个敏感键的值都含 \" 转义引号，全部需掩码为 ***。
        String raw = "{\"password\":\"p\\\"q\",\"token\":\"t\\\"u\",\"secret\":\"s\\\"v\",\"sign\":\"g\\\"h\"}";

        String masked = SensitiveDataMasker.mask(raw);

        assertThat(masked).isEqualTo("{\"password\":\"***\",\"token\":\"***\",\"secret\":\"***\",\"sign\":\"***\"}");
        assertThat(masked).doesNotContain("p\\", "q\"", "t\\", "u\"", "s\\", "v\"", "g\\", "h\"");
    }

    @Test
    void maskingDoesNotCorruptAdjacentJsonStructure() throws IOException {
        // 敏感键值掩码后整段 JSON 结构必须仍然合法可解析，
        // 非敏感键（username/reason）原样保留。
        String raw = "{\"username\":\"alice\",\"password\":\"a\\\"b\",\"reason\":\"ORD20260717000001\"}";
        ObjectMapper mapper = new ObjectMapper();

        String masked = SensitiveDataMasker.mask(raw);

        JsonNode node = mapper.readTree(masked);
        assertThat(node.get("username").asText()).isEqualTo("alice");
        assertThat(node.get("password").asText()).isEqualTo("***");
        assertThat(node.get("reason").asText()).isEqualTo("***");
    }

    @Test
    void masksPasswordWithBackslashEscapeSequence() {
        // 密码含 \\ 转义（JSON 字符串中表示单个反斜杠）：修复后的正则把 \\. 视为单个转义序列，
        // 整段值被掩码，不会因反斜杠提前终止。
        String raw = "{\"password\":\"a\\\\b\\\\c\"}";

        String masked = SensitiveDataMasker.mask(raw);

        assertThat(masked).isEqualTo("{\"password\":\"***\"}");
    }

    @Test
    void preservesEmptyStringValueBehavior() {
        // 空字符串值保持原行为：用 + 量词（一个或多个）时不掩码空值，与原 ([^"]+) 一致。
        String raw = "{\"password\":\"\",\"username\":\"alice\"}";

        String masked = SensitiveDataMasker.mask(raw);

        assertThat(masked).isEqualTo("{\"password\":\"\",\"username\":\"alice\"}");
    }

    // === A26-P3-32：BUSINESS_REFERENCE_PATTERN 大小写不敏感 + 字符类过宽，把普通英文单词整体替换为 *** ===

    @Test
    void doesNotCorruptRiskResponseSummaryJsonKeys() {
        // 风险采集成功证据的 response_summary_json 包含 paymentFailureCount / noticeFailureCount 等键名。
        // 修复前 BUSINESS_REFERENCE_PATTERN (?i)\\b(?:ORD|PAY|REF|RES|TRADE)[-_A-Z0-9]{6,}\\b 在大小写不敏感下
        // 把 paymentFailureCount 整体替换为 ***，导致 {"paymentFailureCount":0} → {"***":0}，每次风险采集必现。
        // 修复后键名原样保留，仅业务单号被脱敏。
        String raw = "{\"snapshotId\":501,\"paymentFailureCount\":0,"
            + "\"noticeFailureCount\":0,\"openComplaintCount\":0}";

        String masked = SensitiveDataMasker.mask(raw);

        assertThat(masked).isEqualTo(raw);
    }

    @Test
    void doesNotCorruptWeatherFailureMessageEnglishWords() {
        // 天气 Provider 失败信息可能含 resolution / trade_id 等普通英文词。
        // 修复前 resolution / trade_id 被整体替换为 ***，运维丢失根因。
        // 修复后普通英文词原样保留，仅真实业务单号被脱敏。
        String raw = "Open-Meteo response resolution failed for trade_id_12345";

        String masked = SensitiveDataMasker.mask(raw);

        assertThat(masked).isEqualTo(raw);
    }

    @Test
    void stillMasksRealUppercaseBusinessReferencesAfterP3_32Fix() {
        // 修复后仍能脱敏本系统生成的全大写业务单号及外部 Provider 回传的全大写标识。
        String raw = "order ORD20260717000001 refund REFUND20260717000001 payment PAY-20260717-0001 "
            + "reservation RES20260717000001 trade TRADE-20260717-0001";

        String masked = SensitiveDataMasker.mask(raw);

        assertThat(masked).doesNotContain(
            "ORD20260717000001", "REFUND20260717000001", "PAY-20260717-0001",
            "RES20260717000001", "TRADE-20260717-0001");
        assertThat(masked).contains("***");
    }
}
