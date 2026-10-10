package com.example.venue.reservation;

import com.example.venue.reservation.enums.ReservationStatus;
import com.example.venue.reservation.enums.ReservationStatusTransitions;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReservationStatusTest {
    @Test
    void occupyingStatusesMatchRouteMap() {
        assertThat(ReservationStatus.PENDING_REVIEW.occupies()).isTrue();
        assertThat(ReservationStatus.APPROVED.occupies()).isTrue();
        assertThat(ReservationStatus.PENDING_PAYMENT.occupies()).isTrue();
        assertThat(ReservationStatus.PAID.occupies()).isTrue();
        assertThat(ReservationStatus.COMPLETED.occupies()).isFalse();
        assertThat(ReservationStatus.REFUNDING.occupies()).isTrue();
        assertThat(ReservationStatus.REJECTED.occupies()).isFalse();
        assertThat(ReservationStatus.CANCELED.occupies()).isFalse();
        assertThat(ReservationStatus.EXPIRED.occupies()).isFalse();
        assertThat(ReservationStatus.REFUNDED.occupies()).isFalse();
    }

    @Test
    void statusContractMatchesB05CompatibilityRules() {
        assertThat(ReservationStatus.APPROVED.legacyCompatible()).isTrue();
        assertThat(ReservationStatus.APPROVED.cancelable()).isTrue();

        assertThat(ReservationStatus.COMPLETED.terminal()).isTrue();
        assertThat(ReservationStatus.COMPLETED.checkinCandidate()).isFalse();
        assertThat(ReservationStatus.COMPLETED.cancelable()).isFalse();

        assertThat(ReservationStatus.PAID.checkinCandidate()).isTrue();
        assertThat(ReservationStatus.PAID.refundCandidate()).isTrue();
        assertThat(ReservationStatus.PENDING_PAYMENT.payable()).isTrue();
        assertThat(ReservationStatus.REFUNDING.occupies()).isTrue();
        assertThat(ReservationStatus.REFUNDING.refundCandidate()).isFalse();
    }

    @Test
    void canceledPaidReservationCanReachRefundedOnlyThroughTheStateMachine() {
        assertThatCode(() -> ReservationStatusTransitions.parseAndValidate("CANCELED", "REFUNDED"))
            .doesNotThrowAnyException();
    }

    @Test
    void expiredReservationCanReachRefundedDirectlyForLatePaymentRefund() {
        // A26-P2-06：迟到支付确认后预约保持 EXPIRED（不再推回 REFUNDING 占用态），
        // 退款到账时由 EXPIRED 直接迁移到 REFUNDED 终态。原 EXPIRED→REFUNDING 路径已移除。
        assertThatCode(() -> ReservationStatusTransitions.parseAndValidate("EXPIRED", "REFUNDED"))
            .doesNotThrowAnyException();
        // EXPIRED→REFUNDING 已移除，应被状态机拒绝
        assertThatThrownBy(() -> ReservationStatusTransitions.parseAndValidate("EXPIRED", "REFUNDING"))
            .isInstanceOf(com.example.venue.common.BusinessException.class);
    }
}
