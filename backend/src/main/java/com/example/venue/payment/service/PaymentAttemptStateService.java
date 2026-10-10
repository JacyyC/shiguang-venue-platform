package com.example.venue.payment.service;

import com.example.venue.payment.entity.PaymentAttempt;
import com.example.venue.payment.mapper.PaymentAttemptMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Service
public class PaymentAttemptStateService {
    private static final BigDecimal ZERO_AMOUNT = new BigDecimal("0.00");

    private final PaymentAttemptMapper paymentAttemptMapper;
    private final TransactionTemplate requiresNew;

    public PaymentAttemptStateService(PaymentAttemptMapper paymentAttemptMapper,
                                      PlatformTransactionManager txManager) {
        this.paymentAttemptMapper = paymentAttemptMapper;
        this.requiresNew = new TransactionTemplate(txManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public void markTerminalFailureInRequiresNew(PaymentAttempt attempt, String status, String failureCode,
                                                 String failureMessage, String externalRef, String summaryJson) {
        requiresNew.executeWithoutResult(transactionStatus -> {
            attempt.status = status;
            attempt.externalRef = externalRef;
            attempt.failureCode = failureCode;
            attempt.failureMessage = failureMessage;
            attempt.paidAmount = ZERO_AMOUNT;
            attempt.feeAmount = ZERO_AMOUNT;
            attempt.netAmount = ZERO_AMOUNT;
            attempt.requestSummaryJson = summaryJson;
            attempt.responseSummaryJson = "{\"providerName\":\"DEV_PAYMENT\",\"status\":\"" + status
                + "\",\"failureCode\":\"" + failureCode + "\"}";
            attempt.updateTime = LocalDateTime.now();
            paymentAttemptMapper.updateById(attempt);
        });
    }
}
