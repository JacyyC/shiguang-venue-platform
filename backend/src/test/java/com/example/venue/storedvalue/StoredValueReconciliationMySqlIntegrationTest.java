package com.example.venue.storedvalue;

import com.example.venue.common.PageQuery;
import com.example.venue.order.mapper.BizOrderMapper;
import com.example.venue.payment.service.OrderLocalSettlementService;
import com.example.venue.provider.schedule.InProcessProviderRunGuard;
import com.example.venue.storedvalue.domain.StoredValueCommands;
import com.example.venue.storedvalue.provider.DevStoredValueProvider;
import com.example.venue.storedvalue.provider.DevStoredValueStatementProvider;
import com.example.venue.storedvalue.provider.StoredValueProviderDescriptor;
import com.example.venue.storedvalue.provider.StoredValueProviderVerification;
import com.example.venue.storedvalue.provider.StoredValueStatement;
import com.example.venue.storedvalue.provider.StoredValueStatementItem;
import com.example.venue.storedvalue.service.StoredValueApiService;
import com.example.venue.storedvalue.service.StoredValueConfigurationService;
import com.example.venue.storedvalue.service.StoredValueProductSnapshot;
import com.example.venue.storedvalue.service.StoredValueRechargePersistenceService;
import com.example.venue.storedvalue.service.StoredValueRechargeResult;
import com.example.venue.storedvalue.service.StoredValueRechargeService;
import com.example.venue.storedvalue.service.StoredValueReconciliationResult;
import com.example.venue.storedvalue.service.StoredValueReconciliationService;
import com.example.venue.storedvalue.service.StoredValueReference;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.mockito.ArgumentMatchers;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;

