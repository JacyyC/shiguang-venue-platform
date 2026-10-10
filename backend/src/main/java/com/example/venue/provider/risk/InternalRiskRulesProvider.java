package com.example.venue.provider.risk;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.example.venue.feedback.entity.CustomerComplaint;
import com.example.venue.feedback.mapper.CustomerComplaintMapper;
import com.example.venue.notice.entity.NoticeOutbox;
import com.example.venue.notice.mapper.NoticeOutboxMapper;
import com.example.venue.payment.entity.PaymentAttempt;
import com.example.venue.payment.mapper.PaymentAttemptMapper;
import com.example.venue.provider.entity.RiskAssessmentSnapshot;
import com.example.venue.system.entity.SysLoginLog;
import com.example.venue.system.mapper.SysLoginLogMapper;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Component
public class InternalRiskRulesProvider {
    public static final String PROVIDER_KEY = "risk.internal_rules.dev";
    public static final String PROVIDER_DOMAIN = "RISK";
    public static final String PROVIDER_ENV = "DEV";
    public static final String PROVIDER_NAME = "INTERNAL_RISK_RULES";
    public static final String CONFIG_VERSION = "INTERNAL_RISK_RULES_V1";
    public static final String OPERATION = "EVALUATE_RISK";
    public static final String SNAPSHOT_TYPE = "RISK";

    private static final DateTimeFormatter SNAPSHOT_NO_TIME_FORMAT =
        DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS");
    private static final BigDecimal CONFIDENCE = new BigDecimal("0.9000");

    private final SysLoginLogMapper loginLogMapper;
    private final PaymentAttemptMapper paymentAttemptMapper;
    private final NoticeOutboxMapper noticeOutboxMapper;
    private final CustomerComplaintMapper complaintMapper;

    public InternalRiskRulesProvider(SysLoginLogMapper loginLogMapper,
                                     PaymentAttemptMapper paymentAttemptMapper,
                                     NoticeOutboxMapper noticeOutboxMapper,
                                     CustomerComplaintMapper complaintMapper) {
        this.loginLogMapper = loginLogMapper;
        this.paymentAttemptMapper = paymentAttemptMapper;
        this.noticeOutboxMapper = noticeOutboxMapper;
        this.complaintMapper = complaintMapper;
    }

    public RiskAssessmentSnapshot assess(Long venueId) {
        LocalDateTime windowEnd = LocalDateTime.now();
        return assess(venueId, windowEnd.minusHours(24), windowEnd);
    }

    RiskAssessmentSnapshot assess(Long venueId, LocalDateTime windowStart, LocalDateTime windowEnd) {
        int failedLoginCount = count(loginLogMapper.selectCount(new QueryWrapper<SysLoginLog>()
            .in("status", List.of("FAIL", "FAILED"))
            .ge("create_time", windowStart)
            .lt("create_time", windowEnd)));
        int paymentFailureCount = count(paymentAttemptMapper.selectCount(paymentFailureWrapper(venueId, windowStart, windowEnd)));
        int noticeFailureCount = count(noticeOutboxMapper.selectCount(noticeFailureWrapper(venueId, windowStart, windowEnd)));
        int openComplaintCount = count(complaintMapper.selectCount(openComplaintWrapper(venueId, windowStart, windowEnd)));
        int severeComplaintCount = count(complaintMapper.selectCount(severeComplaintWrapper(venueId, windowStart, windowEnd)));
        int score = Math.min(100,
            failedLoginCount * 5
                + paymentFailureCount * 8
                + noticeFailureCount * 4
                + openComplaintCount * 6
                + severeComplaintCount * 15);

        LocalDateTime now = LocalDateTime.now();
        RiskAssessmentSnapshot snapshot = new RiskAssessmentSnapshot();
        snapshot.snapshotNo = snapshotNo(now);
        snapshot.scopeType = venueId == null ? "SYSTEM" : "VENUE";
        snapshot.scopeId = venueId;
        snapshot.providerKey = PROVIDER_KEY;
        snapshot.providerEnv = PROVIDER_ENV;
        snapshot.providerName = PROVIDER_NAME;
        snapshot.configVersion = CONFIG_VERSION;
        snapshot.riskScore = BigDecimal.valueOf(score).setScale(2);
        snapshot.riskLevel = riskLevel(score);
        snapshot.windowStart = windowStart;
        snapshot.windowEnd = windowEnd;
        snapshot.failedLoginCount = failedLoginCount;
        snapshot.paymentFailureCount = paymentFailureCount;
        snapshot.noticeFailureCount = noticeFailureCount;
        snapshot.openComplaintCount = openComplaintCount;
        snapshot.severeComplaintCount = severeComplaintCount;
        snapshot.ruleSummaryJson = ruleSummaryJson(snapshot);
        snapshot.confidence = CONFIDENCE;
        snapshot.createTime = now;
        snapshot.deleted = 0;
        return snapshot;
    }

