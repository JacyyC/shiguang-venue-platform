package com.example.venue.provider.dto;

import java.math.BigDecimal;

public class VenueObservationLocationRequest {
    public String areaCode;
    public BigDecimal latitude;
    public BigDecimal longitude;
    public String locationLabel;
    public String timezone;
    public BigDecimal confidence;
    public String metadataJson;
}
