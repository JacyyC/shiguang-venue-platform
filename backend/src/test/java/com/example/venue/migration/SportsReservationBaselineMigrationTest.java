package com.example.venue.migration;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class SportsReservationBaselineMigrationTest {
    private static final Path MIGRATION_DIR = Path.of("src/main/resources/db/migration");
    private static final Set<String> SCHEMA_TABLES = Set.of(
        "asset_inspection_record",
        "asset_item",
        "asset_lifecycle_event",
        "asset_repair_record",
        "biz_order",
        "business_event",
        "coupon_status_log",
        "coupon_template",
        "customer_complaint",
        "customer_service_case",
        "customer_service_case_event",
        "finance_reconciliation_item",
        "funnel_event",
        "funnel_session",
        "member_account",
        "member_account_log",
        "member_level",
        "member_profile_tag",
        "notice",
        "notice_audience_snapshot",
        "notice_delivery_batch",
        "notice_delivery_log",
        "notice_email_delivery",
        "notice_email_preference",
        "notice_experiment",
        "notice_experiment_variant",
        "notice_message",
        "notice_outbox",
        "notice_read_receipt_event",
        "ops_metric_snapshot",
        "order_status_log",
        "payment_attempt",
        "payment_callback_log",
        "payment_provider_operation_log",
        "payment_record",
        "price_rule",
        "promotion_campaign",
        "provider_call_log",
        "provider_registry_entry",
        "refund_callback_log",
        "refund_record",
        "reservation",
        "reservation_lifecycle",
        "reservation_lifecycle_event",
        "reservation_occupancy",
        "reservation_reschedule_request",
        "reservation_status_log",
        "review_moderation_record",
        "risk_assessment_snapshot",
        "site_incident_log",
        "site_shift",
        "site_shift_handover",
        "site_task",
        "sys_config",
        "sys_deleted_user_snapshot",
        "sys_dict_data",
        "sys_dict_type",
        "sys_login_log",
        "sys_menu",
        "sys_operation_log",
        "sys_role",
        "sys_role_menu",
        "sys_user",
        "sys_user_role",
        "user_coupon",
        "user_venue_favorite",
        "venue",
        "venue_admin_relation",
        "venue_business_calendar",
        "venue_facility",
        "venue_facility_maintenance",
        "venue_inventory_audit",
        "venue_observation_location",
        "venue_review",
        "venue_space",
        "venue_time_slot",
        "venue_unavailable_block",
        "weather_observation_snapshot"
    );
    private static final Set<String> BOOTSTRAP_TABLES = Set.of(
        "member_level",
        "provider_registry_entry",
        "sys_config",
        "sys_dict_data",
        "sys_dict_type",
        "sys_menu",
        "sys_role",
        "sys_role_menu",
        "sys_user",
        "sys_user_role"
    );
    private static final Set<String> COMMUNITY_TABLES = Set.of(
        "community_comment",
        "community_governance_event",
        "community_group",
        "community_group_member",
        "community_post",
        "community_report",
        "community_user_block"
    );
    private static final Set<String> COMMUNITY_ACTIVITY_TABLES = Set.of(
        "community_activity",
        "community_companion_request",
        "community_activity_application",
        "community_activity_participation",
        "community_match_decision",
        "community_activity_state_event",
        "community_activity_command",
        "community_activity_notification_task"
    );
    private static final Set<String> MEMBER_REWARD_TABLES = Set.of(
        "member_reward_catalog_item",
        "member_reward_catalog_version",
        "member_reward_redemption",
        "member_reward_event"
    );
    private static final Set<String> PLACEMENT_TABLES = Set.of(
        "placement_slot",
        "placement_candidate_version",
        "placement_decision",
        "placement_event"
    );
    private static final Set<String> ADVERTISING_TABLES = Set.of(
        "advertising_advertiser",
        "advertising_campaign",
        "advertising_creative_version",
        "advertising_audit_event"
    );
    private static final Set<String> ADVERTISING_DECISION_TABLES = Set.of(
        "advertising_privacy_preference",
        "advertising_decision_guard",
        "advertising_decision",
        "advertising_event"
    );
    private static final Set<String> ADVERTISING_CONVERSION_TABLES = Set.of(
        "advertising_conversion_callback",
        "advertising_attribution"
    );

    @Test
    void migrationsKeepTheV1V2BaselineAndAppendRemediations() throws IOException {
        List<String> migrationNames;
        try (var files = Files.list(MIGRATION_DIR)) {
            // 按 V 后的版本号数字升序排序，避免字典序把 V10 排到 V2 之前。
            migrationNames = files
                .filter(Files::isRegularFile)
                .map(path -> path.getFileName().toString())
                .sorted(Comparator.comparingInt(SportsReservationBaselineMigrationTest::migrationVersion))
                .toList();
        }

        assertThat(migrationNames).containsExactly(
            "V1__sports_reservation_schema.sql",
            "V2__sports_reservation_bootstrap.sql",
            "V3__user_credential_and_concurrency_controls.sql",
            "V4__reservation_credential_remediation.sql",
            "V5__password_reset_tokens.sql",
            "V6__site_shift_assignment_guard.sql",
            "V7__agent_operations_summary_indexes.sql",
            "V8__agent_remaining_analytics_indexes.sql",
            "V9__feedback_content_raw.sql",
            "V10__extend_lifecycle_reason_text.sql",
            "V11__reservation_budget_released.sql",
            "V12__checkin_credential_hash_uniqueness.sql",
            "V13__site_shift_user_level_guard.sql",
            "V14__vertical_resource_allocation.sql",
            "V15__community_governance.sql",
            "V16__community_activity_matching.sql",
            "V17__member_reward_redemption.sql",
            "V18__owned_placement_decision_measurement.sql",
            "V19__placement_s09_query_indexes.sql",
            "V20__placement_decision_guard.sql",
            "V21__stored_value_account_ledger_recharge.sql",
            "V22__stored_value_order_payment_refund_reconciliation.sql",
            "V23__stored_value_s07_api_menus.sql",
            "V24__advertising_lifecycle_permissions.sql",
            "V25__advertising_decision_privacy_measurement.sql",
            "V26__advertising_conversion_attribution.sql",
            "V27__advertising_s05_query_indexes.sql",
            "V28__stored_value_posted_ledger_guard.sql",
            "V29__stored_value_callback_receipt_boundary.sql",
            "V30__stored_value_posting_recovery_claim.sql",
            "V31__stored_value_approval_workflow.sql",
            "V32__stored_value_reconciliation_snapshot_observations.sql",
            "V33__stored_value_account_close_permission.sql",
            "V34__community_group_category.sql",
            "V35__system_admin_full_permissions.sql",
            // V36：预约创建的请求身份与结果核对（S03 / AR-01），同样只做新增
            "V36__reservation_create_command.sql"
        );
    }

    @Test
    void v35GrantsSystemAdminFullMenuCoverageAppendOnly() throws IOException {
        // readMigration 会归一化为全小写并去除反引号，断言按归一化文本书写。
        String migration = readMigration("V35__system_admin_full_permissions.sql");

        assertThat(migration).contains(
            "insert into sys_role_menu",
            "from sys_role system_role",
            "join sys_menu permission_menu",
            "system_role.id = 3",
            "system_role.role_key = 'system_admin'",
            "not exists (",
            "select 1 from sys_role_menu existing_relation");
        assertThat(migration).doesNotContain(
            "update ", "delete from", "drop table", "truncate table", "alter table");
    }

    @Test
    void v34RegistersGroupCategoryDictAndNullableColumnWithoutRoleGrant() throws IOException {
        // readMigration 会归一化为全小写并去除反引号，断言按归一化文本书写。
        String migration = readMigration("V34__community_group_category.sql");

        assertThat(migration).contains(
            "'community_group_category'",
            "'study'", "'sports'", "'art'", "'board_game'", "'family'", "'career'",
            "category_code varchar(32) default null",
            "idx_community_group_category");
        assertThat(migration).doesNotContain(
            "drop table", "delete from", "truncate table", "insert into sys_role_menu", "alter table sys_role");
    }

    @Test
    void v33RegistersAccountClosePermissionWithoutRoleGrant() throws IOException {
        String migration = readMigration("V33__stored_value_account_close_permission.sql");

        assertThat(migration).contains(
            "stored-value:account:close",
            "insert into sys_menu",
            "not exists (select 1 from sys_menu existing_menu");
        assertThat(migration).doesNotContain("insert into sys_role_menu", "drop table", "delete from", "truncate table");
    }

    @Test
    void v32AddsStableReconciliationSnapshotAndAppendOnlyObservations() throws IOException {
        String migration = readMigration("V32__stored_value_reconciliation_snapshot_observations.sql");

        assertThat(migration).contains(
            "statement_snapshot_key",
            "period_start",
            "provider_config_digest",
            "provider_source",
            "uk_stored_value_reconciliation_snapshot",
            "create table stored_value_reconciliation_difference_observation",
            "uk_stored_value_reconciliation_difference_observation");
        assertThat(withoutLineComments(migration)).doesNotContain(
            "drop table", "delete from", "truncate table", "insert into sys_role_menu");
    }

    @Test
    void v31AddsApprovalFactsAndOnlyRegistersMissingLeafPermissions() throws IOException {
        String migration = readMigration("V31__stored_value_approval_workflow.sql");

        assertThat(migration).contains(
            "create table stored_value_approval",
            "create table stored_value_approval_event",
            "uk_stored_value_approval_active",
            "trg_stored_value_approval_event_no_update",
            "trg_stored_value_approval_event_no_delete",
            "trg_stored_value_approval_snapshot_no_update",
            "new.expires_at > old.expires_at",
            "stored-value:recharge:execute",
            "stored-value:refund:review",
            "stored-value:account:unfreeze:review",
            "insert into sys_menu",
            "not exists (select 1 from sys_menu existing_menu");
        assertThat(migration).doesNotContain("insert into sys_role_menu", "drop table", "delete from");
    }

    @Test
    void v30AddsPostingRecoveryClaimWithoutChangingRechargeFacts() throws IOException {
        String migration = readMigration("V30__stored_value_posting_recovery_claim.sql");

        assertThat(migration).contains(
            "add column posting_attempt_count",
            "add column posting_claim_token",
            "add column posting_started_at",
            "add column posting_last_failure_code",
            "add column posting_last_failure_message",
            "idx_stored_value_recharge_posting_recovery");
        assertThat(migration).doesNotContain(
            "DROP TABLE `stored_value_recharge`", "DELETE FROM `stored_value_recharge`",
            "DROP COLUMN `recharge_id`", "DROP COLUMN `principal_amount`");
    }

    @Test
    void v29AddsAReceiptBoundaryWithoutChangingRechargeFacts() throws IOException {
        String migration = readMigration("V29__stored_value_callback_receipt_boundary.sql");

        assertThat(migration).contains(
            "add column request_id varchar(64)",
            "add column route varchar(128)",
            "modify column provider_key varchar(128) default null",
            "modify column provider_env varchar(16) default null",
            "drop index uk_stored_value_callback_event",
            "uk_stored_value_callback_request",
            "idx_stored_value_callback_event");
        assertThat(migration).doesNotContain(
            "DROP TABLE `stored_value_recharge`", "DELETE FROM `stored_value_recharge`",
            "DROP COLUMN `recharge_id`");
    }

    @Test
    void v24AddsIndependentAdvertisingLifecycleFactsAndSeparatedPermissions() throws IOException {
        String migration = readMigration("V24__advertising_lifecycle_permissions.sql");

        assertThat(statementTables(migration, "create table "))
            .containsExactlyInAnyOrderElementsOf(ADVERTISING_TABLES);
        assertThat(migration).contains(
            "advertising_advertiser",
            "advertising_campaign",
            "advertising_creative_version",
            "advertising_audit_event",
            "uk_advertising_campaign_no",
            "uk_advertising_creative_campaign_version",
            "chk_advertising_creative_target",
            "advertising:manage",
            "advertising:review",
            "advertising:publish",
            "advertising:analysis:view",
            "advertising:audit:view",
            "permission_menu.perms in ('content-admin:view','advertising:audit:view')",
            "system_role.role_key = 'system_admin'"
        );
        assertThat(withoutLineComments(migration)).doesNotContain(
            "drop table", "drop column", "truncate table", "delete from",
            "insert into sys_role (", "insert into sys_menu (id", "insert into sys_role_menu (id"
        );
    }

    @Test
    void v25AddsIndependentPrivacyDecisionGuardAndMeasurementFacts() throws IOException {
        String migration = readMigration("V25__advertising_decision_privacy_measurement.sql");

        assertThat(statementTables(migration, "create table "))
            .containsExactlyInAnyOrderElementsOf(ADVERTISING_DECISION_TABLES);
        assertThat(migration).contains(
            "uk_advertising_privacy_user",
            "uk_advertising_guard_scope",
            "uk_advertising_decision_token",
            "uk_advertising_event_key",
            "uk_advertising_event_decision_type",
            "idx_advertising_event_user_slot_time",
            "idx_advertising_event_user_creative_time",
            "chk_advertising_decision_slot",
            "chk_advertising_event_type",
            "client_viewable",
            "home_commercial",
            "commercial_enabled",
            "measurement_enabled"
        );
        assertThat(withoutLineComments(migration)).doesNotContain(
            "drop table", "drop column", "truncate table", "delete from",
            "insert into sys_role", "insert into sys_menu"
        );
    }

    @Test
    void v26AddsOnlyNarrowAdvertisingConversionAndAttributionFacts() throws IOException {
        String migration = readMigration("V26__advertising_conversion_attribution.sql");

        assertThat(statementTables(migration, "create table "))
            .containsExactlyInAnyOrderElementsOf(ADVERTISING_CONVERSION_TABLES);
        assertThat(migration).contains(
            "uk_advertising_callback_no",
            "uk_advertising_callback_event_key",
            "idx_advertising_callback_reservation_event",
            "idx_advertising_callback_retry",
            "chk_advertising_callback_event_type",
            "chk_advertising_callback_status",
            "uk_advertising_attribution_no",
            "uk_advertising_attribution_reservation",
            "idx_advertising_attribution_click_event",
            "idx_advertising_attribution_user_status_time",
            "idx_advertising_attribution_campaign_time",
            "chk_advertising_attribution_status",
            "chk_advertising_attribution_click_snapshot",
            "reservation_id",
            "policy_version",
            "rule_version_snapshot"
        );
        assertThat(withoutLineComments(migration)).doesNotContain(
            "drop table", "drop column", "truncate table", "delete from",
            "alter table", "insert into sys_role", "insert into sys_menu"
        );
    }

    @Test
    void v23AddsOnlyStoredValueUserAndFinanceMenuBindings() throws IOException {
        String migration = readMigration("V23__stored_value_s07_api_menus.sql");

        assertThat(statementTables(migration, "create table ")).isEmpty();
        assertThat(migration).contains(
            "/me/stored-value",
            "/system-admin/stored-value",
            "stored-value:me",
            "stored-value:finance:view",
            "role_key in ('user', 'system_admin')",
            "role_key = 'system_admin'"
        );
        assertThat(withoutLineComments(migration)).doesNotContain(
            "drop table", "drop column", "truncate table", "delete from", "grant ");
    }

    @Test
    void v22AddsStoredValueOrderFactsRefundSnapshotsAndReconciliationLinks() throws IOException {
        String migration = readMigration("V22__stored_value_order_payment_refund_reconciliation.sql");

        assertThat(statementTables(migration, "create table "))
            .containsExactlyInAnyOrder(
                "stored_value_order_payment",
                "stored_value_order_payment_allocation",
                "stored_value_order_refund",
                "stored_value_order_refund_allocation");
        assertThat(migration).contains(
            "alter table biz_order",
            "payment_tool_type",
            "stored_value_order_payment",
            "uk_stored_value_order_payment_order",
            "stored_value_order_payment_allocation",
            "expires_at_snapshot",
            "source_order_payment_id",
            "stored_value_order_refund",
            "stored_value_order_refund_allocation",
            "scope_type",
            "order_payment_id",
            "order_refund_id",
            "ledger_transaction_id",
            "account_id"
        );
        assertThat(withoutLineComments(migration)).doesNotContain(
            "drop table", "drop column", "truncate table", "delete from");
    }

    @Test
    void v18AddsPlacementDecisionMeasurementAndSeparatedPermissions() throws IOException {
        String migration = readMigration("V18__owned_placement_decision_measurement.sql");

        assertThat(statementTables(migration, "create table "))
            .containsExactlyInAnyOrderElementsOf(PLACEMENT_TABLES);
        assertThat(migration).contains(
            "uk_placement_slot_code",
            "uk_placement_candidate_no",
            "uk_placement_decision_token_hash",
            "uk_placement_event_key",
            "uk_placement_event_decision_type",
            "chk_placement_candidate_audience_level",
            "chk_placement_candidate_window",
            "chk_placement_candidate_frequency",
            "chk_placement_event_visible",
            "'home_primary'",
            "'placement:manage'",
            "'placement:publish'",
            "'placement:audit:view'",
            "permission_menu.perms in ('content-admin:view','placement:audit:view')",
            "system_role.role_key = 'system_admin'"
        );
        assertThat(withoutLineComments(migration)).doesNotContain(
            "drop table", "drop column", "truncate table", "delete from",
            "insert into sys_role (", "insert into sys_menu (id", "insert into sys_role_menu (id"
        );
    }

    @Test
    void v19AddsBoundedPlacementDecisionAndEventIndexes() throws IOException {
        String migration = readMigration("V19__placement_s09_query_indexes.sql");

        assertThat(migration).contains(
            "create index idx_placement_candidate_slot_status_priority",
            "slot_id, status, priority desc, effective_from desc, id asc",
            "create index idx_placement_event_slot_time",
            "slot_id, occurred_at desc, id desc"
        );
        assertThat(withoutLineComments(migration)).doesNotContain(
            "drop table", "drop column", "truncate table", "delete from", "update "
        );
    }

    @Test
    void v20AddsPersistentPlacementDecisionGuard() throws IOException {
        String migration = readMigration("V20__placement_decision_guard.sql");

        assertThat(statementTables(migration, "create table "))
            .containsExactly("placement_decision_guard");
        assertThat(migration).contains(
            "primary key (user_id, slot_id, candidate_version_id)"
        );
        assertThat(withoutLineComments(migration)).doesNotContain(
            "drop table", "drop column", "truncate table", "delete from", "update "
        );
    }

    @Test
    void v3AddsOnlyUserCredentialAndCompareAndSetColumns() throws IOException {
        String migration = readMigration("V3__user_credential_and_concurrency_controls.sql");

        assertThat(migration).contains(
            "alter table sys_user",
            "add column credential_version bigint not null default 0",
            "add column user_version bigint not null default 0"
        );
    }

    @Test
    void v4ClearsOnlyLegacyReplayableQrPayloads() throws IOException {
        String migration = readMigration("V4__reservation_credential_remediation.sql");

        assertThat(migration).contains(
            "update reservation_lifecycle",
            "set qr_payload = null",
            "where qr_payload is not null"
        );
        assertThat(migration).doesNotContain("drop column", "delete from");
    }

    @Test
    void v5AddsOneTimePasswordResetTokens() throws IOException {
        String migration = readMigration("V5__password_reset_tokens.sql");

        assertThat(migration).contains(
            "create table password_reset_token",
            "unique key uk_password_reset_token_hash",
            "idx_password_reset_token_user_active"
        );
    }

    @Test
    void v6AddsShiftAssignmentSerializationGuard() throws IOException {
        String migration = readMigration("V6__site_shift_assignment_guard.sql");

        assertThat(migration).contains(
            "create table site_shift_assignment_guard",
            "primary key (assigned_user_id, role_code)"
        );
    }

    @Test
    void v7AddsOnlyAgentOperationsSummaryQueryIndexes() throws IOException {
        String migration = readMigration("V7__agent_operations_summary_indexes.sql");

        assertThat(migration).contains(
            "idx_reservation_venue_create (venue_id, create_time)",
            "idx_reservation_create (create_time)",
            "idx_payment_status_time_order (status, pay_time, order_id)",
            "idx_refund_status_time_order (status, update_time, order_id)"
        );
        assertThat(migration).doesNotContain("drop ", "delete ", "update ");
    }

    @Test
    void v8AddsOnlyRemainingAgentAnalyticsQueryIndexes() throws IOException {
        String migration = readMigration("V8__agent_remaining_analytics_indexes.sql");

        assertThat(migration).contains(
            "idx_funnel_metric_scope_time",
            "idx_funnel_dropoff_scope_time",
            "idx_payment_failure_status_time_order",
            "idx_business_event_type_result_time_entity",
            "idx_coupon_template_scope_valid_time",
            "idx_user_coupon_used_attribution"
        );
        assertThat(migration).doesNotContain("drop ", "delete ", "update ");
    }

    @Test
    void v9AddsOnlyFeedbackContentRawColumns() throws IOException {
        String migration = readMigration("V9__feedback_content_raw.sql");

        assertThat(migration).contains(
            "alter table venue_review",
            "add column content_raw text",
            "alter table customer_complaint",
            "alter table customer_service_case_event",
            "add column note_raw text"
        );
        // V9 只追加原文列，不建表、不删列、不删数据、不改既有数据
        assertThat(migration).doesNotContain("drop ", "delete ", "update ", "create table");
    }

    @Test
    void v10ExtendsOnlyLifecycleReasonTextColumns() throws IOException {
        String migration = readMigration("V10__extend_lifecycle_reason_text.sql");

        // A26-P3-01：lifecycle 两表 + 改期申请表 reason_text 从 varchar(255) 加长到 varchar(500)，
        // 与 ReservationCancelRequest/RescheduleRequest/CheckinVerifyRequest/ManualAttendanceRequest/
        // NoShowDecisionRequest 的 @Size(max=500) 对齐，避免超长即事务失败且返回泛化 400。
        assertThat(migration).contains(
            "alter table reservation_lifecycle",
            "alter table reservation_lifecycle_event",
            "alter table reservation_reschedule_request",
            "modify column reason_text varchar(500)"
        );
        // V10 只扩展列长度，不建表、不删列、不删数据、不改既有数据、不改其他列
        assertThat(withoutLineComments(migration))
            .doesNotContain("drop ", "delete ", "update ", "create table", "rename ");
    }

    @Test
    void v11AddsReservationBudgetReleasedColumnForIdempotentRelease() throws IOException {
        String migration = readMigration("V11__reservation_budget_released.sql");

        // AUD-P2-04：reservation 加 budget_released 列作为活动预算回补 CAS 幂等标记，
        // 防 expireOrder+markRefundSuccess 双终态路径对同一 reservation 重复回补活动预算。
        assertThat(migration).contains(
            "alter table reservation",
            "add column budget_released tinyint not null default 0"
        );
        // V11 只追加列，不建表、不删列、不删数据、不改既有数据、不改其他列
        assertThat(withoutLineComments(migration))
            .doesNotContain("drop ", "delete ", "update ", "create table", "rename ");
    }

    @Test
    void v12InvalidatesDuplicateCredentialsAndAddsUniqueHashIndex() throws IOException {
        String migration = readMigration("V12__checkin_credential_hash_uniqueness.sql");

        assertThat(migration).contains(
            "create temporary table v12_duplicate_checkin_hash",
            "default charset=utf8mb4 collate=utf8mb4_unicode_ci",
            "having count(*) > 1",
            "checkin_credential_invalidated",
            "duplicate_credential_hash",
            "set lifecycle.checkin_code_hash = null",
            "drop index idx_reservation_lifecycle_code_hash",
            "add unique key uk_reservation_lifecycle_code_hash (checkin_code_hash)"
        );
        assertThat(migration).doesNotContain("qr_payload =", "content_raw =");
    }

    @Test
    void v13ConvergesShiftGuardToUserLevel() throws IOException {
        String migration = readMigration("V13__site_shift_user_level_guard.sql");

        assertThat(migration).contains(
            "delete duplicate_guard",
            "retained_guard.assigned_user_id = duplicate_guard.assigned_user_id",
            "drop primary key",
            "drop column role_code",
            "add primary key (assigned_user_id)"
        );
        assertThat(migration).doesNotContain("delete from site_shift", "drop table site_shift");
    }

    @Test
    void v14AddsVersionedRulesSeatsAndAllocationSnapshots() throws IOException {
        String migration = readMigration("V14__vertical_resource_allocation.sql");

        assertThat(migration).contains(
            "create table venue_resource_rule",
            "unique key uk_venue_resource_rule_active",
            "create table venue_resource_seat",
            "create table reservation_occupancy_seat",
            "alter table reservation_occupancy",
            "drop index uk_reservation_occupancy_slot",
            "add unique key uk_reservation_occupancy_active",
            "alter table reservation",
            "add column resource_category_snapshot",
            "add column booking_mode_snapshot",
            "insert into venue_resource_rule",
            "'sports_field', 'exclusive', 1, 1",
            "update reservation r",
            "update reservation_occupancy occupancy"
        );
        assertThat(migration).doesNotContain("drop table", "delete from reservation", "delete from venue_space");
    }

    @Test
    void v15AddsCommunityGovernanceRetentionAndDynamicPermissions() throws IOException {
        String migration = readMigration("V15__community_governance.sql");

        assertThat(statementTables(migration, "create table "))
            .containsExactlyInAnyOrderElementsOf(COMMUNITY_TABLES);
        assertThat(migration).contains(
            "unique key uk_community_group_owner_idempotency",
            "unique key uk_community_group_active_owner",
            "active_owner_group_id bigint generated always as",
            "unique key uk_community_post_author_idempotency",
            "unique key uk_community_comment_author_idempotency",
            "unique key uk_community_user_block_pair",
            "unique key uk_community_reporter_idempotency",
            "unique key uk_community_governance_event_idempotency",
            "chk_community_group_owner_identity",
            "chk_community_group_member_identity",
            "chk_community_post_author_identity",
            "chk_community_comment_author_identity",
            "chk_community_user_block_identity",
            "chk_community_report_reporter_identity",
            "idx_community_post_group_cursor",
            "idx_community_comment_post_cursor",
            "idx_community_user_block_forward",
            "idx_community_user_block_reverse",
            "idx_community_report_queue",
            "idx_community_report_target",
            "idx_community_governance_event_subject",
            "idx_community_governance_event_retention",
            "char_length(trim(title)) between 1 and 80",
            "char_length(trim(content_text)) between 1 and 5000",
            "char_length(trim(content_text)) between 1 and 1000",
            "reason_retain_until >= date_add(closed_at, interval 180 day)",
            "minimal_retain_until >= date_add(create_time, interval 365 day)",
            "retain_until >= date_add(occurred_at, interval 365 day)",
            "alter table sys_deleted_user_snapshot",
            "add column deidentify_after datetime",
            "add column deidentified_at datetime",
            "'/community'",
            "'communityhome'",
            "'community:access'",
            "existing_role.role_key in ('user','venue_admin','system_admin')",
            "'/content-admin'",
            "'content-admin:view'",
            "'community:report:review'",
            "'community:content:moderate'",
            "'community:content:restore'",
            "'community:audit:view'",
            "where system_role.role_key = 'system_admin'",
            "permission_menu.perms in ('content-admin:view','community:audit:view')"
        );
        assertThat(withoutLineComments(migration)).doesNotContain(
            "drop table",
            "drop column",
            "truncate table",
            "delete from",
            "insert into sys_role (",
            "insert into sys_menu (id",
            "insert into sys_role_menu (id"
        );
    }

    @Test
    void v16AddsActivitiesCompanionsDeterministicMatchingAndReliableNotificationHandoff() throws IOException {
        String migration = readMigration("V16__community_activity_matching.sql");

        assertThat(statementTables(migration, "create table "))
            .containsExactlyInAnyOrderElementsOf(COMMUNITY_ACTIVITY_TABLES);
        assertThat(migration).contains(
            "uk_community_activity_application_target_user",
            "uk_community_activity_participation_target_user",
            "uk_community_match_decision_replay",
            "uk_community_activity_event_idempotency",
            "uk_community_activity_command",
            "uk_community_activity_notification_event",
            "idx_community_activity_discovery",
            "idx_community_companion_discovery",
            "idx_community_activity_notification_due",
            "companion_match_v1",
            "chk_community_activity_commitment",
            "chk_community_companion_commitment",
            "chk_community_activity_notification_timeline",
            "attempt_count between 0 and 8"
        );
        assertThat(withoutLineComments(migration)).doesNotContain(
            "drop table", "drop column", "truncate table", "delete from", "alter table");
    }

    @Test
    void v17AddsMemberRewardLedgerBridgeCatalogRedemptionAndPermissions() throws IOException {
        String migration = readMigration("V17__member_reward_redemption.sql");

        assertThat(statementTables(migration, "create table "))
            .containsExactlyInAnyOrderElementsOf(MEMBER_REWARD_TABLES);
        assertThat(migration).contains(
            "alter table member_account_log",
            "add column business_key varchar(128)",
            "add column rule_version varchar(64)",
            "add column points_balance_before int",
            "add column points_balance_after int",
            "unique key uk_member_account_log_business_key",
            "member_points_opening_bridge",
            "r4_opening_v1",
            "opening_bridge:r4_v17:account:",
            "member_account.points_balance",
            "member_reward_catalog_item",
            "member_reward_catalog_version",
            "member_reward_redemption",
            "member_reward_event",
            "uk_member_reward_redemption_command",
            "uk_member_reward_event_key",
            "'member-reward:me'",
            "'member-reward:catalog:manage'",
            "'member-reward:exception:manage'",
            "'member-reward:audit:view'"
        );
        assertThat(withoutLineComments(migration)).doesNotContain(
            "drop table", "drop column", "truncate table", "delete from",
            "insert into sys_role (", "insert into sys_menu (id", "insert into sys_role_menu (id"
        );
    }

    @Test
    void schemaCoversEverySportsReservationDomain() throws IOException {
        String schema = readMigration("V1__sports_reservation_schema.sql");

        assertThat(statementTables(schema, "create table "))
            .containsExactlyInAnyOrderElementsOf(SCHEMA_TABLES);
        assertThat(schema).contains("key idx_sys_menu_perms (perms)");
        assertThat(schema).doesNotContain("unique key idx_sys_menu_perms (perms)");
        assertThat(schema).doesNotContain("auto_increment=");
    }

    @Test
    void bootstrapContainsOnlyRequiredReferenceData() throws IOException {
        String bootstrap = readMigration("V2__sports_reservation_bootstrap.sql");

        assertThat(Set.copyOf(statementTables(bootstrap, "insert into ")))
            .isEqualTo(BOOTSTRAP_TABLES);
        assertThat(bootstrap).contains(
            "insert into sys_user (",
            "'admin'",
            "'user'",
            "'venue_admin'",
            "'system_admin'",
            "insert into sys_config",
            "insert into sys_dict_type",
            "insert into sys_dict_data",
            "insert into member_level",
            "insert into provider_registry_entry",
            "'open_meteo'",
            "'internal_risk_rules'",
            "'local_runtime'",
            "'alipay'",
            "'smtp_email'"
        );
        assertThat(countOccurrences(bootstrap, "insert into sys_user (")).isEqualTo(1);
        List<String> adminInserts = bootstrap.lines()
            .filter(line -> line.startsWith("insert into sys_user ("))
            .toList();
        assertThat(adminInserts).hasSize(1);
        assertThat(adminInserts.get(0)).contains(
            "last_login_time",
            "'active',3,null,'2026-07-16 00:00:00'"
        );
        assertThat(countOccurrences(bootstrap, "insert into member_level (")).isEqualTo(3);
        assertThat(countOccurrences(bootstrap, "insert into provider_registry_entry (")).isEqualTo(5);
        assertThat(bootstrap).contains(
            "'payment.alipay.sandbox','payment','sandbox','alipay','alipay sandbox','alipay_sandbox_v1',0,'disabled'",
            "'notice.smtp.sandbox','message','sandbox','smtp_email','smtp 邮件沙箱','smtp_sandbox_v1',0,'disabled'"
        );
        assertThat(bootstrap).doesNotContain(
            "insert into venue ",
            "insert into venue_space",
            "insert into reservation ",
            "insert into biz_order",
            "insert into payment_record",
            "insert into notice ",
            "insert into customer_complaint",
            "insert into provider_call_log",
            "insert into weather_observation_snapshot"
        );
    }

    private static String readMigration(String name) throws IOException {
        Path migration = MIGRATION_DIR.resolve(name);
        assertThat(migration).exists();
        return Files.readString(migration)
            .toLowerCase(Locale.ROOT)
            .replace(Character.toString(96), "");
    }

    // 从迁移文件名（形如 V1__xxx.sql、V10__xxx.sql）中提取 V 后、__ 前的版本号，
    // 用于按数字升序排序，避免字典序把 V10 排到 V2 之前。无法解析时返回 Integer.MAX_VALUE 保持稳定顺序。
    private static int migrationVersion(String name) {
        if (!name.startsWith("V")) {
            return Integer.MAX_VALUE;
        }
        int underscore = name.indexOf("__");
        if (underscore <= 1) {
            return Integer.MAX_VALUE;
        }
        try {
            return Integer.parseInt(name.substring(1, underscore));
        } catch (NumberFormatException ex) {
            return Integer.MAX_VALUE;
        }
    }

    private static int countOccurrences(String text, String token) {
        int count = 0;
        int offset = 0;
        while ((offset = text.indexOf(token, offset)) >= 0) {
            count++;
            offset += token.length();
        }
        return count;
    }

    private static String withoutLineComments(String sql) {
        return sql.lines()
            .filter(line -> !line.stripLeading().startsWith("--"))
            .reduce("", (left, right) -> left + right + "\n");
    }

    private static List<String> statementTables(String sql, String prefix) {
        return sql.lines()
            .filter(line -> line.startsWith(prefix))
            .map(line -> line.substring(prefix.length(), line.indexOf(' ', prefix.length())))
            .toList();
    }
}
