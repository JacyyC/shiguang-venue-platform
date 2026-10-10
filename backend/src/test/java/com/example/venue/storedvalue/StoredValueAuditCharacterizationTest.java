package com.example.venue.storedvalue;

import com.example.venue.common.CurrentUser;
import com.example.venue.storedvalue.domain.StoredValueCommands;
import com.example.venue.storedvalue.dto.StoredValueRequests;
import com.example.venue.storedvalue.dto.StoredValueResponses;
import com.example.venue.storedvalue.provider.StoredValueProviderRouter;
import com.example.venue.storedvalue.service.StoredValueAccountService;
import com.example.venue.storedvalue.service.StoredValueApiService;
import com.example.venue.storedvalue.service.StoredValueConfigurationService;
import com.example.venue.storedvalue.service.StoredValueConfigService;
import com.example.venue.storedvalue.service.StoredValueEligibilityService;
import com.example.venue.storedvalue.service.StoredValueFinanceQueryService;
import com.example.venue.storedvalue.service.StoredValueOrderPaymentService;
import com.example.venue.storedvalue.service.StoredValueRechargePersistenceService;
import com.example.venue.storedvalue.service.StoredValueRechargeResult;
import com.example.venue.storedvalue.service.StoredValueRechargeService;
import com.example.venue.storedvalue.service.StoredValueReconciliationService;
import com.example.venue.storedvalue.service.StoredValueUserQueryService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * S02 characterization/regression tests for the audit findings. The assertions
 * keep the trusted signing and receipt-before-validation boundaries explicit.
 */
class StoredValueAuditCharacterizationTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final StoredValueConfigService configService = mock(StoredValueConfigService.class);
    private final StoredValueEligibilityService eligibilityService = mock(StoredValueEligibilityService.class);
    private final StoredValueRechargeService rechargeService = mock(StoredValueRechargeService.class);
    private final StoredValueOrderPaymentService orderPaymentService = mock(StoredValueOrderPaymentService.class);
    private final StoredValueReconciliationService reconciliationService = mock(StoredValueReconciliationService.class);
    private final StoredValueConfigurationService configurationService = mock(StoredValueConfigurationService.class);
    private final StoredValueProviderRouter providerRouter = mock(StoredValueProviderRouter.class);
    private final StoredValueAccountService accountService = mock(StoredValueAccountService.class);
    private final StoredValueUserQueryService userQueryService = mock(StoredValueUserQueryService.class);
    private final StoredValueFinanceQueryService financeQueryService = mock(StoredValueFinanceQueryService.class);

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void devPageCallbackDelegatesSigningToTrustedRechargeService() {
        authenticate(7L);
        StoredValueApiService service = new StoredValueApiService(jdbc, configService, eligibilityService,
            rechargeService, orderPaymentService, reconciliationService, configurationService, providerRouter,
            accountService, userQueryService, financeQueryService);
        StoredValueResponses.RechargeResponse recharge = rechargeResponse();
        StoredValueRequests.DevCallbackRequest request = new StoredValueRequests.DevCallbackRequest();
        request.status = "SUCCESS";
        when(jdbc.query(any(String.class), any(RowMapper.class), eq("SVR-1"), eq(7L)))
            .thenReturn(List.of(recharge));
        when(rechargeService.confirmDevCallback(7L, "SVR-1", "SUCCESS")).thenReturn(new StoredValueRechargeResult(
            21L, "SVR-1", "MANUAL_REVIEW", new BigDecimal("100.00"), new BigDecimal("5.00"),
            BigDecimal.ZERO.setScale(2), "stored-value.dev", "DEV", "DEV_STORED_VALUE",
            "RECHARGE:SVR-1", null, "INVALID_DEV_ENVELOPE"));

        service.confirmDevCallback("SVR-1", request);

        verify(rechargeService).confirmDevCallback(7L, "SVR-1", "SUCCESS");
    }

    @Test
    void malformedCallbackLeavesReceiptBeforeValidationRejection() {
        StoredValueRechargePersistenceService persistence = mock(StoredValueRechargePersistenceService.class);
        StoredValueRechargeService service = new StoredValueRechargeService(configService, providerRouter, persistence);
        StoredValueCommands.Callback malformed = new StoredValueCommands.Callback(
            "stored-value.dev", "DEV", "DEV_STORED_VALUE", "DEV_V1", null, null, null,
            "DEV_STORED_VALUE", null, null, null, null, "malformed callback");

        assertThatThrownBy(() -> service.handleCallback(malformed))
            .isInstanceOf(RuntimeException.class);

        var receipt = org.mockito.ArgumentCaptor.forClass(StoredValueCommands.Callback.class);
        var calls = inOrder(persistence);
        calls.verify(persistence).recordCallbackEvidence(receipt.capture(), isNull(), any(String.class));
        calls.verify(persistence).markCallbackRejected(eq(receipt.getValue().requestId()),
            eq("INVALID_CALLBACK_ENVELOPE"));
        assertThat(receipt.getValue().requestId()).startsWith("SVRCV-");
        assertThat(receipt.getValue().providerKey()).isEqualTo("stored-value.dev");
    }

    private StoredValueResponses.RechargeResponse rechargeResponse() {
        return new StoredValueResponses.RechargeResponse(
            21L, "SVR-1", 11L, "WAITING_PROVIDER", new BigDecimal("100.00"),
            new BigDecimal("5.00"), BigDecimal.ZERO.setScale(2), "CNY", "stored-value.dev", "DEV",
            "DEV_STORED_VALUE", "RECHARGE:SVR-1", null, null, null, null);
    }

    private void authenticate(Long userId) {
        CurrentUser user = new CurrentUser(userId, "user", Set.of("USER"), Set.of("stored-value:me"));
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
            user, null, List.of(new SimpleGrantedAuthority("stored-value:me"))));
    }
}
