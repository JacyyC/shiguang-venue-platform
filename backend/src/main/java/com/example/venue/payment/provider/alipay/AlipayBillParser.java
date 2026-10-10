package com.example.venue.payment.provider.alipay;

import com.example.venue.payment.dto.ProviderStatementItem;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Component
public class AlipayBillParser {
    private static final String SCHEMA_UNSUPPORTED = "ALIPAY_BILL_SCHEMA_UNSUPPORTED";
    private static final String PARSE_FAILED = "ALIPAY_BILL_PARSE_FAILED";
    private static final Charset GBK = Charset.forName("GBK");
    private static final int MAX_ENTRIES = 4;
    private static final long MAX_DECOMPRESSED_BYTES = 50L * 1024 * 1024;
    private static final int BUFFER_SIZE = 8 * 1024;
    private static final Set<String> SUMMARY_MARKERS = Set.of("合计", "总计", "汇总", "SUMMARY", "TOTAL");
    private static final Map<String, String> HEADER_ALIASES = aliases();
    private static final Set<String> REQUIRED_COLUMNS = Set.of(
        "tradeNo", "paymentNo", "amount", "businessType", "tradeStatus");

    public List<ProviderStatementItem> parse(byte[] archive) {
        if (archive == null || archive.length == 0) {
            throw schemaUnsupported();
        }
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(archive), GBK)) {
            List<ProviderStatementItem> items = new ArrayList<>();
            long[] decompressed = {0};
            int entryCount = 0;
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                entryCount++;
                validateEntry(entry, entryCount);
                byte[] bytes = readEntry(zip, decompressed);
                if (!isSummaryEntry(entry.getName())) {
                    items.addAll(parseCsv(bytes));
                }
            }
            if (entryCount == 0) {
                throw schemaUnsupported();
            }
            return List.copyOf(items);
        } catch (AlipaySandboxException ex) {
            throw ex;
        } catch (IOException | RuntimeException ex) {
            throw parseFailed();
        }
    }

    private void validateEntry(ZipEntry entry, int entryCount) {
        String name = entry.getName();
        if (entryCount > MAX_ENTRIES || entry.isDirectory() || name == null || name.isBlank()
            || name.startsWith("/") || name.startsWith("\\") || name.contains("\\") || name.contains("..")
            || !name.toLowerCase(Locale.ROOT).endsWith(".csv")) {
            throw schemaUnsupported();
        }
    }

    private byte[] readEntry(ZipInputStream zip, long[] total) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[BUFFER_SIZE];
        for (int read; (read = zip.read(buffer)) != -1;) {
            if (read > MAX_DECOMPRESSED_BYTES - total[0]) {
                throw parseFailed();
            }
            output.write(buffer, 0, read);
            total[0] += read;
        }
        return output.toByteArray();
    }

    private List<ProviderStatementItem> parseCsv(byte[] bytes) throws IOException {
        // 真实支付宝业务明细 CSV 以 # 开头的注释行开头（如 "#支付宝业务明细查询"），
        // 表头约 25 列（含 商品名称、创建时间、订单金额（元） 等本解析器不需要的列），
        // 且每行尾部带尾随分隔符。commons-csv 1.11.0 在 setHeader() 自动模式下遇空表头
        // （来自尾随分隔符）会抛 IllegalArgumentException 拒绝构造 parser；而 setTrailingDelimiter(true)
        // 会让数据行尾随空值被吃掉导致与表头列数不一致。这里不使用 setHeader() 自动模式，
        // 改为手动读取第一条非注释、非空记录作为表头并建立 alias→列索引映射，跳过空表头与未知列，
        // 用 record.get(index) 取值；列数与表头不一致的记录仍按 parseFailed 拒绝。
        // 编码显式指定 GBK（支付宝账单为 GBK 编码，InputStreamReader 不指定 charset 会用系统默认导致中文乱码）。
        CSVFormat format = CSVFormat.DEFAULT.builder()
            .setIgnoreEmptyLines(true)
            .setCommentMarker('#')
            .build();
        try (CSVParser parser = new CSVParser(new InputStreamReader(new ByteArrayInputStream(bytes), GBK), format)) {
            Map<String, Integer> columns = new LinkedHashMap<>();
            int headerColumnCount = -1;
            boolean headerConsumed = false;
            List<ProviderStatementItem> items = new ArrayList<>();
            for (CSVRecord record : parser) {
                if (!headerConsumed) {
                    headerColumnCount = record.size();
                    Map<String, Integer> resolved = canonicalColumns(record);
                    if (resolved.isEmpty()) {
                        throw schemaUnsupported();
                    }
                    columns.putAll(resolved);
                    headerConsumed = true;
                    continue;
                }
                if (record.size() != headerColumnCount) {
                    throw parseFailed();
                }
                if (isSummaryRow(record, columns)) {
                    continue;
                }
                items.add(statementItem(record, columns));
            }
            if (!headerConsumed) {
                throw schemaUnsupported();
            }
            return List.copyOf(items);
        } catch (AlipaySandboxException ex) {
            throw ex;
        } catch (IOException | RuntimeException ex) {
            throw parseFailed();
        }
    }

    private Map<String, Integer> canonicalColumns(CSVRecord headerRecord) {
        Map<String, Integer> columns = new LinkedHashMap<>();
        for (int index = 0; index < headerRecord.size(); index++) {
            String normalized = normalize(headerRecord.get(index));
            // 尾随分隔符会让 commons-csv 解析出空表头（""），跳过避免干扰列索引与必需列校验。
            if (normalized.isEmpty()) {
                continue;
            }
            String alias = HEADER_ALIASES.get(normalized);
            // 真实支付宝账单含本解析器不需要的列（商品名称、创建时间、订单金额（元） 等），
            // 未知列直接跳过，不整单拒绝；只取已知列并校验必需列齐全即可生成对账项。
            if (alias == null) {
                continue;
            }
            // 同一 alias 出现两次（如同时存在中文别名与英文别名指向同一字段）仍属 schema 异常，拒绝。
            if (columns.putIfAbsent(alias, index) != null) {
                throw schemaUnsupported();
            }
        }
        if (!columns.keySet().containsAll(REQUIRED_COLUMNS)) {
            throw schemaUnsupported();
        }
        return columns;
    }

    private ProviderStatementItem statementItem(CSVRecord record, Map<String, Integer> columns) {
        String tradeNo = required(record, columns, "tradeNo");
        String paymentNo = required(record, columns, "paymentNo");
        BigDecimal amount = money(required(record, columns, "amount"));
        String feeRaw = value(record, columns, "feeAmount");
        boolean missingFee = feeRaw == null || feeRaw.isBlank();
        BigDecimal feeAmount = missingFee ? BigDecimal.ZERO.setScale(2) : money(feeRaw);
        String netRaw = value(record, columns, "netAmount");
        BigDecimal netAmount = netRaw == null || netRaw.isBlank()
            ? amount.subtract(feeAmount).setScale(2, RoundingMode.UNNECESSARY)
            : money(netRaw);
        String businessType = required(record, columns, "businessType");
        required(record, columns, "tradeStatus");

        ProviderStatementItem item = ProviderStatementItem.payment(tradeNo, paymentNo, amount, feeAmount, netAmount);
        if ("交易".equals(businessType) || "PAYMENT".equals(businessType)) {
            item.businessType = "PAYMENT";
            item.dataGap = missingFee ? "FEE_AMOUNT_MISSING" : null;
            return item;
        }
        if ("退款".equals(businessType) || "撤销".equals(businessType)
            || "REFUND".equals(businessType) || "VOID".equals(businessType)) {
            item.businessType = "REFUND_OR_VOID";
            item.dataGap = missingFee ? "FEE_AMOUNT_MISSING" : null;
            return item;
        }
        throw schemaUnsupported();
    }

    private boolean isSummaryRow(CSVRecord record, Map<String, Integer> columns) {
        String type = value(record, columns, "businessType");
        if (type != null && SUMMARY_MARKERS.contains(type.trim().toUpperCase(Locale.ROOT))) {
            return true;
        }
        String tradeNo = value(record, columns, "tradeNo");
        return tradeNo != null && SUMMARY_MARKERS.contains(tradeNo.trim().toUpperCase(Locale.ROOT));
    }

    private String required(CSVRecord record, Map<String, Integer> columns, String name) {
        String value = value(record, columns, name);
        if (value == null || value.isBlank()) {
            throw schemaUnsupported();
        }
        return value.trim();
    }

    private String value(CSVRecord record, Map<String, Integer> columns, String name) {
        Integer index = columns.get(name);
        if (index == null || index < 0 || index >= record.size()) {
            return null;
        }
        return record.get(index);
    }

    private BigDecimal money(String raw) {
        if (raw == null || !raw.matches("-?[0-9]+\\.[0-9]{2}")) {
            throw parseFailed();
        }
        try {
            return new BigDecimal(raw).setScale(2, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException | NumberFormatException ex) {
            throw parseFailed();
        }
    }

    private boolean isSummaryEntry(String name) {
        String normalized = name.toLowerCase(Locale.ROOT);
        return normalized.contains("summary") || name.contains("汇总") || name.contains("合计");
    }

    private String normalize(String header) {
        return header == null ? "" : header.trim();
    }

    private static Map<String, String> aliases() {
        Map<String, String> aliases = new LinkedHashMap<>();
        aliases.put("支付宝交易号", "tradeNo");
        aliases.put("trade_no", "tradeNo");
        aliases.put("商户订单号", "paymentNo");
        aliases.put("out_trade_no", "paymentNo");
        aliases.put("金额（元）", "amount");
        aliases.put("total_amount", "amount");
        aliases.put("服务费（元）", "feeAmount");
        aliases.put("service_fee", "feeAmount");
        aliases.put("商家实收（元）", "netAmount");
        aliases.put("net_amount", "netAmount");
        aliases.put("类型", "businessType");
        aliases.put("business_type", "businessType");
        aliases.put("交易状态", "tradeStatus");
        aliases.put("trade_status", "tradeStatus");
        return Map.copyOf(aliases);
    }

    private AlipaySandboxException schemaUnsupported() {
        return AlipaySandboxException.config(SCHEMA_UNSUPPORTED);
    }

    private AlipaySandboxException parseFailed() {
        return AlipaySandboxException.config(PARSE_FAILED);
    }
}
