package com.example.venue.statistics.dto;

import java.math.BigDecimal;

public class ChartPoint {
    public String label;
    public BigDecimal value;

    public ChartPoint() {
    }

    public ChartPoint(String label, BigDecimal value) {
        this.label = label;
        this.value = value;
    }
}
