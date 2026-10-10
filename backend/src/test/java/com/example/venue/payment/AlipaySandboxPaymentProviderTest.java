package com.example.venue.payment;

import com.alipay.api.AlipayClient;
import com.alipay.api.AlipayApiException;
import com.alipay.api.internal.util.AlipaySignature;
import com.alipay.api.request.AlipayDataDataserviceBillDownloadurlQueryRequest;
import com.alipay.api.request.AlipayTradePagePayRequest;
import com.alipay.api.request.AlipayTradeQueryRequest;
import com.alipay.api.response.AlipayDataDataserviceBillDownloadurlQueryResponse;
import com.alipay.api.response.AlipayTradePagePayResponse;
import com.alipay.api.response.AlipayTradeQueryResponse;
import com.example.venue.payment.dto.PaymentCallbackEnvelope;
import com.example.venue.payment.dto.PaymentAction;
import com.example.venue.payment.dto.PaymentCreateCommand;
import com.example.venue.payment.dto.PaymentCreateResult;
import com.example.venue.payment.dto.ProviderStatementItem;
import com.example.venue.payment.dto.ProviderStatementQuery;
import com.example.venue.payment.dto.VerifiedPaymentCallback;
import com.example.venue.payment.provider.PaymentProviderDescriptor;
import com.example.venue.payment.provider.alipay.AlipayBillDownloader;
import com.example.venue.payment.provider.alipay.AlipayBillParser;
import com.example.venue.payment.provider.alipay.AlipayPagePayActionParser;
import com.example.venue.payment.provider.alipay.AlipaySandboxClientFactory;
import com.example.venue.payment.provider.alipay.AlipaySandboxException;
import com.example.venue.payment.provider.alipay.AlipaySandboxPaymentProvider;
import com.example.venue.payment.provider.alipay.AlipaySandboxProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.lang.reflect.Constructor;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.mock;

@ExtendWith(MockitoExtension.class)
class AlipaySandboxPaymentProviderTest {
    private static final String PAYMENT_NO = "PAY001";
    private static final String TRADE_NO = "202607120000001";
    private static String appId;
    private static String sellerId;
    private static String appPrivateKey;
    private static String alipayPublicKey;

    @Mock
    private AlipaySandboxClientFactory clientFactory;
    @Mock
    private AlipayClient client;
    @Mock
    private AlipayTradePagePayResponse pagePayResponse;
    @Mock
    private AlipayPagePayActionParser parser;

    private AlipaySandboxProperties properties;
    private AlipaySandboxPaymentProvider provider;

