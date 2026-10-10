package com.example.venue.payment.controller;

import com.example.venue.common.Result;
import com.example.venue.order.dto.PaymentCallbackRequest;
import com.example.venue.payment.dto.DevPaymentSimulationRequest;
import com.example.venue.payment.dto.PaymentCallbackProcessResult;
import com.example.venue.payment.dto.PaymentIntentResponse;
import com.example.venue.payment.dto.PaymentCallbackEnvelope;
import com.example.venue.payment.provider.alipay.AlipayCallbackEnvelopeFactory;
import com.example.venue.payment.provider.alipay.AlipaySandboxException;
import com.example.venue.payment.service.PaymentService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PaymentController {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(PaymentController.class);
    private static final String ALIPAY_SANDBOX_PROVIDER_KEY = "payment.alipay.sandbox";
    private static final MediaType PLAIN_TEXT_UTF8 = MediaType.parseMediaType("text/plain;charset=UTF-8");

    private final PaymentService paymentService;
    private final AlipayCallbackEnvelopeFactory callbackEnvelopeFactory;

    public PaymentController(PaymentService paymentService, AlipayCallbackEnvelopeFactory callbackEnvelopeFactory) {
        this.paymentService = paymentService;
        this.callbackEnvelopeFactory = callbackEnvelopeFactory;
    }

    @PostMapping("/api/orders/{orderId}/payment-intents")
    @PreAuthorize("hasAuthority('order:my')")
    public Result<PaymentIntentResponse> create(@PathVariable Long orderId) {
        return Result.ok(paymentService.createPaymentIntent(orderId));
    }

    @GetMapping("/api/payments/{paymentNo}")
    @PreAuthorize("hasAuthority('order:my')")
    public Result<PaymentIntentResponse> detail(@PathVariable String paymentNo) {
        return Result.ok(paymentService.detail(paymentNo));
    }

    @PostMapping("/api/payments/dev/{paymentNo}/simulate")
    @PreAuthorize("hasAuthority('order:my')")
    public Result<PaymentCallbackProcessResult> simulate(@PathVariable String paymentNo,
                                                         @Valid @RequestBody DevPaymentSimulationRequest request) {
        return Result.ok(paymentService.simulateDevCallback(paymentNo, request.scenario));
    }

    @PostMapping("/api/payments/callback/{providerName}")
    public Result<Void> callback(@PathVariable String providerName,
                                 @Valid @RequestBody PaymentCallbackRequest request) {
        paymentService.handleProviderCallback(providerName, request);
        return Result.ok();
    }

    @PostMapping(value = "/api/payments/callback/alipay/sandbox", produces = "text/plain;charset=UTF-8")
    public ResponseEntity<String> alipaySandboxCallback(HttpServletRequest request) {
        PaymentCallbackEnvelope envelope;
        try {
            envelope = callbackEnvelopeFactory.from(request);
        } catch (AlipaySandboxException ex) {
            paymentService.recordRejectedRawProviderCallback(ALIPAY_SANDBOX_PROVIDER_KEY, ex.getCode());
            return plainText("failure");
        }
        try {
            paymentService.handleRawProviderCallback(ALIPAY_SANDBOX_PROVIDER_KEY, envelope);
            return plainText("success");
        } catch (Exception ex) {
            log.error("Alipay sandbox callback failed for providerKey={}, formParameterCount={}, errorType={}",
                ALIPAY_SANDBOX_PROVIDER_KEY, envelope.formParameters().size(), ex.getClass().getSimpleName());
            return plainText("failure");
        }
    }

    private ResponseEntity<String> plainText(String body) {
        return ResponseEntity.ok().contentType(PLAIN_TEXT_UTF8).body(body);
    }
}
