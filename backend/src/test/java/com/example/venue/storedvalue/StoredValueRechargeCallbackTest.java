package com.example.venue.storedvalue;

import com.example.venue.storedvalue.domain.StoredValueCommands;
import com.example.venue.storedvalue.domain.StoredValueMoney;
import com.example.venue.common.BusinessException;
import com.example.venue.storedvalue.provider.StoredValueProviderDescriptor;
import com.example.venue.storedvalue.provider.StoredValueProviderVerification;
import com.example.venue.storedvalue.provider.StoredValueRechargeProvider;
import com.example.venue.storedvalue.provider.StoredValueProviderRouter;
import com.example.venue.storedvalue.service.StoredValueConfigService;
import com.example.venue.storedvalue.service.StoredValueRechargePersistenceService;
import com.example.venue.storedvalue.service.StoredValueRechargeResult;
import com.example.venue.storedvalue.service.StoredValueRechargeService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StoredValueRechargeCallbackTest {
    private final StoredValueConfigService configService = mock(StoredValueConfigService.class);
    private final StoredValueProviderRouter providerRouter = mock(StoredValueProviderRouter.class);
    private final StoredValueRechargePersistenceService persistence = mock(StoredValueRechargePersistenceService.class);
    private final StoredValueRechargeProvider provider = mock(StoredValueRechargeProvider.class);

    @Test
    void devConfirmationSignsWithAttemptProviderSnapshotBeforeHandlingCallback() {
        StoredValueProviderDescriptor descriptor = new StoredValueProviderDescriptor(
            "stored-value.dev", "DEV", "DEV_STORED_VALUE", "DEV_V1", true);
        StoredValueRechargePersistenceService.ProviderSnapshot snapshot =
            new StoredValueRechargePersistenceService.ProviderSnapshot(
                11L, "stored-value.dev", "DEV", "DEV_STORED_VALUE", "DEV_V1");
        StoredValueRechargeResult pending = new StoredValueRechargeResult(
            21L, "SVR-1", "WAITING_PROVIDER", new BigDecimal("100.00"),
            new BigDecimal("5.00"), BigDecimal.ZERO.setScale(2),
            "stored-value.dev", "DEV", "DEV_STORED_VALUE", "RECHARGE:SVR-1", null, null);
        when(persistence.findProviderSnapshot("SVR-1")).thenReturn(snapshot);
        when(persistence.findResultForUser("SVR-1", 7L)).thenReturn(pending);
        when(persistence.findResult("SVR-1")).thenReturn(pending);
        when(providerRouter.require("stored-value.dev", 11L)).thenReturn(provider);
        when(provider.descriptor()).thenReturn(descriptor);
        when(provider.signCallback(any())).thenAnswer(invocation ->
            com.example.venue.storedvalue.provider.DevStoredValueProvider.signatureDigest(
                invocation.getArgument(0)));
        when(provider.verify(any())).thenReturn(new StoredValueProviderVerification(
            true, "SUCCESS", null, new BigDecimal("100.00"), StoredValueMoney.CNY,
            "DEV-SVR-1", "signature", "verified"));
        when(persistence.recordCallbackEvidence(any(), isNull(), anyString())).thenReturn(31L);
        when(persistence.recordCallbackOutcome(any(), anyString(), any(), eq(descriptor))).thenReturn(pending);

        StoredValueRechargeService service = new StoredValueRechargeService(
            configService, providerRouter, persistence);

        StoredValueRechargeResult result = service.confirmDevCallback(7L, "SVR-1", "SUCCESS");

        assertThat(result).isSameAs(pending);
        var unsigned = org.mockito.ArgumentCaptor.forClass(StoredValueCommands.Callback.class);
        verify(provider).signCallback(unsigned.capture());
        var handled = org.mockito.ArgumentCaptor.forClass(StoredValueCommands.Callback.class);
        verify(persistence).recordCallbackOutcome(handled.capture(), anyString(), any(), eq(descriptor));
        assertThat(handled.getValue().providerKey()).isEqualTo("stored-value.dev");
        assertThat(handled.getValue().configVersion()).isEqualTo("DEV_V1");
        assertThat(handled.getValue().signatureDigest())
            .isEqualTo(com.example.venue.storedvalue.provider.DevStoredValueProvider.signatureDigest(unsigned.getValue()));
        assertThat(handled.getValue().requestId()).startsWith("SVRCV-");
    }

    @Test
    void devConfirmationRejectsRechargeOutsideAuthenticatedUserScope() {
        when(persistence.findResultForUser("SVR-1", 7L))
            .thenThrow(BusinessException.notFound("充值记录不存在"));

        StoredValueRechargeService service = new StoredValueRechargeService(
            configService, providerRouter, persistence);

        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> service.confirmDevCallback(7L, "SVR-1", "SUCCESS"))
            .isInstanceOf(BusinessException.class)
            .hasMessage("充值记录不存在");
        verify(providerRouter, never()).require(anyString(), org.mockito.ArgumentMatchers.anyLong());
        verify(persistence, never()).recordCallbackEvidence(any(), isNull(), anyString());
    }
}
