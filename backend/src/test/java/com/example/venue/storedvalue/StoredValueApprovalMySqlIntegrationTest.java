package com.example.venue.storedvalue;

import com.example.venue.common.CurrentUser;
import com.example.venue.common.BusinessException;
import com.example.venue.common.PageQuery;
import com.example.venue.order.mapper.BizOrderMapper;
import com.example.venue.payment.service.OrderLocalSettlementService;
import com.example.venue.provider.schedule.InProcessProviderRunGuard;
import com.example.venue.storedvalue.domain.StoredValueCommands;
import com.example.venue.storedvalue.provider.DevStoredValueProvider;
import com.example.venue.storedvalue.provider.StoredValueProviderDescriptor;
import com.example.venue.storedvalue.provider.StoredValueProviderVerification;
import com.example.venue.storedvalue.service.StoredValueAccountService;
import com.example.venue.storedvalue.service.StoredValueAccountSnapshot;
import com.example.venue.storedvalue.service.StoredValueAdjustmentService;
import com.example.venue.storedvalue.service.StoredValueApprovalService;
import com.example.venue.storedvalue.service.StoredValueConfigurationService;
import com.example.venue.storedvalue.service.StoredValueProductSnapshot;
import com.example.venue.storedvalue.service.StoredValueRechargePersistenceService;
import com.example.venue.storedvalue.service.StoredValueRechargeResult;
import com.example.venue.storedvalue.service.StoredValueRechargeService;
import com.example.venue.storedvalue.dto.StoredValueResponses;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@EnabledIfSystemProperty(named = "stored.value.approval.integration", matches = "true")
@SpringBootTest(classes = StoredValueApprovalMySqlIntegrationTest.IntegrationApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {
        "spring.main.web-application-type=none",
        "spring.task.scheduling.enabled=false"
    })
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class StoredValueApprovalMySqlIntegrationTest {
    private static final String DEV_PROVIDER = "stored-value.dev";
    private static final String DEV_ENV = "DEV";
    private static final String DEV_NAME = "DEV_STORED_VALUE";
    private static final String DEV_CONFIG = "DEV_V1";
    private static final String DIGEST = "1111111111111111111111111111111111111111111111111111111111111111";

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @ComponentScan(basePackages = "com.example.venue.storedvalue")
    @Import(InProcessProviderRunGuard.class)
    static class IntegrationApplication {
    }

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> required("stored.value.approval.jdbc-url"));
        registry.add("spring.datasource.username", () -> required("stored.value.approval.jdbc-user"));
        registry.add("spring.datasource.password", () -> required("stored.value.approval.jdbc-password"));
        registry.add("spring.flyway.enabled", () -> true);
        registry.add("spring.data.redis.host", () -> "127.0.0.1");
        registry.add("spring.data.redis.port", () -> 63998);
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
    private final StoredValueRechargeService rechargeService;
    private final StoredValueRechargePersistenceService rechargePersistence;
    private final StoredValueApprovalService approvalService;
    private final StoredValueAdjustmentService adjustmentService;
    private StoredValueProductSnapshot product;

    @MockBean
    private BizOrderMapper orderMapper;

    @MockBean
    private OrderLocalSettlementService orderSettlementService;

    @Autowired
    StoredValueApprovalMySqlIntegrationTest(JdbcTemplate jdbc,
                                            StoredValueConfigurationService configurationService,
                                            StoredValueAccountService accountService,
                                            StoredValueRechargeService rechargeService,
                                            StoredValueRechargePersistenceService rechargePersistence,
                                            StoredValueApprovalService approvalService,
                                            StoredValueAdjustmentService adjustmentService) {
        this.jdbc = jdbc;
        this.configurationService = configurationService;
        this.accountService = accountService;
        this.rechargeService = rechargeService;
        this.rechargePersistence = rechargePersistence;
        this.approvalService = approvalService;
        this.adjustmentService = adjustmentService;
    }

    @BeforeAll
    void configureFixtures() {
        product = configurationService.enableDevProduct("S04_APPROVAL_IT", "S04 approval product", 1L);
        createUser(99101L, "recovery-both", "stored-value:recharge:execute", "stored-value:recharge:review");
        createUser(99102L, "recovery-reviewer", "stored-value:recharge:review");
        createUser(99103L, "recovery-executor", "stored-value:recharge:execute");
        createUser(99104L, "refund-requester", "stored-value:refund:manage");
        createUser(99105L, "refund-reviewer", "stored-value:refund:review");
        createUser(99106L, "refund-executor", "stored-value:refund:manage");
        createUser(99107L, "unfreeze-requester", "stored-value:account:unfreeze");
        createUser(99108L, "unfreeze-reviewer", "stored-value:account:unfreeze:review");
        createUser(99109L, "unfreeze-executor", "stored-value:account:unfreeze");
        createUser(99110L, "adjust-requester", "stored-value:adjustment:execute");
        createUser(99111L, "adjust-reviewer", "stored-value:adjustment:review");
        createUser(99112L, "adjust-executor", "stored-value:adjustment:execute");
        createUser(99113L, "disabled-executor", "stored-value:recharge:execute");
        jdbc.update("UPDATE sys_user SET status = 'DISABLED' WHERE id = 99113");
        for (long userId : List.of(99101L, 99102L, 99103L, 99104L, 99105L, 99106L, 99107L, 99108L, 99109L, 99110L, 99111L, 99112L)) {
            jdbc.update("""
                INSERT INTO stored_value_identity_verification
                  (user_id, verification_status, source, environment, evidence_digest, verified_at, operator_id)
                VALUES (?, 'VERIFIED', 'DEV_MANUAL', 'DEV', ?, CURRENT_TIMESTAMP, 1)
                """, userId, DIGEST);
        }
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @Order(1)
    void recoveryRequiresDifferentReviewAndExecutionSubjectsAndConsumesOnce() {
        StoredValueRechargeResult pending = createPending(99101L, "approval-recovery", "30.00");

        authenticate(99101L, "stored-value:recharge:execute", "stored-value:recharge:review");
        var approval = approvalService.submitPostingRecovery(pending.rechargeNo(), "渠道成功待入账复核");
        authenticate(99103L, "stored-value:recharge:execute");
        assertThat(approvalService.list(new PageQuery()).getRecords())
            .extracting(StoredValueResponses.ApprovalListResponse::approvalNo)
            .contains(approval.approvalNo());
        authenticate(99101L, "stored-value:recharge:execute", "stored-value:recharge:review");
        assertThatThrownBy(() -> approvalService.approve(approval.approvalNo(), "同一主体自审"))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("申请人不能");

        authenticate(99102L, "stored-value:recharge:review");
        approvalService.approve(approval.approvalNo(), "核对渠道成功证据");
        authenticate(99103L, "stored-value:recharge:execute");
        var consumed = approvalService.execute(approval.approvalNo());

        assertThat(consumed.status()).isEqualTo("CONSUMED");
        assertThat(jdbc.queryForObject("SELECT status FROM stored_value_recharge WHERE id = ?", String.class, pending.id()))
            .isEqualTo("SUCCEEDED");
        assertThat(count("SELECT COUNT(*) FROM stored_value_ledger_transaction WHERE source_id = ? AND transaction_type = 'RECHARGE_PRINCIPAL_POSTING'", pending.rechargeNo()))
            .isEqualTo(1L);
        Long approvalId = jdbc.queryForObject("SELECT id FROM stored_value_approval WHERE approval_no = ?",
            Long.class, approval.approvalNo());
        assertThat(count("SELECT COUNT(*) FROM stored_value_approval_event WHERE approval_id = ?", approvalId))
            .isEqualTo(4L);
        assertThatThrownBy(() -> approvalService.execute(approval.approvalNo()))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("尚未批准或已经消费");
    }

    @Test
    @Order(2)
    void rejectedExpiredRevokedAndDisabledApprovalsCannotExecute() {
        StoredValueRechargeResult rejectedPending = createPending(99101L, "approval-rejected", "20.00");
        authenticate(99101L, "stored-value:recharge:execute", "stored-value:recharge:review");
        var rejected = approvalService.submitPostingRecovery(rejectedPending.rechargeNo(), "拒绝分支");
        authenticate(99102L, "stored-value:recharge:review");
        approvalService.reject(rejected.approvalNo(), "证据不足");
        authenticate(99103L, "stored-value:recharge:execute");
        assertThatThrownBy(() -> approvalService.execute(rejected.approvalNo()))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("尚未批准或已经消费");

        StoredValueRechargeResult expiredPending = createPending(99101L, "approval-expired", "20.00");
        authenticate(99101L, "stored-value:recharge:execute", "stored-value:recharge:review");
        var expired = approvalService.submitPostingRecovery(expiredPending.rechargeNo(), "过期分支");
        jdbc.update("UPDATE stored_value_approval SET expires_at = DATE_SUB(CURRENT_TIMESTAMP, INTERVAL 1 SECOND) WHERE approval_no = ?",
            expired.approvalNo());
        authenticate(99102L, "stored-value:recharge:review");
        assertThatThrownBy(() -> approvalService.approve(expired.approvalNo(), "过期审批"))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("已过期");
        assertThat(jdbc.queryForObject("SELECT status FROM stored_value_approval WHERE approval_no = ?", String.class, expired.approvalNo()))
            .isEqualTo("EXPIRED");

        StoredValueRechargeResult revokedPending = createPending(99101L, "approval-revoked", "20.00");
        authenticate(99101L, "stored-value:recharge:execute", "stored-value:recharge:review");
        var revoked = approvalService.submitPostingRecovery(revokedPending.rechargeNo(), "撤回分支");
        approvalService.revoke(revoked.approvalNo(), "申请人撤回");
        authenticate(99103L, "stored-value:recharge:execute");
        assertThatThrownBy(() -> approvalService.execute(revoked.approvalNo()))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("尚未批准或已经消费");

        authenticate(99113L, "stored-value:recharge:execute");
        assertThatThrownBy(() -> approvalService.submitPostingRecovery(revokedPending.rechargeNo(), "失效账号"))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("已失效");
    }

    @Test
    @Order(3)
    void targetSnapshotCasRejectsTamperingBeforeConsumption() {
        StoredValueRechargeResult pending = createPending(99101L, "approval-tamper", "20.00");
        authenticate(99101L, "stored-value:recharge:execute");
        var approval = approvalService.submitPostingRecovery(pending.rechargeNo(), "快照篡改分支");
        jdbc.update("UPDATE stored_value_recharge SET posting_attempt_count = posting_attempt_count + 1 WHERE id = ?", pending.id());
        authenticate(99102L, "stored-value:recharge:review");
        approvalService.approve(approval.approvalNo(), "复核原始快照");
        authenticate(99103L, "stored-value:recharge:execute");
        assertThatThrownBy(() -> approvalService.execute(approval.approvalNo()))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("目标快照已变化");
        assertThat(jdbc.queryForObject("SELECT status FROM stored_value_approval WHERE approval_no = ?", String.class, approval.approvalNo()))
            .isEqualTo("FAILED");
        assertThat(count("SELECT COUNT(*) FROM stored_value_ledger_transaction WHERE source_id = ? AND transaction_type = 'RECHARGE_PRINCIPAL_POSTING'", pending.rechargeNo()))
            .isZero();
    }

    @Test
    @Order(4)
    void refundAndUnfreezeUseTheSameApprovalContract() {
        StoredValueRechargeResult success = createSuccess(99104L, "approval-refund", "25.00");
        authenticate(99104L, "stored-value:refund:manage");
        var refund = approvalService.submitRefund(success.rechargeNo(), "退款双人复核");
        authenticate(99105L, "stored-value:refund:review");
        approvalService.approve(refund.approvalNo(), "退款事实核验");
        authenticate(99106L, "stored-value:refund:manage");
        assertThat(approvalService.execute(refund.approvalNo()).status()).isEqualTo("CONSUMED");
        assertThat(jdbc.queryForObject("SELECT status FROM stored_value_recharge WHERE recharge_no = ?", String.class, success.rechargeNo()))
            .isEqualTo("REFUNDED");

        StoredValueAccountSnapshot account = accountService.ensureAccount(99107L, product.id());
        accountService.freezeAccount(account.id(), 99107L, "冻结解冻测试", "S04-FREEZE-" + account.id());
        authenticate(99107L, "stored-value:account:unfreeze");
        var unfreeze = approvalService.submitUnfreeze(account.id(), "账户解冻双人复核");
        authenticate(99108L, "stored-value:account:unfreeze:review");
        approvalService.approve(unfreeze.approvalNo(), "确认冻结原因已解除");
        authenticate(99109L, "stored-value:account:unfreeze");
        assertThat(approvalService.execute(unfreeze.approvalNo()).status()).isEqualTo("CONSUMED");
        assertThat(jdbc.queryForObject("SELECT status FROM stored_value_account WHERE id = ?", String.class, account.id()))
            .isEqualTo("NORMAL");
    }

    @Test
    @Order(5)
    void approvedAdjustmentWritesBalancedLedgerAndResolvesDifference() {
        StoredValueAccountSnapshot account = accountService.ensureAccount(99110L, product.id());
        Long runId = insertRun("adjustment");
        Long differenceId = insertDifference(runId, account.id(), "10.00", "0.00");
        authenticate(99110L, "stored-value:adjustment:execute");
        var approval = approvalService.submitAdjustment(differenceId, new BigDecimal("10.00"), "对账差异平衡调整");
        authenticate(99111L, "stored-value:adjustment:review");
        approvalService.approve(approval.approvalNo(), "复核渠道与本地差额");
        authenticate(99112L, "stored-value:adjustment:execute");
        assertThat(approvalService.execute(approval.approvalNo()).status()).isEqualTo("CONSUMED");
        assertThat(jdbc.queryForObject("SELECT status FROM stored_value_reconciliation_difference WHERE id = ?", String.class, differenceId))
            .isEqualTo("RESOLVED");
        assertThat(jdbc.queryForObject("SELECT principal_available FROM stored_value_account WHERE id = ?", BigDecimal.class, account.id()))
            .isEqualByComparingTo("10.00");
        assertThat(count("SELECT COUNT(*) FROM stored_value_ledger_transaction WHERE business_key = ?", "ADJUSTMENT:" + approval.approvalNo()))
            .isEqualTo(1L);
        assertThat(count("""
            SELECT COUNT(*) FROM (
              SELECT transaction_id, SUM(CASE WHEN direction = 'DEBIT' THEN amount ELSE 0 END) debit_amount,
                     SUM(CASE WHEN direction = 'CREDIT' THEN amount ELSE 0 END) credit_amount
              FROM stored_value_ledger_entry WHERE transaction_id = (SELECT id FROM stored_value_ledger_transaction WHERE business_key = ?)
              GROUP BY transaction_id
            ) balanced WHERE debit_amount <> credit_amount
            """, "ADJUSTMENT:" + approval.approvalNo())).isZero();
    }

    @Test
    @Order(6)
    void concurrentExecutionConsumesApprovalOnce() throws Exception {
        StoredValueRechargeResult pending = createPending(99101L, "approval-concurrent", "20.00");
        authenticate(99101L, "stored-value:recharge:execute");
        var approval = approvalService.submitPostingRecovery(pending.rechargeNo(), "并发消费分支");
        authenticate(99102L, "stored-value:recharge:review");
        approvalService.approve(approval.approvalNo(), "并发前复核");

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<String> first = executor.submit(() -> executeAs(approval.approvalNo(), start));
            Future<String> second = executor.submit(() -> executeAs(approval.approvalNo(), start));
            start.countDown();
            List<String> results = List.of(first.get(), second.get());
            assertThat(results).contains("CONSUMED").contains("CONFLICT");
        } finally {
            executor.shutdownNow();
        }
        assertThat(count("SELECT COUNT(*) FROM stored_value_ledger_transaction WHERE source_id = ? AND transaction_type = 'RECHARGE_PRINCIPAL_POSTING'", pending.rechargeNo()))
            .isEqualTo(1L);
    }

    private String executeAs(String approvalNo, CountDownLatch start) throws InterruptedException {
        authenticate(99103L, "stored-value:recharge:execute");
        start.await();
        try {
            return approvalService.execute(approvalNo).status();
        } catch (BusinessException exception) {
            return "CONFLICT";
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private StoredValueRechargeResult createPending(Long userId, String requestId, String amount) {
        StoredValueRechargeResult prepared = rechargeService.create(new StoredValueCommands.CreateRecharge(
            userId, product.id(), new BigDecimal(amount), "CNY", requestId, null, "S04_TEST"));
        StoredValueCommands.Callback callback = callback(prepared, requestId + "-event", amount);
        StoredValueProviderDescriptor descriptor = new StoredValueProviderDescriptor(
            DEV_PROVIDER, DEV_ENV, DEV_NAME, DEV_CONFIG, true);
        rechargePersistence.recordCallbackEvidence(callback, null, DIGEST);
        return rechargePersistence.recordCallbackOutcome(callback, DIGEST,
            new StoredValueProviderVerification(true, "SUCCESS", "provider-proof", new BigDecimal(amount),
                "CNY", "external-" + requestId, "verified", "verified"), descriptor);
    }

    private StoredValueRechargeResult createSuccess(Long userId, String requestId, String amount) {
        StoredValueRechargeResult pending = rechargeService.create(new StoredValueCommands.CreateRecharge(
            userId, product.id(), new BigDecimal(amount), "CNY", requestId, null, "S04_TEST"));
        return rechargeService.handleCallback(callback(pending, requestId + "-event", amount));
    }

    private StoredValueCommands.Callback callback(StoredValueRechargeResult result, String eventId, String amount) {
        StoredValueCommands.Callback unsigned = new StoredValueCommands.Callback(
            DEV_PROVIDER, DEV_ENV, DEV_NAME, DEV_CONFIG, result.rechargeNo(), eventId, "external-" + eventId,
            DevStoredValueProvider.MERCHANT_IDENTIFIER, "SUCCESS", "CNY", new BigDecimal(amount), null,
            "S04 approval callback");
        return new StoredValueCommands.Callback(unsigned.providerKey(), unsigned.providerEnv(), unsigned.providerName(),
            unsigned.configVersion(), unsigned.rechargeNo(), unsigned.externalEventId(), unsigned.externalRef(),
            unsigned.merchantIdentifier(), unsigned.status(), unsigned.currency(), unsigned.amount(),
            DevStoredValueProvider.signatureDigest(unsigned), unsigned.minimalSummary());
    }

    private Long insertRun(String suffix) {
        jdbc.update("""
            INSERT INTO stored_value_reconciliation_run
              (run_no, reconcile_date, provider_env, provider_name, scope_type, status, operator_id)
            VALUES (?, CURRENT_DATE, 'DEV', 'S04_TEST', 'PROVIDER', 'MANUAL_REVIEW', 99110)
            """, "S04-APPROVAL-RUN-" + suffix + "-" + System.nanoTime());
        return jdbc.queryForObject("SELECT id FROM stored_value_reconciliation_run ORDER BY id DESC LIMIT 1", Long.class);
    }

    private Long insertDifference(Long runId, Long accountId, String providerAmount, String localAmount) {
        jdbc.update("""
            INSERT INTO stored_value_reconciliation_difference
              (run_id, difference_no, difference_type, account_id, provider_amount, local_amount,
               currency, status, reason)
            VALUES (?, ?, 'ACCOUNT_PROJECTION_DIFFERENCE', ?, ?, ?, 'CNY', 'OPEN', 'S04 test difference')
            """, runId, "S04-DIFF-" + System.nanoTime(), accountId,
            new BigDecimal(providerAmount), new BigDecimal(localAmount));
        return jdbc.queryForObject("SELECT id FROM stored_value_reconciliation_difference ORDER BY id DESC LIMIT 1", Long.class);
    }

    private void createUser(Long userId, String key, String... permissions) {
        jdbc.update("""
            INSERT INTO sys_role (role_name, role_key, data_scope, status, create_time, update_time, deleted)
            VALUES (?, ?, 'ALL', 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0)
            """, "S04 " + key, "S04_" + key.toUpperCase().replace('-', '_'));
        Long roleId = jdbc.queryForObject("SELECT id FROM sys_role WHERE role_key = ?", Long.class,
            "S04_" + key.toUpperCase().replace('-', '_'));
        jdbc.update("""
            INSERT INTO sys_user (id, username, password, nickname, status, role_id, create_time, update_time,
                                  deleted, credential_version, user_version)
            VALUES (?, ?, 'test', ?, 'ACTIVE', ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0, 0, 0)
            """, userId, key, key, roleId);
        jdbc.update("INSERT INTO sys_user_role (user_id, role_id) VALUES (?, ?)", userId, roleId);
        for (String permission : permissions) {
            Long menuId = jdbc.queryForObject("SELECT id FROM sys_menu WHERE perms = ? AND deleted = 0 LIMIT 1",
                Long.class, permission);
            jdbc.update("INSERT INTO sys_role_menu (role_id, menu_id) VALUES (?, ?)", roleId, menuId);
        }
    }

    private void authenticate(Long userId, String... permissions) {
        Set<String> permissionSet = Set.of(permissions);
        CurrentUser user = new CurrentUser(userId, "approval-" + userId, Set.of("S04_TEST"), permissionSet);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
            user, null, permissionSet.stream().map(SimpleGrantedAuthority::new).toList()));
    }

    private long count(String sql, Object... args) {
        Long value = jdbc.queryForObject(sql, Long.class, args);
        return value == null ? 0L : value;
    }
}