    private QueryWrapper<PaymentAttempt> paymentFailureWrapper(Long venueId, LocalDateTime windowStart,
                                                               LocalDateTime windowEnd) {
        QueryWrapper<PaymentAttempt> wrapper = new QueryWrapper<PaymentAttempt>()
            .eq("deleted", 0)
            .in("status", List.of("FAILED", "CANCELED", "TIMEOUT"))
            .ge("update_time", windowStart)
            .lt("update_time", windowEnd);
        if (venueId != null) {
            wrapper.eq("venue_id", venueId);
        }
        return wrapper;
    }

    private QueryWrapper<NoticeOutbox> noticeFailureWrapper(Long venueId, LocalDateTime windowStart,
                                                            LocalDateTime windowEnd) {
        QueryWrapper<NoticeOutbox> wrapper = new QueryWrapper<NoticeOutbox>()
            .eq("deleted", 0)
            .eq("status", "FAILED")
            .ge("update_time", windowStart)
            .lt("update_time", windowEnd);
        if (venueId != null) {
            wrapper.inSql("notice_id", "select id from notice where deleted = 0 and target_venue_id = " + venueId);
        }
        return wrapper;
    }

    private QueryWrapper<CustomerComplaint> openComplaintWrapper(Long venueId, LocalDateTime windowStart,
                                                                 LocalDateTime windowEnd) {
        QueryWrapper<CustomerComplaint> wrapper = new QueryWrapper<CustomerComplaint>()
            .eq("deleted", 0)
            .in("status", List.of("SUBMITTED", "TRIAGED", "IN_PROGRESS", "WAITING_USER"))
            .ge("create_time", windowStart)
            .lt("create_time", windowEnd);
        if (venueId != null) {
            wrapper.eq("venue_id", venueId);
        }
        return wrapper;
    }

    private QueryWrapper<CustomerComplaint> severeComplaintWrapper(Long venueId, LocalDateTime windowStart,
                                                                   LocalDateTime windowEnd) {
        QueryWrapper<CustomerComplaint> wrapper = openComplaintWrapper(venueId, windowStart, windowEnd);
        wrapper.eq("severity", "HIGH");
        return wrapper;
    }

    private String riskLevel(int score) {
        if (score <= 29) {
            return "LOW";
        }
        if (score <= 69) {
            return "MEDIUM";
        }
        return "HIGH";
    }

    private String snapshotNo(LocalDateTime now) {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase(Locale.ROOT);
        return "RK" + now.format(SNAPSHOT_NO_TIME_FORMAT) + suffix;
    }

    private String ruleSummaryJson(RiskAssessmentSnapshot snapshot) {
        return "{\"formula\":\"min(100, failedLoginCount * 5 + paymentFailureCount * 8"
            + " + noticeFailureCount * 4 + openComplaintCount * 6 + severeComplaintCount * 15)\","
            + "\"failedLoginCount\":" + snapshot.failedLoginCount
            + ",\"paymentFailureCount\":" + snapshot.paymentFailureCount
            + ",\"noticeFailureCount\":" + snapshot.noticeFailureCount
            + ",\"openComplaintCount\":" + snapshot.openComplaintCount
            + ",\"severeComplaintCount\":" + snapshot.severeComplaintCount
            + ",\"riskScore\":" + snapshot.riskScore.toPlainString()
            + ",\"riskLevel\":\"" + snapshot.riskLevel + "\"}";
    }

    private int count(Long value) {
        return value == null ? 0 : Math.toIntExact(value);
    }
}
