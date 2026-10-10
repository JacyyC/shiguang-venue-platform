package com.example.venue.payment.dto;

import jakarta.validation.constraints.NotNull;

public class DevPaymentSimulationRequest {
    @NotNull
    public DevPaymentScenario scenario;
}
