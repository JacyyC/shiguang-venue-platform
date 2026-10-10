package com.example.venue.payment.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.example.venue.payment.dto.PaymentAttemptStatus;
import com.example.venue.payment.dto.PaymentAction;
import com.example.venue.payment.dto.PaymentCallbackCommand;
import com.example.venue.payment.dto.PaymentCallbackResult;
import com.example.venue.payment.dto.PaymentCallbackStatus;
import com.example.venue.payment.dto.PaymentCloseCommand;
import com.example.venue.payment.dto.PaymentCloseResult;
import com.example.venue.payment.dto.PaymentCreateCommand;
import com.example.venue.payment.dto.PaymentCreateResult;
import com.example.venue.payment.dto.PaymentProviderOperationType;
import com.example.venue.payment.dto.ProviderStatementItem;
import com.example.venue.payment.dto.ProviderStatementQuery;
import com.example.venue.payment.entity.PaymentProviderOperationLog;
import com.example.venue.payment.mapper.PaymentProviderOperationLogMapper;
import com.example.venue.payment.provider.PaymentProvider;
import com.example.venue.payment.provider.PaymentProviderDescriptor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Component
public class DevPaymentProvider implements PaymentProvider {
    public static final String PROVIDER_KEY = "payment.dev";
    public static final String PROVIDER_ENV = "DEV";
    public static final String PROVIDER_NAME = "DEV_PAYMENT";
    public static final String CONFIG_VERSION = "DEV_PAYMENT_V1";
    public static final String CURRENCY = "CNY";

    private static final BigDecimal FEE_RATE = new BigDecimal("0.0060");

    private final PaymentProviderOperationLogMapper operationLogMapper;
    private final ObjectMapper objectMapper;

    public DevPaymentProvider() {
        this(null, new ObjectMapper().findAndRegisterModules());
    }

    @Autowired
    public DevPaymentProvider(PaymentProviderOperationLogMapper operationLogMapper,
                               ObjectMapper objectMapper) {
        this.operationLogMapper = operationLogMapper;
        this.objectMapper = objectMapper;
    }

    @Override
    public PaymentProviderDescriptor descriptor() {
        return new PaymentProviderDescriptor(
            PROVIDER_KEY, PROVIDER_ENV, PROVIDER_NAME, "DEV PaymentProvider", CONFIG_VERSION, true);
    }

    @Override
    public PaymentCreateResult createPayment(PaymentCreateCommand command) {
        BigDecimal amount = money(command.amount());
        BigDecimal feeAmount = fee(amount);
        BigDecimal netAmount = amount.subtract(feeAmount).setScale(2, RoundingMode.HALF_UP);
        return new PaymentCreateResult(
            PROVIDER_ENV,
            PROVIDER_NAME,
            CONFIG_VERSION,
            PaymentAttemptStatus.PENDING.name(),
            amount,
            feeAmount,
            netAmount,
            normalizeCurrency(command.currency()),
            paymentSummary(command.paymentNo(), command.orderNo(), amount, normalizeCurrency(command.currency())),
            "{\"providerName\":\"DEV_PAYMENT\",\"status\":\"PENDING\"}",
            new PaymentAction("DEV_SIMULATION", null, null, Map.of())
        );
    }

    @Override
    public PaymentCloseResult closePayment(PaymentCloseCommand command) {
        String paymentNo = command == null ? null : command.paymentNo();
        String orderNo = command == null ? null : command.orderNo();
        ObjectNode request = objectMapper.createObjectNode();
        request.put("paymentNo", paymentNo);
        request.put("orderNo", orderNo);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("providerName", PROVIDER_NAME);
        response.put("status", "CLOSED");
        return new PaymentCloseResult("CLOSED", "dev-close-" + paymentNo, request.toString(), response.toString());
    }

    @Override
    public PaymentCallbackResult handleCallback(PaymentCallbackCommand command) {
        BigDecimal amount = money(command.amount());
        BigDecimal feeAmount = fee(amount);
        BigDecimal netAmount = amount.subtract(feeAmount).setScale(2, RoundingMode.HALF_UP);
        return new PaymentCallbackResult(
            PaymentCallbackStatus.SUCCESS.name(),
            command.externalRef(),
            amount,
            feeAmount,
            netAmount,
            callbackSummary(command.paymentNo(), command.orderNo(), command.externalRef(), amount),
            callbackResponseSummary(command.paymentNo(), command.orderNo(), command.externalRef(), amount,
                feeAmount, netAmount)
        );
    }

