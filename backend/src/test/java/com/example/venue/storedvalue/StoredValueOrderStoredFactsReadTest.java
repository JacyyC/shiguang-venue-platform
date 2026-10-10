package com.example.venue.storedvalue;

import com.example.venue.common.BusinessException;
import com.example.venue.storedvalue.dto.StoredValueResponses;
import com.example.venue.storedvalue.service.StoredValueFinanceQueryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * B5-H11 反例：财务侧原桶分摊只读摘要——受控引用出参、未知订单不假成功。
 */
@ExtendWith(MockitoExtension.class)
class StoredValueOrderStoredFactsReadTest {
    @Mock
    private JdbcTemplate jdbc;

    private StoredValueFinanceQueryService service;

    @BeforeEach
    void setUp() {
        service = new StoredValueFinanceQueryService(jdbc);
    }

    @Test
    @SuppressWarnings("unchecked")
    void returnsMaskedFactsForKnownOrder() {
        StoredValueResponses.OrderStoredFactPaymentResponse payment =
            new StoredValueResponses.OrderStoredFactPaymentResponse(
                "...ENT-1", "SUCCEEDED", money("100.00"), money("80.00"), money("20.00"));
        when(jdbc.query(anyString(), any(ResultSetExtractor.class), eq("O-2026-1")))
            .thenReturn(payment)
            .thenReturn(null);

        StoredValueResponses.OrderStoredFactsResponse facts = service.orderStoredFacts(" O-2026-1 ");
        // B5-H25（S08 隔离栈实测）：退款子查询必须经 biz_order 关联（stored_value_order_refund 无
        // order_no 列），否则真实 MySQL 抛 Unknown column。Mock 不执行 SQL，这里锁 SQL 文本形状。
        org.mockito.ArgumentCaptor<String> refundSql = org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(jdbc, org.mockito.Mockito.atLeastOnce())
            .query(refundSql.capture(), org.mockito.ArgumentMatchers.any(
                org.springframework.jdbc.core.ResultSetExtractor.class), org.mockito.ArgumentMatchers.eq("O-2026-1"));
        assertThat(refundSql.getValue()).contains("JOIN biz_order o ON o.id = r.order_id");
        assertThat(refundSql.getValue()).contains("WHERE o.order_no = ?");

        assertThat(facts.orderNo()).isEqualTo("O-2026-1");
        assertThat(facts.payment()).isSameAs(payment);
        assertThat(facts.payment().paymentNoSuffix()).isEqualTo("...ENT-1");
        assertThat(facts.payment().principalAmount()).isEqualByComparingTo("80.00");
        assertThat(facts.payment().bonusAmount()).isEqualByComparingTo("20.00");
        assertThat(facts.refund()).isNull();
        String printed = facts.payment().toString();
        assertThat(printed).doesNotContain("O-2026-1");
    }

    @Test
    @SuppressWarnings("unchecked")
    void unknownOrderFactsAreRejectedInsteadOfFabricated() {
        when(jdbc.query(anyString(), any(ResultSetExtractor.class), eq("O-MISSING")))
            .thenReturn(null);

        assertThatThrownBy(() -> service.orderStoredFacts("O-MISSING"))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("没有储值支付或退款事实");
    }

    @Test
    void blankOrderNoIsRejected() {
        assertThatThrownBy(() -> service.orderStoredFacts("   "))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("订单号不能为空");
    }

    private BigDecimal money(String value) {
        return new BigDecimal(value);
    }
}
