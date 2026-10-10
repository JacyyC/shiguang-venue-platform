package com.example.venue.storedvalue;

import com.example.venue.storedvalue.domain.StoredValueRechargeStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StoredValueRechargeStatusTest {
    @Test
    void parsesEveryPersistedStatusWithoutCreatingASecondStatusModel() {
        for (StoredValueRechargeStatus status : StoredValueRechargeStatus.values()) {
            assertThat(StoredValueRechargeStatus.parse(status.name())).isSameAs(status);
            assertThat(status.matches(status.name())).isTrue();
        }
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", " ", "NOT_A_STATUS", "succeeded", " SUCCEEDED "})
    void rejectsMissingOrNonCanonicalPersistedStatus(String persistedValue) {
        assertThatThrownBy(() -> StoredValueRechargeStatus.parse(persistedValue))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void onlyFailureLikeTerminalStatesCanReleasePendingReservation() {
        assertThat(StoredValueRechargeStatus.values())
            .filteredOn(StoredValueRechargeStatus::isReservationReleaseTarget)
            .containsExactlyInAnyOrder(
                StoredValueRechargeStatus.FAILED,
                StoredValueRechargeStatus.CANCELED,
                StoredValueRechargeStatus.TIMED_OUT,
                StoredValueRechargeStatus.MANUAL_REVIEW);
    }
}
