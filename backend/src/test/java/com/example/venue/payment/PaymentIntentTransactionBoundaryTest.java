package com.example.venue.payment;

import com.example.venue.payment.service.PaymentIntentPersistenceService;
import com.example.venue.payment.service.PaymentLocalTransactionService;
import com.example.venue.payment.service.PaymentService;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

class PaymentIntentTransactionBoundaryTest {
    @Test
    void providerOrchestrationIsNotTransactionalWhilePersistenceStepsAreShortTransactions() throws Exception {
        Method createIntent = PaymentService.class.getMethod("createPaymentIntent", Long.class);
        Method providerCallback = PaymentService.class.getMethod(
            "handleProviderCallback", String.class, com.example.venue.order.dto.PaymentCallbackRequest.class);
        Method prepare = PaymentIntentPersistenceService.class.getMethod(
            "prepare", Long.class, Long.class,
            com.example.venue.payment.provider.PaymentProviderDescriptor.class, String.class, String.class);
        Method complete = PaymentIntentPersistenceService.class.getMethod(
            "markCreateSucceeded", com.example.venue.payment.entity.PaymentAttempt.class,
            com.example.venue.payment.dto.PaymentCreateResult.class);

        assertThat(createIntent.getAnnotation(Transactional.class)).isNull();
        assertThat(providerCallback.getAnnotation(Transactional.class)).isNull();
        assertThat(prepare.getAnnotation(Transactional.class)).isNotNull();
        assertThat(complete.getAnnotation(Transactional.class)).isNotNull();
    }

    @Test
    void rawProviderVerificationIsOutsideTheShortLocalSettlementTransaction() throws Exception {
        Method rawCallback = PaymentService.class.getMethod(
            "handleRawProviderCallback", String.class, com.example.venue.payment.dto.PaymentCallbackEnvelope.class);
        Method localSettlement = PaymentLocalTransactionService.class.getMethod("execute", Runnable.class);

        assertThat(rawCallback.getAnnotation(Transactional.class)).isNull();
        assertThat(localSettlement.getAnnotation(Transactional.class)).isNotNull();
    }
}
