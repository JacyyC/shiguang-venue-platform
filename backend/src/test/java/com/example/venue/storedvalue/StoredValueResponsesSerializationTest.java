package com.example.venue.storedvalue;

import com.example.venue.storedvalue.dto.StoredValueResponses;
import com.example.venue.storedvalue.service.StoredValueReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class StoredValueResponsesSerializationTest {
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void financeDtosExposeOnlyPurposeBuiltReferencesAndSummaries() throws Exception {
        JsonNode account = objectMapper.valueToTree(new StoredValueResponses.FinanceAccountResponse(
            "SVA-ACCOUNT", "USR-USER", "DEV_GENERAL", "DEV", "NORMAL",
            amount("10.00"), amount("0.00"), amount("1.00"), amount("0.00"),
            amount("11.00"), amount("11.00"), LocalDateTime.now()));
        JsonNode recharge = objectMapper.valueToTree(new StoredValueResponses.FinanceRechargeResponse(
            "SVR-REF-RECHARGE", "...ABC123", "USR-USER", "DEV_GENERAL", "SUCCEEDED",
            amount("10.00"), amount("1.00"), amount("1.00"), "CNY", "stored-value.dev", "DEV",
            "DEV_STORED_VALUE", "...EXT123", null, LocalDateTime.now(), LocalDateTime.now()));
        JsonNode ledger = objectMapper.valueToTree(new StoredValueResponses.FinanceLedgerResponse(
            "SVT-TRANSACTION", "SVA-ACCOUNT", "USR-USER", "RECHARGE_PRINCIPAL_POSTING", "RECHARGE",
            "...SOURCE", "PRINCIPAL_AVAILABLE", "CREDIT", amount("10.00"), "CNY", "充值入账",
            LocalDateTime.now()));
        JsonNode run = objectMapper.valueToTree(new StoredValueResponses.ReconciliationRunResponse(
            "SVRUN-20260804", LocalDate.of(2026, 8, 4), "DEV", "DEV_STORED_VALUE", "STORED_VALUE",
            "COMPLETED", 0, LocalDateTime.now()));
        JsonNode difference = objectMapper.valueToTree(new StoredValueResponses.ReconciliationDifferenceResponse(
            "SVD-DIFFERENCE", "SVRUN-20260804", "LOCAL_ONLY", amount("1.00"), amount("0.00"),
            "CNY", "OPEN", "待复核", "SVT-TRANSACTION", LocalDateTime.now()));

        assertFields(account, "accountRef", "userRef", "productCode", "environment", "status",
            "principalAvailable", "principalFrozen", "bonusAvailable", "bonusFrozen", "availableTotal",
            "totalBalance", "updateTime");
        assertFields(recharge, "rechargeRef", "rechargeNoSuffix", "userRef", "productCode", "status",
            "principalAmount", "expectedBonusAmount", "actualBonusAmount", "currency", "providerKey",
            "providerEnv", "providerName", "externalRefSummary", "failureCode", "createTime", "updateTime");
        assertFields(ledger, "transactionRef", "accountRef", "userRef", "transactionType", "sourceType",
            "sourceRef", "bucket", "direction", "amount", "currency", "reason", "occurredAt");
        assertFields(run, "runRef", "reconcileDate", "providerEnv", "providerName", "scopeType", "status",
            "differenceCount", "completedAt");
        assertFields(difference, "differenceRef", "runRef", "differenceType", "providerAmount", "localAmount",
            "currency", "status", "reason", "ledgerTransactionRef", "createTime");

        String combined = account.toString() + recharge + ledger + run + difference;
        assertThat(combined).doesNotContain("\"accountId\"", "\"productId\"", "\"rechargeId\"",
            "\"transactionId\"", "\"sourceId\"", "\"runId\"", "\"differenceNo\"", "\"externalRef\"");
    }

    @Test
    void approvalDtosExposeSafeTargetAndActorReferences() throws Exception {
        JsonNode list = objectMapper.valueToTree(new StoredValueResponses.ApprovalListResponse(
            "SVA-APPROVAL", "RECHARGE_REFUND", "RECHARGE", "SVR-REF-RECHARGE", "USR-REQUESTER", true,
            amount("10.00"), "CNY", "stored-value:refund:review", "stored-value:refund:manage", "PENDING",
            LocalDateTime.now().plusDays(1), LocalDateTime.now(), LocalDateTime.now()));
        JsonNode detail = objectMapper.valueToTree(new StoredValueResponses.ApprovalDetailResponse(
            "SVA-APPROVAL", "RECHARGE_REFUND", "RECHARGE", "SVR-REF-RECHARGE", "USR-REQUESTER",
            "USR-REVIEWER", null, true, amount("10.00"), "CNY", "人工复核", "stored-value:refund:review",
            "stored-value:refund:manage", "PENDING", LocalDateTime.now().plusDays(1), "R5", 0,
            null, null, null, null, LocalDateTime.now(), LocalDateTime.now()));

        assertThat(fieldNames(list)).contains("approvalNo", "targetRef", "requesterRef", "requesterIsCurrentActor")
            .doesNotContain("id", "targetId", "requesterId", "reviewerId", "executorId");
        assertThat(fieldNames(detail)).contains("approvalNo", "targetRef", "requesterRef", "reviewerRef", "executorRef")
            .doesNotContain("id", "targetId", "requesterId", "reviewerId", "executorId");
    }

    @Test
    void referencesAreStableAndDoNotContainRawIdentifiers() {
        assertThat(StoredValueReference.account(101L)).startsWith("SVA-").doesNotContain("101");
        assertThat(StoredValueReference.user(101L)).startsWith("USR-").doesNotContain("101");
        assertThat(StoredValueReference.recharge("SVR-RAW-101")).startsWith("SVR-REF-").doesNotContain("SVR-RAW-101");
        assertThat(StoredValueReference.difference(101L)).startsWith("SVD-").doesNotContain("101");
        assertThat(StoredValueReference.suffix("provider-external-reference-101")).isEqualTo("...ENCE-101");
    }

    private void assertFields(JsonNode node, String... expected) {
        assertThat(fieldNames(node)).containsExactlyInAnyOrder(expected);
    }

    private Set<String> fieldNames(JsonNode node) {
        Set<String> fields = new HashSet<>();
        node.fieldNames().forEachRemaining(fields::add);
        return fields;
    }

    private BigDecimal amount(String value) {
        return new BigDecimal(value);
    }
}
