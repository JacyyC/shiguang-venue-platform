package com.example.venue.storedvalue;

import com.example.venue.order.entity.BizOrder;
import com.example.venue.order.mapper.BizOrderMapper;
import com.example.venue.payment.service.OrderLocalSettlementService;
import com.example.venue.payment.service.PaidOrderFact;
import com.example.venue.provider.schedule.InProcessProviderRunGuard;
import com.example.venue.storedvalue.domain.StoredValueCommands;
import com.example.venue.storedvalue.provider.DevStoredValueProvider;
import com.example.venue.storedvalue.service.StoredValueAccountService;
import com.example.venue.storedvalue.service.StoredValueAccountSnapshot;
import com.example.venue.storedvalue.service.StoredValueConfigurationService;
import com.example.venue.storedvalue.service.StoredValueConfigService;
import com.example.venue.storedvalue.service.StoredValueLedgerService;
import com.example.venue.storedvalue.service.StoredValueLedgerResult;
import com.example.venue.storedvalue.service.StoredValueOrderPaymentService;
import com.example.venue.storedvalue.service.StoredValueOrderPaymentResult;
import com.example.venue.storedvalue.service.StoredValueOrderRefundResult;
import com.example.venue.storedvalue.service.StoredValueProductSnapshot;
import com.example.venue.storedvalue.service.StoredValueReconciliationResult;
import com.example.venue.storedvalue.service.StoredValueReconciliationService;
import com.example.venue.storedvalue.service.StoredValueRechargeResult;
import com.example.venue.storedvalue.service.StoredValueRechargeService;
import org.flywaydb.core.Flyway;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@EnabledIfSystemProperty(named = "stored.value.order.integration", matches = "true")
@SpringBootTest(classes = StoredValueOrderPaymentMySqlIntegrationTest.IntegrationApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {
        "spring.main.web-application-type=none",
        "spring.task.scheduling.enabled=false"
    })
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class StoredValueOrderPaymentMySqlIntegrationTest {
    private static final String DEV_PROVIDER = "stored-value.dev";
    private static final String DEV_ENV = "DEV";
    private static final String DEV_NAME = "DEV_STORED_VALUE";
    private static final String DEV_CONFIG = "DEV_V1";
    private static final String DIGEST = "0000000000000000000000000000000000000000000000000000000000000000";
    private static final AtomicLong IDS = new AtomicLong(60_000L);

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @ComponentScan(basePackages = "com.example.venue.storedvalue")
    @Import(InProcessProviderRunGuard.class)
    @MapperScan(basePackages = "com.example.venue.order.mapper")
    static class IntegrationApplication {
    }

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> required("stored.value.order.jdbc-url"));
        registry.add("spring.datasource.username", () -> required("stored.value.order.jdbc-user"));
        registry.add("spring.datasource.password", () -> required("stored.value.order.jdbc-password"));
        registry.add("spring.flyway.enabled", () -> true);
        registry.add("spring.flyway.locations", () -> "classpath:db/migration");
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

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private BizOrderMapper orderMapper;
    @Autowired
    private StoredValueConfigurationService configurationService;
    @Autowired
    private StoredValueConfigService configService;
    @Autowired
    private StoredValueRechargeService rechargeService;
    @Autowired
    private StoredValueAccountService accountService;
    @SpyBean
    private StoredValueLedgerService ledgerService;
    @Autowired
    private StoredValueOrderPaymentService orderPaymentService;
    @Autowired
    private StoredValueReconciliationService reconciliationService;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @MockBean
    private OrderLocalSettlementService orderSettlementService;

    private StoredValueProductSnapshot product;

    @BeforeAll
    void configureFixtures() {
        product = configurationService.enableDevProduct("S06_IT", "S06 order payment integration product", 1L);
        jdbc.update("UPDATE stored_value_product SET stored_value_spend_enabled = 1 WHERE id = ?", product.id());
        product = configService.requireProduct(product.id());
    }

    @BeforeEach
    void resetSettlementStub() {
        reset(orderSettlementService);
    }

    @Test
    @Order(1)
    void migratesV1ToV33AndRepeatMigrateIsStable() {
        Flyway flyway = Flyway.configure()
            .dataSource(required("stored.value.order.jdbc-url"), required("stored.value.order.jdbc-user"),
                required("stored.value.order.jdbc-password"))
            .locations("classpath:db/migration")
            .baselineOnMigrate(false)
            .load();
        flyway.migrate();
        flyway.migrate();

        // 迁移为 append-only：批次新增 V34+ 后计数随之增长，不再锁死 33（S08 实测修正），
        // 仅要求全部成功且不低于本测试依赖的 V33。
        Integer migrated = jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE success = 1", Integer.class);
        assertThat(migrated).isGreaterThanOrEqualTo(33);
        assertThat(jdbc.queryForObject("SELECT version FROM flyway_schema_history WHERE success = 1 ORDER BY installed_rank DESC LIMIT 1", String.class))
            .isEqualTo("36");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name IN (?, ?, ?, ?)",
            Integer.class, "stored_value_order_payment", "stored_value_order_payment_allocation",
            "stored_value_order_refund", "stored_value_order_refund_allocation")).isEqualTo(4);
    }

    @Test
    @Order(2)
    void paysByFefoReplaysWithoutDoubleDebitAndSurvivesSpendDisable() {
        long userId = nextId();
        ensureVerifiedUser(userId);
        createSuccess(userId, "fefo-a-" + userId, "100.00");
        createSuccess(userId, "fefo-b-" + userId, "20.00");
        long orderId = insertOrder(userId, "6.00");

        StoredValueOrderPaymentResult result = orderPaymentService.pay(
            new StoredValueCommands.OrderPayment(orderId, userId, product.id(), "fefo-key-" + orderId, "TEST"));
        List<Map<String, Object>> allocations = jdbc.queryForList("""
            SELECT bucket, bonus_batch_id, amount
            FROM stored_value_order_payment_allocation
            WHERE payment_id = ? ORDER BY allocation_no
            """, result.paymentId());

        assertThat(result.status()).isEqualTo("SUCCEEDED");
        assertThat(result.principalAmount()).isEqualByComparingTo("0.00");
        assertThat(result.bonusAmount()).isEqualByComparingTo("6.00");
        assertThat(allocations).hasSize(2);
        assertThat(allocations.get(0)).containsEntry("amount", new BigDecimal("5.00"));
        assertThat(allocations.get(1)).containsEntry("amount", new BigDecimal("1.00"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM stored_value_ledger_transaction WHERE source_id = ?",
            Integer.class, result.paymentNo())).isEqualTo(1);

        jdbc.update("UPDATE stored_value_product SET stored_value_spend_enabled = 0 WHERE id = ?", product.id());
        StoredValueOrderPaymentResult replay = orderPaymentService.pay(
            new StoredValueCommands.OrderPayment(orderId, userId, product.id(), "fefo-key-" + orderId, "TEST"));
        jdbc.update("UPDATE stored_value_product SET stored_value_spend_enabled = 1 WHERE id = ?", product.id());

        assertThat(replay.replayed()).isTrue();
        assertThat(replay.paymentId()).isEqualTo(result.paymentId());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM stored_value_order_payment WHERE order_id = ?", Integer.class, orderId))
            .isEqualTo(1);
        StoredValueAccountSnapshot account = accountService.requireAccount(userId, product.id());
        assertThat(account.principalAvailable()).isEqualByComparingTo("120.00");
        assertThat(account.bonusAvailable()).isEqualByComparingTo("0.00");
        verify(orderSettlementService, times(1)).settle(any());
    }

    @Test
    @Order(3)
    void orderLockSerializesStoredValueAgainstExternalPaymentFact() throws Exception {
        long userId = nextId();
        ensureVerifiedUser(userId);
        createSuccess(userId, "race-fund-" + userId, "100.00");
        long orderId = insertOrder(userId, "10.00");
        CountDownLatch settlementEntered = new CountDownLatch(1);
        CountDownLatch releaseSettlement = new CountDownLatch(1);
        doAnswer(invocation -> {
            PaidOrderFact fact = invocation.getArgument(0, PaidOrderFact.class);
            settlementEntered.countDown();
            assertThat(releaseSettlement.await(10, TimeUnit.SECONDS)).isTrue();
            markOrderPaid(fact);
            return null;
        }).when(orderSettlementService).settle(any());

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<StoredValueOrderPaymentResult> storedPayment = executor.submit(() -> orderPaymentService.pay(
                new StoredValueCommands.OrderPayment(orderId, userId, product.id(), "race-key-" + orderId, "TEST")));
            assertThat(settlementEntered.await(10, TimeUnit.SECONDS)).isTrue();
            TransactionTemplate transaction = new TransactionTemplate(transactionManager);
            Future<Boolean> externalPayment = executor.submit(() -> transaction.execute(status -> {
                BizOrder locked = orderMapper.selectByIdForUpdate(orderId);
                if (!"PENDING_PAYMENT".equals(locked.status)) {
                    return false;
                }
                locked.paymentToolType = OrderLocalSettlementService.EXTERNAL_PROVIDER_TOOL;
                locked.paymentFactId = "external-race-" + orderId;
                locked.paymentCurrency = "CNY";
                locked.status = "PAID";
                orderMapper.updateById(locked);
                return true;
            }));
            releaseSettlement.countDown();

            assertThat(storedPayment.get(10, TimeUnit.SECONDS).status()).isEqualTo("SUCCEEDED");
            assertThat(externalPayment.get(10, TimeUnit.SECONDS)).isFalse();
        } finally {
            executor.shutdownNow();
        }

        assertThat(jdbc.queryForObject("SELECT payment_tool_type FROM biz_order WHERE id = ?", String.class, orderId))
            .isEqualTo(OrderLocalSettlementService.STORED_VALUE_TOOL);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM stored_value_order_payment WHERE order_id = ?", Integer.class, orderId))
            .isEqualTo(1);
    }

    @Test
    @Order(4)
    void serializesSameAccountOrdersAndRejectsFrozenOrInsufficientBalanceWithoutPartialFacts() throws Exception {
        long userId = nextId();
        ensureVerifiedUser(userId);
        createSuccess(userId, "parallel-fund-" + userId, "10.00");
        long firstOrder = insertOrder(userId, "8.00");
        long secondOrder = insertOrder(userId, "8.00");

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch start = new CountDownLatch(1);
            Future<Boolean> first = executor.submit(() -> payWithoutThrow(start, firstOrder, userId));
            Future<Boolean> second = executor.submit(() -> payWithoutThrow(start, secondOrder, userId));
            start.countDown();
            assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
                .containsExactlyInAnyOrder(true, false);
        } finally {
            executor.shutdownNow();
        }

        StoredValueAccountSnapshot account = accountService.requireAccount(userId, product.id());
        assertThat(account.principalAvailable()).isEqualByComparingTo("2.50");
        assertThat(account.bonusAvailable()).isEqualByComparingTo("0.00");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM stored_value_order_payment WHERE order_id IN (?, ?)",
            Integer.class, firstOrder, secondOrder)).isEqualTo(1);

        long frozenUser = nextId();
        ensureVerifiedUser(frozenUser);
        createSuccess(frozenUser, "frozen-fund-" + frozenUser, "20.00");
        StoredValueAccountSnapshot frozenAccount = accountService.requireAccount(frozenUser, product.id());
        accountService.freezeAccount(frozenAccount.id(), 1001L, "integration freeze", "S06-FREEZE-" + frozenUser);
        long frozenOrder = insertOrder(frozenUser, "1.00");
        assertThatThrownBy(() -> orderPaymentService.pay(new StoredValueCommands.OrderPayment(
            frozenOrder, frozenUser, product.id(), "frozen-key-" + frozenOrder, "TEST")))
            .hasMessageContaining("不允许消费");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM stored_value_order_payment WHERE order_id = ?", Integer.class, frozenOrder))
            .isZero();
    }

    @Test
    @Order(5)
    void rollsBackLedgerAndAllocationWhenLocalSettlementFails() {
        long userId = nextId();
        ensureVerifiedUser(userId);
        createSuccess(userId, "rollback-fund-" + userId, "50.00");
        long orderId = insertOrder(userId, "10.00");
        StoredValueAccountSnapshot before = accountService.requireAccount(userId, product.id());
        int ledgerCountBefore = jdbc.queryForObject(
            "SELECT COUNT(*) FROM stored_value_ledger_transaction WHERE source_type = 'ORDER_PAYMENT'",
            Integer.class);
        doThrow(new IllegalStateException("injected local settlement failure"))
            .when(orderSettlementService).settle(any());

        assertThatThrownBy(() -> orderPaymentService.pay(new StoredValueCommands.OrderPayment(
            orderId, userId, product.id(), "rollback-key-" + orderId, "TEST")))
            .isInstanceOf(IllegalStateException.class);

        StoredValueAccountSnapshot after = accountService.requireAccount(userId, product.id());
        assertThat(after.principalAvailable()).isEqualByComparingTo(before.principalAvailable());
        assertThat(after.bonusAvailable()).isEqualByComparingTo(before.bonusAvailable());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM stored_value_order_payment WHERE order_id = ?", Integer.class, orderId))
            .isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM stored_value_ledger_transaction WHERE source_type = 'ORDER_PAYMENT' AND source_id LIKE ?",
            Integer.class, "SVOP-%")).isEqualTo(ledgerCountBefore);
    }

    @Test
    @Order(6)
    void refundsOriginalBucketsCompensatesExpiredBonusAndReplaysOnce() {
        long expiredUser = nextId();
        ensureVerifiedUser(expiredUser);
        createSuccess(expiredUser, "refund-expired-fund-" + expiredUser, "100.00");
        long expiredOrder = insertOrder(expiredUser, "10.00");
        StoredValueOrderPaymentResult expiredPayment = orderPaymentService.pay(
            new StoredValueCommands.OrderPayment(expiredOrder, expiredUser, product.id(), "refund-pay-" + expiredOrder, "TEST"));
        jdbc.update("""
            UPDATE stored_value_order_payment_allocation
            SET expires_at_snapshot = DATE_SUB(CURRENT_TIMESTAMP, INTERVAL 1 DAY)
            WHERE payment_id = ? AND bucket = 'BONUS_AVAILABLE'
            """, expiredPayment.paymentId());

        StoredValueOrderRefundResult expiredRefund = orderPaymentService.refund(
            new StoredValueCommands.OrderRefund(expiredOrder, null, "refund-key-" + expiredOrder, "expired bonus"));
        StoredValueOrderRefundResult refundReplay = orderPaymentService.refund(
            new StoredValueCommands.OrderRefund(expiredOrder, null, "refund-key-" + expiredOrder, "expired bonus"));

        assertThat(expiredRefund.status()).isEqualTo("SUCCEEDED");
        assertThat(expiredRefund.principalAmount()).isEqualByComparingTo("5.00");
        assertThat(expiredRefund.bonusAmount()).isEqualByComparingTo("5.00");
        assertThat(expiredRefund.compensationAmount()).isEqualByComparingTo("5.00");
        assertThat(refundReplay.replayed()).isTrue();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM stored_value_order_refund WHERE order_id = ?", Integer.class, expiredOrder))
            .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM stored_value_ledger_transaction WHERE source_type = 'ORDER_REFUND' AND source_id = ?",
            Integer.class, expiredRefund.refundNo())).isEqualTo(2);
        assertThat(jdbc.queryForObject("""
            SELECT COUNT(*) FROM stored_value_bonus_batch
            WHERE source_order_payment_id = ? AND compensation_ref = ?
            """, Integer.class, expiredPayment.paymentId(), expiredRefund.refundNo())).isEqualTo(1);
        assertThat(accountService.requireAccount(expiredUser, product.id()).principalAvailable())
            .isEqualByComparingTo("100.00");

        long activeUser = nextId();
        ensureVerifiedUser(activeUser);
        createSuccess(activeUser, "refund-active-fund-" + activeUser, "100.00");
        long activeOrder = insertOrder(activeUser, "3.00");
        orderPaymentService.pay(new StoredValueCommands.OrderPayment(
            activeOrder, activeUser, product.id(), "refund-active-pay-" + activeOrder, "TEST"));
        StoredValueOrderRefundResult activeRefund = orderPaymentService.refund(
            new StoredValueCommands.OrderRefund(activeOrder, null, "refund-active-key-" + activeOrder, "active bonus"));
        assertThat(activeRefund.compensationAmount()).isEqualByComparingTo("0.00");
        assertThat(activeRefund.bonusAmount()).isEqualByComparingTo("3.00");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM stored_value_bonus_batch WHERE source_order_payment_id = ?",
            Integer.class, jdbc.queryForObject("SELECT id FROM stored_value_order_payment WHERE order_id = ?", Long.class, activeOrder)))
            .isZero();
    }

    @Test
    @Order(7)
    void reconcilesAllocationAndAllProjectionBucketsAndCanRebuildProjection() {
        long userId = nextId();
        ensureVerifiedUser(userId);
        createSuccess(userId, "recon-fund-" + userId, "100.00");
        long orderId = insertOrder(userId, "5.00");
        StoredValueOrderPaymentResult payment = orderPaymentService.pay(
            new StoredValueCommands.OrderPayment(orderId, userId, product.id(), "recon-key-" + orderId, "TEST"));
        LocalDate reconDate = LocalDate.now(ZoneId.of("Asia/Shanghai"));

        StoredValueReconciliationResult clean = reconciliationService.reconcile(reconDate, 1001L);
        assertThat(clean.status()).isEqualTo("COMPLETED");
        assertThat(clean.differenceCount()).isZero();

        jdbc.update("UPDATE stored_value_order_payment_allocation SET amount = amount + 0.01 WHERE payment_id = ? LIMIT 1",
            payment.paymentId());
        StoredValueReconciliationResult allocationMismatch = reconciliationService.reconcile(reconDate, 1001L);
        assertThat(allocationMismatch.status()).isEqualTo("MANUAL_REVIEW");
        assertThat(jdbc.queryForObject("""
            SELECT COUNT(*) FROM stored_value_reconciliation_difference
            WHERE run_id = ? AND difference_type = 'ORDER_PAYMENT_ALLOCATION_DIFFERENCE'
            """, Integer.class, allocationMismatch.runId())).isEqualTo(1);

        Long accountId = jdbc.queryForObject("SELECT account_id FROM stored_value_order_payment WHERE id = ?", Long.class,
            payment.paymentId());
        jdbc.update("UPDATE stored_value_order_payment_allocation SET amount = amount - 0.01 WHERE payment_id = ? LIMIT 1",
            payment.paymentId());
        jdbc.update("UPDATE stored_value_account SET principal_available = principal_available + 0.01 WHERE id = ?", accountId);
        StoredValueReconciliationResult projectionMismatch = reconciliationService.reconcile(reconDate, 1001L);
        assertThat(projectionMismatch.status()).isEqualTo("MANUAL_REVIEW");
        assertThat(jdbc.queryForObject("""
            SELECT COUNT(*) FROM stored_value_reconciliation_difference
            WHERE run_id = ? AND difference_type = 'ACCOUNT_PROJECTION_DIFFERENCE'
            """, Integer.class, projectionMismatch.runId())).isGreaterThanOrEqualTo(1);

        ledgerService.rebuildAccountProjection(accountId);
        StoredValueReconciliationResult rebuilt = reconciliationService.reconcile(reconDate, 1001L);
        assertThat(rebuilt.status()).isEqualTo("COMPLETED");
    }

    @Test
    @Order(8)
    void orderPaymentAndRefundKeepLedgerOccurrenceOutOfReplayPayload() {
        long userId = nextId();
        ensureVerifiedUser(userId);
        createSuccess(userId, "stable-occurrence-fund-" + userId, "100.00");
        long orderId = insertOrder(userId, "6.00");

        clearInvocations(ledgerService);
        StoredValueOrderPaymentResult payment = orderPaymentService.pay(
            new StoredValueCommands.OrderPayment(orderId, userId, product.id(),
                "stable-occurrence-pay-" + orderId, "TEST"));
        ArgumentCaptor<StoredValueCommands.Ledger> paymentLedger =
            ArgumentCaptor.forClass(StoredValueCommands.Ledger.class);
        verify(ledgerService, times(1)).append(paymentLedger.capture());
        assertThat(paymentLedger.getValue().sourceType()).isEqualTo("ORDER_PAYMENT");
        assertThat(paymentLedger.getValue().occurredAt()).isNotNull();
        StoredValueCommands.Ledger paymentReplayCommand = withOccurredAt(paymentLedger.getValue(),
            paymentLedger.getValue().occurredAt().plusSeconds(1));
        StoredValueLedgerResult paymentReplay = ledgerService.append(paymentReplayCommand);
        assertThat(paymentReplay.replayed()).isTrue();

        clearInvocations(ledgerService);
        orderPaymentService.refund(new StoredValueCommands.OrderRefund(
            orderId, null, "stable-occurrence-refund-" + orderId, "stable occurrence"));
        ArgumentCaptor<StoredValueCommands.Ledger> refundLedger =
            ArgumentCaptor.forClass(StoredValueCommands.Ledger.class);
        verify(ledgerService, atLeastOnce()).append(refundLedger.capture());
        assertThat(refundLedger.getAllValues()).allSatisfy(command -> {
            assertThat(command.sourceType()).isEqualTo("ORDER_REFUND");
            assertThat(command.occurredAt()).isNotNull();
            StoredValueLedgerResult refundReplay = ledgerService.append(withOccurredAt(command,
                command.occurredAt().plusSeconds(1)));
            assertThat(refundReplay.replayed()).isTrue();
        });

        assertThat(payment.status()).isEqualTo("SUCCEEDED");
    }

    private StoredValueCommands.Ledger withOccurredAt(StoredValueCommands.Ledger command,
                                                       LocalDateTime occurredAt) {
        return new StoredValueCommands.Ledger(command.issuerId(), command.productId(), command.accountId(),
            command.currency(), command.transactionType(), command.sourceType(), command.sourceId(),
            command.businessKey(), command.policyVersion(), command.reason(), command.operatorId(),
            command.systemSource(), occurredAt, command.lines());
    }

    private boolean payWithoutThrow(CountDownLatch start, long orderId, long userId) {
        try {
            start.await(10, TimeUnit.SECONDS);
            orderPaymentService.pay(new StoredValueCommands.OrderPayment(
                orderId, userId, product.id(), "parallel-key-" + orderId, "TEST"));
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private void markOrderPaid(PaidOrderFact fact) {
        jdbc.update("""
            UPDATE biz_order
            SET paid_amount = ?, fee_amount = ?, net_amount = ?, pay_type = ?,
                payment_tool_type = ?, payment_fact_id = ?, payment_currency = 'CNY',
                status = 'PAID', update_time = CURRENT_TIMESTAMP
            WHERE id = ? AND status = 'PENDING_PAYMENT'
            """, fact.paidAmount(), fact.feeAmount() == null ? BigDecimal.ZERO : fact.feeAmount(),
            fact.netAmount() == null ? BigDecimal.ZERO : fact.netAmount(), fact.payType(),
            fact.paymentToolType(), fact.paymentFactId(), fact.order().id);
    }

    private StoredValueRechargeResult createSuccess(long userId, String requestId, String amount) {
        StoredValueRechargeResult prepared = rechargeService.create(new StoredValueCommands.CreateRecharge(
            userId, product.id(), new BigDecimal(amount), "CNY", requestId, null, "TEST"));
        StoredValueCommands.Callback unsigned = new StoredValueCommands.Callback(
            DEV_PROVIDER, DEV_ENV, DEV_NAME, DEV_CONFIG, prepared.rechargeNo(), requestId + "-event",
            "external-" + requestId, DevStoredValueProvider.MERCHANT_IDENTIFIER, "SUCCESS", "CNY",
            new BigDecimal(amount), null, "S06 integration callback");
        return rechargeService.handleCallback(new StoredValueCommands.Callback(
            unsigned.providerKey(), unsigned.providerEnv(), unsigned.providerName(), unsigned.configVersion(),
            unsigned.rechargeNo(), unsigned.externalEventId(), unsigned.externalRef(), unsigned.merchantIdentifier(),
            unsigned.status(), unsigned.currency(), unsigned.amount(), DevStoredValueProvider.signatureDigest(unsigned),
            unsigned.minimalSummary()));
    }

    private long insertOrder(long userId, String amount) {
        long orderId = nextId();
        long reservationId = nextId();
        BigDecimal value = new BigDecimal(amount);
        jdbc.update("""
            INSERT INTO biz_order
              (id, order_no, reservation_id, user_id, venue_id, total_amount, status,
               original_amount, member_discount_amount, coupon_discount_amount, activity_discount_amount,
               payable_amount, paid_amount, fee_amount, net_amount, create_time, update_time)
            VALUES (?, ?, ?, ?, 1, ?, 'PENDING_PAYMENT', ?, 0.00, 0.00, 0.00, ?, 0.00, 0.00, 0.00,
                    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            """, orderId, "S06-ORDER-" + orderId, reservationId, userId, value, value, value);
        return orderId;
    }

    private void ensureVerifiedUser(long userId) {
        jdbc.update("""
            INSERT INTO sys_user (id, username, password, nickname, status, role_id, create_time, update_time, deleted,
                                  credential_version, user_version)
            VALUES (?, ?, 'test', ?, 'ACTIVE', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0, 0, 0)
            ON DUPLICATE KEY UPDATE status = 'ACTIVE', deleted = 0
            """, userId, "s06-user-" + userId, "S06 user " + userId);
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
