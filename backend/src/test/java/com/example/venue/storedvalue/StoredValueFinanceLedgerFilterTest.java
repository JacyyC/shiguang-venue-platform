package com.example.venue.storedvalue;

import com.example.venue.common.PageQuery;
import com.example.venue.common.PageResult;
import com.example.venue.storedvalue.dto.StoredValueResponses;
import com.example.venue.storedvalue.service.StoredValueFinanceQueryService;
import com.example.venue.storedvalue.service.StoredValueReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * B5-H9 反例：账本 accountRef 筛选必须到达 SQL；未知引用按空结果处理，不假成功。
 */
@ExtendWith(MockitoExtension.class)
class StoredValueFinanceLedgerFilterTest {
    @Mock
    private JdbcTemplate jdbc;

    private StoredValueFinanceQueryService service;

    @BeforeEach
    void setUp() {
        service = new StoredValueFinanceQueryService(jdbc);
    }

    @Test
    void accountRefFilterResolvesReferenceAndReachesSql() {
        String ref = StoredValueReference.account(42L);
        lenient().when(jdbc.queryForList(anyString(), eq(Long.class))).thenReturn(List.of(7L, 42L));
        when(jdbc.queryForObject(anyString(), eq(Long.class), eq(42L))).thenReturn(1L);
        when(jdbc.query(anyString(), any(RowMapper.class), eq(42L), eq(10), eq(0)))
            .thenReturn(List.of(ledgerRow(ref)));

        PageResult<StoredValueResponses.FinanceLedgerResponse> page =
            service.financeLedger(new PageQuery(), ref);

        assertThat(page.getTotal()).isEqualTo(1L);
        assertThat(page.getRecords()).hasSize(1);
        assertThat(page.getRecords().get(0).accountRef()).isEqualTo(ref);

        ArgumentCaptor<String> ledgerSql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).query(ledgerSql.capture(), any(RowMapper.class), eq(42L), eq(10), eq(0));
        assertThat(ledgerSql.getValue()).contains("WHERE e.account_id = ?");
        // B5-H24 反例（S05 隔离栈实测）：拼接处缺空格曾生成 `?ORDER BY`（BadSqlGrammar）。
        // Mock 测试不执行 SQL，必须锁 SQL 文本形状。
        assertThat(ledgerSql.getValue()).contains("? ORDER BY t.occurred_at DESC");

        ArgumentCaptor<String> countSql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).queryForObject(countSql.capture(), eq(Long.class), eq(42L));
        assertThat(countSql.getValue()).contains("WHERE e.account_id = ?");
    }

    @Test
    void unknownAccountRefReturnsEmptyPageWithoutLedgerQueries() {
        when(jdbc.queryForList(anyString(), eq(Long.class))).thenReturn(List.of(7L));

        PageResult<StoredValueResponses.FinanceLedgerResponse> page =
            service.financeLedger(new PageQuery(), StoredValueReference.account(999_999L));

        assertThat(page.getTotal()).isZero();
        assertThat(page.getRecords()).isEmpty();
        verify(jdbc, never()).query(anyString(), any(RowMapper.class), any(Object[].class));
    }

    @Test
    void withoutAccountRefLedgerStaysUnfiltered() {
        when(jdbc.queryForObject(anyString(), eq(Long.class))).thenReturn(3L);
        when(jdbc.query(anyString(), any(RowMapper.class), eq(10), eq(0)))
            .thenReturn(List.of(ledgerRow(StoredValueReference.account(7L))));

        PageResult<StoredValueResponses.FinanceLedgerResponse> page =
            service.financeLedger(new PageQuery(), null);

        assertThat(page.getTotal()).isEqualTo(3L);
        ArgumentCaptor<String> ledgerSql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).query(ledgerSql.capture(), any(RowMapper.class), eq(10), eq(0));
        assertThat(ledgerSql.getValue()).contains("WHERE e.account_id IS NOT NULL");
        assertThat(ledgerSql.getValue()).doesNotContain("e.account_id = ?");
        assertThat(ledgerSql.getValue()).contains("IS NOT NULL ORDER BY t.occurred_at DESC");
    }

    private StoredValueResponses.FinanceLedgerResponse ledgerRow(String accountRef) {
        return new StoredValueResponses.FinanceLedgerResponse(
            StoredValueReference.transaction(1L), accountRef, StoredValueReference.user(9L),
            "RECHARGE_PRINCIPAL_POSTING", "RECHARGE", "...SRC", "PRINCIPAL_AVAILABLE", "CREDIT",
            new BigDecimal("10.00"), "CNY", "充值入账", LocalDateTime.now());
    }
}
