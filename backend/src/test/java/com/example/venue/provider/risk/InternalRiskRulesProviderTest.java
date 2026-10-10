package com.example.venue.provider.risk;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.example.venue.feedback.entity.CustomerComplaint;
import com.example.venue.feedback.mapper.CustomerComplaintMapper;
import com.example.venue.notice.entity.NoticeOutbox;
import com.example.venue.notice.mapper.NoticeOutboxMapper;
import com.example.venue.payment.entity.PaymentAttempt;
import com.example.venue.payment.mapper.PaymentAttemptMapper;
import com.example.venue.system.entity.SysLoginLog;
import com.example.venue.system.mapper.SysLoginLogMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InternalRiskRulesProviderTest {
    @Mock private SysLoginLogMapper loginLogMapper;
    @Mock private PaymentAttemptMapper paymentAttemptMapper;
    @Mock private NoticeOutboxMapper noticeOutboxMapper;
    @Mock private CustomerComplaintMapper complaintMapper;

    @Test
    void paymentAndNoticeFailuresUseTheirTerminalFailureTimeWindow() {
        when(loginLogMapper.selectCount(any(QueryWrapper.class))).thenReturn(0L);
        when(paymentAttemptMapper.selectCount(any(QueryWrapper.class))).thenReturn(0L);
        when(noticeOutboxMapper.selectCount(any(QueryWrapper.class))).thenReturn(0L);
        when(complaintMapper.selectCount(any(QueryWrapper.class))).thenReturn(0L);
        InternalRiskRulesProvider provider = new InternalRiskRulesProvider(loginLogMapper, paymentAttemptMapper,
            noticeOutboxMapper, complaintMapper);

        provider.assess(null, LocalDateTime.of(2026, 7, 16, 0, 0), LocalDateTime.of(2026, 7, 17, 0, 0));

        ArgumentCaptor<QueryWrapper<PaymentAttempt>> payment = ArgumentCaptor.forClass(QueryWrapper.class);
        ArgumentCaptor<QueryWrapper<NoticeOutbox>> notice = ArgumentCaptor.forClass(QueryWrapper.class);
        verify(paymentAttemptMapper).selectCount(payment.capture());
        verify(noticeOutboxMapper).selectCount(notice.capture());
        assertThat(payment.getValue().getSqlSegment()).contains("update_time").doesNotContain("create_time");
        assertThat(notice.getValue().getSqlSegment()).contains("update_time").doesNotContain("create_time");
    }
}
