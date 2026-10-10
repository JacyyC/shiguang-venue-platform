package com.example.venue.notice.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.example.venue.common.BusinessException;
import com.example.venue.common.ResultCode;
import com.example.venue.notice.dto.TransactionalEmailPreferenceRequest;
import com.example.venue.notice.dto.TransactionalEmailPreferenceResponse;
import com.example.venue.notice.email.EmailAddressValidator;
import com.example.venue.notice.email.EmailRecipientEligibility;
import com.example.venue.notice.email.EmailRecipientResolver;
import com.example.venue.notice.entity.NoticeEmailPreference;
import com.example.venue.notice.mapper.NoticeEmailPreferenceMapper;
import com.example.venue.notice.routing.NotificationRecipientKind;
import com.example.venue.security.SecurityContext;
import com.example.venue.user.entity.SysUser;
import com.example.venue.user.mapper.SysUserMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

@Service
public class TransactionalEmailPreferenceService {
    private final SysUserMapper userMapper;
    private final NoticeEmailPreferenceMapper preferenceMapper;
    private final EmailRecipientResolver recipientResolver;
    private final EmailAddressValidator addressValidator;

    public TransactionalEmailPreferenceService(
        SysUserMapper userMapper,
        NoticeEmailPreferenceMapper preferenceMapper,
        EmailRecipientResolver recipientResolver,
        EmailAddressValidator addressValidator
    ) {
        this.userMapper = Objects.requireNonNull(userMapper, "userMapper");
        this.preferenceMapper = Objects.requireNonNull(preferenceMapper, "preferenceMapper");
        this.recipientResolver = Objects.requireNonNull(recipientResolver, "recipientResolver");
        this.addressValidator = Objects.requireNonNull(addressValidator, "addressValidator");
    }

    @Transactional(readOnly = true)
    public TransactionalEmailPreferenceResponse current() {
        Long userId = requireCurrentUserId();
        SysUser user = userMapper.selectById(userId);
        EmailRecipientEligibility eligibility = recipientResolver.resolve(
            userId, NotificationRecipientKind.USER);
        return response(user, eligibility);
    }

    @Transactional
    public TransactionalEmailPreferenceResponse update(TransactionalEmailPreferenceRequest request) {
        if (request == null || request.transactionalEmailEnabled == null) {
            throw BusinessException.badRequest("transactionalEmailEnabled is required");
        }

        Long userId = requireCurrentUserId();
        SysUser user = userMapper.selectById(userId);
        boolean enabled = request.transactionalEmailEnabled;
        if (enabled) {
            validateEnableCandidate(user);
        }

        NoticeEmailPreference preference = findPreference(userId);
        if (preference == null) {
            preference = new NoticeEmailPreference();
            preference.userId = userId;
            preference.transactionalEmailEnabled = enabled ? 1 : 0;
            preferenceMapper.insert(preference);
        } else {
            preference.transactionalEmailEnabled = enabled ? 1 : 0;
            preferenceMapper.updateById(preference);
        }
        return current();
    }

    private void validateEnableCandidate(SysUser user) {
        if (user == null) {
            throw BusinessException.badRequest("RECIPIENT_NOT_FOUND");
        }
        if (!Integer.valueOf(0).equals(user.deleted) || !"ACTIVE".equals(user.status)) {
            throw BusinessException.badRequest("RECIPIENT_INACTIVE");
        }
        if (addressValidator.normalizeStrict(user.email) == null) {
            throw BusinessException.badRequest("EMAIL_ADDRESS_INVALID");
        }
    }

    private NoticeEmailPreference findPreference(Long userId) {
        return preferenceMapper.selectOne(
            new QueryWrapper<NoticeEmailPreference>()
                .eq("user_id", userId)
                .last("limit 1"));
    }

    private TransactionalEmailPreferenceResponse response(
        SysUser user, EmailRecipientEligibility eligibility
    ) {
        TransactionalEmailPreferenceResponse response = new TransactionalEmailPreferenceResponse();
        response.transactionalEmailEnabled = eligibility.preferenceEnabled();
        response.emailConfigured = user != null && user.email != null && !user.email.trim().isEmpty();
        response.emailEligible = eligibility.emailEligible();
        response.maskedEmail = eligibility.maskedAddress();
        response.addressValidated = eligibility.addressValidated();
        response.addressVerified = false;
        response.exclusionCode = eligibility.exclusionCode();
        return response;
    }

    private Long requireCurrentUserId() {
        Long userId = SecurityContext.currentUserId();
        if (userId == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED, "Authentication is required");
        }
        return userId;
    }
}
