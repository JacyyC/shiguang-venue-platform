package com.example.venue.order.enums;

import com.example.venue.common.BusinessException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderStatusTransitionsTest {

    @Test
    void parseAndValidateAllowsLegalTransitions() {
        assertThatCode(() -> OrderStatusTransitions.parseAndValidate(
                OrderStatus.PENDING_PAYMENT.name(), OrderStatus.PAID.name()))
            .doesNotThrowAnyException();
        assertThatCode(() -> OrderStatusTransitions.parseAndValidate(
                OrderStatus.PENDING_PAYMENT.name(), OrderStatus.CLOSED.name()))
            .doesNotThrowAnyException();
        assertThatCode(() -> OrderStatusTransitions.parseAndValidate(
                OrderStatus.PAID.name(), OrderStatus.REFUNDING.name()))
            .doesNotThrowAnyException();
        assertThatCode(() -> OrderStatusTransitions.parseAndValidate(
                OrderStatus.REFUNDING.name(), OrderStatus.REFUNDED.name()))
            .doesNotThrowAnyException();
        assertThatCode(() -> OrderStatusTransitions.parseAndValidate(
                OrderStatus.REFUNDING.name(), OrderStatus.PAID.name()))
            .doesNotThrowAnyException();
        // 迟到支付确认后允许从超时进入退款审核，审核拒绝可回退超时
        assertThatCode(() -> OrderStatusTransitions.parseAndValidate(
                OrderStatus.TIMEOUT.name(), OrderStatus.REFUNDING.name()))
            .doesNotThrowAnyException();
        assertThatCode(() -> OrderStatusTransitions.parseAndValidate(
                OrderStatus.REFUNDING.name(), OrderStatus.TIMEOUT.name()))
            .doesNotThrowAnyException();
    }

    @Test
    void parseAndValidateRejectsIllegalJump() {
        assertThatThrownBy(() -> OrderStatusTransitions.parseAndValidate(
                OrderStatus.PENDING_PAYMENT.name(), OrderStatus.REFUNDED.name()))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("非法的状态变更");

        assertThatThrownBy(() -> OrderStatusTransitions.parseAndValidate(
                OrderStatus.CLOSED.name(), OrderStatus.PAID.name()))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("非法的状态变更");
    }

    @Test
    void parseAndValidateRejectsNullFromStatus() {
        assertThatThrownBy(() -> OrderStatusTransitions.parseAndValidate(
                null, OrderStatus.PAID.name()))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("订单当前状态为空");
    }

    @Test
    void parseAndValidateRejectsInvalidStatusName() {
        assertThatThrownBy(() -> OrderStatusTransitions.parseAndValidate(
                "PENDING_PAYMENT", "NOT_A_STATUS"))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("无效的订单状态");
    }

    @Test
    void parseAndValidateReturnsParsedTarget() {
        OrderStatus target = OrderStatusTransitions.parseAndValidate(
            OrderStatus.PENDING_PAYMENT.name(), OrderStatus.PAID.name());
        assertThat(target).isEqualTo(OrderStatus.PAID);
    }

    @Test
    void terminalStatesHaveNoOutgoingTransitions() {
        assertThatThrownBy(() -> OrderStatusTransitions.parseAndValidate(
                OrderStatus.REFUNDED.name(), OrderStatus.PAID.name()))
            .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> OrderStatusTransitions.parseAndValidate(
                OrderStatus.TIMEOUT.name(), OrderStatus.PAID.name()))
            .isInstanceOf(BusinessException.class);
    }
}