@EnabledIfSystemProperty(named = "stored.value.reconciliation.integration", matches = "true")
@SpringBootTest(classes = StoredValueReconciliationMySqlIntegrationTest.IntegrationApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {
        "spring.main.web-application-type=none",
        "spring.task.scheduling.enabled=false"
    })
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class StoredValueReconciliationMySqlIntegrationTest {
    private static final String DEV_PROVIDER = "stored-value.dev";
    private static final String DEV_ENV = "DEV";
    private static final String DEV_NAME = "DEV_STORED_VALUE";
    private static final String DEV_CONFIG = "DEV_V1";
    private static final String DIGEST = "2222222222222222222222222222222222222222222222222222222222222222";
    private static final AtomicLong IDS = new AtomicLong(70_000L);

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @ComponentScan(basePackages = "com.example.venue.storedvalue")
    @Import(InProcessProviderRunGuard.class)
    @MapperScan(basePackages = "com.example.venue.order.mapper")
    static class IntegrationApplication {
    }

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> required("stored.value.reconciliation.jdbc-url"));
        registry.add("spring.datasource.username", () -> required("stored.value.reconciliation.jdbc-user"));
        registry.add("spring.datasource.password", () -> required("stored.value.reconciliation.jdbc-password"));
        registry.add("spring.flyway.enabled", () -> true);
        registry.add("spring.data.redis.host", () -> "127.0.0.1");
        registry.add("spring.data.redis.port", () -> 63997);
    }

    private static String required(String name) {
        String value = System.getProperty(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("missing integration property: " + name);
        }
        return value;
    }

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private StoredValueConfigurationService configurationService;
    @Autowired
    private StoredValueRechargeService rechargeService;
    @Autowired
    private StoredValueRechargePersistenceService rechargePersistence;
    @Autowired
    private StoredValueReconciliationService reconciliationService;
    @Autowired
    private StoredValueApiService apiService;
    @SpyBean
    private DevStoredValueStatementProvider statementProvider;
    @MockBean
    private OrderLocalSettlementService orderSettlementService;

    private StoredValueProductSnapshot product;

    @BeforeAll
    void configureFixture() {
        product = configurationService.enableDevProduct("S05_RECON_IT", "S05 reconciliation product", 1L);
    }

    @Test
    @Order(1)
    void providerFailureIsRecordedAndARepeatFailureIsIdempotent() {
        LocalDate date = LocalDate.now().minusDays(2);
        doThrow(new IllegalStateException("fixture provider timeout"))
            .when(statementProvider).statement(ArgumentMatchers.any());

        StoredValueReconciliationResult failed = reconciliationService.reconcile(date, 1001L);
        StoredValueReconciliationResult repeated = reconciliationService.reconcile(date, 1001L);

        assertThat(failed.status()).isEqualTo("FAILED");
        assertThat(repeated.runId()).isEqualTo(failed.runId());
        assertThat(jdbc.queryForObject("SELECT failure_code FROM stored_value_reconciliation_run WHERE id = ?",
            String.class, failed.runId())).isEqualTo("STORED_VALUE_STATEMENT_FAILED");

        doReturn(new StoredValueStatement("DEV_FIXTURE", "retry", List.of(), "empty retry"))
            .when(statementProvider).statement(ArgumentMatchers.any());
        StoredValueReconciliationResult recovered = reconciliationService.reconcile(date, 1001L);
        assertThat(recovered.status()).isEqualTo("COMPLETED");
        assertThat(recovered.runId()).isNotEqualTo(failed.runId());
    }

    @Test
    @Order(2)
    void fullStatementReconciliationClassifiesPendingDuplicateMissingAndKeepsStableRerun() {
        long successUser = nextId();
        long refundUser = nextId();
        long disputeUser = nextId();
        long pendingUser = nextId();
        long missingProviderUser = nextId();
        long missingRefundUser = nextId();
        long missingDisputeUser = nextId();
        ensureVerifiedUser(successUser);
        ensureVerifiedUser(refundUser);
        ensureVerifiedUser(disputeUser);
        ensureVerifiedUser(pendingUser);
        ensureVerifiedUser(missingProviderUser);
        ensureVerifiedUser(missingRefundUser);
        ensureVerifiedUser(missingDisputeUser);

        StoredValueRechargeResult success = createSuccess(successUser, "recon-success-" + successUser, "100.00");
        StoredValueRechargeResult refundRecharge = createSuccess(refundUser, "recon-refund-" + refundUser, "100.00");
        rechargeService.refund(new StoredValueCommands.Refund(
            refundRecharge.rechargeNo(), 1001L, 1002L, "S05 provider refund"));
        StoredValueRechargeResult disputeRecharge = createSuccess(disputeUser, "recon-dispute-" + disputeUser, "100.00");
        rechargeService.dispute(new StoredValueCommands.Dispute(
            disputeRecharge.rechargeNo(), "provider-case-" + disputeUser, new BigDecimal("5.00"),
            1001L, "S05 dispute fixture"));
        StoredValueRechargeResult pending = createPendingPosting(pendingUser,
            "recon-pending-" + pendingUser, "50.00");
        createSuccess(missingProviderUser, "recon-missing-provider-" + missingProviderUser, "60.00");
        StoredValueRechargeResult missingRefundRecharge = createSuccess(missingRefundUser,
            "recon-missing-refund-" + missingRefundUser, "70.00");
        rechargeService.refund(new StoredValueCommands.Refund(
            missingRefundRecharge.rechargeNo(), 1001L, 1002L, "S05 missing Provider refund"));
        StoredValueRechargeResult missingDisputeRecharge = createSuccess(missingDisputeUser,
            "recon-missing-dispute-" + missingDisputeUser, "80.00");
        rechargeService.dispute(new StoredValueCommands.Dispute(
            missingDisputeRecharge.rechargeNo(), "provider-missing-dispute-" + missingDisputeUser,
            new BigDecimal("5.00"), 1001L, "S05 missing Provider dispute"));

        List<StoredValueStatementItem> items = new ArrayList<>();
        items.add(rechargeItem(success.rechargeNo(), new BigDecimal("101.00"), successUser));
        items.add(rechargeItem(refundRecharge.rechargeNo(), refundRecharge.principalAmount(), refundUser));
        items.add(refundItem(refundRecharge.rechargeNo(), new BigDecimal("99.00"), "FAILED"));
        items.add(disputeItem(disputeRecharge.rechargeNo(), disputeUser));
        items.add(rechargeItem(pending.rechargeNo(), pending.principalAmount(), pendingUser));
        items.add(rechargeItem(pending.rechargeNo(), pending.principalAmount(), pendingUser));
        items.add(rechargeItem(missingRefundRecharge.rechargeNo(), missingRefundRecharge.principalAmount(), missingRefundUser));
        items.add(rechargeItem(missingDisputeRecharge.rechargeNo(), missingDisputeRecharge.principalAmount(), missingDisputeUser));
        items.add(new StoredValueStatementItem("RECHARGE", "provider-only-record", "RECHARGE:provider-only",
            "provider-only-external", new BigDecimal("8.00"), "CNY", "SUCCEEDED", null,
            LocalDateTime.now(), null));
        items.add(new StoredValueStatementItem("RECHARGE", "provider-pending-record", "RECHARGE:provider-pending",
            "provider-pending-external", new BigDecimal("9.00"), "CNY", "PENDING", null,
            LocalDateTime.now(), null));
        StoredValueStatement statement = new StoredValueStatement("DEV_FIXTURE", "S05-full",
            items, "full fixture");
        doReturn(statement).when(statementProvider).statement(ArgumentMatchers.any());

        AtomicBoolean providerRanInsideTransaction = new AtomicBoolean(true);
        doAnswer(invocation -> {
            providerRanInsideTransaction.set(TransactionSynchronizationManager.isActualTransactionActive());
            return statement;
        }).when(statementProvider).statement(ArgumentMatchers.any());

        LocalDate date = LocalDate.now();
        StoredValueReconciliationResult result = reconciliationService.reconcile(date, 1001L);
        StoredValueReconciliationResult replay = reconciliationService.reconcile(date, 1001L);

        assertThat(providerRanInsideTransaction).isFalse();
        assertThat(result.status()).isEqualTo("MANUAL_REVIEW");
        assertThat(result.differenceCount()).isGreaterThanOrEqualTo(3);
        assertThat(replay.runId()).isEqualTo(result.runId());
        assertThat(replay.differenceCount()).isEqualTo(result.differenceCount());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM stored_value_reconciliation_difference_observation WHERE run_id = ?",
            Integer.class, result.runId())).isEqualTo(result.differenceCount());
        assertThat(jdbc.queryForList("SELECT DISTINCT difference_type FROM stored_value_reconciliation_difference WHERE run_id = ?",
            result.runId()).stream().map(row -> row.get("difference_type")).toList())
            .contains("RECHARGE_PENDING_POSTING", "PROVIDER_DUPLICATE_RECORD", "PROVIDER_RECHARGE_MISSING_LOCAL",
                "PROVIDER_RECHARGE_AMOUNT_DIFFERENCE", "PROVIDER_REFUND_AMOUNT_DIFFERENCE",
                "PROVIDER_REFUND_FAILED", "LOCAL_RECHARGE_MISSING_PROVIDER", "LOCAL_REFUND_MISSING_PROVIDER",
                "DISPUTE_MISSING_PROVIDER", "PROVIDER_RECHARGE_PENDING");
        assertThat(jdbc.queryForObject("SELECT statement_item_count FROM stored_value_reconciliation_run WHERE id = ?",
            Integer.class, result.runId())).isEqualTo(items.size());
        String expectedRunRef = StoredValueReference.summary(jdbc.queryForObject(
            "SELECT run_no FROM stored_value_reconciliation_run WHERE id = ?", String.class, result.runId()));

        PageQuery page = new PageQuery();
        page.setPageSize(100);
        assertThat(apiService.reconciliationRuns(page).getRecords())
            .anyMatch(run -> expectedRunRef.equals(run.runRef())
                && run.differenceCount() == result.differenceCount());
        assertThat(apiService.reconciliationDifferences(page).getRecords())
            .anyMatch(difference -> expectedRunRef.equals(difference.runRef()));
    }

    @Test
    @Order(3)
    void manualAndScheduledLaneShareTheSameInProcessMutex() throws Exception {
        LocalDate date = LocalDate.now().minusDays(1);
        CountDownLatch providerEntered = new CountDownLatch(1);
        CountDownLatch releaseProvider = new CountDownLatch(1);
        doAnswer(invocation -> {
            providerEntered.countDown();
            assertThat(releaseProvider.await(10, TimeUnit.SECONDS)).isTrue();
            return new StoredValueStatement("DEV_FIXTURE", "mutex", List.of(
                new StoredValueStatementItem("UNKNOWN", "mutex-record", "mutex-business",
                    "mutex-external", new BigDecimal("1.00"), "CNY", "SUCCEEDED", null,
                    LocalDateTime.now(), null)), "mutex fixture");
        }).when(statementProvider).statement(ArgumentMatchers.any());

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<StoredValueReconciliationResult> owner = pool.submit(() -> reconciliationService.reconcile(date, 1001L));
            assertThat(providerEntered.await(10, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> reconciliationService.reconcile(date, 1001L))
                .hasMessageContaining("正在运行");
            releaseProvider.countDown();
            assertThat(owner.get(10, TimeUnit.SECONDS).status()).isEqualTo("MANUAL_REVIEW");
        } finally {
            releaseProvider.countDown();
            pool.shutdownNow();
        }
    }

    private StoredValueStatementItem rechargeItem(String rechargeNo, BigDecimal amount, long userId) {
        return jdbc.query("""
            SELECT provider_business_no, external_ref FROM stored_value_recharge
            WHERE recharge_no = ? AND user_id = ?
            """, rs -> {
            if (!rs.next()) {
                throw new IllegalStateException("missing recharge fixture: " + rechargeNo);
            }
            return new StoredValueStatementItem("RECHARGE", "fixture-recharge:" + rechargeNo,
                rs.getString("provider_business_no"), rs.getString("external_ref"), amount, "CNY",
                "SUCCEEDED", null, LocalDateTime.now(), null);
        }, rechargeNo, userId);
    }

    private StoredValueStatementItem refundItem(String rechargeNo) {
        return refundItem(rechargeNo, null, null);
    }

    private StoredValueStatementItem refundItem(String rechargeNo, BigDecimal amount, String status) {
        return jdbc.query("""
            SELECT f.refund_no, f.external_ref, f.amount FROM stored_value_recharge_refund f
            JOIN stored_value_recharge r ON r.id = f.recharge_id WHERE r.recharge_no = ?
            """, rs -> {
            if (!rs.next()) {
                throw new IllegalStateException("missing refund fixture: " + rechargeNo);
            }
            return new StoredValueStatementItem("REFUND", "fixture-refund:" + rs.getString("refund_no"),
                rs.getString("refund_no"), rs.getString("external_ref"),
                amount == null ? rs.getBigDecimal("amount") : amount, "CNY",
                status == null ? "SUCCEEDED" : status, null, LocalDateTime.now(), null);
        }, rechargeNo);
    }

    private StoredValueStatementItem disputeItem(String rechargeNo, long userId) {
        return jdbc.query("""
            SELECT d.case_no, d.provider_case_id, d.frozen_amount FROM stored_value_dispute_case d
            JOIN stored_value_recharge r ON r.id = d.recharge_id
            WHERE r.recharge_no = ? AND r.user_id = ?
            """, rs -> {
            if (!rs.next()) {
                throw new IllegalStateException("missing dispute fixture: " + rechargeNo);
            }
            return new StoredValueStatementItem("DISPUTE", "fixture-dispute:" + rs.getString("case_no"),
                rs.getString("case_no"), null, rs.getBigDecimal("frozen_amount"), "CNY",
                "UNDER_REVIEW", rs.getString("provider_case_id"), LocalDateTime.now(), null);
        }, rechargeNo, userId);
    }

    private StoredValueRechargeResult createSuccess(long userId, String requestId, String amount) {
        StoredValueRechargeResult prepared = rechargeService.create(new StoredValueCommands.CreateRecharge(
            userId, product.id(), new BigDecimal(amount), "CNY", requestId, null, "TEST"));
        StoredValueCommands.Callback unsigned = new StoredValueCommands.Callback(
            DEV_PROVIDER, DEV_ENV, DEV_NAME, DEV_CONFIG, prepared.rechargeNo(), requestId + "-event",
            "external-" + requestId, DevStoredValueProvider.MERCHANT_IDENTIFIER, "SUCCESS", "CNY",
            new BigDecimal(amount), null, "S05 reconciliation callback");
        return rechargeService.handleCallback(new StoredValueCommands.Callback(
            unsigned.providerKey(), unsigned.providerEnv(), unsigned.providerName(), unsigned.configVersion(),
            unsigned.rechargeNo(), unsigned.externalEventId(), unsigned.externalRef(), unsigned.merchantIdentifier(),
            unsigned.status(), unsigned.currency(), unsigned.amount(), DevStoredValueProvider.signatureDigest(unsigned),
            unsigned.minimalSummary()));
    }

    private StoredValueRechargeResult createPendingPosting(long userId, String requestId, String amount) {
        StoredValueRechargeResult prepared = rechargeService.create(new StoredValueCommands.CreateRecharge(
            userId, product.id(), new BigDecimal(amount), "CNY", requestId, null, "TEST"));
        StoredValueCommands.Callback unsigned = new StoredValueCommands.Callback(
            DEV_PROVIDER, DEV_ENV, DEV_NAME, DEV_CONFIG, prepared.rechargeNo(), requestId + "-event",
            "external-" + requestId, DevStoredValueProvider.MERCHANT_IDENTIFIER, "SUCCESS", "CNY",
            new BigDecimal(amount), null, "S05 pending posting callback", "S05-" + requestId);
        StoredValueCommands.Callback callback = new StoredValueCommands.Callback(
            unsigned.providerKey(), unsigned.providerEnv(), unsigned.providerName(), unsigned.configVersion(),
            unsigned.rechargeNo(), unsigned.externalEventId(), unsigned.externalRef(), unsigned.merchantIdentifier(),
            unsigned.status(), unsigned.currency(), unsigned.amount(), DevStoredValueProvider.signatureDigest(unsigned),
            unsigned.minimalSummary(), unsigned.requestId());
        StoredValueProviderVerification verification = new StoredValueProviderVerification(
            true, "SUCCESS", "provider-proof", new BigDecimal(amount), "CNY", unsigned.externalRef(),
            "verified", "verified");
        StoredValueProviderDescriptor descriptor = new StoredValueProviderDescriptor(
            DEV_PROVIDER, DEV_ENV, DEV_NAME, DEV_CONFIG, true);
        rechargePersistence.recordCallbackEvidence(callback, null, DIGEST);
        return rechargePersistence.recordCallbackOutcome(callback, DIGEST, verification, descriptor);
    }

    private void ensureVerifiedUser(long userId) {
        jdbc.update("""
            INSERT INTO sys_user (id, username, password, nickname, status, role_id, create_time, update_time, deleted,
                                  credential_version, user_version)
            VALUES (?, ?, 'test', ?, 'ACTIVE', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0, 0, 0)
            ON DUPLICATE KEY UPDATE status = 'ACTIVE', deleted = 0
            """, userId, "s05-user-" + userId, "S05 user " + userId);
        jdbc.update("""
            INSERT INTO stored_value_identity_verification
              (user_id, verification_status, source, environment, evidence_digest, verified_at, operator_id)
            VALUES (?, 'VERIFIED', 'DEV_MANUAL', 'DEV', ?, DATE_SUB(CURRENT_TIMESTAMP, INTERVAL 1 SECOND), 1)
            """, userId, DIGEST);
    }

    private long nextId() {
        return IDS.incrementAndGet();
    }
}
