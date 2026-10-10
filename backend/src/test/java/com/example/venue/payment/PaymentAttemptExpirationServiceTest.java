package com.example.venue.payment;

import com.example.venue.order.entity.BizOrder;
import com.example.venue.payment.dto.PaymentAttemptStatus;
import com.example.venue.payment.dto.PaymentCloseCommand;
import com.example.venue.payment.dto.PaymentCloseResult;
import com.example.venue.payment.entity.PaymentAttempt;
import com.example.venue.payment.entity.PaymentProviderOperationLog;
import com.example.venue.payment.mapper.PaymentAttemptMapper;
import com.example.venue.payment.provider.PaymentProvider;
import com.example.venue.payment.provider.PaymentProviderDescriptor;
import com.example.venue.payment.provider.PaymentProviderEvidenceMirror;
import com.example.venue.payment.provider.PaymentProviderProperties;
import com.example.venue.payment.provider.PaymentProviderRouter;
import com.example.venue.payment.service.PaymentAttemptExpirationService;
import com.example.venue.payment.service.PaymentCallbackLogService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentAttemptExpirationServiceTest {
    @Mock
    private PaymentAttemptMapper paymentAttemptMapper;
    @Mock
    private PaymentCallbackLogService callbackLogService;
    @Mock
    private PaymentProviderEvidenceMirror paymentProviderEvidenceMirror;
    @Mock
    private PaymentProvider provider;

    @Test
    void expiryTerminatesLocalAttemptBeforeIssuingProviderCloseOutsideTheOrderFlow() {
        PaymentProviderDescriptor descriptor = new PaymentProviderDescriptor(
            "payment.test", "SANDBOX", "TEST_PAYMENT", "Test Payment", "TEST_V1", true);
        when(provider.descriptor()).thenReturn(descriptor);
        when(provider.closePayment(any(PaymentCloseCommand.class))).thenReturn(new PaymentCloseResult(
            "CLOSED", "close-ref", "{\"operation\":\"CLOSE\"}", "{\"status\":\"CLOSED\"}"));
        PaymentProviderProperties properties = new PaymentProviderProperties();
        properties.setDefaultProviderKey("payment.test");
        PaymentAttemptExpirationService service = new PaymentAttemptExpirationService(
            paymentAttemptMapper, new PaymentProviderRouter(List.of(provider), properties), callbackLogService,
            paymentProviderEvidenceMirror, new ObjectMapper());
        BizOrder order = order();
        PaymentAttempt attempt = attempt(order);
        when(paymentAttemptMapper.findActiveByOrderId(order.id)).thenReturn(attempt);
        when(paymentAttemptMapper.findByPaymentNo(attempt.paymentNo)).thenReturn(attempt);

        service.expireActiveAttemptAfterOrderCommit(order);

        InOrder orderOfEffects = inOrder(paymentAttemptMapper, provider);
        orderOfEffects.verify(paymentAttemptMapper).updateById(attempt);
        ArgumentCaptor<PaymentCloseCommand> commandCaptor = ArgumentCaptor.forClass(PaymentCloseCommand.class);
        orderOfEffects.verify(provider).closePayment(commandCaptor.capture());
        assertThat(attempt.status).isEqualTo(PaymentAttemptStatus.TIMEOUT.name());
        assertThat(attempt.failureCode).isEqualTo("PAYMENT_TIMEOUT");
        assertThat(commandCaptor.getValue().paymentNo()).isEqualTo("PAY001");
        assertThat(commandCaptor.getValue().orderNo()).isEqualTo("ORD001");
        verify(callbackLogService).recordOperation(org.mockito.ArgumentMatchers.argThat(
            (PaymentProviderOperationLog log) -> "CLOSE_PAYMENT".equals(log.operationType)
                && "SUCCESS".equals(log.status)
                && attempt.id.equals(log.paymentAttemptId)));
    }

    private BizOrder order() {
        BizOrder order = new BizOrder();
        order.id = 11L;
        order.orderNo = "ORD001";
        return order;
    }

    private PaymentAttempt attempt(BizOrder order) {
        PaymentAttempt attempt = new PaymentAttempt();
        attempt.id = 51L;
        attempt.orderId = order.id;
        attempt.orderNo = order.orderNo;
        attempt.paymentNo = "PAY001";
        attempt.providerEnv = "SANDBOX";
        attempt.providerName = "TEST_PAYMENT";
        attempt.configVersion = "TEST_V1";
        attempt.status = PaymentAttemptStatus.PENDING.name();
        attempt.paidAmount = BigDecimal.ZERO;
        attempt.feeAmount = BigDecimal.ZERO;
        attempt.netAmount = BigDecimal.ZERO;
        return attempt;
    }
}
