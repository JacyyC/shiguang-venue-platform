package com.example.venue.member;

import org.springframework.test.annotation.DirtiesContext;

import com.example.venue.common.BusinessException;
import com.example.venue.common.CurrentUser;
import com.example.venue.common.PageQuery;
import com.example.venue.member.dto.MemberRewardCatalogResponse;
import com.example.venue.member.dto.MemberRewardRedemptionResponse;
import com.example.venue.member.dto.PublishMemberRewardVersionRequest;
import com.example.venue.member.dto.SaveMemberRewardCatalogItemRequest;
import com.example.venue.member.mapper.MemberRewardCatalogVersionMapper;
import com.example.venue.member.service.MemberPointsService;
import com.example.venue.member.service.MemberRewardExecutionFaultInjector;
import com.example.venue.member.service.MemberRewardService;
import com.example.venue.member.service.MemberService;
import com.example.venue.order.entity.BizOrder;
import com.example.venue.reservation.entity.Reservation;
import com.example.venue.promotion.service.CouponRewardIssuanceFaultInjector;
import com.example.venue.promotion.service.CouponRewardIssuanceService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

// Close the cached Spring context while this class still owns live containers.
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Testcontainers
@SpringBootTest(properties = {
    "spring.profiles.active=test",
    "spring.task.scheduling.enabled=false",
    "app.notice.outbox.scheduling-enabled=false",
    "app.community.retention.enabled=false"
})
class MemberRewardMySqlIntegrationTest {
    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
        .withDatabaseName("member_reward_test")
        .withUsername("reward")
        .withPassword("reward")
        .withCommand("--character-set-server=utf8mb4",
                     "--collation-server=utf8mb4_unicode_ci",
                     "--log-bin-trust-function-creators=1");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.flyway.enabled", () -> true);
        registry.add("spring.data.redis.host", () -> "127.0.0.1");
        registry.add("spring.data.redis.port", () -> 6399);
    }

    @Autowired private JdbcTemplate jdbc;
    @Autowired private MemberRewardService rewardService;
    @Autowired private MemberPointsService pointsService;
    @Autowired private MemberService memberService;
    @Autowired private CouponRewardIssuanceService couponService;
    @Autowired private MemberRewardCatalogVersionMapper versionMapper;
    @MockBean private MemberRewardExecutionFaultInjector faultInjector;
    @MockBean private CouponRewardIssuanceFaultInjector couponFaultInjector;

    @BeforeEach
    void setUp() {
        authenticate(1L);
        jdbc.update("DELETE FROM member_reward_event");
        jdbc.update("DELETE FROM member_reward_redemption");
        jdbc.update("DELETE FROM member_reward_catalog_version");
        jdbc.update("DELETE FROM member_reward_catalog_item");
        jdbc.update("DELETE FROM coupon_status_log");
        jdbc.update("DELETE FROM user_coupon");
        jdbc.update("DELETE FROM coupon_template");
        jdbc.update("DELETE FROM member_profile_tag WHERE user_id IN (1, 9902)");
        jdbc.update("DELETE FROM member_account_log WHERE user_id IN (1, 9902)");
        jdbc.update("DELETE FROM member_account WHERE user_id IN (1, 9902)");
        jdbc.update("DELETE FROM notice_outbox");
        jdbc.update("DELETE FROM notice_message");
        jdbc.update("DELETE FROM sys_user WHERE id = 9902");
        jdbc.update("""
            INSERT INTO sys_user (id, username, password, nickname, status, role_id, create_time, update_time, deleted,
                                  credential_version, user_version)
            VALUES (9902, 'reward-user-2', 'test', '兑换用户2', 'ACTIVE', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0, 0, 0)
            """);
        reset(faultInjector);
        reset(couponFaultInjector);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void idempotencyConcurrencyInventoryFaultRollbackAndCompensationStayConservative() throws Exception {
        seedAccount(1L, 100);
        seedCouponTemplate(9001L, 10, 10);
        MemberRewardCatalogResponse first = publish("REWARD_ONE", 80, 9001L);
        MemberRewardCatalogResponse second = publish("REWARD_TWO", 80, 9001L);

        MemberRewardRedemptionResponse success = rewardService.redeem(first.itemId, first.versionId, "same-command");
        MemberRewardRedemptionResponse replay = rewardService.redeem(first.itemId, first.versionId, "same-command");
        assertThat(success.status).isEqualTo("SUCCEEDED");
        assertThat(replay.id).isEqualTo(success.id);
        assertThat(count("SELECT COUNT(*) FROM user_coupon")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM member_account_log WHERE business_key = 'REWARD_REDEEM:" + success.id + "'"))
            .isEqualTo(1);
        assertThatThrownBy(() -> rewardService.redeem(second.itemId, second.versionId, "same-command"))
            .isInstanceOf(BusinessException.class).hasMessageContaining("不同请求");

        resetAccount(1L, 100);
        clearRuntimeFacts();
        CountDownLatch start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            Future<String> a = executor.submit(() -> concurrentRedeem(start, first, "parallel-a", 1L));
            Future<String> b = executor.submit(() -> concurrentRedeem(start, second, "parallel-b", 1L));
            start.countDown();
            assertThat(List.of(a.get(), b.get())).containsExactlyInAnyOrder("SUCCEEDED", "REJECTED");
        } finally {
            executor.shutdownNow();
        }
        assertThat(points(1L)).isEqualTo(20);
        assertProjectionMatchesLedger(1L);
        assertThat(count("SELECT COUNT(*) FROM user_coupon")).isEqualTo(1);

        clearRuntimeFacts();
        resetAccount(1L, 100);
        doThrow(new IllegalStateException("after coupon")).when(faultInjector).afterCouponIssued(org.mockito.ArgumentMatchers.any());
        MemberRewardRedemptionResponse failedAfterCoupon = rewardService.redeem(
            first.itemId, first.versionId, "fault-coupon");
        assertThat(failedAfterCoupon.status).isEqualTo("FAILED");
        assertThat(count("SELECT COUNT(*) FROM user_coupon")).isZero();
        assertThat(points(1L)).isEqualTo(100);
        assertProjectionMatchesLedger(1L);

        MemberRewardRedemptionResponse failedSelfReplay = rewardService.redeem(
            first.itemId, first.versionId, "fault-coupon");
        assertThat(failedSelfReplay.id).isEqualTo(failedAfterCoupon.id);
        assertThat(failedSelfReplay.status).isEqualTo("FAILED");
        assertThat(count("SELECT COUNT(*) FROM user_coupon")).isZero();
        assertThat(points(1L)).isEqualTo(100);

        MemberRewardRedemptionResponse failedAuthorizedRetry = rewardService.retry(failedAfterCoupon.id);
        assertThat(failedAuthorizedRetry.status).isEqualTo("FAILED");
        assertThat(count("SELECT COUNT(*) FROM member_reward_event WHERE event_type = 'REDEMPTION_RETRY_STARTED'"))
            .isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM member_reward_event WHERE event_type = 'REDEMPTION_RETRY_RESULT'"))
            .isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM user_coupon")).isZero();

        reset(faultInjector);
        MemberRewardRedemptionResponse retried = rewardService.retry(failedAfterCoupon.id);
        assertThat(retried.status).isEqualTo("SUCCEEDED");
        assertThat(count("SELECT COUNT(*) FROM member_reward_event WHERE event_type = 'REDEMPTION_RETRY_STARTED'"))
            .isEqualTo(2);
        assertThat(count("SELECT COUNT(*) FROM member_reward_event WHERE event_type = 'REDEMPTION_RETRY_RESULT'"))
            .isEqualTo(2);
        assertThat(points(1L)).isEqualTo(20);
        jdbc.update("UPDATE user_coupon SET status = 'LOCKED' WHERE id = ?", retried.userCouponId);
        assertThatThrownBy(() -> rewardService.compensate(retried.id, "锁定券禁止补偿"))
            .isInstanceOf(BusinessException.class).hasMessageContaining("CLAIMED");
        assertThat(points(1L)).isEqualTo(20);
        for (String status : List.of("USED", "REFUNDED", "VOIDED")) {
            jdbc.update("UPDATE user_coupon SET status = ? WHERE id = ?", status, retried.userCouponId);
            assertThatThrownBy(() -> rewardService.compensate(retried.id, status + " 券禁止补偿"))
                .isInstanceOf(BusinessException.class).hasMessageContaining("CLAIMED");
            assertThat(points(1L)).isEqualTo(20);
        }
        jdbc.update("UPDATE user_coupon SET status = 'CLAIMED' WHERE id = ?", retried.userCouponId);
        MemberRewardRedemptionResponse compensated = rewardService.compensate(retried.id, "人工确认未使用券");
        assertThat(compensated.status).isEqualTo("COMPENSATED");
        assertThat(points(1L)).isEqualTo(100);
        assertThat(jdbc.queryForObject("SELECT status FROM user_coupon WHERE id = ?", String.class,
            retried.userCouponId)).isEqualTo("VOIDED");
        assertProjectionMatchesLedger(1L);

        clearRuntimeFacts();
        resetAccount(1L, 100);
        reset(faultInjector);
        doThrow(new IllegalStateException("after points")).when(faultInjector)
            .afterPointsDebited(org.mockito.ArgumentMatchers.any());
        MemberRewardRedemptionResponse failedAfterPoints = rewardService.redeem(
            first.itemId, first.versionId, "fault-points");
        assertThat(failedAfterPoints.status).isEqualTo("FAILED");
        assertThat(count("SELECT COUNT(*) FROM user_coupon")).isZero();
        assertThat(count("SELECT issued_quantity FROM coupon_template WHERE id = 9001")).isZero();
        assertThat(count("SELECT COUNT(*) FROM member_account_log WHERE business_key = 'REWARD_REDEEM:"
            + failedAfterPoints.id + "'")) .isZero();
        assertThat(points(1L)).isEqualTo(100);
        assertProjectionMatchesLedger(1L);
    }

    @Test
    void committedSuccessReplaySurvivesRetirementExpiryAndCatalogDisableWithoutDuplicateNotification() {
        seedAccount(1L, 300);
        seedCouponTemplate(9004L, 10, 10);
        MemberRewardCatalogResponse first = publish("STABLE_REPLAY", 80, 9004L);
        doThrow(new IllegalStateException("response lost after commit")).when(faultInjector)
            .afterSuccessfulCommit(org.mockito.ArgumentMatchers.any());

        assertThatThrownBy(() -> rewardService.redeem(first.itemId, first.versionId, "lost-response"))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("response lost");
        Long redemptionId = jdbc.queryForObject("""
            SELECT id FROM member_reward_redemption WHERE idempotency_key = 'lost-response'
            """, Long.class);
        assertThat(jdbc.queryForObject("SELECT status FROM member_reward_redemption WHERE id = ?",
            String.class, redemptionId)).isEqualTo("SUCCEEDED");

        reset(faultInjector);
        MemberRewardCatalogResponse replacement = publishVersion(first.itemId, 90, 9004L,
            null, LocalDateTime.now().plusDays(5));
        jdbc.update("UPDATE member_reward_catalog_version SET effective_end_time = DATE_SUB(CURRENT_TIMESTAMP, INTERVAL 1 DAY) WHERE id = ?",
            first.versionId);
        jdbc.update("UPDATE member_reward_catalog_item SET status = 'INACTIVE' WHERE id = ?", first.itemId);

        MemberRewardRedemptionResponse replay = rewardService.redeem(
            first.itemId, first.versionId, "lost-response");
        assertThat(replay.id).isEqualTo(redemptionId);
        assertThat(replay.status).isEqualTo("SUCCEEDED");
        assertThat(count("SELECT COUNT(*) FROM user_coupon")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM member_account_log WHERE business_key = 'REWARD_REDEEM:"
            + redemptionId + "'")) .isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM notice_message WHERE type = 'MEMBER_REWARD'"))
            .isEqualTo(1);
        assertThatThrownBy(() -> rewardService.redeem(first.itemId, replacement.versionId, "lost-response"))
            .isInstanceOf(BusinessException.class).hasMessageContaining("不同请求");
    }

    @Test
    void narrowCouponIssuanceRollsBackInventoryWhenPostGuardWorkFails() {
        seedCouponTemplate(9005L, 1, 1);
        doThrow(new IllegalStateException("post-guard failure")).when(couponFaultInjector)
            .afterInventoryReserved(9005L, 1L, 88005L);

        assertThatThrownBy(() -> couponService.issue(1L, 9005L, 88005L, 30))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("post-guard");
        assertThat(count("SELECT issued_quantity FROM coupon_template WHERE id = 9005")).isZero();
        assertThat(count("SELECT COUNT(*) FROM user_coupon WHERE template_id = 9005")).isZero();
        assertThat(count("SELECT COUNT(*) FROM coupon_status_log")).isZero();
    }

    @Test
    void rewardVersionWindowIsHalfOpenAndInvalidBoundsFailBeforePersistence() {
        seedAccount(1L, 200);
        seedCouponTemplate(9006L, 10, 10);
        SaveMemberRewardCatalogItemRequest item = new SaveMemberRewardCatalogItemRequest();
        item.itemCode = "HALF_OPEN";
        item.itemName = "HALF_OPEN";
        MemberRewardCatalogResponse created = rewardService.createCatalogItem(item);

        PublishMemberRewardVersionRequest invalid = new PublishMemberRewardVersionRequest();
        invalid.pointsCost = 80;
        invalid.minimumLevelCode = "BASIC";
        invalid.couponTemplateId = 9006L;
        invalid.validityDays = 30;
        invalid.effectiveStartTime = LocalDateTime.now().plusDays(1);
        invalid.effectiveEndTime = invalid.effectiveStartTime;
        assertThatThrownBy(() -> rewardService.publishVersion(created.itemId, invalid))
            .isInstanceOf(BusinessException.class).hasMessageContaining("结束时间");
        assertThat(count("SELECT COUNT(*) FROM member_reward_catalog_version WHERE catalog_item_id = "
            + created.itemId)).isZero();

        MemberRewardCatalogResponse boundary = publishVersion(created.itemId, 80, 9006L,
            null, LocalDateTime.now().withNano(0));
        assertThat(rewardService.preview(created.itemId).versionId).isNull();
        MemberRewardRedemptionResponse rejected = rewardService.redeem(
            created.itemId, boundary.versionId, "end-boundary");
        assertThat(rejected.status).isEqualTo("REJECTED");
        assertThat(rejected.failureCode).isEqualTo("VERSION_OUTSIDE_WINDOW");
        assertThat(count("SELECT COUNT(*) FROM user_coupon")).isZero();
    }

    @Test
    void lastCouponAndRefundRedemptionRacesNeverOverIssueOrLoseLedgerBalance() throws Exception {
        seedAccount(1L, 100);
        seedAccount(9902L, 100);
        seedCouponTemplate(9002L, 1, 1);
        MemberRewardCatalogResponse reward = publish("LAST_COUPON", 80, 9002L);
        CountDownLatch couponStart = new CountDownLatch(1);
        var couponExecutor = Executors.newFixedThreadPool(2);
        try {
            Future<String> first = couponExecutor.submit(() -> concurrentRedeem(couponStart, reward, "last-1", 1L));
            Future<String> second = couponExecutor.submit(() -> concurrentRedeem(couponStart, reward, "last-2", 9902L));
            couponStart.countDown();
            assertThat(List.of(first.get(), second.get())).containsExactlyInAnyOrder("SUCCEEDED", "REJECTED");
        } finally {
            couponExecutor.shutdownNow();
        }
        assertThat(count("SELECT COUNT(*) FROM user_coupon WHERE template_id = 9002")).isEqualTo(1);
        assertThat(count("SELECT issued_quantity FROM coupon_template WHERE id = 9002")).isEqualTo(1);
        assertProjectionMatchesLedger(1L);
        assertProjectionMatchesLedger(9902L);

        clearRuntimeFacts();
        jdbc.update("UPDATE coupon_template SET total_quantity = 10, issued_quantity = 0, per_user_limit = 10 WHERE id = 9002");
        jdbc.update("DELETE FROM member_account_log WHERE user_id = 1");
        jdbc.update("UPDATE member_account SET points_balance = 0, growth_value = 100, total_consumption_amount = 100.00, total_reservations = 1 WHERE user_id = 1");
        pointsService.append(1L, "ORDER_PAID_POINTS:777", "MEMBER_POINTS_CHANGED", "R4_POINTS_V1",
            100, 100, new BigDecimal("100.00"), 777L, "PAYMENT", "PAID_ORDER", "并发退款测试支付");
        // resetAccount clears the ledger and append restores the authoritative 100-point projection.
        BizOrder order = new BizOrder();
        order.id = 777L;
        order.userId = 1L;
        order.orderNo = "ORDER-777";
        order.paidAmount = new BigDecimal("100.00");
        Reservation reservation = new Reservation();
        reservation.id = 888L;
        reservation.userId = 1L;
        CountDownLatch race = new CountDownLatch(1);
        var raceExecutor = Executors.newFixedThreadPool(2);
        try {
            Future<String> redeem = raceExecutor.submit(() -> concurrentRedeem(race, reward, "refund-race", 1L));
            Future<String> refund = raceExecutor.submit(() -> {
                authenticate(1L);
                race.await();
                memberService.reverseRefundedOrder(order, reservation);
                return "REFUNDED";
            });
            race.countDown();
            assertThat(refund.get()).isEqualTo("REFUNDED");
            assertThat(redeem.get()).isIn("SUCCEEDED", "REJECTED");
        } finally {
            raceExecutor.shutdownNow();
        }
        assertProjectionMatchesLedger(1L);
        assertThat(points(1L)).isIn(0, -80);
    }

    @Test
    void oneUserConcurrentCommandsRespectPerUserCouponLimitWithRemainingTotalInventory() throws Exception {
        seedAccount(1L, 200);
        seedCouponTemplate(9007L, 5, 1);
        MemberRewardCatalogResponse reward = publish("PER_USER_LIMIT", 80, 9007L);
        CountDownLatch start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            Future<String> first = executor.submit(() -> concurrentRedeem(start, reward, "per-user-a", 1L));
            Future<String> second = executor.submit(() -> concurrentRedeem(start, reward, "per-user-b", 1L));
            start.countDown();
            assertThat(List.of(first.get(), second.get())).containsExactlyInAnyOrder("SUCCEEDED", "REJECTED");
        } finally {
            executor.shutdownNow();
        }

        assertThat(count("SELECT issued_quantity FROM coupon_template WHERE id = 9007")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM user_coupon WHERE template_id = 9007 AND user_id = 1")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM member_account_log WHERE business_key LIKE 'REWARD_REDEEM:%'")).isEqualTo(1);
        assertThat(points(1L)).isEqualTo(120);
        assertProjectionMatchesLedger(1L);
    }

    @Test
    void compensationRejectsWrongClaimedCouponSourceOrRedemptionMetadataWithoutMutatingFacts() {
        seedAccount(1L, 100);
        seedCouponTemplate(9008L, 5, 5);
        MemberRewardCatalogResponse reward = publish("COMPENSATION_GUARD", 80, 9008L);
        MemberRewardRedemptionResponse redeemed = rewardService.redeem(reward.itemId, reward.versionId, "comp-guard");
        assertThat(redeemed.status).isEqualTo("SUCCEEDED");

        jdbc.update("UPDATE user_coupon SET source_type = 'ADMIN' WHERE id = ?", redeemed.userCouponId);
        assertThatThrownBy(() -> rewardService.compensate(redeemed.id, "来源错误"))
            .isInstanceOf(BusinessException.class).hasMessageContaining("不属于");
        assertCompensationRejectionDidNotMutate(redeemed, 20);

        jdbc.update("UPDATE user_coupon SET source_type = 'MEMBER_REWARD', metadata_json = '{\"redemptionId\":999999}' WHERE id = ?",
            redeemed.userCouponId);
        assertThatThrownBy(() -> rewardService.compensate(redeemed.id, "兑换元数据错误"))
            .isInstanceOf(BusinessException.class).hasMessageContaining("不属于");
        assertCompensationRejectionDidNotMutate(redeemed, 20);

        jdbc.update("UPDATE user_coupon SET user_id = 9902, metadata_json = ? WHERE id = ?",
            "{\"redemptionId\":" + redeemed.id + "}", redeemed.userCouponId);
        assertThatThrownBy(() -> rewardService.compensate(redeemed.id, "用户归属错误"))
            .isInstanceOf(BusinessException.class).hasMessageContaining("不属于");
        assertCompensationRejectionDidNotMutate(redeemed, 20);

        jdbc.update("UPDATE user_coupon SET user_id = 1, template_id = 999999, metadata_json = ? WHERE id = ?",
            "{\"redemptionId\":" + redeemed.id + "}", redeemed.userCouponId);
        assertThatThrownBy(() -> rewardService.compensate(redeemed.id, "模板归属错误"))
            .isInstanceOf(BusinessException.class).hasMessageContaining("不属于");
        assertCompensationRejectionDidNotMutate(redeemed, 20);
    }

    @Test
    void catalogPublishAndStatusChangesAppendStableMemberRewardEvents() {
        seedCouponTemplate(9010L, 5, 5);
        SaveMemberRewardCatalogItemRequest item = new SaveMemberRewardCatalogItemRequest();
        item.itemCode = "CATALOG_EVENTS";
        item.itemName = "CATALOG_EVENTS";
        MemberRewardCatalogResponse created = rewardService.createCatalogItem(item);

        MemberRewardCatalogResponse published = publishVersion(created.itemId, 80, 9010L, null, null);
        assertThat(jdbc.queryForMap("""
            SELECT event_type, event_key, catalog_item_id, catalog_version_id, from_status, to_status,
                   source_type, result_status
            FROM member_reward_event WHERE event_key = ?
            """, "CATALOG_VERSION_PUBLISHED:" + published.versionId))
            .containsEntry("event_type", "CATALOG_VERSION_PUBLISHED")
            .containsEntry("catalog_item_id", published.itemId)
            .containsEntry("catalog_version_id", published.versionId)
            .containsEntry("from_status", "DRAFT")
            .containsEntry("to_status", "ACTIVE")
            .containsEntry("source_type", "ADMIN")
            .containsEntry("result_status", "SUCCESS");

        rewardService.setCatalogItemStatus(published.itemId, "INACTIVE");
        assertThat(count("SELECT COUNT(*) FROM member_reward_event WHERE event_type = 'CATALOG_ITEM_STATUS_CHANGED'"))
            .isEqualTo(1);
        rewardService.setCatalogItemStatus(published.itemId, "INACTIVE");
        assertThat(count("SELECT COUNT(*) FROM member_reward_event WHERE event_type = 'CATALOG_ITEM_STATUS_CHANGED'"))
            .isEqualTo(1);
    }

    @Test
    void mapperExcludesTheSamePersistedEffectiveEndTimeBoundary() {
        seedCouponTemplate(9009L, 5, 5);
        SaveMemberRewardCatalogItemRequest item = new SaveMemberRewardCatalogItemRequest();
        item.itemCode = "MAPPER_BOUNDARY";
        item.itemName = "MAPPER_BOUNDARY";
        MemberRewardCatalogResponse created = rewardService.createCatalogItem(item);
        MemberRewardCatalogResponse version = publishVersion(created.itemId, 80, 9009L,
            LocalDateTime.of(2030, 1, 1, 0, 0), LocalDateTime.of(2030, 1, 2, 0, 0));

        LocalDateTime boundary = jdbc.queryForObject("SELECT effective_end_time FROM member_reward_catalog_version WHERE id = ?",
            Timestamp.class, version.versionId).toLocalDateTime();
        assertThat(versionMapper.findCurrentPublished(created.itemId, boundary)).isNull();
    }

    @Test
    void confirmationBindsPreviewVersionAndRevalidatesMutableEligibility() {
        seedAccount(1L, 200);
        seedCouponTemplate(9003L, 10, 10);
        MemberRewardCatalogResponse first = publish("VERSIONED_REWARD", 80, 9003L);
        assertThat(rewardService.preview(first.itemId).versionId).isEqualTo(first.versionId);

        PublishMemberRewardVersionRequest replacement = new PublishMemberRewardVersionRequest();
        replacement.pointsCost = 90;
        replacement.minimumLevelCode = "SILVER";
        replacement.couponTemplateId = 9003L;
        replacement.validityDays = 15;
        MemberRewardCatalogResponse second = rewardService.publishVersion(first.itemId, replacement);
        assertThat(jdbc.queryForObject("SELECT status FROM member_reward_catalog_version WHERE id = ?",
            String.class, first.versionId)).isEqualTo("RETIRED");
        assertThat(rewardService.preview(first.itemId).versionId).isEqualTo(second.versionId);
        assertThatThrownBy(() -> rewardService.redeem(first.itemId, first.versionId, "stale-preview"))
            .isInstanceOf(BusinessException.class).hasMessageContaining("预览兑换版本已不可用");
        assertThat(count("SELECT COUNT(*) FROM member_reward_redemption WHERE idempotency_key = 'stale-preview'"))
            .isZero();

        MemberRewardRedemptionResponse levelChanged = rewardService.redeem(
            first.itemId, second.versionId, "level-changed");
        assertThat(levelChanged.status).isEqualTo("REJECTED");
        assertThat(levelChanged.failureCode).isEqualTo("LEVEL_NOT_ELIGIBLE");

        jdbc.update("UPDATE member_account SET growth_value = 1000 WHERE user_id = 1");
        jdbc.update("UPDATE coupon_template SET status = 'INACTIVE' WHERE id = 9003");
        MemberRewardRedemptionResponse templateChanged = rewardService.redeem(
            first.itemId, second.versionId, "template-changed");
        assertThat(templateChanged.status).isEqualTo("REJECTED");
        assertThat(templateChanged.failureCode).isEqualTo("COUPON_UNAVAILABLE");

        jdbc.update("UPDATE coupon_template SET status = 'ACTIVE' WHERE id = 9003");
        jdbc.update("UPDATE member_reward_catalog_version SET effective_end_time = DATE_SUB(CURRENT_TIMESTAMP, INTERVAL 1 SECOND) WHERE id = ?",
            second.versionId);
        MemberRewardRedemptionResponse windowChanged = rewardService.redeem(
            first.itemId, second.versionId, "window-changed");
        assertThat(windowChanged.status).isEqualTo("REJECTED");
        assertThat(windowChanged.failureCode).isEqualTo("VERSION_OUTSIDE_WINDOW");
        assertThat(count("SELECT COUNT(*) FROM user_coupon")).isZero();
        assertThat(points(1L)).isEqualTo(200);
        assertProjectionMatchesLedger(1L);

        authenticate(9902L);
        assertThat(rewardService.history(new PageQuery(), null).getRecords()).isEmpty();
    }

    @Test
    void historyFiltersByStatusWithRealPaginationAndRejectsUnknownStatus() {
        seedAccount(1L, 100);
        seedCouponTemplate(9011L, 10, 10);
        MemberRewardCatalogResponse published = publish("HISTORY_FILTER", 80, 9011L);
        assertThat(rewardService.redeem(published.itemId, published.versionId, "hist-ok").status)
            .isEqualTo("SUCCEEDED");
        assertThat(rewardService.redeem(published.itemId, published.versionId, "hist-reject").status)
            .isEqualTo("REJECTED");

        assertThat(rewardService.history(new PageQuery(), "SUCCEEDED").getRecords())
            .extracting(row -> row.status).containsExactly("SUCCEEDED");
        assertThat(rewardService.history(new PageQuery(), "REJECTED").getRecords())
            .extracting(row -> row.status).containsExactly("REJECTED");
        assertThat(rewardService.history(new PageQuery(), "PENDING").getRecords()).isEmpty();
        assertThat(rewardService.history(new PageQuery(), null).getTotal()).isEqualTo(2);
        assertThatThrownBy(() -> rewardService.history(new PageQuery(), "ARCHIVED"))
            .isInstanceOf(BusinessException.class).hasMessageContaining("兑换记录状态");
    }

    @Test
    void missingOrWrongOwnerPaidFactsDoNotBlockRefundAndLeaveZeroPointEvidence() {
        seedAccount(1L, 30);
        seedAccount(9902L, 10);
        jdbc.update("""
            UPDATE member_account
            SET growth_value = 20, total_consumption_amount = 20.00,
                total_reservations = 1, total_member_discount_amount = 0.00
            WHERE user_id = 1
            """);
        BizOrder missing = refundedOrder(778L, 1L);
        Reservation firstReservation = reservation(888L, 1L);

        memberService.reverseRefundedOrder(missing, firstReservation);

        assertThat(points(1L)).isEqualTo(30);
        assertThat(jdbc.queryForMap("""
            SELECT growth_value, total_consumption_amount, total_reservations
            FROM member_account WHERE user_id = 1
            """))
            .containsEntry("growth_value", 0)
            .containsEntry("total_consumption_amount", new BigDecimal("0.00"))
            .containsEntry("total_reservations", 0);
        assertThat(jdbc.queryForMap("""
            SELECT event_type, reason_code, points_delta, points_balance_before, points_balance_after,
                   reservation_id
            FROM member_account_log WHERE business_key = 'ORDER_REFUND_POINTS_EXCEPTION:778'
            """))
            .containsEntry("event_type", "MEMBER_REFUND_POINTS_EXCEPTION")
            .containsEntry("reason_code", "PAID_POINTS_FACT_MISSING")
            .containsEntry("points_delta", 0)
            .containsEntry("points_balance_before", 30)
            .containsEntry("points_balance_after", 30)
            .containsEntry("reservation_id", 888L);

        pointsService.append(9902L, "ORDER_PAID_POINTS:779", "MEMBER_POINTS_CHANGED", "R4_POINTS_V1",
            20, 20, new BigDecimal("20.00"), 779L, "PAYMENT", "PAID_ORDER", "归属反例支付事实");
        authenticate(1L);
        memberService.reverseRefundedOrder(refundedOrder(779L, 1L), reservation(889L, 1L));

        assertThat(points(1L)).isEqualTo(30);
        assertThat(jdbc.queryForObject("""
            SELECT reason_code FROM member_account_log
            WHERE business_key = 'ORDER_REFUND_POINTS_EXCEPTION:779'
            """, String.class)).isEqualTo("PAID_POINTS_FACT_OWNER_MISMATCH");
        assertProjectionMatchesLedger(1L);
    }

    @Test
    void sameUserAccountMismatchAndMalformedPaidFactsLeaveDeterministicZeroPointReviewFacts() {
        seedAccount(1L, 30);
        long accountId = jdbc.queryForObject("SELECT id FROM member_account WHERE user_id = 1", Long.class);
        pointsService.append(1L, "ORDER_PAID_POINTS:780", "MEMBER_POINTS_CHANGED", "R4_POINTS_V1",
            20, 20, new BigDecimal("20.00"), 780L, "PAYMENT", "PAID_ORDER", "账户错配支付事实");
        jdbc.update("UPDATE member_account_log SET account_id = ? WHERE business_key = 'ORDER_PAID_POINTS:780'", accountId + 1000);
        memberService.reverseRefundedOrder(refundedOrder(780L, 1L), reservation(890L, 1L));

        assertThat(points(1L)).isEqualTo(50);
        assertThat(jdbc.queryForObject("SELECT reason_code FROM member_account_log WHERE business_key = 'ORDER_REFUND_POINTS_EXCEPTION:780'",
            String.class)).isEqualTo("PAID_POINTS_FACT_ACCOUNT_MISMATCH");

        pointsService.append(1L, "ORDER_PAID_POINTS:781", "MEMBER_POINTS_CHANGED", "R4_POINTS_V1",
            20, 20, new BigDecimal("20.00"), 781L, "PAYMENT", "PAID_ORDER", "载荷错误支付事实");
        jdbc.update("UPDATE member_account_log SET source_type = 'MANUAL' WHERE business_key = 'ORDER_PAID_POINTS:781'");
        memberService.reverseRefundedOrder(refundedOrder(781L, 1L), reservation(891L, 1L));

        assertThat(points(1L)).isEqualTo(70);
        assertThat(jdbc.queryForObject("SELECT reason_code FROM member_account_log WHERE business_key = 'ORDER_REFUND_POINTS_EXCEPTION:781'",
            String.class)).isEqualTo("PAID_POINTS_FACT_PAYLOAD_INVALID");
    }

    private MemberRewardCatalogResponse publish(String code, int cost, long templateId) {
        SaveMemberRewardCatalogItemRequest item = new SaveMemberRewardCatalogItemRequest();
        item.itemCode = code;
        item.itemName = code;
        MemberRewardCatalogResponse created = rewardService.createCatalogItem(item);
        return publishVersion(created.itemId, cost, templateId, null, null);
    }

    private MemberRewardCatalogResponse publishVersion(long itemId, int cost, long templateId,
                                                        LocalDateTime start, LocalDateTime end) {
        PublishMemberRewardVersionRequest version = new PublishMemberRewardVersionRequest();
        version.pointsCost = cost;
        version.minimumLevelCode = "BASIC";
        version.couponTemplateId = templateId;
        version.validityDays = 30;
        version.effectiveStartTime = start;
        version.effectiveEndTime = end;
        return rewardService.publishVersion(itemId, version);
    }

    private void seedAccount(long userId, int points) {
        authenticate(userId);
        memberService.account(userId);
        pointsService.append(userId, "TEST_SEED:" + userId, "MEMBER_POINTS_TEST_SEED", "TEST_V1",
            points, 0, BigDecimal.ZERO, null, "TEST", "TEST_SEED", "测试积分夹具");
        authenticate(1L);
    }

    private void seedCouponTemplate(long id, int total, int perUser) {
        jdbc.update("""
            INSERT INTO coupon_template (
              id, template_code, template_name, coupon_type, discount_value, threshold_amount,
              total_quantity, issued_quantity, used_quantity, per_user_limit, validity_days,
              allow_activity_stack, status, source_type, sort_order, deleted
            ) VALUES (?, ?, ?, 'CASH', 10.00, 0.00, ?, 0, 0, ?, 30, 1, 'ACTIVE', 'ADMIN', 0, 0)
            """, id, "TPL-" + id, "模板" + id, total, perUser);
    }

    private BizOrder refundedOrder(long orderId, long userId) {
        BizOrder order = new BizOrder();
        order.id = orderId;
        order.userId = userId;
        order.orderNo = "ORDER-" + orderId;
        order.paidAmount = new BigDecimal("20.00");
        return order;
    }

    private Reservation reservation(long reservationId, long userId) {
        Reservation reservation = new Reservation();
        reservation.id = reservationId;
        reservation.userId = userId;
        return reservation;
    }

    private String concurrentRedeem(CountDownLatch start, MemberRewardCatalogResponse reward,
                                    String key, Long userId) throws Exception {
        authenticate(userId);
        start.await();
        return rewardService.redeem(reward.itemId, reward.versionId, key).status;
    }

    private void clearRuntimeFacts() {
        jdbc.update("DELETE FROM member_reward_event");
        jdbc.update("DELETE FROM member_reward_redemption");
        jdbc.update("DELETE FROM coupon_status_log");
        jdbc.update("DELETE FROM user_coupon");
        jdbc.update("UPDATE coupon_template SET issued_quantity = 0");
        jdbc.update("DELETE FROM member_account_log WHERE business_key LIKE 'REWARD_%'");
    }

    private void resetAccount(long userId, int points) {
        jdbc.update("DELETE FROM member_account_log WHERE user_id = ?", userId);
        jdbc.update("UPDATE member_account SET points_balance = ?, growth_value = 100, total_consumption_amount = 100.00, total_reservations = 1 WHERE user_id = ?",
            points, userId);
        pointsService.append(userId, "RESET_SEED:" + userId + ":" + System.nanoTime(),
            "MEMBER_POINTS_TEST_SEED", "TEST_V1", points, 0, BigDecimal.ZERO,
            null, "TEST", "TEST_SEED", "重置积分夹具");
        // append starts from the requested projection, so normalize to a single fact and matching projection.
        jdbc.update("UPDATE member_account SET points_balance = ? WHERE user_id = ?", points, userId);
        jdbc.update("UPDATE member_account_log SET points_delta = ?, points_balance_before = 0, points_balance_after = ? WHERE user_id = ?",
            points, points, userId);
    }

    private int points(long userId) {
        return jdbc.queryForObject("SELECT points_balance FROM member_account WHERE user_id = ?", Integer.class, userId);
    }

    private int count(String sql) {
        return jdbc.queryForObject(sql, Integer.class);
    }

    private void assertProjectionMatchesLedger(long userId) {
        assertThat(jdbc.queryForObject("""
            SELECT a.points_balance - COALESCE(SUM(l.points_delta), 0)
            FROM member_account a LEFT JOIN member_account_log l ON l.account_id = a.id
            WHERE a.user_id = ? GROUP BY a.id, a.points_balance
            """, Integer.class, userId)).isZero();
    }

    private void assertCompensationRejectionDidNotMutate(MemberRewardRedemptionResponse redeemed, int expectedPoints) {
        assertThat(points(1L)).isEqualTo(expectedPoints);
        assertThat(jdbc.queryForObject("SELECT status FROM user_coupon WHERE id = ?", String.class,
            redeemed.userCouponId)).isEqualTo("CLAIMED");
        assertThat(jdbc.queryForObject("SELECT status FROM member_reward_redemption WHERE id = ?", String.class,
            redeemed.id)).isEqualTo("SUCCEEDED");
        assertThat(count("SELECT COUNT(*) FROM member_account_log WHERE business_key = 'REWARD_COMPENSATE:" + redeemed.id + "'"))
            .isZero();
        assertProjectionMatchesLedger(1L);
    }

    private void authenticate(Long userId) {
        CurrentUser user = new CurrentUser(userId, "reward-" + userId, Set.of("USER"),
            Set.of("member-reward:me", "member-reward:catalog:manage",
                "member-reward:exception:manage", "member-reward:audit:view"));
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(user, null, user.permissions().stream()
                .map(org.springframework.security.core.authority.SimpleGrantedAuthority::new).toList()));
    }
}
