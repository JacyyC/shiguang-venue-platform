package com.example.venue.storedvalue.provider;

public interface StoredValueStatementProvider {
    StoredValueProviderDescriptor descriptor();

    StoredValueStatement statement(StoredValueStatementQuery query);
}
