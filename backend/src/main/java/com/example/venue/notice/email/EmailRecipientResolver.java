package com.example.venue.notice.email;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.example.venue.notice.entity.NoticeEmailPreference;
import com.example.venue.notice.mapper.NoticeEmailPreferenceMapper;
import com.example.venue.notice.routing.NotificationRecipientKind;
import com.example.venue.user.entity.SysUser;
import com.example.venue.user.mapper.SysUserMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Objects;

public interface EmailRecipientResolver {
    EmailRecipientEligibility resolve(Long userId, NotificationRecipientKind recipientKind);

    @Component
    final class Database implements EmailRecipientResolver {
        private final SysUserMapper userMapper;
        private final NoticeEmailPreferenceMapper preferenceMapper;
        private final EmailAddressMasker addressMasker;
        private final EmailAddressValidator addressValidator;

        public Database(
            SysUserMapper userMapper,
            NoticeEmailPreferenceMapper preferenceMapper,
            EmailAddressMasker addressMasker
        ) {
            this(userMapper, preferenceMapper, addressMasker, new EmailAddressValidator());
        }

        @Autowired
        public Database(
            SysUserMapper userMapper,
            NoticeEmailPreferenceMapper preferenceMapper,
            EmailAddressMasker addressMasker,
            EmailAddressValidator addressValidator
        ) {
            this.userMapper = Objects.requireNonNull(userMapper, "userMapper");
            this.preferenceMapper = Objects.requireNonNull(preferenceMapper, "preferenceMapper");
            this.addressMasker = Objects.requireNonNull(addressMasker, "addressMasker");
            this.addressValidator = Objects.requireNonNull(addressValidator, "addressValidator");
        }

        @Override
        public EmailRecipientEligibility resolve(
            Long userId, NotificationRecipientKind recipientKind
        ) {
            if (userId == null || userId <= 0 || recipientKind == null) {
                return ineligible(userId, recipientKind, false, false, "RECIPIENT_INVALID");
            }

            SysUser user = userMapper.selectById(userId);
            if (user == null) {
                return ineligible(userId, recipientKind, false, false, "RECIPIENT_NOT_FOUND");
            }
            boolean active = Integer.valueOf(0).equals(user.deleted) && "ACTIVE".equals(user.status);
            if (!active) {
                return ineligible(userId, recipientKind, false, false, "RECIPIENT_INACTIVE");
            }

            NoticeEmailPreference preference = preferenceMapper.selectOne(
                new QueryWrapper<NoticeEmailPreference>()
                    .eq("user_id", userId)
                    .last("limit 1"));
            boolean preferenceEnabled = preference != null
                && Integer.valueOf(1).equals(preference.transactionalEmailEnabled);
            if (!preferenceEnabled) {
                return ineligible(
                    userId, recipientKind, true, false, "EMAIL_PREFERENCE_DISABLED");
            }

            String normalizedAddress = addressValidator.normalizeSandboxRecipient(user.email);
            if (normalizedAddress == null) {
                return ineligible(
                    userId, recipientKind, true, true, "EMAIL_ADDRESS_INVALID");
            }
            return new EmailRecipientEligibility(
                userId, recipientKind, true, true, normalizedAddress,
                addressMasker.mask(normalizedAddress), true, null);
        }

        private EmailRecipientEligibility ineligible(
            Long userId,
            NotificationRecipientKind recipientKind,
            boolean active,
            boolean preferenceEnabled,
            String exclusionCode
        ) {
            return new EmailRecipientEligibility(
                userId, recipientKind, active, false, null, null, preferenceEnabled, exclusionCode);
        }
    }
}
