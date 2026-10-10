package com.example.venue.notice.email;

import com.example.venue.common.BusinessException;
import com.example.venue.notice.entity.NoticeEmailDelivery;
import com.example.venue.notice.mapper.NoticeEmailDeliveryMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Objects;

@Service
public class EmailSnapshotService {
    private final NoticeEmailDeliveryMapper deliveryMapper;
    private final EmailAddressValidator addressValidator = new EmailAddressValidator();

    public EmailSnapshotService(NoticeEmailDeliveryMapper deliveryMapper) {
        this.deliveryMapper = Objects.requireNonNull(deliveryMapper, "deliveryMapper");
    }

    public NoticeEmailDelivery create(
        Long outboxId,
        Long recipientUserId,
        EmailRecipientEligibility recipient,
        EmailTemplate template,
        RenderedEmail rendered,
        String messageIdHeader
    ) {
        requireCreateArguments(
            outboxId, recipientUserId, recipient, template, rendered, messageIdHeader);
        LocalDateTime now = LocalDateTime.now();
        NoticeEmailDelivery delivery = new NoticeEmailDelivery();
        delivery.outboxId = outboxId;
        delivery.recipientUserId = recipientUserId;
        delivery.recipientAddress = recipient.normalizedAddress();
        delivery.recipientMasked = recipient.maskedAddress();
        delivery.templateKey = template.templateKey();
        delivery.templateVersion = template.templateVersion();
        delivery.subjectSnapshot = rendered.subject();
        delivery.bodyTextSnapshot = rendered.body();
        delivery.messageIdHeader = messageIdHeader;
        delivery.acceptanceStatus = "PENDING";
        delivery.downstreamStatus = "NOT_OBSERVED";
        delivery.createTime = now;
        delivery.updateTime = now;
        if (deliveryMapper.insert(delivery) != 1) {
            throw snapshotInvalid();
        }
        return delivery;
    }

    public EmailSnapshot loadForSmtp(Long outboxId, Long snapshotId) {
        NoticeEmailDelivery delivery = requireMatchingSnapshot(outboxId, snapshotId);
        validatePrivateSnapshot(delivery);
        return new EmailSnapshot(
            delivery.id, delivery.recipientAddress, delivery.recipientMasked,
            delivery.subjectSnapshot, delivery.bodyTextSnapshot, delivery.messageIdHeader,
            delivery.templateKey, delivery.templateVersion);
    }

    public CommandSnapshot loadForCommand(Long outboxId) {
        NoticeEmailDelivery delivery = requireMatchingSnapshot(outboxId, null);
        validatePrivateSnapshot(delivery);
        return new CommandSnapshot(
            delivery.id, delivery.outboxId, delivery.recipientUserId, delivery.recipientMasked,
            delivery.templateKey, delivery.templateVersion, delivery.messageIdHeader);
    }

    private NoticeEmailDelivery requireMatchingSnapshot(Long outboxId, Long snapshotId) {
        if (outboxId == null || outboxId <= 0 || (snapshotId != null && snapshotId <= 0)) {
            throw snapshotInvalid();
        }
        NoticeEmailDelivery delivery = deliveryMapper.findByOutboxId(outboxId);
        if (delivery == null || delivery.id == null || !outboxId.equals(delivery.outboxId)
            || (snapshotId != null && !snapshotId.equals(delivery.id))) {
            throw snapshotInvalid();
        }
        return delivery;
    }

    private void requireCreateArguments(
        Long outboxId,
        Long recipientUserId,
        EmailRecipientEligibility recipient,
        EmailTemplate template,
        RenderedEmail rendered,
        String messageIdHeader
    ) {
        if (outboxId == null || outboxId <= 0 || recipientUserId == null || recipientUserId <= 0
            || recipient == null || !recipient.emailEligible()
            || !recipientUserId.equals(recipient.userId())
            || isBlank(recipient.maskedAddress()) || template == null || rendered == null
            || isBlank(template.templateKey()) || isBlank(template.templateVersion())
            || isBlank(rendered.subject()) || rendered.body() == null
            || !"text/plain".equals(rendered.contentType())
            || !"zh-CN".equals(rendered.locale())) {
            throw snapshotInvalid();
        }
        validateAddress(recipient.normalizedAddress());
        validateHeader(rendered.subject());
        validateMessageId(messageIdHeader);
    }

    private void validatePrivateSnapshot(NoticeEmailDelivery delivery) {
        if (delivery.recipientUserId == null || delivery.recipientUserId <= 0
            || isBlank(delivery.recipientMasked) || isBlank(delivery.templateKey)
            || isBlank(delivery.templateVersion) || isBlank(delivery.subjectSnapshot)
            || delivery.bodyTextSnapshot == null) {
            throw snapshotInvalid();
        }
        validateAddress(delivery.recipientAddress);
        validateHeader(delivery.subjectSnapshot);
        validateMessageId(delivery.messageIdHeader);
    }

    private void validateAddress(String address) {
        if (!Objects.equals(address, addressValidator.normalizeSandboxRecipient(address))) {
            throw BusinessException.badRequest("EMAIL_ADDRESS_INVALID: email snapshot address is invalid");
        }
    }

    private void validateHeader(String value) {
        if (value == null || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
            throw BusinessException.badRequest("EMAIL_HEADER_INVALID: email snapshot header is invalid");
        }
    }

    private void validateMessageId(String messageIdHeader) {
        if (messageIdHeader == null
            || !messageIdHeader.matches("<[0-9a-f]{64}@venue\\.local>")) {
            throw BusinessException.badRequest("EMAIL_HEADER_INVALID: message id header is invalid");
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private BusinessException snapshotInvalid() {
        return BusinessException.badRequest(
            "EMAIL_SNAPSHOT_INVALID: email delivery snapshot is invalid");
    }

    public record CommandSnapshot(
        Long snapshotId,
        Long outboxId,
        Long recipientUserId,
        String maskedRecipient,
        String templateKey,
        String templateVersion,
        String messageIdHeader
    ) {
    }
}
