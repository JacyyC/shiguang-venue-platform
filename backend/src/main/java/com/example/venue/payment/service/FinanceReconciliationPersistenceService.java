package com.example.venue.payment.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.example.venue.order.entity.PaymentRecord;
import com.example.venue.order.mapper.PaymentRecordMapper;
import com.example.venue.payment.dto.ProviderStatementItem;
import com.example.venue.payment.dto.ReconciliationRunResponse;
import com.example.venue.payment.entity.FinanceReconciliationItem;
import com.example.venue.payment.mapper.FinanceReconciliationItemMapper;
import com.example.venue.payment.provider.PaymentProviderDescriptor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class FinanceReconciliationPersistenceService {
    private static final BigDecimal ZERO_AMOUNT = new BigDecimal("0.00");
    private static final String BUSINESS_TYPE_PAYMENT = "PAYMENT";
    private static final DateTimeFormatter RECONCILE_DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final PaymentRecordMapper paymentRecordMapper;
    private final FinanceReconciliationItemMapper reconciliationMapper;

    public FinanceReconciliationPersistenceService(PaymentRecordMapper paymentRecordMapper,
                                                    FinanceReconciliationItemMapper reconciliationMapper) {
        this.paymentRecordMapper = paymentRecordMapper;
        this.reconciliationMapper = reconciliationMapper;
    }

    @Transactional
    public ReconciliationRunResponse persist(LocalDate date, PaymentProviderDescriptor descriptor,
                                             List<ProviderStatementItem.ImmutableSnapshot> statementItems) {
        List<ProviderStatementItem.ImmutableSnapshot> paymentItems = paymentItems(statementItems);
        List<PaymentRecord> systemRecords = successPaymentRecords(
            date, descriptor.providerEnv(), descriptor.providerName());
        Map<String, ProviderStatementItem.ImmutableSnapshot> providerByKey = providerIndex(paymentItems);
        Set<ProviderStatementItem.ImmutableSnapshot> usedProviderItems =
            Collections.newSetFromMap(new IdentityHashMap<>());
        ReconciliationRunResponse response = new ReconciliationRunResponse(
            date, descriptor.providerEnv(), descriptor.providerName());
        for (PaymentRecord record : systemRecords) {
            ProviderStatementItem.ImmutableSnapshot providerItem = matchProvider(record, providerByKey, usedProviderItems);
            FinanceReconciliationItem item = providerItem == null
                ? missingProviderItem(date, descriptor, record)
                : comparedItem(date, descriptor, record, providerItem);
            if (providerItem != null) {
                usedProviderItems.add(providerItem);
            }
            // A26-P3-10：仅在实际写入时计数，避免账单重复 trade_no 行把 MATCHED 静默改写为 MISSING_SYSTEM
            // 时响应计数与落库不一致。
            if (persistItem(item)) {
                response.record(item.status);
            }
        }
        for (ProviderStatementItem.ImmutableSnapshot providerItem : paymentItems) {
            if (usedProviderItems.contains(providerItem)) {
                continue;
            }
            FinanceReconciliationItem item = missingSystemItem(date, descriptor, providerItem);
            if (persistItem(item)) {
                response.record(item.status);
            }
        }
        return response;
    }

    private List<ProviderStatementItem.ImmutableSnapshot> paymentItems(
        List<ProviderStatementItem.ImmutableSnapshot> statementItems) {
        if (statementItems == null || statementItems.isEmpty()) {
            return List.of();
        }
        return statementItems.stream()
            .filter(item -> item != null && BUSINESS_TYPE_PAYMENT.equals(item.businessType()))
            .toList();
    }

    private List<PaymentRecord> successPaymentRecords(LocalDate date, String providerEnv, String providerName) {
        LocalDateTime start = date.atStartOfDay();
        LocalDateTime end = date.plusDays(1).atStartOfDay();
        return paymentRecordMapper.selectList(new QueryWrapper<PaymentRecord>()
            .eq("provider_env", providerEnv)
            .eq("provider_name", providerName)
            .eq("status", "SUCCESS")
            .ge("pay_time", start)
            .lt("pay_time", end));
    }

    private Map<String, ProviderStatementItem.ImmutableSnapshot> providerIndex(
        List<ProviderStatementItem.ImmutableSnapshot> providerItems) {
        Map<String, ProviderStatementItem.ImmutableSnapshot> index = new LinkedHashMap<>();
        for (ProviderStatementItem.ImmutableSnapshot item : providerItems) {
            for (String key : providerKeys(item)) {
                index.putIfAbsent(key, item);
            }
        }
        return index;
    }

    private ProviderStatementItem.ImmutableSnapshot matchProvider(PaymentRecord record,
                                                                    Map<String, ProviderStatementItem.ImmutableSnapshot> providerByKey,
                                                                    Set<ProviderStatementItem.ImmutableSnapshot> usedProviderItems) {
        for (String key : systemKeys(record)) {
            ProviderStatementItem.ImmutableSnapshot item = providerByKey.get(key);
            if (item != null && !usedProviderItems.contains(item)) {
                return item;
            }
        }
        return null;
    }

    private FinanceReconciliationItem comparedItem(LocalDate date, PaymentProviderDescriptor descriptor,
                                                   PaymentRecord record,
                                                   ProviderStatementItem.ImmutableSnapshot providerItem) {
        BigDecimal systemAmount = money(record.payAmount);
        BigDecimal providerAmount = money(providerItem.amount());
        FinanceReconciliationItem item = baseItem(date, descriptor, BUSINESS_TYPE_PAYMENT,
            reconcileRef(record), systemAmount, providerAmount);
        item.orderId = record.orderId;
        item.paymentRecordId = record.id;
        item.paymentAttemptId = record.paymentAttemptId;
        item.feeAmount = money(providerItem.feeAmount());
        item.netAmount = money(providerItem.netAmount());
        item.status = systemAmount.compareTo(providerAmount) == 0 ? "MATCHED" : "DIFFERENCE";
        item.evidenceJson = evidenceJson(record, providerItem);
        return item;
    }

    private FinanceReconciliationItem missingProviderItem(LocalDate date, PaymentProviderDescriptor descriptor,
                                                          PaymentRecord record) {
        BigDecimal systemAmount = money(record.payAmount);
        FinanceReconciliationItem item = baseItem(date, descriptor, BUSINESS_TYPE_PAYMENT,
            reconcileRef(record), systemAmount, ZERO_AMOUNT);
        item.orderId = record.orderId;
        item.paymentRecordId = record.id;
        item.paymentAttemptId = record.paymentAttemptId;
        item.feeAmount = money(record.feeAmount);
        item.netAmount = money(record.netAmount);
        item.status = "MISSING_PROVIDER";
        item.evidenceJson = evidenceJson(record, null);
        return item;
    }

    private FinanceReconciliationItem missingSystemItem(LocalDate date, PaymentProviderDescriptor descriptor,
                                                        ProviderStatementItem.ImmutableSnapshot providerItem) {
        BigDecimal providerAmount = money(providerItem.amount());
        FinanceReconciliationItem item = baseItem(date, descriptor, BUSINESS_TYPE_PAYMENT,
            reconcileRef(providerItem), ZERO_AMOUNT, providerAmount);
        item.feeAmount = money(providerItem.feeAmount());
        item.netAmount = money(providerItem.netAmount());
        item.status = "MISSING_SYSTEM";
        item.evidenceJson = evidenceJson(null, providerItem);
        return item;
    }

    private FinanceReconciliationItem baseItem(LocalDate date, PaymentProviderDescriptor descriptor,
                                               String businessType, String externalRef,
                                               BigDecimal systemAmount, BigDecimal providerAmount) {
        FinanceReconciliationItem item = new FinanceReconciliationItem();
        item.reconcileDate = date;
        item.businessType = businessType;
        item.providerEnv = descriptor.providerEnv();
        item.providerName = descriptor.providerName();
        item.externalRef = externalRef;
        item.systemAmount = systemAmount;
        item.providerAmount = providerAmount;
        item.differenceAmount = systemAmount.subtract(providerAmount).setScale(2, RoundingMode.HALF_UP);
        item.reconcileNo = reconcileNo(date, businessType, externalRef);
        item.metadataJson = "{\"providerEnv\":\"" + descriptor.providerEnv()
            + "\",\"providerName\":\"" + descriptor.providerName()
            + "\",\"configVersion\":\"" + descriptor.configVersion() + "\"}";
        item.createTime = LocalDateTime.now();
        item.updateTime = item.createTime;
        item.deleted = 0;
        return item;
    }

    /**
     * A26-P3-10 / A26-P3-11：写入对账项。
     * <p>已存在时（含 ADJUSTED 与其他状态）一律不再覆盖，避免账单重复 trade_no 行把 MATCHED 静默改写为
     * MISSING_SYSTEM 导致响应计数与落库不一致；也避免"对账刷新"语义下被并发覆盖真实结果。
     * <p>采用 select-then-insert，并发同日期对账可能在 select 后撞 unique key；
     * 此时 catch {@link DuplicateKeyException} 重查后视为已存在，跳过本条而不抛 500 丢弃已算结果。
     * <p>返回 true 表示新增成功（调用方计入响应计数），false 表示已存在跳过（不计入）。
     */
    private boolean persistItem(FinanceReconciliationItem item) {
        FinanceReconciliationItem existing = reconciliationMapper.findByReconcileNo(item.reconcileNo);
        if (existing != null) {
            return false;
        }
        try {
            reconciliationMapper.insert(item);
            return true;
        } catch (DuplicateKeyException ex) {
            // A26-P3-11：并发同日期对账已写入该 reconcile_no，重查确认后跳过本条，已算结果不丢弃。
            FinanceReconciliationItem concurrent = reconciliationMapper.findByReconcileNo(item.reconcileNo);
            if (concurrent != null) {
                return false;
            }
            throw ex;
        }
    }

    private List<String> systemKeys(PaymentRecord record) {
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        addIfPresent(keys, record.externalRef);
        addIfPresent(keys, record.transactionId);
        if (keys.isEmpty()) {
            addIfPresent(keys, record.orderNo);
        }
        return new ArrayList<>(keys);
    }

    private List<String> providerKeys(ProviderStatementItem.ImmutableSnapshot item) {
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        addIfPresent(keys, item.externalRef());
        addIfPresent(keys, item.transactionId());
        if (keys.isEmpty()) {
            addIfPresent(keys, item.orderNo());
        }
        return new ArrayList<>(keys);
    }

    private void addIfPresent(Set<String> values, String value) {
        if (value != null && !value.isBlank()) {
            values.add(value);
        }
    }

    private String reconcileNo(LocalDate date, String businessType, String externalRef) {
        String prefix = "REC-" + RECONCILE_DATE_FORMATTER.format(date) + "-" + businessType + "-";
        String reference = externalRef == null ? "" : externalRef;
        int remaining = 64 - prefix.length();
        if (reference.length() <= remaining) {
            return prefix + reference;
        }
        return prefix + sha256Hex(reference).substring(0, remaining);
    }

    private String sha256Hex(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte item : digest) {
                hex.append(String.format("%02x", item));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    private String reconcileRef(PaymentRecord record) {
        return firstNonBlank(record.externalRef, record.transactionId, record.orderNo);
    }

    private String reconcileRef(ProviderStatementItem.ImmutableSnapshot item) {
        return firstNonBlank(item.externalRef(), item.transactionId(), item.orderNo());
    }

    private String firstNonBlank(String first, String second, String third) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        if (second != null && !second.isBlank()) {
            return second;
        }
        return third;
    }

    private BigDecimal money(BigDecimal amount) {
        return (amount == null ? ZERO_AMOUNT : amount).setScale(2, RoundingMode.HALF_UP);
    }

    private String evidenceJson(PaymentRecord record, ProviderStatementItem.ImmutableSnapshot providerItem) {
        return "{\"systemRecordPresent\":" + (record != null)
            + ",\"providerRecordPresent\":" + (providerItem != null) + "}";
    }
}
