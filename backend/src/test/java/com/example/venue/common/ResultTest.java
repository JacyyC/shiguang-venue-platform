package com.example.venue.common;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ResultTest {
    @Test
    void okUsesUnifiedSuccessCode() {
        Result<String> result = Result.ok("data");
        assertThat(result.getCode()).isEqualTo(ResultCode.SUCCESS.getCode());
        assertThat(result.getData()).isEqualTo("data");
        assertThat(result.getTimestamp()).isNotNull();
    }

    @Test
    void pageResultHasUnifiedShape() {
        PageResult<String> page = PageResult.of(java.util.List.of("a"), 1, 1, 10);
        assertThat(page.getRecords()).containsExactly("a");
        assertThat(page.getTotal()).isEqualTo(1);
        assertThat(page.getPageNum()).isEqualTo(1);
        assertThat(page.getPageSize()).isEqualTo(10);
    }
}
