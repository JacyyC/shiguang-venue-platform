package com.example.venue.payment;

import com.example.venue.common.BusinessException;
import com.example.venue.common.Result;
import com.example.venue.order.dto.PaymentCallbackRequest;
import com.example.venue.payment.controller.PaymentController;
import com.example.venue.payment.dto.DevPaymentScenario;
import com.example.venue.payment.dto.DevPaymentSimulationRequest;
import com.example.venue.payment.dto.PaymentCallbackEnvelope;
import com.example.venue.payment.dto.PaymentCallbackProcessResult;
import com.example.venue.payment.dto.PaymentIntentResponse;
import com.example.venue.payment.provider.alipay.AlipayCallbackEnvelopeFactory;
import com.example.venue.payment.provider.alipay.AlipaySandboxException;
import com.example.venue.payment.service.PaymentService;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentControllerTest {
    @Mock
    private PaymentService paymentService;
    @Mock
    private AlipayCallbackEnvelopeFactory callbackEnvelopeFactory;
    @Mock
    private HttpServletRequest request;

    @Test
    void createPaymentIntentDelegatesToPaymentService() {
        PaymentController controller = controller();
        PaymentIntentResponse intent = new PaymentIntentResponse();
        intent.paymentNo = "PAY001";
        when(paymentService.createPaymentIntent(11L)).thenReturn(intent);

        Result<PaymentIntentResponse> result = controller.create(11L);

        assertThat(result.getData()).isSameAs(intent);
        verify(paymentService).createPaymentIntent(11L);
    }

    @Test
    void simulateDevPaymentDelegatesScenarioToPaymentService() {
        PaymentController controller = controller();
        DevPaymentSimulationRequest request = new DevPaymentSimulationRequest();
        request.scenario = DevPaymentScenario.SUCCESS;
        PaymentCallbackProcessResult processResult = PaymentCallbackProcessResult.of(
            "PAY001", 11L, "SUCCESS", "PAID");
        when(paymentService.simulateDevCallback("PAY001", DevPaymentScenario.SUCCESS)).thenReturn(processResult);

        Result<PaymentCallbackProcessResult> result = controller.simulate("PAY001", request);

        assertThat(result.getData()).isSameAs(processResult);
        verify(paymentService).simulateDevCallback("PAY001", DevPaymentScenario.SUCCESS);
    }

    @Test
    void callbackDelegatesProviderPayloadToPaymentService() {
        PaymentController controller = controller();
        PaymentCallbackRequest request = new PaymentCallbackRequest();

        controller.callback("DEV_PAYMENT", request);

        verify(paymentService).handleProviderCallback("DEV_PAYMENT", request);
    }

    @Test
    void userPaymentEndpointsRequireOrderMyAuthorityButProviderCallbackRemainsPublic() throws Exception {
        assertOrderMyAuthority(PaymentController.class.getMethod("create", Long.class));
        assertOrderMyAuthority(PaymentController.class.getMethod("detail", String.class));
        assertOrderMyAuthority(PaymentController.class.getMethod(
            "simulate", String.class, DevPaymentSimulationRequest.class));

        Method callback = PaymentController.class.getMethod("callback", String.class, PaymentCallbackRequest.class);
        assertThat(callback.getAnnotation(PreAuthorize.class)).isNull();
    }

    @Test
    void providerCallbackRawPayloadRequiresNotBlankValidation() throws Exception {
        assertThat(PaymentCallbackRequest.class.getField("rawPayload").getAnnotation(NotBlank.class))
            .isNotNull();
    }

    @Test
    void providerCallbackRequestFieldsEnforceSizeLimits() throws Exception {
        // A26-P3-05：PaymentCallbackRequest 各字段必须同时有 @NotBlank 与 @Size，
        // 避免超长值导致 RECEIVED 证据插入本身失败（payload 字段为 text 不需要 @Size）。
        assertSize(PaymentCallbackRequest.class.getField("orderNo"), 64);
        assertSize(PaymentCallbackRequest.class.getField("transactionId"), 128);
        assertSize(PaymentCallbackRequest.class.getField("channel"), 32);
        assertSize(PaymentCallbackRequest.class.getField("sign"), 256);
        // rawPayload 是 text 类型，不需要 @Size 限制
        assertThat(PaymentCallbackRequest.class.getField("rawPayload").getAnnotation(jakarta.validation.constraints.Size.class))
            .isNull();
    }

    private void assertSize(java.lang.reflect.Field field, int expectedMax) {
        jakarta.validation.constraints.Size size = field.getAnnotation(jakarta.validation.constraints.Size.class);
        assertThat(size).as("%s must declare @Size", field.getName()).isNotNull();
        assertThat(size.max()).as("%s @Size.max", field.getName()).isEqualTo(expectedMax);
    }

    @Test
    void alipaySandboxFactoryRejectionReturnsPlainFailureAndRecordsOnlyRejectedReceipt() {
        when(callbackEnvelopeFactory.from(request))
            .thenThrow(AlipaySandboxException.config("ALIPAY_CALLBACK_ENVELOPE_INVALID"));

        ResponseEntity<String> response = controller().alipaySandboxCallback(request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getContentType().toString()).isEqualTo("text/plain;charset=UTF-8");
        assertThat(response.getBody()).isEqualTo("failure");
        verify(paymentService).recordRejectedRawProviderCallback(
            "payment.alipay.sandbox", "ALIPAY_CALLBACK_ENVELOPE_INVALID");
        verify(paymentService, never()).handleRawProviderCallback(any(), any());
    }

    @Test
    void alipaySandboxHandlerFailureReturnsPlainFailureWithoutSecondRejectedReceipt() {
        String rawSignature = "sandbox-signature-secret";
        String rawPaymentNo = "merchant-order-123456";
        PaymentCallbackEnvelope envelope = new PaymentCallbackEnvelope(
            "application/x-www-form-urlencoded", "UTF-8", Map.of(
                "sign", List.of(rawSignature),
                "out_trade_no", List.of(rawPaymentNo)
            ));
        when(callbackEnvelopeFactory.from(request)).thenReturn(envelope);
        doThrow(BusinessException.badRequest("callback failed"))
            .when(paymentService).handleRawProviderCallback("payment.alipay.sandbox", envelope);

        Logger logger = (Logger) LoggerFactory.getLogger(PaymentController.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        ResponseEntity<String> response;
        try {
            response = controller().alipaySandboxCallback(request);
            assertThat(appender.list)
                .extracting(ILoggingEvent::getFormattedMessage)
                .contains("Alipay sandbox callback failed for providerKey=payment.alipay.sandbox, formParameterCount=2, errorType=BusinessException")
                .noneMatch(message -> message.contains(rawSignature) || message.contains(rawPaymentNo));
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getContentType().toString()).isEqualTo("text/plain;charset=UTF-8");
        assertThat(response.getBody()).isEqualTo("failure");
        verify(paymentService).handleRawProviderCallback("payment.alipay.sandbox", envelope);
        verify(paymentService, never()).recordRejectedRawProviderCallback(any(), any());
    }

    @Test
    void alipaySandboxHandlerSuccessReturnsLiteralPlainSuccess() {
        PaymentCallbackEnvelope envelope = new PaymentCallbackEnvelope(
            "application/x-www-form-urlencoded", "UTF-8", Map.of());
        when(callbackEnvelopeFactory.from(request)).thenReturn(envelope);

        ResponseEntity<String> response = controller().alipaySandboxCallback(request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getContentType().toString()).isEqualTo("text/plain;charset=UTF-8");
        assertThat(response.getBody()).isEqualTo("success");
        verify(paymentService).handleRawProviderCallback("payment.alipay.sandbox", envelope);
    }

    @Test
    void alipaySandboxCallbackRemainsPublicAndUsesRawServletRequest() throws Exception {
        Method method = PaymentController.class.getMethod("alipaySandboxCallback", HttpServletRequest.class);
        PostMapping mapping = method.getAnnotation(PostMapping.class);

        assertThat(method.getAnnotation(PreAuthorize.class)).isNull();
        assertThat(mapping.value()).containsExactly("/api/payments/callback/alipay/sandbox");
        assertThat(mapping.consumes()).isEmpty();
        assertThat(mapping.produces()).containsExactly("text/plain;charset=UTF-8");
    }

    private PaymentController controller() {
        return new PaymentController(paymentService, callbackEnvelopeFactory);
    }

    private void assertOrderMyAuthority(Method method) {
        PreAuthorize preAuthorize = method.getAnnotation(PreAuthorize.class);
        assertThat(preAuthorize).isNotNull();
        assertThat(preAuthorize.value()).isEqualTo("hasAuthority('order:my')");
    }
}
