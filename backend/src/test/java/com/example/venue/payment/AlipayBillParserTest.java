package com.example.venue.payment.provider.alipay;

import com.example.venue.payment.dto.ProviderStatementItem;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AlipayBillParserTest {
    private static final Charset GBK = Charset.forName("GBK");

    @Test
    void parsesGbkChineseAliasesReorderedHeadersQuotedFieldsAndSummaryRows() throws Exception {
        String csv = "类型,商家实收（元）,支付宝交易号,交易状态,金额（元）,商户订单号,服务费（元）\n"
            + "交易,99.40,TRADE-1,TRADE_SUCCESS,100.00,\"ORDER,with\nnewline\",0.60\n"
            + "合计,,,,,,\n";
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("交易明细.csv", csv.getBytes(GBK));
        entries.put("summary.csv", "this summary entry is intentionally not a bill table".getBytes(GBK));

        List<ProviderStatementItem> items = new AlipayBillParser().parse(zip(entries));

        assertThat(items).hasSize(1);
        ProviderStatementItem item = items.get(0);
        assertThat(item.businessType).isEqualTo("PAYMENT");
        assertThat(item.externalRef).isEqualTo("TRADE-1");
        assertThat(item.transactionId).isEqualTo("TRADE-1");
        assertThat(item.orderNo).isEqualTo("ORDER,with\nnewline");
        assertThat(item.amount).isEqualByComparingTo("100.00");
        assertThat(item.feeAmount).isEqualByComparingTo("0.60");
        assertThat(item.netAmount).isEqualByComparingTo("99.40");
        assertThat(item.dataGap).isNull();
    }

    @Test
    void parsesEnglishAliasesAndKeepsRefundAndVoidRowsAsExplicitNonPayments() throws Exception {
        String csv = "trade_no,out_trade_no,total_amount,net_amount,business_type,trade_status\n"
            + "TRADE-1,ORDER-1,100.00,100.00,PAYMENT,TRADE_SUCCESS\n"
            + "TRADE-2,ORDER-2,10.00,10.00,REFUND,REFUND_SUCCESS\n"
            + "TRADE-3,ORDER-3,10.00,10.00,VOID,VOIDED\n"
            + "TRADE-4,ORDER-4,10.00,10.00,退款,REFUND_SUCCESS\n"
            + "TRADE-5,ORDER-5,10.00,10.00,撤销,VOIDED\n";

        List<ProviderStatementItem> items = new AlipayBillParser().parse(zipCsv("trade.csv", csv));

        assertThat(items).hasSize(5);
        assertThat(items.get(0).businessType).isEqualTo("PAYMENT");
        assertThat(items.get(0).feeAmount).isEqualByComparingTo("0.00");
        assertThat(items.get(0).dataGap).isEqualTo("FEE_AMOUNT_MISSING");
        assertThat(items.subList(1, 5)).allSatisfy(item -> {
            assertThat(item.businessType).isEqualTo("REFUND_OR_VOID");
            assertThat(item.externalRef).startsWith("TRADE-");
        });
    }

    @Test
    void fallsBackToAmountMinusFeeWhenNetAmountHeaderIsMissing() throws Exception {
        String csv = "trade_no,out_trade_no,total_amount,service_fee,business_type,trade_status\n"
            + "TRADE-1,ORDER-1,100.00,0.60,PAYMENT,TRADE_SUCCESS\n";

        List<ProviderStatementItem> items = new AlipayBillParser().parse(zipCsv("trade.csv", csv));

        assertThat(items).singleElement().satisfies(item -> {
            assertThat(item.amount).isEqualByComparingTo("100.00");
            assertThat(item.feeAmount).isEqualByComparingTo("0.60");
            assertThat(item.netAmount).isEqualByComparingTo("99.40");
        });
    }

    @Test
    void fallsBackToAmountMinusFeeWhenNetAmountCellIsBlank() throws Exception {
        String csv = "trade_no,out_trade_no,total_amount,service_fee,net_amount,business_type,trade_status\n"
            + "TRADE-1,ORDER-1,100.00,0.60,,PAYMENT,TRADE_SUCCESS\n";

        List<ProviderStatementItem> items = new AlipayBillParser().parse(zipCsv("trade.csv", csv));

        assertThat(items).singleElement().satisfies(item -> assertThat(item.netAmount).isEqualByComparingTo("99.40"));
    }

    @Test
    void rejectsUnknownOrMissingHeadersValuesAndMalformedAmountsWithoutReturningPartialRows() throws Exception {
        String validHeader = "trade_no,out_trade_no,total_amount,service_fee,net_amount,business_type,trade_status\n";
        List<byte[]> invalidArchives = List.of(
            zipCsv("trade.csv", validHeader + "TRADE-1,ORDER-1,100.0,0.60,99.40,PAYMENT,TRADE_SUCCESS\n"),
            zipCsv("trade.csv", validHeader + "TRADE-1,ORDER-1,100.00,0.60,99.40,CHARGEBACK,TRADE_SUCCESS\n"),
            zipCsv("trade.csv", "trade_no,out_trade_no,total_amount,service_fee,net_amount,unknown_type,trade_status\n"
                + "TRADE-1,ORDER-1,100.00,0.60,99.40,PAYMENT,TRADE_SUCCESS\n"),
            zipCsv("trade.csv", "trade_no,out_trade_no,total_amount,service_fee,net_amount,business_type\n"
                + "TRADE-1,ORDER-1,100.00,0.60,99.40,PAYMENT\n"));

        for (byte[] archive : invalidArchives) {
            assertBillRejected(archive);
        }
    }

    @Test
    void rejectsAnInconsistentCsvRowWithoutReturningPartialResults() throws Exception {
        String header = "trade_no,out_trade_no,total_amount,service_fee,net_amount,business_type,trade_status\n";
        String validRow = "TRADE-1,ORDER-1,100.00,0.60,99.40,PAYMENT,TRADE_SUCCESS\n";
        List<byte[]> archives = List.of(
            zipCsv("trade.csv", header + validRow
                + "TRADE-2,ORDER-2,100.00,0.60,99.40,PAYMENT,TRADE_SUCCESS,unexpected-column\n"),
            zipCsv("trade.csv", header + validRow + "TRADE-3,ORDER-3,100.00,0.60,99.40,PAYMENT\n"));

        for (byte[] archive : archives) {
            assertBillRejected(archive);
        }
    }

    @Test
    void rejectsUnsafeOrUnsupportedZipEntriesAsAnAllOrNothingBill() throws Exception {
        String csv = "trade_no,out_trade_no,total_amount,service_fee,net_amount,business_type,trade_status\n"
            + "TRADE-1,ORDER-1,100.00,0.60,99.40,PAYMENT,TRADE_SUCCESS\n";
        List<byte[]> invalidArchives = List.of(
            zipWithDirectory(),
            zip(Map.of("../trade.csv", csv.getBytes(GBK))),
            zip(Map.of("nested.zip", new byte[]{1, 2, 3})),
            zip(Map.of("trade.txt", csv.getBytes(GBK))),
            zip(fiveEntries()),
            Arrays.copyOf(zipCsv("trade.csv", csv), 12),
            overFiftyMegabytesZip());

        for (byte[] archive : invalidArchives) {
            assertBillRejected(archive);
        }
    }

    @Test
    void parsesRealAlipayBillWithCommentLinesUnknownColumnsTrailingDelimiterAndGbkChinese() throws Exception {
        // 真实支付宝业务明细 CSV：以 # 注释行开头、表头约 25 列含本解析器不需要的未知列、
        // 每行尾部带尾随分隔符（commons-csv 会解析出空表头）、GBK 编码、含中文商品名称与备注。
        // 已知列用真实账单的中文名（支付宝交易号/商户订单号/金额（元）/服务费（元）/商家实收（元）/类型/交易状态）。
        String csv = "#支付宝业务明细查询\n"
            + "#账号：2088xxxxxxxxxxxx\n"
            + "#起始时间：2026-07-01 00:00:00    终止时间：2026-07-27 23:59:59\n"
            + "#---------------------------------交易记录明细列表------------------------------------\n"
            + "支付宝交易号,商户订单号,业务流水号,商品名称,创建时间,订单金额（元）,金额（元）,服务费（元）,商家实收（元）,类型,交易状态,备注,\n"
            + "TRADE-20260727001,ORDER-20260727001,FLOW-001,乒乓球场地预约（周末）,2026-07-27 09:00:00,100.00,100.00,0.60,99.40,交易,TRADE_SUCCESS,用户到场核销,\n"
            + "TRADE-20260727002,ORDER-20260727002,FLOW-002,改期重订差价补缴,2026-07-27 10:30:00,30.00,30.00,0.00,30.00,退款,REFUND_SUCCESS,改期后退旧单,\n";

        List<ProviderStatementItem> items = new AlipayBillParser().parse(zipCsv("业务明细.csv", csv));

        assertThat(items).hasSize(2);
        ProviderStatementItem payment = items.get(0);
        assertThat(payment.businessType).isEqualTo("PAYMENT");
        assertThat(payment.externalRef).isEqualTo("TRADE-20260727001");
        assertThat(payment.orderNo).isEqualTo("ORDER-20260727001");
        assertThat(payment.amount).isEqualByComparingTo("100.00");
        assertThat(payment.feeAmount).isEqualByComparingTo("0.60");
        assertThat(payment.netAmount).isEqualByComparingTo("99.40");
        assertThat(payment.dataGap).isNull();
        ProviderStatementItem refund = items.get(1);
        assertThat(refund.businessType).isEqualTo("REFUND_OR_VOID");
        assertThat(refund.externalRef).isEqualTo("TRADE-20260727002");
        assertThat(refund.orderNo).isEqualTo("ORDER-20260727002");
        assertThat(refund.amount).isEqualByComparingTo("30.00");
        assertThat(refund.feeAmount).isEqualByComparingTo("0.00");
        assertThat(refund.netAmount).isEqualByComparingTo("30.00");
        assertThat(refund.dataGap).isNull();
    }

    @Test
    void skipsHashCommentLinesBeforeHeaderRecord() throws Exception {
        // 支付宝账单头部以 # 开头的业务说明行必须被跳过，不能当作表头参与列映射。
        String csv = "#支付宝业务明细查询\n"
            + "#导出时间：2026-07-27 10:00:00\n"
            + "trade_no,out_trade_no,total_amount,service_fee,net_amount,business_type,trade_status\n"
            + "TRADE-1,ORDER-1,100.00,0.60,99.40,PAYMENT,TRADE_SUCCESS\n";

        List<ProviderStatementItem> items = new AlipayBillParser().parse(zipCsv("trade.csv", csv));

        assertThat(items).hasSize(1);
        assertThat(items.get(0).externalRef).isEqualTo("TRADE-1");
        assertThat(items.get(0).businessType).isEqualTo("PAYMENT");
    }

    @Test
    void toleratesUnknownColumnsAndOnlyKeepsKnownAliases() throws Exception {
        // 真实账单含本解析器不需要的列（商品名称、创建时间、订单金额（元） 等），
        // 未知列只跳过不整单拒绝，必需列齐全即生成对账项。
        String csv = "trade_no,out_trade_no,total_amount,商品名称,创建时间,service_fee,net_amount,订单金额（元）,business_type,trade_status\n"
            + "TRADE-1,ORDER-1,100.00,乒乓球场地,2026-07-27 09:00:00,0.60,99.40,100.00,PAYMENT,TRADE_SUCCESS\n";

        List<ProviderStatementItem> items = new AlipayBillParser().parse(zipCsv("trade.csv", csv));

        assertThat(items).hasSize(1);
        ProviderStatementItem item = items.get(0);
        assertThat(item.externalRef).isEqualTo("TRADE-1");
        assertThat(item.orderNo).isEqualTo("ORDER-1");
        assertThat(item.amount).isEqualByComparingTo("100.00");
        assertThat(item.feeAmount).isEqualByComparingTo("0.60");
        assertThat(item.netAmount).isEqualByComparingTo("99.40");
    }

    @Test
    void skipsTrailingDelimiterEmptyHeaderCell() throws Exception {
        // 支付宝账单表头行尾部带逗号，commons-csv 会解析出空表头 ""，
        // 必须跳过空表头避免触发"未知列拒绝"或干扰列索引；数据行同样带尾随逗号保持列数一致。
        String csv = "trade_no,out_trade_no,total_amount,service_fee,net_amount,business_type,trade_status,\n"
            + "TRADE-1,ORDER-1,100.00,0.60,99.40,PAYMENT,TRADE_SUCCESS,\n"
            + "TRADE-2,ORDER-2,10.00,0.00,10.00,REFUND,REFUND_SUCCESS,\n";

        List<ProviderStatementItem> items = new AlipayBillParser().parse(zipCsv("trade.csv", csv));

        assertThat(items).hasSize(2);
        assertThat(items.get(0).businessType).isEqualTo("PAYMENT");
        assertThat(items.get(1).businessType).isEqualTo("REFUND_OR_VOID");
    }

    @Test
    void parsesGbkChineseCellsWhenCommentMarkerEnabled() throws Exception {
        // 启用 # 注释标记后仍要保证 GBK 解码正确，避免 # 标记解析逻辑误改编码处理。
        // 用中文表头（支付宝交易号 等）+ tradeNo/orderNo 字段本身含中文覆盖 GBK 解码全链路。
        String csv = "#支付宝业务明细查询\n"
            + "支付宝交易号,商户订单号,金额（元）,服务费（元）,商家实收（元）,类型,交易状态\n"
            + "TRADE-中文-1,ORDER-中文-1,100.00,0.60,99.40,交易,TRADE_SUCCESS\n";

        List<ProviderStatementItem> items = new AlipayBillParser().parse(zipCsv("业务明细.csv", csv));

        assertThat(items).singleElement().satisfies(item -> {
            assertThat(item.externalRef).isEqualTo("TRADE-中文-1");
            assertThat(item.orderNo).isEqualTo("ORDER-中文-1");
            assertThat(item.amount).isEqualByComparingTo("100.00");
            assertThat(item.businessType).isEqualTo("PAYMENT");
        });
    }

    private static void assertBillRejected(byte[] archive) {
        assertThatThrownBy(() -> new AlipayBillParser().parse(archive))
            .isInstanceOf(AlipaySandboxException.class)
            .extracting(error -> ((AlipaySandboxException) error).getCode())
            .isIn("ALIPAY_BILL_SCHEMA_UNSUPPORTED", "ALIPAY_BILL_PARSE_FAILED");
    }

    private static byte[] zipCsv(String name, String csv) throws IOException {
        return zip(Map.of(name, csv.getBytes(GBK)));
    }

    private static byte[] zip(Map<String, byte[]> entries) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream output = new ZipOutputStream(bytes)) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                output.putNextEntry(new ZipEntry(entry.getKey()));
                output.write(entry.getValue());
                output.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    private static byte[] zipWithDirectory() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream output = new ZipOutputStream(bytes)) {
            output.putNextEntry(new ZipEntry("reports/"));
            output.closeEntry();
        }
        return bytes.toByteArray();
    }

    private static Map<String, byte[]> fiveEntries() {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        for (int index = 0; index < 5; index++) {
            entries.put("summary-" + index + ".csv", new byte[0]);
        }
        return entries;
    }

    private static byte[] overFiftyMegabytesZip() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream output = new ZipOutputStream(bytes)) {
            output.putNextEntry(new ZipEntry("trade.csv"));
            byte[] zeros = new byte[8 * 1024];
            for (int written = 0; written <= 50 * 1024 * 1024; written += zeros.length) {
                output.write(zeros);
            }
            output.closeEntry();
        }
        return bytes.toByteArray();
    }
}
