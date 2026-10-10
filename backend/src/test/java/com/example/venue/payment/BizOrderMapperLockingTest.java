package com.example.venue.payment;

import com.example.venue.order.mapper.BizOrderMapper;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

class BizOrderMapperLockingTest {
    @Test
    void paymentIntentLookupLocksTheOrderRow() throws NoSuchMethodException {
        Method method = BizOrderMapper.class.getMethod("selectByIdForUpdate", Long.class);

        assertThat(method.getAnnotation(Select.class).value())
            .singleElement()
            .asString()
            .containsIgnoringCase("where id = #{id}")
            .containsIgnoringCase("for update");
    }

    @Test
    void callbackOrderLookupLocksTheOrderRow() throws NoSuchMethodException {
        Method method = BizOrderMapper.class.getMethod("findByOrderNoForUpdate", String.class);

        assertThat(method.getAnnotation(Select.class).value())
            .singleElement()
            .asString()
            .containsIgnoringCase("where order_no = #{orderNo}")
            .containsIgnoringCase("for update");
    }

    @Test
    void rawCallbackOrderLookupLocksTheOrderRowByAttemptOrderId() throws NoSuchMethodException {
        Method method = BizOrderMapper.class.getMethod("selectByIdForUpdate", Long.class);

        assertThat(method.getAnnotation(Select.class).value())
            .singleElement()
            .asString()
            .containsIgnoringCase("where id = #{id}")
            .containsIgnoringCase("for update");
    }
}
