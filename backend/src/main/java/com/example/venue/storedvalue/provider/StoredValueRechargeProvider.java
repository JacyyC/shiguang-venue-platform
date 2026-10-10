package com.example.venue.storedvalue.provider;

import com.example.venue.storedvalue.domain.StoredValueCommands;

public interface StoredValueRechargeProvider {
    StoredValueProviderDescriptor descriptor();

    StoredValueProviderCreateResult create(StoredValueProviderCreateRequest request);

    StoredValueProviderVerification verify(StoredValueCommands.Callback callback);

    default String signCallback(StoredValueCommands.Callback callback) {
        throw new UnsupportedOperationException("provider does not support simulated callback signing");
    }

    StoredValueProviderRefundResult refund(StoredValueProviderRefundRequest request);
}
