package com.example.venue.storedvalue;

import com.example.venue.common.BusinessException;
import com.example.venue.order.mapper.BizOrderMapper;
import com.example.venue.payment.service.OrderLocalSettlementService;
import com.example.venue.provider.schedule.InProcessProviderRunGuard;
import com.example.venue.storedvalue.domain.StoredValueCommands;
import com.example.venue.storedvalue.domain.StoredValuePolicy;
import com.example.venue.storedvalue.provider.DevStoredValueProvider;
import com.example.venue.storedvalue.provider.StoredValueProviderDescriptor;
import com.example.venue.storedvalue.provider.StoredValueProviderRefundResult;
import com.example.venue.storedvalue.provider.StoredValueProviderVerification;
import com.example.venue.storedvalue.service.StoredValueAccountService;
import com.example.venue.storedvalue.service.StoredValueAccountSnapshot;
import com.example.venue.storedvalue.service.StoredValueBonusExpiryService;
import com.example.venue.storedvalue.service.StoredValueConfigurationService;
import com.example.venue.storedvalue.service.StoredValueLedgerService;
import com.example.venue.storedvalue.service.StoredValueLedgerResult;
import com.example.venue.storedvalue.service.StoredValueProductSnapshot;
import com.example.venue.storedvalue.service.StoredValueRechargePersistenceService;
import com.example.venue.storedvalue.service.StoredValueRechargeRecoveryService;
import com.example.venue.storedvalue.service.StoredValueRechargeResult;
import com.example.venue.storedvalue.service.StoredValueRechargeService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@EnabledIfSystemProperty(named = "stored.value.integration", matches = "true")
@SpringBootTest(classes = StoredValueMySqlIntegrationTest.IntegrationApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {
        "spring.main.web-application-type=none",
        "spring.task.scheduling.enabled=false"
    })
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class StoredValueMySqlIntegrationTest {
    private static final String DEV_PROVIDER = "stored-value.dev";
    private static final String DEV_ENV = "DEV";
    private static final String DEV_NAME = "DEV_STORED_VALUE";
    private static final String DEV_CONFIG = "DEV_V1";
    private static final String DIGEST = "0000000000000000000000000000000000000000000000000000000000000000";

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @ComponentScan(basePackages = "com.example.venue.storedvalue")
    @Import(InProcessProviderRunGuard.class)
    static class IntegrationApplication {
    }

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> required("stored.value.jdbc-url"));
        registry.add("spring.datasource.username", () -> required("stored.value.jdbc-user"));
        registry.add("spring.datasource.password", () -> required("stored.value.jdbc-password"));
        registry.add("spring.flyway.enabled", () -> true);
        registry.add("spring.data.redis.host", () -> "127.0.0.1");
        registry.add("spring.data.redis.port", () -> 63999);
    }

    private static String required(String name) {
        String value = System.getProperty(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("missing integration property: " + name);
        }
        return value;
    }

    private final JdbcTemplate jdbc;
    private final StoredValueConfigurationService configurationService;
    private final StoredValueAccountService accountService;
    private final StoredValueLedgerService ledgerService;
    private final StoredValueRechargeService rechargeService;
    private final StoredValueRechargePersistenceService rechargePersistence;
    private final StoredValueRechargeRecoveryService rechargeRecoveryService;
    private final StoredValueBonusExpiryService bonusExpiryService;
    private StoredValueProductSnapshot product;
    private StoredValueProductSnapshot secondProduct;

    @MockBean
    private BizOrderMapper orderMapper;

    @MockBean
    private OrderLocalSettlementService orderSettlementService;

    @Autowired
    StoredValueMySqlIntegrationTest(JdbcTemplate jdbc,
                                    StoredValueConfigurationService configurationService,
                                    StoredValueAccountService accountService,
                                    StoredValueLedgerService ledgerService,
                                    StoredValueRechargeService rechargeService,
                                    StoredValueRechargePersistenceService rechargePersistence,
                                    StoredValueRechargeRecoveryService rechargeRecoveryService,
                                    StoredValueBonusExpiryService bonusExpiryService) {
        this.jdbc = jdbc;
        this.configurationService = configurationService;
        this.accountService = accountService;
        this.ledgerService = ledgerService;
        this.rechargeService = rechargeService;
        this.rechargePersistence = rechargePersistence;
        this.rechargeRecoveryService = rechargeRecoveryService;
        this.bonusExpiryService = bonusExpiryService;
    }

    @BeforeAll
    void configureFixtures() {
        product = configurationService.enableDevProduct("S05_IT", "S05 integration product", 1L);
        secondProduct = configurationService.enableDevProduct("S05_IT_2", "S05 second product", 1L);
        for (long userId = 99001L; userId <= 99012L; userId++) {
            ensureUser(userId);
            if (userId != 99001L) {
                jdbc.update("""
                    INSERT INTO stored_value_identity_verification
                      (user_id, verification_status, source, environment, evidence_digest, verified_at, operator_id)
                    VALUES (?, 'VERIFIED', 'DEV_MANUAL', 'DEV', ?, CURRENT_TIMESTAMP, 1)
                    """, userId, DIGEST);
            }
        }
        jdbc.update("""
            INSERT INTO stored_value_identity_verification
              (user_id, verification_status, source, environment, evidence_digest, verified_at, operator_id)
            VALUES (1, 'VERIFIED', 'DEV_MANUAL', 'DEV', ?, CURRENT_TIMESTAMP, 1)
            """, DIGEST);
    }

    @Test
    @Order(1)
    void failsClosedAndKeepsMoneyBoundaries() {
        assertThatThrownBy(() -> rechargeService.create(command(99001L, product.id(), "unverified", "10.00")))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("未完成储值身份核验");
        assertThatThrownBy(() -> rechargeService.create(command(1L, product.id(), "too-small", "9.99")))
            .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> rechargeService.create(command(1L, product.id(), "too-large", "5000.01")))
            .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> rechargeService.create(command(1L, product.id(), "three-decimals", "10.001")))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> rechargeService.create(new StoredValueCommands.CreateRecharge(
            1L, product.id(), new BigDecimal("10.00"), "USD", "wrong-currency", null, "TEST")))
            .isInstanceOf(IllegalArgumentException.class);

        StoredValueRechargeResult minimum = createPending(1L, "minimum", "10.00");
        StoredValueRechargeResult maximum = createPending(1L, "maximum", "5000.00");
        assertThat(minimum.principalAmount()).isEqualByComparingTo("10.00");
        assertThat(maximum.principalAmount()).isEqualByComparingTo("5000.00");
        rechargePersistence.releaseByNo(minimum.rechargeNo(), "CANCELED", "TEST_RELEASE");
        rechargePersistence.releaseByNo(maximum.rechargeNo(), "CANCELED", "TEST_RELEASE");
        assertThat(jdbc.queryForObject("SELECT status FROM stored_value_recharge WHERE id = ?", String.class, minimum.id()))
            .isEqualTo("CANCELED");
        assertThat(jdbc.queryForObject("SELECT status FROM stored_value_recharge WHERE id = ?", String.class, maximum.id()))
            .isEqualTo("CANCELED");
        assertThat(count("SELECT COUNT(*) FROM stored_value_recharge WHERE recharge_no IN (?, ?)",
            minimum.rechargeNo(), maximum.rechargeNo())).isEqualTo(2L);

        StoredValueRechargeResult invalidTarget = createPending(1L, "invalid-release-target", "10.00");
        assertThatThrownBy(() -> rechargePersistence.releaseByNo(
            invalidTarget.rechargeNo(), "SUCCEEDED", "INVALID_TRANSITION"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("不能用于释放处理中额度");
        assertThat(jdbc.queryForObject("SELECT status FROM stored_value_recharge WHERE id = ?", String.class, invalidTarget.id()))
            .isEqualTo("WAITING_PROVIDER");
        assertThat(count("SELECT COUNT(*) FROM stored_value_limit_reservation WHERE recharge_id = ? AND status = 'RESERVED'",
            invalidTarget.id())).isEqualTo(1L);
        rechargePersistence.releaseByNo(invalidTarget.rechargeNo(), "CANCELED", "TEST_RELEASE");
    }

    @Test
    @Order(2)
    void serializesAccountCreationAndPendingReservations() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<StoredValueAccountSnapshot> first = executor.submit(() -> {
                start.await();
                return accountService.ensureAccount(99002L, product.id());
            });
            Future<StoredValueAccountSnapshot> second = executor.submit(() -> {
                start.await();
                return accountService.ensureAccount(99002L, product.id());
            });
            start.countDown();
            assertThat(first.get().id()).isEqualTo(second.get().id());
        } finally {
            executor.shutdownNow();
        }
        assertThat(count("SELECT COUNT(*) FROM stored_value_account WHERE user_id = 99002 AND product_id = ?", product.id()))
            .isEqualTo(1L);

        List<StoredValueRechargeResult> pending = new ArrayList<>();
        for (int index = 0; index < 5; index++) {
            pending.add(createPending(1L, "pending-" + index, "10.00"));
        }
        assertThatThrownBy(() -> createPending(1L, "pending-overflow", "10.00"))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("处理中的充值");
        assertThat(count("SELECT pending_count FROM stored_value_limit_day_guard g JOIN stored_value_account a ON a.id = g.account_id WHERE a.user_id = 1 AND g.guard_date = CURRENT_DATE"))
            .isEqualTo(5L);
        for (StoredValueRechargeResult item : pending) {
            rechargePersistence.releaseByNo(item.rechargeNo(), "TIMED_OUT", "TEST_RELEASE");
        }
    }

    @Test
    @Order(3)
    void postsOnceUsesRechargePolicyAndRebuildsProjection() {
        StoredValueRechargeResult prepared = createPending(99003L, "policy-snapshot", "100.00");
        Long originalPolicyId = jdbc.queryForObject("SELECT current_policy_id FROM stored_value_product WHERE id = ?",
            Long.class, product.id());
        jdbc.update("""
            INSERT INTO stored_value_policy
              (product_id, policy_version, currency, effective_from, status,
               min_recharge_amount, max_recharge_amount, daily_recharge_limit, principal_balance_limit,
               total_balance_limit, max_pending_recharges, max_daily_initiations, bonus_rate_percent,
               bonus_per_recharge_limit, monthly_bonus_limit, bonus_valid_days, refund_compensation_valid_days,
               timezone_id, policy_digest, created_by)
            SELECT product_id, 'S05-TEST-SHORT-POLICY', currency, CURRENT_TIMESTAMP, 'ACTIVE',
                   min_recharge_amount, max_recharge_amount, daily_recharge_limit, principal_balance_limit,
                   total_balance_limit, max_pending_recharges, max_daily_initiations, bonus_rate_percent,
                   bonus_per_recharge_limit, monthly_bonus_limit, 1, refund_compensation_valid_days,
                   timezone_id, policy_digest, 1
            FROM stored_value_policy WHERE id = ?
            """, originalPolicyId);
        Long shortPolicyId = jdbc.queryForObject("SELECT id FROM stored_value_policy WHERE policy_version = 'S05-TEST-SHORT-POLICY' AND product_id = ?",
            Long.class, product.id());
        jdbc.update("UPDATE stored_value_product SET current_policy_id = ? WHERE id = ?", shortPolicyId, product.id());

        StoredValueRechargeResult success = rechargeService.handleCallback(callback(prepared, "policy-event", "100.00"));
        assertThat(success.status()).isEqualTo("SUCCEEDED");
        assertThat(jdbc.queryForObject("SELECT DATEDIFF(expires_at, issued_at) FROM stored_value_bonus_batch WHERE recharge_id = ?",
            Integer.class, prepared.id())).isEqualTo(180);
        StoredValueRechargeResult replay = rechargeService.handleCallback(callback(prepared, "policy-event", "100.00"));
        assertThat(replay.id()).isEqualTo(success.id());
        assertThat(count("SELECT COUNT(*) FROM stored_value_callback_evidence WHERE recharge_id = ? AND external_event_id = 'policy-event'",
            prepared.id())).isEqualTo(2L);
        assertThat(count("SELECT COUNT(*) FROM stored_value_ledger_transaction WHERE source_id = ? AND transaction_type = 'RECHARGE_PRINCIPAL_POSTING'",
            prepared.rechargeNo())).isEqualTo(1L);
        assertThat(count("SELECT COUNT(*) FROM stored_value_ledger_transaction WHERE source_id = ? AND transaction_type = 'RECHARGE_BONUS_POSTING'",
            prepared.rechargeNo())).isEqualTo(1L);
        assertThat(count("SELECT COUNT(*) FROM stored_value_operation_evidence WHERE recharge_id = ?", prepared.id()))
            .isGreaterThanOrEqualTo(1L);

        StoredValueAccountSnapshot account = accountService.requireAccount(99003L, product.id());
        assertThat(account.principalAvailable()).isEqualByComparingTo("100.00");
        assertThat(account.bonusAvailable()).isEqualByComparingTo("5.00");
        ledgerService.rebuildAccountProjection(account.id());
        StoredValueAccountSnapshot rebuilt = accountService.requireAccount(99003L, product.id());
        assertThat(rebuilt.principalAvailable()).isEqualByComparingTo(account.principalAvailable());
        assertThat(rebuilt.bonusAvailable()).isEqualByComparingTo(account.bonusAvailable());
        assertThat(count("""
            SELECT COUNT(*) FROM (
              SELECT transaction_id, SUM(CASE WHEN direction = 'DEBIT' THEN amount ELSE 0 END) debit_amount,
                     SUM(CASE WHEN direction = 'CREDIT' THEN amount ELSE 0 END) credit_amount
              FROM stored_value_ledger_entry GROUP BY transaction_id
            ) balanced WHERE debit_amount <> credit_amount
            """)).isZero();
        jdbc.update("UPDATE stored_value_product SET current_policy_id = ? WHERE id = ?", originalPolicyId, product.id());
    }

    @Test
    @Order(4)
    void rejectsCrossScopeRollsBackAndClassifiesCallbackFailures() {
        StoredValueAccountSnapshot account = accountService.ensureAccount(99004L, product.id());
        String businessKey = "TEST-ROLLBACK-" + account.id();
        StoredValueCommands.Ledger unbalancedProjection = new StoredValueCommands.Ledger(
            product.issuerId(), product.id(), account.id(), "CNY", "TEST_ROLLBACK", "TEST", businessKey,
            businessKey, StoredValuePolicy.VERSION, "test projection rollback", null, "TEST", LocalDateTime.now(), List.of(
            new StoredValueCommands.LedgerLine(account.id(), null, "PRINCIPAL_AVAILABLE", "DEBIT", new BigDecimal("1.00")),
            new StoredValueCommands.LedgerLine(null, null, "CHANNEL_CLEARING", "CREDIT", new BigDecimal("1.00"))));
        assertThatThrownBy(() -> ledgerService.append(unbalancedProjection)).isInstanceOf(BusinessException.class);
        assertThat(count("SELECT COUNT(*) FROM stored_value_ledger_transaction WHERE business_key = ?", businessKey)).isZero();
        assertThat(count("SELECT COUNT(*) FROM stored_value_ledger_entry e JOIN stored_value_ledger_transaction t ON t.id = e.transaction_id WHERE t.business_key = ?", businessKey)).isZero();

        StoredValueAccountSnapshot otherAccount = accountService.ensureAccount(99005L, secondProduct.id());
        assertThatThrownBy(() -> jdbc.update("""
            INSERT INTO stored_value_ledger_transaction
              (issuer_id, product_id, account_id, currency, transaction_type, source_type, source_id,
               business_key, payload_hash, policy_version, status, reason, system_source, occurred_at)
            VALUES (?, ?, ?, 'CNY', 'TEST_SCOPE', 'TEST', 'scope', 'TEST-SCOPE', ?, ?, 'POSTED', 'scope', 'TEST', CURRENT_TIMESTAMP)
            """, product.issuerId(), product.id(), otherAccount.id(), DIGEST, StoredValuePolicy.VERSION))
            .isInstanceOf(RuntimeException.class);

        StoredValueCommands.Callback unknown = new StoredValueCommands.Callback(
            DEV_PROVIDER, DEV_ENV, DEV_NAME, DEV_CONFIG, "SVR-UNKNOWN", "unknown-event", "unknown-ref", "DEV",
            "SUCCESS", "CNY", new BigDecimal("10.00"), "sig", "unknown callback");
        assertThatThrownBy(() -> rechargeService.handleCallback(unknown))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("充值业务不存在");
        assertThat(count("SELECT COUNT(*) FROM stored_value_callback_evidence WHERE external_event_id = 'unknown-event' AND processing_status = 'MANUAL_REVIEW'"))
            .isEqualTo(1L);

        StoredValueRechargeResult mismatch = createPending(99004L, "callback-mismatch", "20.00");
        StoredValueRechargeResult rejected = rechargeService.handleCallback(callback(mismatch, "mismatch-event", "19.99"));
        assertThat(rejected.status()).isEqualTo("MANUAL_REVIEW");
        assertThat(count("SELECT COUNT(*) FROM stored_value_limit_reservation WHERE recharge_id = ? AND status = 'RESERVED'", mismatch.id()))
            .isEqualTo(1L);
        rechargePersistence.releaseByNo(mismatch.rechargeNo(), "MANUAL_REVIEW", "TEST_RELEASE");

        Long transactionId = jdbc.queryForObject("SELECT id FROM stored_value_ledger_transaction WHERE transaction_type = 'RECHARGE_PRINCIPAL_POSTING' ORDER BY id LIMIT 1", Long.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE stored_value_ledger_transaction SET reason = 'tampered' WHERE id = ?", transactionId))
            .isInstanceOf(RuntimeException.class);
    }

    @Test
    @Order(5)
    void expiresRefundsFreezesDisputesAndClosesOnlyEmptyAccounts() {
        StoredValueRechargeResult expiring = createSuccess(99006L, "expiry", "100.00");
        jdbc.update("UPDATE stored_value_bonus_batch SET expires_at = CURRENT_TIMESTAMP WHERE recharge_id = ?", expiring.id());
        assertThat(bonusExpiryService.expireDue(LocalDateTime.now().plusSeconds(1))).isEqualTo(1);
        assertThat(bonusExpiryService.expireDue(LocalDateTime.now().plusSeconds(2))).isZero();
        assertThat(count("""
            SELECT COUNT(*)
            FROM stored_value_ledger_transaction t
            JOIN stored_value_bonus_batch b ON b.id = CAST(t.source_id AS UNSIGNED)
            WHERE t.transaction_type = 'BONUS_EXPIRY' AND b.recharge_id = ?
            """, expiring.id())).isEqualTo(1L);
        StoredValueRechargeResult refunded = rechargeService.refund(new StoredValueCommands.Refund(expiring.rechargeNo(), 101L, 102L, "test full recharge refund"));
        assertThat(refunded.status()).isEqualTo("REFUNDED");
        assertThat(jdbc.queryForObject("SELECT successful_amount FROM stored_value_limit_day_guard g JOIN stored_value_account a ON a.id = g.account_id WHERE a.user_id = 99006 AND g.guard_date = CURRENT_DATE", BigDecimal.class))
            .isEqualByComparingTo("100.00");

        StoredValueRechargeResult disputeRecharge = createSuccess(99006L, "dispute", "50.00");
        rechargeService.dispute(new StoredValueCommands.Dispute(disputeRecharge.rechargeNo(), "provider-case-1", new BigDecimal("20.00"), 101L, "test dispute"));
        StoredValueAccountSnapshot disputed = accountService.requireAccount(99006L, product.id());
        assertThat(disputed.principalAvailable()).isEqualByComparingTo("30.00");
        assertThat(disputed.principalFrozen()).isEqualByComparingTo("20.00");
        assertThat(disputed.principalAvailable()).isGreaterThanOrEqualTo(BigDecimal.ZERO);
        rechargeService.dispute(new StoredValueCommands.Dispute(disputeRecharge.rechargeNo(), "provider-case-1", new BigDecimal("20.00"), 101L, "replay"));
        assertThat(count("SELECT COUNT(*) FROM stored_value_dispute_case WHERE provider_case_id = 'provider-case-1'"))
            .isEqualTo(1L);

        StoredValueAccountSnapshot empty = accountService.ensureAccount(99007L, secondProduct.id());
        accountService.freezeAccount(empty.id(), 101L, "risk freeze", "close-flow-freeze");
        assertThatThrownBy(() -> accountService.unfreezeAccount(empty.id(), 101L, 101L, "self review", "close-flow-self"))
            .isInstanceOf(BusinessException.class);
        accountService.unfreezeAccount(empty.id(), 101L, 102L, "risk cleared", "close-flow-unfreeze");
        accountService.beginClosing(empty.id(), 101L, "close request", "close-flow-begin");
        accountService.close(empty.id(), 101L, "close approved", "close-flow-final");
        assertThat(accountService.requireAccount(99007L, secondProduct.id()).status()).isEqualTo("CLOSED");

        StoredValueRechargeResult refundFailureRecharge = createSuccess(99005L, "refund-failure", "20.00");
        StoredValueRechargePersistenceService.PreparedRefund prepared = rechargePersistence.prepareRefund(
            refundFailureRecharge.rechargeNo(), 101L, 102L, "provider failure");
        StoredValueRechargeResult pendingRefund = rechargePersistence.finalizeRefund(prepared,
            new StoredValueProviderRefundResult("FAILED", null, null, "provider unavailable", "PROVIDER_DOWN"));
        assertThat(pendingRefund.status()).isEqualTo("REFUNDING");
        assertThat(count("SELECT COUNT(*) FROM stored_value_recharge_refund WHERE recharge_id = ? AND status = 'REFUNDING'", refundFailureRecharge.id()))
            .isEqualTo(1L);
    }

    @Test
    @Order(6)
    void freezesAndUnfreezesNonEmptyBucketsThroughLedgerFacts() {
        StoredValueAccountSnapshot before = accountService.requireAccount(99006L, product.id());
        assertThat(before.principalAvailable()).isGreaterThan(BigDecimal.ZERO);
        assertThat(before.bonusAvailable()).isGreaterThan(BigDecimal.ZERO);

        accountService.freezeAccount(before.id(), 101L, "account freeze", "account-freeze-non-empty");
        StoredValueAccountSnapshot frozen = accountService.requireAccount(99006L, product.id());
        assertThat(frozen.principalAvailable()).isZero();
        assertThat(frozen.bonusAvailable()).isZero();
        assertThat(frozen.principalFrozen()).isEqualByComparingTo(before.principalFrozen().add(before.principalAvailable()));
        assertThat(frozen.bonusFrozen()).isEqualByComparingTo(before.bonusFrozen().add(before.bonusAvailable()));
        assertThat(count("""
            SELECT COUNT(*) FROM stored_value_ledger_transaction
            WHERE transaction_type = 'ACCOUNT_FREEZE' AND source_id = ?
            """, "account-freeze-non-empty")).isGreaterThanOrEqualTo(2L);

        ledgerService.rebuildAccountProjection(frozen.id());
        StoredValueAccountSnapshot rebuilt = accountService.requireAccount(99006L, product.id());
        assertThat(rebuilt.principalAvailable()).isEqualByComparingTo(frozen.principalAvailable());
        assertThat(rebuilt.principalFrozen()).isEqualByComparingTo(frozen.principalFrozen());
        assertThat(rebuilt.bonusAvailable()).isEqualByComparingTo(frozen.bonusAvailable());
        assertThat(rebuilt.bonusFrozen()).isEqualByComparingTo(frozen.bonusFrozen());

        accountService.unfreezeAccount(rebuilt.id(), 101L, 102L, "account unfreeze", "account-unfreeze-non-empty");
        StoredValueAccountSnapshot unfrozen = accountService.requireAccount(99006L, product.id());
        assertThat(unfrozen.principalAvailable()).isEqualByComparingTo(before.principalAvailable());
        assertThat(unfrozen.principalFrozen()).isEqualByComparingTo(before.principalFrozen());
        assertThat(unfrozen.bonusAvailable()).isEqualByComparingTo(before.bonusAvailable());
        assertThat(unfrozen.bonusFrozen()).isEqualByComparingTo(before.bonusFrozen());
    }

    @Test
    @Order(7)
    void replaysSameLedgerKeyWhenOnlyOccurredAtChanges() {
        StoredValueAccountSnapshot account = accountService.ensureAccount(99004L, product.id());
        String businessKey = "LEDGER-OCCURRED-AT-REPLAY-" + account.id();
        StoredValueCommands.Ledger first = ledgerCommand(account, businessKey,
            LocalDateTime.of(2026, 8, 3, 10, 0));
        StoredValueCommands.Ledger replay = ledgerCommand(account, businessKey,
            LocalDateTime.of(2026, 8, 3, 10, 1));

        StoredValueLedgerResult firstResult = ledgerService.append(first);
        StoredValueLedgerResult replayResult = ledgerService.append(replay);

        assertThat(firstResult.replayed()).isFalse();
        assertThat(replayResult.replayed()).isTrue();
        assertThat(replayResult.transactionId()).isEqualTo(firstResult.transactionId());
        assertThat(count("SELECT COUNT(*) FROM stored_value_ledger_transaction WHERE business_key = ?", businessKey))
            .isEqualTo(1L);
    }

    @Test
    @Order(8)
    void databaseRejectsPostedLedgerWithoutBalancedEntries() {
        StoredValueAccountSnapshot account = accountService.ensureAccount(99005L, product.id());
        String directPostedKey = "DB-POSTED-DIRECT-" + account.id();
        assertThatThrownBy(() -> jdbc.update("""
            INSERT INTO stored_value_ledger_transaction
              (issuer_id, product_id, account_id, currency, transaction_type, source_type, source_id,
               business_key, payload_hash, policy_version, status, reason, system_source, occurred_at)
            VALUES (?, ?, ?, 'CNY', 'TEST_DB_GUARD', 'TEST', 'direct-posted', ?, ?, 'POSTED', 'guard', 'TEST', CURRENT_TIMESTAMP)
            """, product.issuerId(), product.id(), account.id(), directPostedKey, DIGEST, StoredValuePolicy.VERSION))
            .isInstanceOf(RuntimeException.class);

        String unbalancedKey = "DB-POSTED-UNBALANCED-" + account.id();
        Long transactionId = insertOpenLedgerTransaction(account, unbalancedKey);
        jdbc.update("""
            INSERT INTO stored_value_ledger_entry
              (transaction_id, line_no, bucket, direction, amount, currency, issuer_id, product_id)
            VALUES (?, 1, 'CHANNEL_CLEARING', 'DEBIT', 1.00, 'CNY', ?, ?)
            """, transactionId, product.issuerId(), product.id());
        assertThatThrownBy(() -> jdbc.update("UPDATE stored_value_ledger_transaction SET status = 'POSTED' WHERE id = ?", transactionId))
            .isInstanceOf(RuntimeException.class);

        jdbc.update("""
            INSERT INTO stored_value_ledger_entry
              (transaction_id, line_no, bucket, direction, amount, currency, issuer_id, product_id)
            VALUES (?, 2, 'ORDER_SETTLEMENT', 'CREDIT', 1.00, 'CNY', ?, ?)
            """, transactionId, product.issuerId(), product.id());
        jdbc.update("UPDATE stored_value_ledger_transaction SET status = 'POSTED' WHERE id = ?", transactionId);
        assertThat(jdbc.queryForObject("SELECT status FROM stored_value_ledger_transaction WHERE id = ?", String.class, transactionId))
            .isEqualTo("POSTED");
    }

    @Test
    @Order(9)
    void malformedCallbackIsRecordedBeforeEnvelopeValidation() {
        StoredValueCommands.Callback malformed = new StoredValueCommands.Callback(
            null, null, null, null, null, null, null, null, null, null, null, null,
            "malformed callback");

        assertThatThrownBy(() -> rechargeService.handleCallback(malformed))
            .isInstanceOf(RuntimeException.class);
        assertThat(count("""
            SELECT COUNT(*)
            FROM stored_value_callback_evidence
            WHERE route = 'stored-value.callback'
              AND request_id LIKE 'SVRCV-%'
              AND provider_env IS NULL
              AND verification_status = 'REJECTED'
              AND processing_status = 'MANUAL_REVIEW'
              AND result_code = 'INVALID_CALLBACK_ENVELOPE'
            """)).isGreaterThanOrEqualTo(1L);
    }

    @Test
    @Order(10)
    void callbackSuccessPersistsPendingPostingBeforeARecoverableClaim() {
        StoredValueRechargeResult prepared = createPending(99008L, "pending-posting-recovery", "30.00");
        StoredValueCommands.Callback callback = callbackWithRequestId(
            callback(prepared, "pending-posting-event", "30.00"), "S03-PENDING-RECEIPT-1");
        StoredValueProviderVerification verification = new StoredValueProviderVerification(
            true, "SUCCESS", "provider-proof", new BigDecimal("30.00"), "CNY",
            "external-pending-posting", "verified", "verified");
        StoredValueRechargeResult pending = recordVerifiedPending(callback, verification);

        assertThat(pending.status()).isEqualTo("PROVIDER_SUCCEEDED_PENDING_POSTING");
        assertThat(jdbc.queryForObject("SELECT status FROM stored_value_recharge WHERE id = ?", String.class, prepared.id()))
            .isEqualTo("PROVIDER_SUCCEEDED_PENDING_POSTING");
        assertThat(count("SELECT COUNT(*) FROM stored_value_ledger_transaction WHERE source_id = ? AND transaction_type = 'RECHARGE_PRINCIPAL_POSTING'",
            prepared.rechargeNo())).isZero();

        StoredValueRechargePersistenceService.PostingClaim claim = rechargePersistence.claimPosting(
            prepared.rechargeNo(), "TEST_CRASH_AFTER_CLAIM", null, "test claim", false);
        assertThat(claim.acquired()).isTrue();
        assertThat(jdbc.queryForObject("SELECT status FROM stored_value_recharge WHERE id = ?", String.class, prepared.id()))
            .isEqualTo("POSTING");

        jdbc.update("UPDATE stored_value_recharge SET posting_started_at = DATE_SUB(CURRENT_TIMESTAMP, INTERVAL 600 SECOND) WHERE id = ?",
            prepared.id());
        StoredValueRechargeRecoveryService.RecoverySummary recovered = rechargeRecoveryService.recoverPendingPostings(10);

        assertThat(recovered.succeeded()).isEqualTo(1);
        StoredValueRechargeResult replay = rechargeService.handleCallback(
            callback(prepared, "pending-posting-replay", "30.00"));
        assertThat(replay.status()).isEqualTo("SUCCEEDED");
        assertThat(count("SELECT COUNT(*) FROM stored_value_ledger_transaction WHERE source_id = ? AND transaction_type = 'RECHARGE_PRINCIPAL_POSTING'",
            prepared.rechargeNo())).isEqualTo(1L);
        assertThat(count("SELECT COUNT(*) FROM stored_value_ledger_transaction WHERE source_id = ? AND transaction_type = 'RECHARGE_BONUS_POSTING'",
            prepared.rechargeNo())).isEqualTo(1L);
    }

    @Test
    @Order(11)
    void postingTransactionRollsBackAndAccountGateDoesNotCreateMoney() {
        StoredValueRechargeResult prepared = createPending(99009L, "posting-rollback", "40.00");
        StoredValueCommands.Callback callback = callbackWithRequestId(
            callback(prepared, "posting-rollback-event", "40.00"), "S03-ROLLBACK-RECEIPT-1");
        StoredValueProviderVerification verification = new StoredValueProviderVerification(
            true, "SUCCESS", "provider-proof", new BigDecimal("40.00"), "CNY",
            "external-rollback", "verified", "verified");
        recordVerifiedPending(callback, verification);
        StoredValueRechargePersistenceService.PostingClaim claim = rechargePersistence.claimPosting(
            prepared.rechargeNo(), "TEST_ROLLBACK", null, "test rollback", false);

        jdbc.update("UPDATE stored_value_limit_reservation SET status = 'RELEASED' WHERE recharge_id = ?", prepared.id());
        assertThatThrownBy(() -> rechargePersistence.completePosting(claim, "external-rollback"))
            .isInstanceOf(BusinessException.class);
        assertThat(jdbc.queryForObject("SELECT status FROM stored_value_recharge WHERE id = ?", String.class, prepared.id()))
            .isEqualTo("POSTING");
        assertThat(count("SELECT COUNT(*) FROM stored_value_ledger_transaction WHERE source_id = ? AND transaction_type = 'RECHARGE_PRINCIPAL_POSTING'",
            prepared.rechargeNo())).isZero();

        StoredValueRechargeResult frozen = createPending(99010L, "posting-account-gate", "20.00");
        StoredValueCommands.Callback frozenCallback = callbackWithRequestId(
            callback(frozen, "posting-account-gate-event", "20.00"), "S03-ACCOUNT-GATE-1");
        recordVerifiedPending(frozenCallback, new StoredValueProviderVerification(
            true, "SUCCESS", "provider-proof", new BigDecimal("20.00"), "CNY",
            "external-account-gate", "verified", "verified"));
        StoredValueAccountSnapshot frozenAccount = accountService.requireAccount(99010L, product.id());
        accountService.freezeAccount(frozenAccount.id(), 101L, "test account gate", "S03-account-gate");

        StoredValueRechargeResult manual = rechargeRecoveryService.recoverManually(
            frozen.rechargeNo(), 101L, "账户门禁变化后的人工恢复测试");
        assertThat(manual.status()).isEqualTo("MANUAL_REVIEW");
        assertThat(count("SELECT COUNT(*) FROM stored_value_ledger_transaction WHERE source_id = ? AND transaction_type = 'RECHARGE_PRINCIPAL_POSTING'",
            frozen.rechargeNo())).isZero();
    }

    @Test
    @Order(12)
    void concurrentPostingClaimsProduceOneSuccessfulLedgerOutcome() throws Exception {
        StoredValueRechargeResult prepared = createPending(99011L, "posting-concurrent", "60.00");
        StoredValueCommands.Callback callback = callbackWithRequestId(
            callback(prepared, "posting-concurrent-event", "60.00"), "S03-CONCURRENT-RECEIPT-1");
        recordVerifiedPending(callback, new StoredValueProviderVerification(
            true, "SUCCESS", "provider-proof", new BigDecimal("60.00"), "CNY",
            "external-concurrent", "verified", "verified"));

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<StoredValueRechargeResult> first = executor.submit(() -> {
                start.await();
                return rechargeService.postPending(prepared.rechargeNo(), null, "CONCURRENT_A", null, null, false);
            });
            Future<StoredValueRechargeResult> second = executor.submit(() -> {
                start.await();
                return rechargeService.postPending(prepared.rechargeNo(), null, "CONCURRENT_B", null, null, false);
            });
            start.countDown();
            StoredValueRechargeResult firstResult = first.get();
            StoredValueRechargeResult secondResult = second.get();
            assertThat(List.of(firstResult.status(), secondResult.status()))
                .contains("SUCCEEDED");
        } finally {
            executor.shutdownNow();
        }

        assertThat(jdbc.queryForObject("SELECT status FROM stored_value_recharge WHERE id = ?", String.class, prepared.id()))
            .isEqualTo("SUCCEEDED");
        assertThat(jdbc.queryForObject("SELECT posting_attempt_count FROM stored_value_recharge WHERE id = ?", Integer.class, prepared.id()))
            .isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM stored_value_ledger_transaction WHERE source_id = ? AND transaction_type = 'RECHARGE_PRINCIPAL_POSTING'",
            prepared.rechargeNo())).isEqualTo(1L);
        assertThat(count("SELECT COUNT(*) FROM stored_value_ledger_transaction WHERE source_id = ? AND transaction_type = 'RECHARGE_BONUS_POSTING'",
            prepared.rechargeNo())).isEqualTo(1L);
    }

    private StoredValueRechargeResult createSuccess(Long userId, String requestId, String amount) {
        StoredValueRechargeResult prepared = createPending(userId, requestId, amount);
        return rechargeService.handleCallback(callback(prepared, requestId + "-event", amount));
    }

    private StoredValueCommands.Ledger ledgerCommand(StoredValueAccountSnapshot account, String businessKey,
                                                     LocalDateTime occurredAt) {
        return new StoredValueCommands.Ledger(account.issuerId(), account.productId(), account.id(), "CNY",
            "TEST_OCCURRED_AT_REPLAY", "TEST", businessKey, businessKey, StoredValuePolicy.VERSION,
            "occurred at replay", null, "TEST", occurredAt, List.of(
            new StoredValueCommands.LedgerLine(null, null, "CHANNEL_CLEARING", "DEBIT", new BigDecimal("1.00")),
            new StoredValueCommands.LedgerLine(null, null, "ORDER_SETTLEMENT", "CREDIT", new BigDecimal("1.00"))));
    }

    private Long insertOpenLedgerTransaction(StoredValueAccountSnapshot account, String businessKey) {
        jdbc.update("""
            INSERT INTO stored_value_ledger_transaction
              (issuer_id, product_id, account_id, currency, transaction_type, source_type, source_id,
               business_key, payload_hash, policy_version, status, reason, system_source, occurred_at)
            VALUES (?, ?, ?, 'CNY', 'TEST_DB_GUARD', 'TEST', 'open', ?, ?, ?, 'OPEN', 'guard', 'TEST', CURRENT_TIMESTAMP)
            """, account.issuerId(), account.productId(), account.id(), businessKey, DIGEST, StoredValuePolicy.VERSION);
        return jdbc.queryForObject("SELECT id FROM stored_value_ledger_transaction WHERE business_key = ?",
            Long.class, businessKey);
    }

    private StoredValueRechargeResult createPending(Long userId, String requestId, String amount) {
        return rechargeService.create(command(userId, product.id(), requestId, amount));
    }

    private StoredValueCommands.CreateRecharge command(Long userId, Long productId, String requestId, String amount) {
        return new StoredValueCommands.CreateRecharge(userId, productId, new BigDecimal(amount), "CNY", requestId, null, "TEST");
    }

    private StoredValueCommands.Callback callback(StoredValueRechargeResult result, String eventId, String amount) {
        StoredValueCommands.Callback unsigned = new StoredValueCommands.Callback(
            DEV_PROVIDER, DEV_ENV, DEV_NAME, DEV_CONFIG, result.rechargeNo(), eventId, "external-" + eventId,
            DevStoredValueProvider.MERCHANT_IDENTIFIER, "SUCCESS", "CNY", new BigDecimal(amount), null,
            "minimal callback " + eventId);
        return new StoredValueCommands.Callback(
            unsigned.providerKey(), unsigned.providerEnv(), unsigned.providerName(), unsigned.configVersion(),
            unsigned.rechargeNo(), unsigned.externalEventId(), unsigned.externalRef(), unsigned.merchantIdentifier(),
            unsigned.status(), unsigned.currency(), unsigned.amount(), DevStoredValueProvider.signatureDigest(unsigned),
            unsigned.minimalSummary());
    }

    private StoredValueCommands.Callback callbackWithRequestId(StoredValueCommands.Callback callback, String requestId) {
        return new StoredValueCommands.Callback(callback.providerKey(), callback.providerEnv(), callback.providerName(),
            callback.configVersion(), callback.rechargeNo(), callback.externalEventId(), callback.externalRef(),
            callback.merchantIdentifier(), callback.status(), callback.currency(), callback.amount(),
            callback.signatureDigest(), callback.minimalSummary(), requestId);
    }

    private StoredValueRechargeResult recordVerifiedPending(StoredValueCommands.Callback callback,
                                                             StoredValueProviderVerification verification) {
        StoredValueProviderDescriptor descriptor = new StoredValueProviderDescriptor(
            DEV_PROVIDER, DEV_ENV, DEV_NAME, DEV_CONFIG, true);
        rechargePersistence.recordCallbackEvidence(callback, null, DIGEST);
        return rechargePersistence.recordCallbackOutcome(callback, DIGEST, verification, descriptor);
    }

    private void ensureUser(Long userId) {
        jdbc.update("""
            INSERT INTO sys_user (id, username, password, nickname, status, role_id, create_time, update_time, deleted,
                                  credential_version, user_version)
            VALUES (?, ?, 'test', ?, 'ACTIVE', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0, 0, 0)
            ON DUPLICATE KEY UPDATE status = 'ACTIVE', deleted = 0
            """, userId, "stored-value-" + userId, "Stored value " + userId);
    }

    private long count(String sql, Object... args) {
        Long value = jdbc.queryForObject(sql, Long.class, args);
        return value == null ? 0L : value;
    }
}
