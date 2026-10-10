package com.example.venue.storedvalue.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;

public final class StoredValueRequests {
    private StoredValueRequests() {
    }

    public static class CreateRechargeRequest {
        @NotNull
        public Long productId;

        @NotNull
        @DecimalMin(value = "0.01")
        @Digits(integer = 16, fraction = 2)
        public BigDecimal principalAmount;

        @NotBlank
        @Size(max = 3)
        public String currency = "CNY";

        @NotBlank
        @Size(max = 128)
        public String clientRequestId;

        @Size(max = 128)
        public String providerKey;
    }

    public static class DevCallbackRequest {
        @NotBlank
        public String status;
    }

    public static class OrderPaymentRequest {
        @NotNull
        public Long productId;

        @NotBlank
        @Size(max = 192)
        public String idempotencyKey;
    }

    public static class CreateProductRequest {
        @NotBlank
        @Size(max = 64)
        public String productCode;

        @NotBlank
        @Size(max = 128)
        public String productName;
    }

    public static class ProductFlagsRequest {
        public Boolean rechargeEnabled;
        public Boolean spendEnabled;

        @Size(max = 16)
        public String status;
    }

    public static class AccountActionRequest {
        @NotBlank
        @Size(max = 512)
        public String reason;

        @NotBlank
        @Size(max = 128)
        public String businessRef;

    }

    public static class RechargeRefundRequest {
        @NotBlank
        @Size(max = 512)
        public String reason;
    }

    public static class ApprovalReasonRequest {
        @NotBlank
        @Size(max = 512)
        public String reason;
    }

    public static class AdjustmentApprovalRequest {
        @NotNull
        @Digits(integer = 16, fraction = 2)
        public BigDecimal amount;

        @NotBlank
        @Size(max = 512)
        public String reason;
    }

    public static class PostingRecoveryRequest {
        @NotBlank
        @Size(max = 512)
        public String reason;
    }

    public static class IdentityVerificationRequest {
        @NotBlank
        @Size(max = 16)
        public String environment = "DEV";

        @NotBlank
        @Size(min = 64, max = 64)
        public String evidenceDigest;

        public LocalDate expiresOn;
    }

    public static class ReconciliationRequest {
        @NotNull
        public LocalDate reconcileDate;
    }

    public static class DisputeRequest {
        @NotBlank
        @Size(max = 128)
        public String providerCaseId;

        @NotNull
        @Digits(integer = 16, fraction = 2)
        public BigDecimal amount;

        @NotBlank
        @Size(max = 512)
        public String reason;
    }
}
