package com.example.venue.member;

import com.example.venue.member.mapper.MemberAccountMapper;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MemberAccountMapperLockingTest {
    @Test
    void settlementLookupLocksTheMemberAccountRow() throws NoSuchMethodException {
        Select select = MemberAccountMapper.class
            .getMethod("findByUserIdForUpdate", Long.class)
            .getAnnotation(Select.class);

        assertThat(select).isNotNull();
        assertThat(select.value()).singleElement().asString().containsIgnoringCase("for update");
    }
}
