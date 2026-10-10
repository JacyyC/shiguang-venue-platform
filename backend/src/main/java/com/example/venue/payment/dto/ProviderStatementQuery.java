package com.example.venue.payment.dto;

import java.time.LocalDate;
import java.util.List;

public class ProviderStatementQuery {
    public LocalDate statementDate;
    public String providerEnv;
    public String providerName;
    public List<ProviderStatementItem> expectedItems;

    public ProviderStatementQuery() {
    }

    public ProviderStatementQuery(LocalDate statementDate, String providerEnv, String providerName,
                                  List<ProviderStatementItem> expectedItems) {
        this.statementDate = statementDate;
        this.providerEnv = providerEnv;
        this.providerName = providerName;
        this.expectedItems = expectedItems;
    }
}
