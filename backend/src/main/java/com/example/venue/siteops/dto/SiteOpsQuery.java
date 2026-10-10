package com.example.venue.siteops.dto;

import com.example.venue.common.PageQuery;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDate;

public class SiteOpsQuery extends PageQuery {
    public Long venueId;
    public Long spaceId;
    public Long shiftId;
    public Long assetId;
    public String status;

    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    public LocalDate date;

    public Long getVenueId() {
        return venueId;
    }

    public void setVenueId(Long venueId) {
        this.venueId = venueId;
    }

    public Long getSpaceId() {
        return spaceId;
    }

    public void setSpaceId(Long spaceId) {
        this.spaceId = spaceId;
    }

    public Long getShiftId() {
        return shiftId;
    }

    public void setShiftId(Long shiftId) {
        this.shiftId = shiftId;
    }

    public Long getAssetId() {
        return assetId;
    }

    public void setAssetId(Long assetId) {
        this.assetId = assetId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public LocalDate getDate() {
        return date;
    }

    public void setDate(LocalDate date) {
        this.date = date;
    }
}
