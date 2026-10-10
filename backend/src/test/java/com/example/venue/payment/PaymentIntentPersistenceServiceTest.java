package com.example.venue.payment;

import com.example.venue.common.BusinessException;
import com.example.venue.order.entity.BizOrder;
import com.example.venue.order.mapper.BizOrderMapper;
import com.example.venue.payment.dto.PaymentAttemptStatus;
import com.example.venue.payment.dto.PaymentCreateResult;
import com.example.venue.payment.entity.PaymentAttempt;
import com.example.venue.payment.mapper.PaymentAttemptMapper;
import com.example.venue.payment.provider.PaymentProviderDescriptor;
import com.example.venue.payment.service.PaymentIntentPersistenceService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A26-P3-06：验证 markCreateSucceeded 的原子条件更新，
 * 防止并发回调结算后把 attempt 从 SUCCEEDED 覆盖回 PENDING 并清零 paidAmount。
 */
@ExtendWith(MockitoExtension.class)
class PaymentIntentPersistenceServiceTest {
    @Mock
    private BizOrderMapper orderMapper;
    @Mock
    private PaymentAttemptMapper paymentAttemptMapper;

    @Test
    void markCreateSucceededUsesConditionalUpdateWhenAttemptIsActive() {
        PaymentIntentPersistenceService service = newService();
        PaymentAttempt attempt = createdAttempt();
        // requireCurrentAttempt 重查仍返回活跃 attempt
        when(paymentAttemptMapper.findByPaymentNo(attempt.paymentNo)).thenReturn(attempt);
        when(paymentAttemptMapper.markCreateSucceededIfActive(anyLong(), anyString(), anyString(), anyString(),
            anyString(), anyString(), anyString(), any(), any(), any(LocalDateTime.class)))
            .thenReturn(1);
        PaymentCreateResult result = createResult();

        PaymentAttempt returned = service.markCreateSucceeded(attempt, result);

        // 走原子条件更新而非 updateById
        verify(paymentAttemptMapper).markCreateSucceededIfActive(eq(attempt.id),
            eq(result.providerEnv()), eq(result.providerName()), eq(result.providerName()),
            eq(result.status()), eq(result.currency()), eq(result.configVersion()),
            eq(result.requestSummaryJson()), eq(result.responseSummaryJson()), any(LocalDateTime.class));
        verify(paymentAttemptMapper, never()).updateById(any(PaymentAttempt.class));
        assertThat(returned.status).isEqualTo(result.status());
        assertThat(returned.providerEnv).isEqualTo(result.providerEnv());
        assertThat(returned.providerName).isEqualTo(result.providerName());
        assertThat(returned.failureCode).isNull();
        assertThat(returned.failureMessage).isNull();
    }

    @Test
    void markCreateSucceededDoesNotOverwriteWhenConcurrentCallbackAlreadySucceeded() {
        PaymentIntentPersistenceService service = newService();
        PaymentAttempt attempt = createdAttempt();
        // 第一次 findByPaymentNo（requireCurrentAttempt）：返回活跃 attempt，通过 isActive 检查
        // 第二次 findByPaymentNo（updated==0 后重查）：返回并发回调结算后的 SUCCEEDED attempt
        PaymentAttempt concurrentSucceeded = createdAttempt();
        concurrentSucceeded.status = PaymentAttemptStatus.SUCCEEDED.name();
        concurrentSucceeded.paidAmount = new BigDecimal("100.00");
        concurrentSucceeded.feeAmount = new BigDecimal("0.60");
        concurrentSucceeded.netAmount = new BigDecimal("99.40");
        when(paymentAttemptMapper.findByPaymentNo(attempt.paymentNo))
            .thenReturn(attempt)
            .thenReturn(concurrentSucceeded);
        // 并发回调已把 DB 中的 status 推进到 SUCCEEDED，WHERE status IN ('CREATED','PENDING') 命中 0 行
        when(paymentAttemptMapper.markCreateSucceededIfActive(anyLong(), anyString(), anyString(), anyString(),
            anyString(), anyString(), anyString(), any(), any(), any(LocalDateTime.class)))
            .thenReturn(0);
        PaymentCreateResult result = createResult();

        PaymentAttempt returned = service.markCreateSucceeded(attempt, result);

        // 不覆盖回 PENDING，不清零 paidAmount；返回重查的最新 SUCCEEDED 状态
        assertThat(returned.status).isEqualTo(PaymentAttemptStatus.SUCCEEDED.name());
        assertThat(returned.paidAmount).isEqualByComparingTo("100.00");
        assertThat(returned.feeAmount).isEqualByComparingTo("0.60");
        verify(paymentAttemptMapper, never()).updateById(any(PaymentAttempt.class));
    }

