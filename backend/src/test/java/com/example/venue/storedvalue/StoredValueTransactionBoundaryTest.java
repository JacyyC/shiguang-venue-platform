package com.example.venue.storedvalue;

import com.example.venue.storedvalue.service.StoredValueRechargePersistenceService;
import com.example.venue.storedvalue.service.StoredValueRechargeService;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

class StoredValueTransactionBoundaryTest {
    @Test
    void providerCreateCallbackAndRefundOrchestrationAreOutsideTransactions() throws Exception {
        for (Method method : StoredValueRechargeService.class.getDeclaredMethods()) {
            if (method.getName().equals("create") || method.getName().equals("handleCallback")
                || method.getName().equals("refund")) {
                assertThat(method.getAnnotation(Transactional.class)).isNull();
            }
        }
        assertThat(StoredValueRechargePersistenceService.class.getMethod("prepare",
            com.example.venue.storedvalue.domain.StoredValueCommands.CreateRecharge.class,
            com.example.venue.storedvalue.provider.StoredValueProviderDescriptor.class,
            java.time.LocalDateTime.class).getAnnotation(Transactional.class)).isNotNull();
        assertThat(StoredValueRechargePersistenceService.class.getMethod("recordCallbackEvidence",
            com.example.venue.storedvalue.domain.StoredValueCommands.Callback.class, Long.class, String.class)
            .getAnnotation(Transactional.class)).isNotNull();
        assertThat(StoredValueRechargePersistenceService.class.getMethod("claimPosting",
            String.class, String.class, Long.class, String.class, boolean.class)
            .getAnnotation(Transactional.class)).isNotNull();
        assertThat(StoredValueRechargePersistenceService.class.getMethod("completePosting",
            StoredValueRechargePersistenceService.PostingClaim.class, String.class)
            .getAnnotation(Transactional.class)).isNotNull();
    }
}
