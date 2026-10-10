package com.example.venue.reservation;

import com.example.venue.reservation.mapper.ReservationMapper;
import com.example.venue.venue.mapper.VenueSpaceMapper;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

class ReservationConcurrencyMapperTest {
    @Test
    void reservationStateChangesLockTheReservationRow() throws NoSuchMethodException {
        Method method = ReservationMapper.class.getMethod("selectByIdForUpdate", Long.class);

        assertThat(method.getAnnotation(Select.class).value())
            .singleElement()
            .asString()
            .containsIgnoringCase("where id = #{id}")
            .containsIgnoringCase("for update");
    }

    @Test
    void reservationCreationLocksTheSpaceRow() throws NoSuchMethodException {
        Method method = VenueSpaceMapper.class.getMethod("selectByIdForUpdate", Long.class);

        assertThat(method.getAnnotation(Select.class).value())
            .singleElement()
            .asString()
            .containsIgnoringCase("where id = #{id}")
            .containsIgnoringCase("for update");
    }
}
