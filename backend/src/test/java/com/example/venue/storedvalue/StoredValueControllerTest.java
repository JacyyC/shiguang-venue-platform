package com.example.venue.storedvalue;

import com.example.venue.common.PageQuery;
import com.example.venue.common.Result;
import com.example.venue.storedvalue.controller.StoredValueAdminController;
import com.example.venue.storedvalue.controller.StoredValueController;
import com.example.venue.storedvalue.dto.StoredValueRequests;
import com.example.venue.storedvalue.dto.StoredValueResponses;
import com.example.venue.storedvalue.service.StoredValueApiService;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StoredValueControllerTest {
    @Test
    void userControllerUsesStoredValueRouteAndOwnDataPermission() throws Exception {
        assertThat(StoredValueController.class.getAnnotation(RequestMapping.class).value())
            .containsExactly("/api/stored-value");
        assertPermission("self", new Class<?>[]{}, "stored-value:me");
        assertPermission("recharges", new Class<?>[]{PageQuery.class}, "stored-value:me");
        assertPermission("createRecharge", new Class<?>[]{StoredValueRequests.CreateRechargeRequest.class},
            "stored-value:me");
        assertPermission("paymentOption", new Class<?>[]{Long.class}, "stored-value:me");
        assertPermission("payOrder", new Class<?>[]{Long.class, StoredValueRequests.OrderPaymentRequest.class},
            "stored-value:me");
    }

    @Test
    void financeReadAndHighRiskEndpointsUseSeparateLeafPermissions() throws Exception {
        assertAdminPermission("summary", new Class<?>[]{}, "stored-value:finance:view");
        assertAdminPermission("accounts", new Class<?>[]{PageQuery.class}, "stored-value:finance:view");
        assertAdminPermission("recharges", new Class<?>[]{PageQuery.class}, "stored-value:finance:view");
        assertAdminPermission("orderStoredFacts", new Class<?>[]{String.class}, "stored-value:finance:view");
        assertAdminPermission("runReconciliation", new Class<?>[]{StoredValueRequests.ReconciliationRequest.class},
            "stored-value:reconciliation:run");
        assertAdminPermission("recoverPosting", new Class<?>[]{String.class, StoredValueRequests.PostingRecoveryRequest.class},
            "stored-value:recharge:execute");
        assertAdminPermission("refund", new Class<?>[]{String.class, StoredValueRequests.RechargeRefundRequest.class},
            "stored-value:refund:manage");
        assertAdminPermission("dispute", new Class<?>[]{String.class, StoredValueRequests.DisputeRequest.class},
            "stored-value:dispute:manage");
        assertAdminPermission("freeze", new Class<?>[]{String.class, StoredValueRequests.AccountActionRequest.class},
            "stored-value:account:freeze");
        assertAdminPermission("beginClosing", new Class<?>[]{String.class, StoredValueRequests.AccountActionRequest.class},
            "stored-value:account:close");
        assertAdminPermission("unfreeze", new Class<?>[]{String.class, StoredValueRequests.ApprovalReasonRequest.class},
            "stored-value:account:unfreeze");
        assertAdminPermission("close", new Class<?>[]{String.class, StoredValueRequests.AccountActionRequest.class},
            "stored-value:account:close");
        assertAdminPermission("requestAdjustment",
            new Class<?>[]{String.class, StoredValueRequests.AdjustmentApprovalRequest.class},
            "stored-value:adjustment:execute");
        assertAdminAnyPermission("approve",
            new Class<?>[]{String.class, StoredValueRequests.ApprovalReasonRequest.class},
            "stored-value:recharge:review", "stored-value:refund:review",
            "stored-value:account:unfreeze:review", "stored-value:adjustment:review");
        assertAdminAnyPermission("approvals", new Class<?>[]{PageQuery.class},
            "stored-value:recharge:review", "stored-value:recharge:execute",
            "stored-value:refund:review", "stored-value:refund:manage",
            "stored-value:account:unfreeze:review", "stored-value:account:unfreeze",
            "stored-value:adjustment:review", "stored-value:adjustment:execute");
        assertAdminAnyPermission("execute", new Class<?>[]{String.class},
            "stored-value:recharge:execute", "stored-value:refund:manage",
            "stored-value:account:unfreeze", "stored-value:adjustment:execute");
    }

    @Test
    void userPaymentEndpointDelegatesToApiService() {
        StoredValueApiService service = mock(StoredValueApiService.class);
        StoredValueController controller = new StoredValueController(service);
        StoredValueRequests.OrderPaymentRequest request = new StoredValueRequests.OrderPaymentRequest();
        StoredValueResponses.OrderPaymentResponse expected = new StoredValueResponses.OrderPaymentResponse(
            1L, "SVOP-1", 2L, "SUCCEEDED", new java.math.BigDecimal("10.00"),
            new java.math.BigDecimal("10.00"), java.math.BigDecimal.ZERO.setScale(2), false);
        when(service.payOrder(2L, request)).thenReturn(expected);

        Result<StoredValueResponses.OrderPaymentResponse> result = controller.payOrder(2L, request);

        assertThat(result.getData()).isSameAs(expected);
        verify(service).payOrder(2L, request);
    }

    @Test
    void orderStoredFactsEndpointDelegatesToApiService() {
        StoredValueApiService service = mock(StoredValueApiService.class);
        StoredValueAdminController controller = new StoredValueAdminController(service, null);
        StoredValueResponses.OrderStoredFactsResponse expected = new StoredValueResponses.OrderStoredFactsResponse(
            "O-2026-1",
            new StoredValueResponses.OrderStoredFactPaymentResponse("...ENT-1", "SUCCEEDED",
                new java.math.BigDecimal("100.00"), new java.math.BigDecimal("80.00"),
                new java.math.BigDecimal("20.00")),
            null);
        when(service.orderStoredFacts("O-2026-1")).thenReturn(expected);

        Result<StoredValueResponses.OrderStoredFactsResponse> result = controller.orderStoredFacts("O-2026-1");

        assertThat(result.getData()).isSameAs(expected);
        verify(service).orderStoredFacts("O-2026-1");
    }

    private void assertPermission(String methodName, Class<?>[] parameterTypes, String permission)
        throws Exception {
        Method method = StoredValueController.class.getMethod(methodName, parameterTypes);
        assertThat(method.getAnnotation(PreAuthorize.class).value())
            .isEqualTo("hasAuthority('" + permission + "')");
    }

    private void assertAdminPermission(String methodName, Class<?>[] parameterTypes, String permission)
        throws Exception {
        Method method = StoredValueAdminController.class.getMethod(methodName, parameterTypes);
        assertThat(method.getAnnotation(PreAuthorize.class).value())
            .isEqualTo("hasAuthority('" + permission + "')");
    }

    private void assertAdminAnyPermission(String methodName, Class<?>[] parameterTypes, String... permissions)
        throws Exception {
        Method method = StoredValueAdminController.class.getMethod(methodName, parameterTypes);
        String expected = "hasAnyAuthority('" + String.join("','", permissions) + "')";
        assertThat(method.getAnnotation(PreAuthorize.class).value()).isEqualTo(expected);
    }
}
