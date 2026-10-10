package com.example.venue.storedvalue;

import com.example.venue.common.CurrentUser;
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
import com.example.venue.storedvalue.service.StoredValueReconciliationService;
import com.example.venue.storedvalue.service.StoredValueRechargeResult;
import com.example.venue.storedvalue.service.StoredValueRechargeService;
import com.example.venue.storedvalue.service.StoredValueUserQueryService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StoredValueApiServiceTest {
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
    void createRechargeUsesAuthenticatedUserAndReturnsOnlyTheOwnedResult() {
        authenticate(7L);
        StoredValueApiService service = newService();
        StoredValueRequests.CreateRechargeRequest request = new StoredValueRequests.CreateRechargeRequest();
        request.productId = 11L;
        request.principalAmount = new BigDecimal("100.00");
        request.clientRequestId = "client-1";
        StoredValueRechargeResult created = new StoredValueRechargeResult(21L, "SVR-1", "WAITING_PROVIDER",
            new BigDecimal("100.00"), new BigDecimal("5.00"), BigDecimal.ZERO.setScale(2),
            "stored-value.dev", "DEV", "DEV_STORED_VALUE", "RECHARGE:SVR-1", null, null);
        StoredValueResponses.RechargeResponse response = new StoredValueResponses.RechargeResponse(
            21L, "SVR-1", 11L, "WAITING_PROVIDER", new BigDecimal("100.00"),
            new BigDecimal("5.00"), BigDecimal.ZERO.setScale(2), "CNY", "stored-value.dev", "DEV",
            "DEV_STORED_VALUE", "RECHARGE:SVR-1", null, null, null, null);
        when(rechargeService.create(any())).thenReturn(created);
        when(userQueryService.rechargeForUser("SVR-1", 7L)).thenReturn(response);

        StoredValueResponses.RechargeResponse actual = service.createRecharge(request);

        assertThat(actual).isSameAs(response);
        verify(rechargeService).create(any());
        verify(userQueryService).rechargeForUser("SVR-1", 7L);
    }

    @Test
    void generatedVerificationTimeCannotRoundIntoTheFutureInSecondPrecisionStorage() {
        var request = new StoredValueRequests.IdentityVerificationRequest();
        request.environment = "DEV";
        request.evidenceDigest = "a".repeat(64);
        var time = org.mockito.ArgumentCaptor.forClass(java.time.LocalDateTime.class);
        newService().verifyIdentity(7L, request, 1L);
        verify(eligibilityService).recordDevVerification(org.mockito.ArgumentMatchers.eq(7L),
            org.mockito.ArgumentMatchers.eq("DEV"), org.mockito.ArgumentMatchers.eq(request.evidenceDigest),
            time.capture(), org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.eq(1L));
        assertThat(time.getValue().getNano()).isZero();
        assertThat(time.getValue()).isBeforeOrEqualTo(java.time.LocalDateTime.now());
    }

    private StoredValueApiService newService() {
        return new StoredValueApiService(jdbc, configService, eligibilityService, rechargeService,
            orderPaymentService, reconciliationService, configurationService, providerRouter, accountService,
            userQueryService, financeQueryService);
    }

    private void authenticate(Long userId) {
        CurrentUser user = new CurrentUser(userId, "user", Set.of("USER"), Set.of("stored-value:me"));
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
            user, null, List.of(new SimpleGrantedAuthority("stored-value:me"))));
    }
}
