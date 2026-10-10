package com.example.venue.order;

import com.example.venue.order.controller.OrderController;
import com.example.venue.order.dto.RefundApplyRequest;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

class OrderControllerTest {
    @Test
    void legacyPayRequiresOrderMyAuthority() throws Exception {
        Method method = OrderController.class.getMethod("pay", Long.class);

        PreAuthorize preAuthorize = method.getAnnotation(PreAuthorize.class);

        assertThat(preAuthorize).isNotNull();
        assertThat(preAuthorize.value()).isEqualTo("hasAuthority('order:my')");
    }

    @Test
    void refundApplicationRejectsReasonsBeyondThePersistedColumnLength() {
        RefundApplyRequest request = new RefundApplyRequest();
        request.reason = "r".repeat(513);

        ValidatorFactory factory = Validation.buildDefaultValidatorFactory();
        try {
            assertThat(factory.getValidator().validate(request))
                .extracting(violation -> violation.getPropertyPath().toString())
                .contains("reason");
        } finally {
            factory.close();
        }
    }
}
