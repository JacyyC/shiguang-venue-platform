package com.example.venue.advertising.service;

import com.example.venue.advertising.dto.AdvertisingDecisionRequests;
import com.example.venue.advertising.dto.AdvertisingDecisionResponses;
import com.example.venue.advertising.entity.AdvertisingPrivacyPreference;
import com.example.venue.advertising.mapper.AdvertisingPrivacyPreferenceMapper;
import com.example.venue.common.BusinessException;
import com.example.venue.common.ResultCode;
import com.example.venue.security.SecurityContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Objects;

@Service
public class AdvertisingPrivacyPreferenceService {
    public static final String POLICY_VERSION = "G-R7-20260803-v1";

    private final AdvertisingPrivacyPreferenceMapper preferenceMapper;

    public AdvertisingPrivacyPreferenceService(AdvertisingPrivacyPreferenceMapper preferenceMapper) {
        this.preferenceMapper = Objects.requireNonNull(preferenceMapper, "preferenceMapper");
    }

    @Transactional(readOnly = true)
    public AdvertisingDecisionResponses.PrivacyPreferenceResponse current() {
        Long userId = requireCurrentUserId();
        return response(preferenceMapper.findByUserId(userId));
    }

    @Transactional
    public AdvertisingDecisionResponses.PrivacyPreferenceResponse update(
        AdvertisingDecisionRequests.UpdatePrivacyRequest request
    ) {
        if (request == null || request.commercialEnabled() == null || request.measurementEnabled() == null) {
            throw BusinessException.badRequest("commercialEnabled and measurementEnabled are required");
        }
        Long userId = requireCurrentUserId();
        LocalDateTime now = LocalDateTime.now();
        AdvertisingPrivacyPreference preference = preferenceMapper.findByUserIdForUpdate(userId);
        if (preference == null) {
            preference = new AdvertisingPrivacyPreference();
            preference.userId = userId;
            preference.createTime = now;
            preference.commercialEnabled = request.commercialEnabled() ? 1 : 0;
            preference.measurementEnabled = request.measurementEnabled() ? 1 : 0;
            preference.policyVersion = POLICY_VERSION;
            preference.changedAt = now;
            preferenceMapper.insert(preference);
        } else {
            preference.commercialEnabled = request.commercialEnabled() ? 1 : 0;
            preference.measurementEnabled = request.measurementEnabled() ? 1 : 0;
            preference.policyVersion = POLICY_VERSION;
            preference.changedAt = now;
            preferenceMapper.updateById(preference);
        }
        return response(preference);
    }

    @Transactional(readOnly = true)
    public boolean isCommercialMeasurementEnabled(Long userId) {
        if (userId == null) {
            return false;
        }
        AdvertisingPrivacyPreference preference = preferenceMapper.findByUserId(userId);
        return preference != null
            && Integer.valueOf(1).equals(preference.commercialEnabled)
            && Integer.valueOf(1).equals(preference.measurementEnabled);
    }

    @Transactional
    public boolean lockForAdvertisingFact(Long userId) {
        if (userId == null) {
            return false;
        }
        AdvertisingPrivacyPreference preference = preferenceMapper.findByUserIdForUpdate(userId);
        return preference != null
            && Integer.valueOf(1).equals(preference.commercialEnabled)
            && Integer.valueOf(1).equals(preference.measurementEnabled);
    }

    @Transactional(readOnly = true)
    public String policyVersion(Long userId) {
        if (userId == null) {
            return POLICY_VERSION;
        }
        AdvertisingPrivacyPreference preference = preferenceMapper.findByUserId(userId);
        return preference == null || preference.policyVersion == null
            ? POLICY_VERSION : preference.policyVersion;
    }

    private AdvertisingDecisionResponses.PrivacyPreferenceResponse response(
        AdvertisingPrivacyPreference preference
    ) {
        return AdvertisingDecisionResponses.PrivacyPreferenceResponse.from(preference, POLICY_VERSION);
    }

    private Long requireCurrentUserId() {
        Long userId = SecurityContext.currentUserId();
        if (userId == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED, "Authentication is required");
        }
        return userId;
    }
}