    @Override
    public List<ProviderStatementItem> statement(ProviderStatementQuery query) {
        if (query == null
            || !PROVIDER_ENV.equals(query.providerEnv)
            || !PROVIDER_NAME.equals(query.providerName)
            || operationLogMapper == null) {
            return List.of();
        }
        LocalDateTime start = query.statementDate.atStartOfDay();
        LocalDateTime end = query.statementDate.plusDays(1).atStartOfDay();
        // A26-P3-07：迟到成功支付写的 operation status 是 ACKNOWLEDGED（非 SUCCESS），
        // 仅取 SUCCESS 会让对账产生 MISSING_PROVIDER 假差异；这里同时包含两个终态成功状态。
        return operationLogMapper.selectList(new QueryWrapper<PaymentProviderOperationLog>()
                .eq("provider_env", query.providerEnv)
                .eq("provider_name", query.providerName)
                .eq("operation_type", PaymentProviderOperationType.HANDLE_CALLBACK.name())
                .in("status", "SUCCESS", "ACKNOWLEDGED")
                .ge("create_time", start)
                .lt("create_time", end)
                .orderByAsc("id"))
            .stream()
            .map(this::statementItemFromOperation)
            .filter(Objects::nonNull)
            .toList();
    }

    private BigDecimal fee(BigDecimal amount) {
        return amount.multiply(FEE_RATE).setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal money(BigDecimal amount) {
        return (amount == null ? BigDecimal.ZERO : amount).setScale(2, RoundingMode.HALF_UP);
    }

    private String normalizeCurrency(String currency) {
        return currency == null || currency.isBlank() ? CURRENCY : currency;
    }

    private String paymentSummary(String paymentNo, String orderNo, BigDecimal amount, String currency) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("paymentNo", paymentNo);
        node.put("orderNo", orderNo);
        node.put("amount", amount.stripTrailingZeros().toPlainString());
        node.put("currency", currency);
        return node.toString();
    }

    private String callbackSummary(String paymentNo, String orderNo, String externalRef, BigDecimal amount) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("paymentNo", paymentNo);
        node.put("orderNo", orderNo);
        node.put("externalRef", externalRef);
        node.put("amount", amount.stripTrailingZeros().toPlainString());
        return node.toString();
    }

    private String callbackResponseSummary(String paymentNo, String orderNo, String externalRef, BigDecimal paidAmount,
                                           BigDecimal feeAmount, BigDecimal netAmount) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("providerName", PROVIDER_NAME);
        node.put("status", "SUCCESS");
        node.put("paymentNo", paymentNo);
        node.put("orderNo", orderNo);
        node.put("externalRef", externalRef);
        node.put("paidAmount", money(paidAmount));
        node.put("feeAmount", money(feeAmount));
        node.put("netAmount", money(netAmount));
        node.put("currency", CURRENCY);
        return node.toString();
    }

    private ProviderStatementItem statementItemFromOperation(PaymentProviderOperationLog log) {
        JsonNode response = parse(log.responseSummaryJson);
        String externalRef = firstNonBlank(text(response, "externalRef"), log.externalRef);
        String orderNo = text(response, "orderNo");
        BigDecimal amount = decimal(response, "paidAmount", decimal(response, "amount", null));
        if (externalRef == null || amount == null) {
            return null;
        }
        BigDecimal feeAmount = decimal(response, "feeAmount", fee(amount));
        BigDecimal netAmount = decimal(response, "netAmount", amount.subtract(feeAmount));
        return ProviderStatementItem.payment(externalRef, orderNo, money(amount), money(feeAmount), money(netAmount));
    }

    private JsonNode parse(String json) {
        try {
            return objectMapper.readTree(json == null || json.isBlank() ? "{}" : json);
        } catch (Exception ex) {
            return objectMapper.createObjectNode();
        }
    }

    private String text(JsonNode node, String fieldName) {
        JsonNode value = node == null ? null : node.get(fieldName);
        if (value == null || value.isNull()) {
            return null;
        }
        String text = value.asText();
        return text == null || text.isBlank() ? null : text;
    }

    private BigDecimal decimal(JsonNode node, String fieldName, BigDecimal fallback) {
        String value = text(node, fieldName);
        if (value == null) {
            return fallback;
        }
        try {
            return money(new BigDecimal(value));
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private String firstNonBlank(String first, String second) {
        return first == null || first.isBlank() ? second : first;
    }
}