    @BeforeAll
    static void generateRuntimeOnlyRsaMaterial() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair keyPair = generator.generateKeyPair();
        appId = "sandbox-app-" + UUID.randomUUID();
        sellerId = "sandbox-merchant-" + UUID.randomUUID();
        appPrivateKey = Base64.getEncoder().encodeToString(keyPair.getPrivate().getEncoded());
        alipayPublicKey = Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded());
    }

    @BeforeEach
    void setUp() {
        properties = new AlipaySandboxProperties();
        properties.setEnabled(true);
        properties.setAppId(appId);
        properties.setAppPrivateKey(appPrivateKey);
        properties.setAlipayPublicKey(alipayPublicKey);
        properties.setNotifyUrl("https://payments.example.test/api/payments/callback/alipay/sandbox");
        properties.setReturnUrl("http://127.0.0.1/me/payments/alipay/sandbox/return");
        provider = new AlipaySandboxPaymentProvider(clientFactory, properties, parser);
    }

    @Test
    void exposesTheFixedSandboxDescriptorAndEnabledState() {
        PaymentProviderDescriptor descriptor = provider.descriptor();

        assertThat(descriptor.providerKey()).isEqualTo("payment.alipay.sandbox");
        assertThat(descriptor.providerEnv()).isEqualTo("SANDBOX");
        assertThat(descriptor.providerName()).isEqualTo("ALIPAY");
        assertThat(descriptor.configVersion()).isEqualTo("ALIPAY_SANDBOX_V1");
        assertThat(descriptor.enabled()).isTrue();

        properties.setEnabled(false);
        assertThat(provider.descriptor().enabled()).isFalse();
    }

    @Test
    void createsAParsedSdkPagePayActionAndOnlySafeSummaries() throws Exception {
        PaymentAction action = new PaymentAction("SUBMIT_FORM", "POST",
            "https://openapi-sandbox.dl.alipaydev.com/gateway.do?opaque=ephemeral-action-token",
            Map.of("biz_content", "{\"out_trade_no\":\"PAY001\"}"));
        when(clientFactory.client()).thenReturn(client);
        when(client.pageExecute(any(AlipayTradePagePayRequest.class), eq("POST"))).thenReturn(pagePayResponse);
        when(pagePayResponse.getBody()).thenReturn("sdk-form-only-in-memory");
        when(parser.parse(eq("sdk-form-only-in-memory"), eq(URI.create(properties.getGatewayUrl())))).thenReturn(action);

        PaymentCreateResult result = provider.createPayment(new PaymentCreateCommand(
            "PAY001", "ORD001", new BigDecimal("100.00"), "CNY", "payment-intent-11"));

        ArgumentCaptor<AlipayTradePagePayRequest> requestCaptor = ArgumentCaptor.forClass(AlipayTradePagePayRequest.class);
        verify(client).pageExecute(requestCaptor.capture(), eq("POST"));
        AlipayTradePagePayRequest request = requestCaptor.getValue();
        assertThat(request.getBizContent())
            .contains("\"product_code\":\"FAST_INSTANT_TRADE_PAY\"")
            .contains("\"out_trade_no\":\"PAY001\"")
            .contains("\"total_amount\":\"100.00\"");
        assertThat(request.getNotifyUrl()).isEqualTo(properties.getNotifyUrl());
        assertThat(request.getReturnUrl()).isEqualTo(properties.getReturnUrl());
        assertThat(result.paymentAction()).isEqualTo(action);
        assertThat(result.status()).isEqualTo("PENDING");
        assertThat(result.payableAmount()).isEqualByComparingTo("100.00");
        assertThat(result.feeAmount()).isEqualByComparingTo("0.00");
        assertThat(result.netAmount()).isEqualByComparingTo("100.00");
        assertThat(result.currency()).isEqualTo("CNY");
        assertThat(result.requestSummaryJson()).doesNotContain("ephemeral-action-token", "biz_content");
        assertThat(result.responseSummaryJson()).doesNotContain("ephemeral-action-token", "biz_content");
    }

    @Test
    void preservesTheParserFailureCode() throws Exception {
        when(clientFactory.client()).thenReturn(client);
        when(client.pageExecute(any(AlipayTradePagePayRequest.class), eq("POST"))).thenReturn(pagePayResponse);
        when(pagePayResponse.getBody()).thenReturn("sdk-form-only-in-memory");
        when(parser.parse(eq("sdk-form-only-in-memory"), any(URI.class)))
            .thenThrow(AlipaySandboxException.config("ALIPAY_PAYMENT_ACTION_INVALID"));

        assertThatThrownBy(() -> provider.createPayment(new PaymentCreateCommand(
            "PAY001", "ORD001", new BigDecimal("100.00"), "CNY", "payment-intent-11")))
            .isInstanceOf(AlipaySandboxException.class)
            .hasMessage("ALIPAY_PAYMENT_ACTION_INVALID");
    }

    @Test
    void mapsSdkFailuresToTheStableCreateCode() throws Exception {
        when(clientFactory.client()).thenReturn(client);
        when(client.pageExecute(any(AlipayTradePagePayRequest.class), eq("POST")))
            .thenThrow(new IllegalStateException("raw-sdk-error-must-not-escape"));

        assertThatThrownBy(() -> provider.createPayment(new PaymentCreateCommand(
            "PAY001", "ORD001", new BigDecimal("100.00"), "CNY", "payment-intent-11")))
            .isInstanceOf(AlipaySandboxException.class)
            .hasMessage("ALIPAY_PAGE_PAY_CREATE_FAILED")
            .hasMessageNotContaining("raw-sdk-error-must-not-escape");
    }

    @Test
    void serializesEscapedPaymentAndOrderNumbersAsValidBizContentJson() throws Exception {
        PaymentAction action = new PaymentAction("SUBMIT_FORM", "POST",
            "https://openapi-sandbox.dl.alipaydev.com/gateway.do?opaque=ephemeral-action-token",
            Map.of("biz_content", "{}"));
        when(clientFactory.client()).thenReturn(client);
        when(client.pageExecute(any(AlipayTradePagePayRequest.class), eq("POST"))).thenReturn(pagePayResponse);
        when(pagePayResponse.getBody()).thenReturn("sdk-form-only-in-memory");
        when(parser.parse(eq("sdk-form-only-in-memory"), eq(URI.create(properties.getGatewayUrl())))).thenReturn(action);

        provider.createPayment(new PaymentCreateCommand(
            "PAY\"001", "ORD\\001\"", new BigDecimal("100.00"), "CNY", "payment-intent-11"));

        ArgumentCaptor<AlipayTradePagePayRequest> requestCaptor = ArgumentCaptor.forClass(AlipayTradePagePayRequest.class);
        verify(client).pageExecute(requestCaptor.capture(), eq("POST"));
        JsonNode bizContent = new ObjectMapper().readTree(requestCaptor.getValue().getBizContent());
        assertThat(bizContent.get("out_trade_no").asText()).isEqualTo("PAY\"001");
        assertThat(bizContent.get("subject").asText()).isEqualTo("Venue reservation ORD\\001\"");
    }

    @Test
    void requestsAPastShanghaiStatementWithTheFixedSdkMethodAndBillParameters() throws Exception {
        AlipayBillDownloader billDownloader = mock(AlipayBillDownloader.class);
        AlipayBillParser billParser = mock(AlipayBillParser.class);
        provider = statementProvider(billDownloader, billParser, fixedShanghaiClock());
        LocalDate statementDate = LocalDate.of(2026, 7, 13);
        URI shortLivedUrl = URI.create("https://fixture-download.alipay.com/trade.zip?token=temporary");
        byte[] archive = new byte[]{1, 2, 3};
        List<ProviderStatementItem> expected = List.of();
        when(clientFactory.client()).thenReturn(client);
        when(client.execute(any(AlipayDataDataserviceBillDownloadurlQueryRequest.class)))
            .thenReturn(successfulBillResponse(shortLivedUrl.toString()));
        when(billDownloader.download(shortLivedUrl)).thenReturn(archive);
        when(billParser.parse(archive)).thenReturn(expected);

        assertThat(provider.statement(statementQuery(statementDate))).isSameAs(expected);

        ArgumentCaptor<AlipayDataDataserviceBillDownloadurlQueryRequest> requestCaptor =
            ArgumentCaptor.forClass(AlipayDataDataserviceBillDownloadurlQueryRequest.class);
        verify(client).execute(requestCaptor.capture());
        assertThat(requestCaptor.getValue().getApiMethodName())
            .isEqualTo("alipay.data.dataservice.bill.downloadurl.query");
        assertThat(requestCaptor.getValue().getBizContent())
            .isEqualTo("{\"bill_type\":\"trade\",\"bill_date\":\"2026-07-13\"}");
        verify(billDownloader).download(shortLivedUrl);
        verify(billParser).parse(archive);
    }

    @ParameterizedTest
    @MethodSource("notReadyStatementDates")
    void rejectsTodayAndFutureStatementsBeforeCallingTheSdk(LocalDate statementDate) throws Exception {
        AlipayBillDownloader billDownloader = mock(AlipayBillDownloader.class);
        AlipayBillParser billParser = mock(AlipayBillParser.class);
        provider = statementProvider(billDownloader, billParser, fixedShanghaiClock());

        assertStableFailure(() -> provider.statement(statementQuery(statementDate)), "ALIPAY_BILL_NOT_READY");

        verifyNoInteractions(clientFactory, billDownloader, billParser);
    }

    @Test
    void mapsShortLivedBillDownloadFailuresWithoutLeakingTheUrlOrInvokingTheParser() throws Exception {
        AlipayBillDownloader billDownloader = mock(AlipayBillDownloader.class);
        AlipayBillParser billParser = mock(AlipayBillParser.class);
        provider = statementProvider(billDownloader, billParser, fixedShanghaiClock());
        String shortLivedUrl = "https://fixture-download.alipay.com/trade.zip?token=temporary-only";
        when(clientFactory.client()).thenReturn(client);
        when(client.execute(any(AlipayDataDataserviceBillDownloadurlQueryRequest.class)))
            .thenReturn(successfulBillResponse(shortLivedUrl));
        when(billDownloader.download(URI.create(shortLivedUrl))).thenThrow(new IllegalStateException(shortLivedUrl));

        assertThatThrownBy(() -> provider.statement(statementQuery(LocalDate.of(2026, 7, 13))))
            .isInstanceOf(AlipaySandboxException.class)
            .hasMessage("ALIPAY_BILL_DOWNLOAD_REJECTED")
            .hasMessageNotContaining(shortLivedUrl);

        verifyNoInteractions(billParser);
    }

    @Test
    void verifiesAValidRsa2CallbackAndMerchantAuthenticatedTradeQuery() throws Exception {
        when(clientFactory.client()).thenReturn(client);
        when(client.execute(any(AlipayTradeQueryRequest.class)))
            .thenReturn(queryResponse(PAYMENT_NO, TRADE_NO, "100.00", "TRADE_SUCCESS"));

        PaymentCallbackEnvelope envelope = callback("TRADE_SUCCESS", "100.00", appId, true);
        VerifiedPaymentCallback verified = provider.verifyCallback(envelope);

        verify(client).execute(org.mockito.ArgumentMatchers.argThat((AlipayTradeQueryRequest request) -> {
            String body = request.getBizContent();
            return body.contains("\"out_trade_no\":\"" + PAYMENT_NO + "\"")
                && body.contains("\"trade_no\":\"" + TRADE_NO + "\"");
        }));
        assertThat(verified.paymentNo()).isEqualTo(PAYMENT_NO);
        assertThat(verified.externalRef()).isEqualTo(TRADE_NO);
        assertThat(verified.providerTradeStatus()).isEqualTo("TRADE_SUCCESS");
        assertThat(verified.settleable()).isTrue();
        assertThat(verified.paidAmount()).isEqualByComparingTo("100.00");
        assertThat(verified.currency()).isEqualTo("CNY");
        assertThat(verified.requestSummaryJson()).doesNotContain("sign", appId, sellerId, TRADE_NO);
        assertThat(verified.responseSummaryJson()).doesNotContain("sign", appId, sellerId, TRADE_NO);
        assertThat(envelope.formParameters()).containsKeys("sign", "sign_type");
    }

    @Test
    void rejectsAChangedProofBeforeQueryingTheMerchant() throws Exception {
        PaymentCallbackEnvelope valid = callback("TRADE_SUCCESS", "100.00", appId, true);
        Map<String, List<String>> changed = new LinkedHashMap<>(valid.formParameters());
        changed.put("sign", List.of("proof-" + UUID.randomUUID()));

        assertStableFailure(() -> provider.verifyCallback(envelope(changed)), "ALIPAY_SIGNATURE_INVALID");

        verifyNoInteractions(clientFactory);
    }

    @Test
    void rejectsAValidProofForAnotherApplicationBeforeQueryingTheMerchant() throws Exception {
        assertStableFailure(
            () -> provider.verifyCallback(callback("TRADE_SUCCESS", "100.00", "other-app-" + UUID.randomUUID(), true)),
            "ALIPAY_APP_ID_MISMATCH");

        verifyNoInteractions(clientFactory);
    }

    @Test
    void rejectsAFormWithoutSellerIdBeforeQueryingTheMerchant() throws Exception {
        assertStableFailure(() -> provider.verifyCallback(callback("TRADE_SUCCESS", "100.00", appId, false)),
            "ALIPAY_MERCHANT_MISMATCH");

        verifyNoInteractions(clientFactory);
    }

    @ParameterizedTest
    @MethodSource("unsupportedSignatureMetadata")
    void rejectsNonRsa2OrNonUtf8MetadataBeforeVerifyingOrQuerying(String field, String value) throws Exception {
        PaymentCallbackEnvelope valid = callback("TRADE_SUCCESS", "100.00", appId, true);
        Map<String, List<String>> changed = new LinkedHashMap<>(valid.formParameters());
        changed.put(field, List.of(value));

        assertStableFailure(() -> provider.verifyCallback(envelope(changed)), "ALIPAY_CALLBACK_ENVELOPE_INVALID");

        verifyNoInteractions(clientFactory);
    }

    @Test
    void rejectsAnyMultiValuedParameterBeforeBuildingTheSdkVerificationMap() throws Exception {
        PaymentCallbackEnvelope valid = callback("TRADE_SUCCESS", "100.00", appId, true);
        Map<String, List<String>> changed = new LinkedHashMap<>(valid.formParameters());
        changed.put("opaque", List.of("first", "second"));

        assertStableFailure(() -> provider.verifyCallback(envelope(changed)), "ALIPAY_CALLBACK_ENVELOPE_INVALID");

        verifyNoInteractions(clientFactory);
    }

    @ParameterizedTest
    @MethodSource("missingRequiredEnvelopeFields")
    void rejectsMissingRequiredEnvelopeFieldsBeforeVerificationOrQuery(String field) throws Exception {
        PaymentCallbackEnvelope valid = callback("TRADE_SUCCESS", "100.00", appId, true);
        Map<String, List<String>> changed = new LinkedHashMap<>(valid.formParameters());
        changed.remove(field);

        assertStableFailure(() -> provider.verifyCallback(envelope(changed)), "ALIPAY_CALLBACK_ENVELOPE_INVALID");

        verifyNoInteractions(clientFactory);
    }

    @Test
    void mapsAQueryApiExceptionToTheStableCodeWithoutLeakingItsMessage() throws Exception {
        String sdkMessage = "sdk-" + UUID.randomUUID();
        when(clientFactory.client()).thenReturn(client);
        when(client.execute(any(AlipayTradeQueryRequest.class))).thenThrow(new AlipayApiException(sdkMessage));

        assertThatThrownBy(() -> provider.verifyCallback(callback("TRADE_SUCCESS", "100.00", appId, true)))
            .isInstanceOf(AlipaySandboxException.class)
            .hasMessage("ALIPAY_TRADE_QUERY_FAILED")
            .hasMessageNotContaining(sdkMessage);
    }

    @Test
    void mapsAnUnsuccessfulQueryResponseToTheStableCode() throws Exception {
        AlipayTradeQueryResponse response = new AlipayTradeQueryResponse();
        response.setCode("40004");
        when(clientFactory.client()).thenReturn(client);
        when(client.execute(any(AlipayTradeQueryRequest.class))).thenReturn(response);

        assertStableFailure(() -> provider.verifyCallback(callback("TRADE_SUCCESS", "100.00", appId, true)),
            "ALIPAY_TRADE_QUERY_FAILED");
    }

    @Test
    void verifiesUtf8BytesForNonAsciiSignedCallbackFields() throws Exception {
        when(clientFactory.client()).thenReturn(client);
        when(client.execute(any(AlipayTradeQueryRequest.class)))
            .thenReturn(queryResponse(PAYMENT_NO, TRADE_NO, "100.00", "TRADE_SUCCESS"));
        String nonAsciiValue = "\u6d4b\u8bd5";

        VerifiedPaymentCallback verified = provider.verifyCallback(callback(
            "TRADE_SUCCESS", "100.00", appId, true, Map.of("passback_params", nonAsciiValue)));

        assertThat(verified.settleable()).isTrue();
    }

    @ParameterizedTest
    @MethodSource("queryMismatches")
    void rejectsEveryMerchantQueryFactMismatch(String field, String value) throws Exception {
        when(clientFactory.client()).thenReturn(client);
        AlipayTradeQueryResponse response = queryResponse(PAYMENT_NO, TRADE_NO, "100.00", "TRADE_SUCCESS");
        switch (field) {
            case "out_trade_no" -> response.setOutTradeNo(value);
            case "trade_no" -> response.setTradeNo(value);
            case "total_amount" -> response.setTotalAmount(value);
            case "trade_status" -> response.setTradeStatus(value);
            default -> throw new IllegalArgumentException("Unexpected query field");
        }
        when(client.execute(any(AlipayTradeQueryRequest.class))).thenReturn(response);

        assertStableFailure(() -> provider.verifyCallback(callback("TRADE_SUCCESS", "100.00", appId, true)),
            "ALIPAY_TRADE_QUERY_MISMATCH");
    }

    @ParameterizedTest
    @MethodSource("acceptedStatuses")
    void acceptsOnlyTheKnownAlipayStatuses(String status, boolean settleable) throws Exception {
        when(clientFactory.client()).thenReturn(client);
        when(client.execute(any(AlipayTradeQueryRequest.class)))
            .thenReturn(queryResponse(PAYMENT_NO, TRADE_NO, "100.00", status));

        VerifiedPaymentCallback verified = provider.verifyCallback(callback(status, "100.00", appId, true));

        assertThat(verified.providerTradeStatus()).isEqualTo(status);
        assertThat(verified.settleable()).isEqualTo(settleable);
    }

    @Test
    void rejectsAnUnknownStatusAfterTheMerchantQueryMatches() throws Exception {
        String unknownStatus = "TRADE_UNKNOWN";
        when(clientFactory.client()).thenReturn(client);
        when(client.execute(any(AlipayTradeQueryRequest.class)))
            .thenReturn(queryResponse(PAYMENT_NO, TRADE_NO, "100.00", unknownStatus));

        assertStableFailure(() -> provider.verifyCallback(callback(unknownStatus, "100.00", appId, true)),
            "ALIPAY_TRADE_STATUS_INVALID");
    }

    @ParameterizedTest
    @MethodSource("invalidAmounts")
    void rejectsNonCanonicalCnyAmounts(String amount) throws Exception {
        when(clientFactory.client()).thenReturn(client);
        when(client.execute(any(AlipayTradeQueryRequest.class)))
            .thenReturn(queryResponse(PAYMENT_NO, TRADE_NO, amount, "TRADE_SUCCESS"));

        assertStableFailure(() -> provider.verifyCallback(callback("TRADE_SUCCESS", amount, appId, true)),
            "ALIPAY_CALLBACK_ENVELOPE_INVALID");
    }

    private PaymentCallbackEnvelope callback(String status, String amount, String callbackAppId, boolean includeSeller)
        throws Exception {
        return callback(status, amount, callbackAppId, includeSeller, Map.of());
    }

    private PaymentCallbackEnvelope callback(String status, String amount, String callbackAppId, boolean includeSeller,
                                             Map<String, String> additionalSignedValues) throws Exception {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("charset", "UTF-8");
        values.put("app_id", callbackAppId);
        if (includeSeller) {
            values.put("seller_id", sellerId);
        }
        values.put("out_trade_no", PAYMENT_NO);
        values.put("trade_no", TRADE_NO);
        values.put("total_amount", amount);
        values.put("trade_status", status);
        values.putAll(additionalSignedValues);
        String proof = AlipaySignature.sign(values, appPrivateKey, StandardCharsets.UTF_8.name(), "RSA2");
        values.put("sign_type", "RSA2");
        values.put("sign", proof);

        Map<String, List<String>> form = new LinkedHashMap<>();
        values.forEach((key, value) -> form.put(key, List.of(value)));
        return envelope(form);
    }

    private PaymentCallbackEnvelope envelope(Map<String, List<String>> values) {
        return new PaymentCallbackEnvelope(
            "application/x-www-form-urlencoded", StandardCharsets.UTF_8.name(), Map.copyOf(values));
    }

    private AlipayTradeQueryResponse queryResponse(String paymentNo, String tradeNo, String amount, String status) {
        AlipayTradeQueryResponse response = new AlipayTradeQueryResponse();
        response.setCode("10000");
        response.setOutTradeNo(paymentNo);
        response.setTradeNo(tradeNo);
        response.setTotalAmount(amount);
        response.setTradeStatus(status);
        return response;
    }

    private AlipaySandboxPaymentProvider statementProvider(AlipayBillDownloader billDownloader,
                                                            AlipayBillParser billParser, Clock clock) throws Exception {
        Constructor<AlipaySandboxPaymentProvider> constructor = AlipaySandboxPaymentProvider.class
            .getDeclaredConstructor(AlipaySandboxClientFactory.class, AlipaySandboxProperties.class,
                AlipayPagePayActionParser.class, AlipayBillDownloader.class, AlipayBillParser.class, Clock.class);
        constructor.setAccessible(true);
        return constructor.newInstance(clientFactory, properties, parser, billDownloader, billParser, clock);
    }

    private static Clock fixedShanghaiClock() {
        return Clock.fixed(Instant.parse("2026-07-14T04:00:00Z"), ZoneId.of("UTC"));
    }

    private static ProviderStatementQuery statementQuery(LocalDate statementDate) {
        return new ProviderStatementQuery(statementDate, "SANDBOX", "ALIPAY", List.of());
    }

    private static AlipayDataDataserviceBillDownloadurlQueryResponse successfulBillResponse(String billDownloadUrl) {
        AlipayDataDataserviceBillDownloadurlQueryResponse response =
            new AlipayDataDataserviceBillDownloadurlQueryResponse();
        response.setCode("10000");
        response.setBillDownloadUrl(billDownloadUrl);
        return response;
    }

    private void assertStableFailure(org.assertj.core.api.ThrowableAssert.ThrowingCallable callback, String code) {
        assertThatThrownBy(callback)
            .isInstanceOf(AlipaySandboxException.class)
            .hasMessage(code);
    }

    private static Stream<Arguments> queryMismatches() {
        return Stream.of(
            Arguments.of("out_trade_no", "OTHER_PAYMENT"),
            Arguments.of("trade_no", "OTHER_TRADE"),
            Arguments.of("total_amount", "99.00"),
            Arguments.of("trade_status", "TRADE_CLOSED")
        );
    }

    private static Stream<Arguments> acceptedStatuses() {
        return Stream.of(
            Arguments.of("TRADE_SUCCESS", true),
            Arguments.of("TRADE_FINISHED", true),
            Arguments.of("WAIT_BUYER_PAY", false),
            Arguments.of("TRADE_CLOSED", false)
        );
    }

    private static Stream<Arguments> unsupportedSignatureMetadata() {
        return Stream.of(
            Arguments.of("sign_type", "RSA"),
            Arguments.of("charset", "GBK")
        );
    }

    private static Stream<String> missingRequiredEnvelopeFields() {
        return Stream.of("sign", "sign_type", "charset", "app_id", "out_trade_no", "trade_no", "trade_status",
            "total_amount");
    }

    private static Stream<String> invalidAmounts() {
        return Stream.of("1E2", "1,00", " 100.00", "-100.00", "0.00", "100.000");
    }

    private static Stream<Arguments> notReadyStatementDates() {
        return Stream.of(
            Arguments.of(LocalDate.of(2026, 7, 14)),
            Arguments.of(LocalDate.of(2026, 7, 15))
        );
    }
}
