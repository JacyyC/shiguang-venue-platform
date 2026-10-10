package com.example.venue.payment.dto;

import com.example.venue.payment.provider.PaymentProviderDescriptor;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

public class PaymentAdminOverviewResponse {
    public static final String DEV_PROVIDER_DATA_GAP =
        "当前支付数据来自 DEV PaymentProvider，不代表真实生产支付网关成功率或渠道成本。";

    public BigDecimal successAmount;
    public BigDecimal feeAmount;
    public BigDecimal netAmount;
    public long successCount;
    public long failedCount;
    public long canceledCount;
    public long timeoutCount;
    public List<FailureReasonMetric> failureReasons = new ArrayList<>();
    public List<String> dataGaps = new ArrayList<>();

    public static PaymentAdminOverviewResponse empty() {
        return empty(null);
    }

    public static PaymentAdminOverviewResponse empty(PaymentProviderDescriptor descriptor) {
        PaymentAdminOverviewResponse response = new PaymentAdminOverviewResponse();
        response.successAmount = BigDecimal.ZERO;
        response.feeAmount = BigDecimal.ZERO;
        response.netAmount = BigDecimal.ZERO;
        response.dataGaps.add(dataGap(descriptor));
        return response;
    }

    private static String dataGap(PaymentProviderDescriptor descriptor) {
        if (descriptor == null || "payment.dev".equals(descriptor.providerKey())) {
            return DEV_PROVIDER_DATA_GAP;
        }
        return "当前支付数据来自 " + descriptor.displayName() + " (" + descriptor.providerEnv() + "/"
            + descriptor.providerName() + ")，仅代表该 Provider 当前配置产生的证据。";
    }
}
