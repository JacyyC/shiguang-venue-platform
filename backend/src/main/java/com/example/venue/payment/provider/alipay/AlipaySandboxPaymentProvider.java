package com.example.venue.payment.provider.alipay;

import com.alipay.api.AlipayApiException;
import com.alipay.api.AlipayClient;
import com.alipay.api.internal.util.AlipaySignature;
import com.alipay.api.request.AlipayDataDataserviceBillDownloadurlQueryRequest;
import com.alipay.api.request.AlipayTradeCloseRequest;
import com.alipay.api.request.AlipayTradePagePayRequest;
import com.alipay.api.request.AlipayTradeQueryRequest;
import com.alipay.api.response.AlipayDataDataserviceBillDownloadurlQueryResponse;
import com.alipay.api.response.AlipayTradeCloseResponse;
import com.alipay.api.response.AlipayTradeQueryResponse;
import com.example.venue.payment.dto.PaymentAction;
import com.example.venue.payment.dto.PaymentAttemptStatus;
import com.example.venue.payment.dto.PaymentCallbackCommand;
import com.example.venue.payment.dto.PaymentCallbackEnvelope;
import com.example.venue.payment.dto.PaymentCallbackResult;
import com.example.venue.payment.dto.PaymentCloseCommand;
import com.example.venue.payment.dto.PaymentCloseResult;
import com.example.venue.payment.dto.PaymentCreateCommand;
import com.example.venue.payment.dto.PaymentCreateResult;
import com.example.venue.payment.dto.ProviderStatementItem;
import com.example.venue.payment.dto.ProviderStatementQuery;
import com.example.venue.payment.dto.VerifiedPaymentCallback;
import com.example.venue.payment.provider.PaymentProvider;
import com.example.venue.payment.provider.PaymentProviderDescriptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class AlipaySandboxPaymentProvider implements PaymentProvider {
    public static final String PROVIDER_KEY = "payment.alipay.sandbox";
    public static final String PROVIDER_ENV = "SANDBOX";
    public static final String PROVIDER_NAME = "ALIPAY";
    public static final String CONFIG_VERSION = "ALIPAY_SANDBOX_V1";
    private static final String CURRENCY = "CNY";
    private static final String PAGE_PAY_CREATE_FAILED = "ALIPAY_PAGE_PAY_CREATE_FAILED";
    private static final String TRADE_CLOSE_FAILED = "ALIPAY_TRADE_CLOSE_FAILED";
    private static final String CALLBACK_ENVELOPE_INVALID = "ALIPAY_CALLBACK_ENVELOPE_INVALID";
    private static final String SIGNATURE_INVALID = "ALIPAY_SIGNATURE_INVALID";
    private static final String APP_ID_MISMATCH = "ALIPAY_APP_ID_MISMATCH";
    private static final String MERCHANT_MISMATCH = "ALIPAY_MERCHANT_MISMATCH";
    private static final String TRADE_QUERY_FAILED = "ALIPAY_TRADE_QUERY_FAILED";
    private static final String TRADE_QUERY_MISMATCH = "ALIPAY_TRADE_QUERY_MISMATCH";
    private static final String TRADE_STATUS_INVALID = "ALIPAY_TRADE_STATUS_INVALID";
    private static final String BILL_NOT_READY = "ALIPAY_BILL_NOT_READY";
    private static final String BILL_DOWNLOAD_REJECTED = "ALIPAY_BILL_DOWNLOAD_REJECTED";
    private static final String ALIPAY_SUCCESS_CODE = "10000";
    private static final String FORM_CONTENT_TYPE = "application/x-www-form-urlencoded";
    private static final String UTF_8 = StandardCharsets.UTF_8.name();
    private static final String RSA2 = "RSA2";
    private static final int MAX_SUBJECT_LENGTH = 128;
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final Set<String> REQUIRED_CALLBACK_FIELDS = Set.of(
        "sign", "sign_type", "charset", "app_id", "seller_id", "out_trade_no", "trade_no", "trade_status",
        "total_amount"
    );
    private static final Set<String> SETTLEABLE_STATUSES = Set.of("TRADE_SUCCESS", "TRADE_FINISHED");
    private static final Set<String> NON_SETTLEABLE_STATUSES = Set.of("WAIT_BUYER_PAY", "TRADE_CLOSED");

    private final AlipaySandboxClientFactory clientFactory;
    private final AlipaySandboxProperties properties;
    private final AlipayPagePayActionParser pagePayActionParser;
    private final AlipayBillDownloader billDownloader;
    private final AlipayBillParser billParser;
    private final Clock clock;

    public AlipaySandboxPaymentProvider(AlipaySandboxClientFactory clientFactory,
                                        AlipaySandboxProperties properties,
                                        AlipayPagePayActionParser pagePayActionParser) {
        this(clientFactory, properties, pagePayActionParser, new AlipayBillDownloader(), new AlipayBillParser(),
            Clock.system(SHANGHAI));
    }

    @Autowired
    public AlipaySandboxPaymentProvider(AlipaySandboxClientFactory clientFactory,
                                        AlipaySandboxProperties properties,
                                        AlipayPagePayActionParser pagePayActionParser,
                                        AlipayBillDownloader billDownloader,
                                        AlipayBillParser billParser) {
        this(clientFactory, properties, pagePayActionParser, billDownloader, billParser, Clock.system(SHANGHAI));
    }

    AlipaySandboxPaymentProvider(AlipaySandboxClientFactory clientFactory,
                                 AlipaySandboxProperties properties,
                                 AlipayPagePayActionParser pagePayActionParser,
                                 AlipayBillDownloader billDownloader,
                                 AlipayBillParser billParser,
                                 Clock clock) {
        this.clientFactory = clientFactory;
        this.properties = properties;
        this.pagePayActionParser = pagePayActionParser;
        this.billDownloader = billDownloader;
        this.billParser = billParser;
        this.clock = clock;
    }

    @Override
    public PaymentProviderDescriptor descriptor() {
        return new PaymentProviderDescriptor(
            PROVIDER_KEY, PROVIDER_ENV, PROVIDER_NAME, "Alipay Sandbox", CONFIG_VERSION, properties.isEnabled());
    }

    @Override
    public PaymentCreateResult createPayment(PaymentCreateCommand command) {
        try {
            BigDecimal amount = cnyAmount(command);
            AlipayTradePagePayRequest request = new AlipayTradePagePayRequest();
            request.setNotifyUrl(properties.getNotifyUrl());
            request.setReturnUrl(properties.getReturnUrl());
            request.setBizContent(pagePayBizContent(command.paymentNo(), amount, subject(command.orderNo())));
            String form = clientFactory.client().pageExecute(request, "POST").getBody();
            PaymentAction action = pagePayActionParser.parse(form, URI.create(properties.getGatewayUrl()));
            return new PaymentCreateResult(
                PROVIDER_ENV,
                PROVIDER_NAME,
                CONFIG_VERSION,
                PaymentAttemptStatus.PENDING.name(),
                amount,
                BigDecimal.ZERO.setScale(2),
                amount,
                CURRENCY,
                "{\"providerName\":\"ALIPAY\",\"operation\":\"PAGE_PAY\"}",
                "{\"providerName\":\"ALIPAY\",\"status\":\"PENDING\"}",
                action
            );
        } catch (AlipaySandboxException ex) {
            throw ex;
        } catch (Exception ex) {
            throw AlipaySandboxException.config(PAGE_PAY_CREATE_FAILED);
        }
    }

    @Override
    public PaymentCloseResult closePayment(PaymentCloseCommand command) {
        if (command == null || command.paymentNo() == null || command.paymentNo().isBlank()) {
            throw AlipaySandboxException.config(TRADE_CLOSE_FAILED);
        }
        try {
            AlipayTradeCloseRequest request = new AlipayTradeCloseRequest();
            request.setBizContent("{\"out_trade_no\":\"" + json(command.paymentNo()) + "\"}");
            AlipayTradeCloseResponse response = clientFactory.client().execute(request);
            if (response == null || !response.isSuccess() || !ALIPAY_SUCCESS_CODE.equals(response.getCode())) {
                throw AlipaySandboxException.config(TRADE_CLOSE_FAILED);
            }
            return new PaymentCloseResult(
                "CLOSED",
                command.paymentNo(),
                "{\"providerName\":\"ALIPAY\",\"operation\":\"TRADE_CLOSE\"}",
                "{\"providerName\":\"ALIPAY\",\"status\":\"CLOSED\"}"
            );
        } catch (AlipaySandboxException ex) {
            throw ex;
        } catch (AlipayApiException | RuntimeException ex) {
            throw AlipaySandboxException.config(TRADE_CLOSE_FAILED);
        }
    }

    @Override
    public PaymentCallbackResult handleCallback(PaymentCallbackCommand command) {
        throw new UnsupportedOperationException("Sandbox callback handling is not available");
    }

    @Override
    public VerifiedPaymentCallback verifyCallback(PaymentCallbackEnvelope envelope) {
        Map<String, String> parameters = singleCallbackParameters(envelope);
        requireRsa2Utf8(parameters);
        verifySignature(parameters);

        String paymentNo = parameters.get("out_trade_no");
        String tradeNo = parameters.get("trade_no");
        if (!matchesConfiguredAppId(parameters.get("app_id"))) {
            throw AlipaySandboxException.config(APP_ID_MISMATCH);
        }

        AlipayTradeQueryResponse queryResponse = queryMerchantTrade(paymentNo, tradeNo);
        if (!matchesQuery(queryResponse, paymentNo, tradeNo, parameters.get("total_amount"),
            parameters.get("trade_status"))) {
            throw AlipaySandboxException.config(TRADE_QUERY_MISMATCH);
        }

        String status = parameters.get("trade_status");
        if (!isKnownStatus(status)) {
            throw AlipaySandboxException.config(TRADE_STATUS_INVALID);
        }
        BigDecimal amount = strictCnyAmount(parameters.get("total_amount"));
        boolean settleable = SETTLEABLE_STATUSES.contains(status);
        String requestSummary = AlipayCallbackEnvelopeFactory.safeSummary(envelope);
        String responseSummary = "{\"merchantQueryMatched\":true,\"statusAccepted\":true,\"settleable\":"
            + settleable + "}";
        return new VerifiedPaymentCallback(
            paymentNo, tradeNo, status, settleable, amount, CURRENCY, requestSummary, responseSummary);
    }

    @Override
    public List<ProviderStatementItem> statement(ProviderStatementQuery query) {
        if (query == null || query.statementDate == null) {
            throw AlipaySandboxException.config(BILL_DOWNLOAD_REJECTED);
        }
        LocalDate today = LocalDate.now(clock.withZone(SHANGHAI));
        if (!query.statementDate.isBefore(today)) {
            throw AlipaySandboxException.config(BILL_NOT_READY);
        }
        try {
            AlipayDataDataserviceBillDownloadurlQueryRequest request =
                new AlipayDataDataserviceBillDownloadurlQueryRequest();
            request.setBizContent("{\"bill_type\":\"trade\",\"bill_date\":\""
                + query.statementDate + "\"}");
            AlipayDataDataserviceBillDownloadurlQueryResponse response = clientFactory.client().execute(request);
            if (response == null || !ALIPAY_SUCCESS_CODE.equals(response.getCode()) || !response.isSuccess()
                || response.getBillDownloadUrl() == null || response.getBillDownloadUrl().isBlank()) {
                throw AlipaySandboxException.config(BILL_DOWNLOAD_REJECTED);
            }
            return billParser.parse(billDownloader.download(URI.create(response.getBillDownloadUrl())));
        } catch (AlipaySandboxException ex) {
            throw ex;
        } catch (AlipayApiException | RuntimeException ex) {
            throw AlipaySandboxException.config(BILL_DOWNLOAD_REJECTED);
        }
    }

    private Map<String, String> singleCallbackParameters(PaymentCallbackEnvelope envelope) {
        if (envelope == null || !isFormContentType(envelope.contentType())
            || !UTF_8.equals(envelope.characterEncoding()) || envelope.formParameters() == null) {
            throw AlipaySandboxException.config(CALLBACK_ENVELOPE_INVALID);
        }
        Map<String, List<String>> formParameters = envelope.formParameters();
        Map<String, String> singleValues = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : formParameters.entrySet()) {
            String key = entry.getKey();
            List<String> values = entry.getValue();
            if (key == null || key.isEmpty() || values == null || values.size() != 1 || values.get(0) == null) {
                throw AlipaySandboxException.config(CALLBACK_ENVELOPE_INVALID);
            }
            singleValues.put(key, values.get(0));
        }
        for (String field : REQUIRED_CALLBACK_FIELDS) {
            if ("seller_id".equals(field)) {
                continue;
            }
            String value = singleValues.get(field);
            if (value == null || value.isBlank()) {
                throw AlipaySandboxException.config(CALLBACK_ENVELOPE_INVALID);
            }
        }
        String sellerId = singleValues.get("seller_id");
        if (sellerId == null || sellerId.isBlank()) {
            throw AlipaySandboxException.config(MERCHANT_MISMATCH);
        }
        return Map.copyOf(singleValues);
    }

    private void requireRsa2Utf8(Map<String, String> parameters) {
        if (!RSA2.equals(parameters.get("sign_type")) || !UTF_8.equals(parameters.get("charset"))) {
            throw AlipaySandboxException.config(CALLBACK_ENVELOPE_INVALID);
        }
    }

    private void verifySignature(Map<String, String> parameters) {
        try {
            if (!AlipaySignature.verifyV1(new LinkedHashMap<>(parameters), properties.getAlipayPublicKey(), UTF_8, RSA2)) {
                throw AlipaySandboxException.config(SIGNATURE_INVALID);
            }
        } catch (AlipaySandboxException ex) {
            throw ex;
        } catch (AlipayApiException | RuntimeException ex) {
            throw AlipaySandboxException.config(SIGNATURE_INVALID);
        }
    }

    private boolean matchesConfiguredAppId(String callbackAppId) {
        String configuredAppId = properties.getAppId();
        return configuredAppId != null && MessageDigest.isEqual(
            configuredAppId.getBytes(StandardCharsets.UTF_8), callbackAppId.getBytes(StandardCharsets.UTF_8));
    }

    private AlipayTradeQueryResponse queryMerchantTrade(String paymentNo, String tradeNo) {
        try {
            AlipayTradeQueryRequest request = new AlipayTradeQueryRequest();
            request.setBizContent("{\"out_trade_no\":\"" + json(paymentNo) + "\",\"trade_no\":\""
                + json(tradeNo) + "\"}");
            AlipayClient client = clientFactory.client();
            AlipayTradeQueryResponse response = client.execute(request);
            if (response == null || !ALIPAY_SUCCESS_CODE.equals(response.getCode()) || !response.isSuccess()) {
                throw AlipaySandboxException.config(TRADE_QUERY_FAILED);
            }
            return response;
        } catch (AlipaySandboxException ex) {
            throw ex;
        } catch (AlipayApiException | RuntimeException ex) {
            throw AlipaySandboxException.config(TRADE_QUERY_FAILED);
        }
    }

    private boolean matchesQuery(AlipayTradeQueryResponse response, String paymentNo, String tradeNo, String amount,
                                 String status) {
        return paymentNo.equals(response.getOutTradeNo())
            && tradeNo.equals(response.getTradeNo())
            && amount.equals(response.getTotalAmount())
            && status.equals(response.getTradeStatus());
    }

    private boolean isKnownStatus(String status) {
        return SETTLEABLE_STATUSES.contains(status) || NON_SETTLEABLE_STATUSES.contains(status);
    }

    private BigDecimal strictCnyAmount(String rawAmount) {
        if (rawAmount == null || !rawAmount.matches("[0-9]+\\.[0-9]{2}")) {
            throw AlipaySandboxException.config(CALLBACK_ENVELOPE_INVALID);
        }
        try {
            BigDecimal amount = new BigDecimal(rawAmount).setScale(2, RoundingMode.UNNECESSARY);
            if (amount.signum() <= 0) {
                throw AlipaySandboxException.config(CALLBACK_ENVELOPE_INVALID);
            }
            return amount;
        } catch (AlipaySandboxException ex) {
            throw ex;
        } catch (ArithmeticException ex) {
            throw AlipaySandboxException.config(CALLBACK_ENVELOPE_INVALID);
        }
    }

    private boolean isFormContentType(String contentType) {
        if (contentType == null) {
            return false;
        }
        int separator = contentType.indexOf(';');
        String mediaType = (separator < 0 ? contentType : contentType.substring(0, separator)).trim();
        return FORM_CONTENT_TYPE.equalsIgnoreCase(mediaType);
    }

    private BigDecimal cnyAmount(PaymentCreateCommand command) {
        if (command == null || command.amount() == null || !CURRENCY.equals(command.currency())) {
            throw AlipaySandboxException.config(PAGE_PAY_CREATE_FAILED);
        }
        try {
            BigDecimal amount = command.amount().setScale(2, RoundingMode.UNNECESSARY);
            if (amount.signum() <= 0 || command.paymentNo() == null || command.paymentNo().isBlank()) {
                throw AlipaySandboxException.config(PAGE_PAY_CREATE_FAILED);
            }
            return amount;
        } catch (ArithmeticException ex) {
            throw AlipaySandboxException.config(PAGE_PAY_CREATE_FAILED);
        }
    }

    private String pagePayBizContent(String paymentNo, BigDecimal amount, String subject) {
        return "{\"product_code\":\"FAST_INSTANT_TRADE_PAY\",\"out_trade_no\":\"" + json(paymentNo)
            + "\",\"total_amount\":\"" + amount.toPlainString() + "\",\"subject\":\"" + json(subject)
            + "\"}";
    }

    private String subject(String orderNo) {
        String value = orderNo == null || orderNo.isBlank() ? "payment" : orderNo.trim();
        String subject = "Venue reservation " + value;
        return subject.length() <= MAX_SUBJECT_LENGTH ? subject : subject.substring(0, MAX_SUBJECT_LENGTH);
    }

    private String json(String value) {
        StringBuilder escaped = new StringBuilder();
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            switch (current) {
                case '"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
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
        return escaped.toString();
    }
}