    @Test
    void markCreateSucceededSkipsTerminalAttemptWithoutCallingConditionalUpdate() {
        PaymentIntentPersistenceService service = newService();
        PaymentAttempt attempt = createdAttempt();
        PaymentAttempt terminalSucceeded = createdAttempt();
        terminalSucceeded.status = PaymentAttemptStatus.SUCCEEDED.name();
        terminalSucceeded.paidAmount = new BigDecimal("100.00");
        // requireCurrentAttempt 重查返回 SUCCEEDED，isActive=false，直接返回不进入条件更新
        when(paymentAttemptMapper.findByPaymentNo(attempt.paymentNo)).thenReturn(terminalSucceeded);
        PaymentCreateResult result = createResult();

        PaymentAttempt returned = service.markCreateSucceeded(attempt, result);

        assertThat(returned).isSameAs(terminalSucceeded);
        verify(paymentAttemptMapper, never()).markCreateSucceededIfActive(anyLong(), anyString(), anyString(),
            anyString(), anyString(), anyString(), anyString(), any(), any(), any(LocalDateTime.class));
        verify(paymentAttemptMapper, never()).updateById(any(PaymentAttempt.class));
    }

    @Test
    void prepareRejectsOrderNotOwnedByUser() {
        PaymentIntentPersistenceService service = newService();
        BizOrder order = new BizOrder();
        order.id = 11L;
        order.userId = 99L; // 不是当前用户
        when(orderMapper.selectByIdForUpdate(11L)).thenReturn(order);
        PaymentProviderDescriptor descriptor = devDescriptor();

        assertThatThrownBy(() -> service.prepare(11L, 7L, descriptor, "PAY001", "idem-1"))
            .isInstanceOf(BusinessException.class)
            .hasMessage("订单不存在");
    }

    private PaymentIntentPersistenceService newService() {
        return new PaymentIntentPersistenceService(orderMapper, paymentAttemptMapper);
    }

    private PaymentAttempt createdAttempt() {
        PaymentAttempt attempt = new PaymentAttempt();
        attempt.id = 31L;
        attempt.paymentNo = "PAY001";
        attempt.orderId = 11L;
        attempt.orderNo = "ORD001";
        attempt.userId = 7L;
        attempt.venueId = 5L;
        attempt.reservationId = 21L;
        attempt.providerEnv = "DEV";
        attempt.providerName = "DEV_PAYMENT";
        attempt.channel = "DEV_PAYMENT";
        attempt.status = PaymentAttemptStatus.CREATED.name();
        attempt.payableAmount = new BigDecimal("100.00");
        attempt.paidAmount = BigDecimal.ZERO;
        attempt.feeAmount = BigDecimal.ZERO;
        attempt.netAmount = BigDecimal.ZERO;
        attempt.currency = "CNY";
        attempt.idempotencyKey = "idem-1";
        attempt.configVersion = "DEV_PAYMENT_V1";
        return attempt;
    }

    private PaymentCreateResult createResult() {
        return new PaymentCreateResult(
            "DEV", "DEV_PAYMENT", "DEV_PAYMENT_V1", PaymentAttemptStatus.PENDING.name(),
            new BigDecimal("100.00"), new BigDecimal("0.60"), new BigDecimal("99.40"), "CNY",
            "{\"paymentNo\":\"PAY001\"}", "{\"status\":\"PENDING\"}", null);
    }

    private PaymentProviderDescriptor devDescriptor() {
        return new PaymentProviderDescriptor(
            "payment.dev", "DEV", "DEV_PAYMENT", "DEV PaymentProvider", "DEV_PAYMENT_V1", true);
    }
}
