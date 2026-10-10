package com.example.venue.notice;

import com.example.venue.common.BusinessException;
import com.example.venue.notice.entity.NoticeMessage;
import com.example.venue.notice.entity.NoticeEmailDelivery;
import com.example.venue.notice.entity.NoticeOutbox;
import com.example.venue.notice.email.EmailSnapshot;
import com.example.venue.notice.email.EmailSnapshotService;
import com.example.venue.notice.email.EmailRecipientEligibility;
import com.example.venue.notice.email.EmailTemplateRegistry;
import com.example.venue.notice.email.RenderedEmail;
import com.example.venue.notice.mapper.NoticeEmailDeliveryMapper;
import com.example.venue.notice.mapper.NoticeMessageMapper;
import com.example.venue.notice.provider.NotificationDeliveryCommand;
import com.example.venue.notice.provider.NotificationProviderDescriptor;
import com.example.venue.notice.routing.NotificationRecipientKind;
import com.example.venue.notice.service.NotificationOutboxCommandFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NotificationOutboxCommandFactoryTest {
    private NoticeMessageMapper messageMapper;
    private NoticeEmailDeliveryMapper emailDeliveryMapper;
    private EmailSnapshotService emailSnapshotService;
    private NotificationOutboxCommandFactory factory;
    private NotificationProviderDescriptor descriptor;

    @BeforeEach
    void setUp() {
        messageMapper = mock(NoticeMessageMapper.class);
        emailDeliveryMapper = mock(NoticeEmailDeliveryMapper.class);
        emailSnapshotService = new EmailSnapshotService(emailDeliveryMapper);
        factory = new NotificationOutboxCommandFactory(
            messageMapper, emailSnapshotService, new ObjectMapper());
        descriptor = new NotificationProviderDescriptor(
            "notice.in_app.dev", "MESSAGE", "DEV", "IN_APP_MESSAGE",
            "IN_APP", "站内消息", "IN_APP_MESSAGE_V1", true);
    }

    @Test
    void directMessageUsesPersistedAudienceAndSanitizedContent() {
        NoticeOutbox outbox = directOutbox(7L);
        NoticeMessage message = message(41L, 7L);
        when(messageMapper.selectById(41L)).thenReturn(message);

        NotificationDeliveryCommand command = factory.create(outbox, descriptor);

        assertThat(command.userId()).isEqualTo(7L);
        assertThat(command.messageId()).isEqualTo(41L);
        assertThat(command.title()).isEqualTo("数据库标题");
        assertThat(command.content()).isEqualTo("数据库正文");
    }

    @Test
    void missingDirectMessageIsRejectedInsteadOfCreatingReplacement() {
        NoticeOutbox outbox = directOutbox(7L);
        when(messageMapper.selectById(41L)).thenReturn(null);

        assertThatThrownBy(() -> factory.create(outbox, descriptor))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("NOTICE_OUTBOX_PAYLOAD_INVALID");
    }

    @Test
    void directMessageAudienceMismatchIsRejected() {
        NoticeOutbox outbox = directOutbox(8L);
        when(messageMapper.selectById(41L)).thenReturn(message(41L, 7L));

        assertThatThrownBy(() -> factory.create(outbox, descriptor))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("NOTICE_OUTBOX_PAYLOAD_INVALID");
    }

    @Test
    void legacyCampaignPayloadMapsToInAppCommandWithoutMetadataProviderKey() {
        NoticeOutbox outbox = new NoticeOutbox();
        outbox.id = 92L;
        outbox.batchId = 31L;
        outbox.audienceId = 51L;
        outbox.noticeId = 71L;
        outbox.userId = 7L;
        outbox.providerEnv = "DEV";
        outbox.providerName = "IN_APP_MESSAGE";
        outbox.idempotencyKey = "NOTICE:31:7:IN_APP_MESSAGE";
        outbox.requestSummaryJson = "{\"noticeId\":71,\"audienceId\":51,\"userId\":7,"
            + "\"title\":\"公告标题\",\"content\":\"公告正文\"}";

        assertThat(factory.providerKey(outbox)).isEqualTo("notice.in_app.dev");
        NotificationDeliveryCommand command = factory.create(outbox, descriptor);

        assertThat(command.businessId()).isEqualTo(71L);
        assertThat(command.businessType()).isEqualTo("NOTICE");
        assertThat(command.requestSummaryJson()).contains("\"batchId\":31", "\"audienceId\":51");
    }

    @Test
    void emailCommandUsesOnlySafeImmutableSnapshotMetadata() {
        NoticeOutbox outbox = emailOutbox();
        NoticeEmailDelivery delivery = emailDelivery();
        when(emailDeliveryMapper.findByOutboxId(91L)).thenReturn(delivery);
        NotificationProviderDescriptor emailDescriptor = emailDescriptor();

        NotificationDeliveryCommand command = factory.create(outbox, emailDescriptor);

        assertThat(command.outboxId()).isEqualTo(91L);
        assertThat(command.userId()).isEqualTo(7L);
        assertThat(command.emailSnapshotId()).isEqualTo(101L);
        assertThat(command.maskedRecipient()).isEqualTo("a***e@e***e.test");
        assertThat(command.templateKey()).isEqualTo("reservation.approved");
        assertThat(command.templateVersion()).isEqualTo("v1");
        assertThat(command.messageIdHeader()).isEqualTo(canonicalMessageId());
        assertThat(command.eventType()).isEqualTo("RESERVATION_APPROVED");
        assertThat(command.title()).isNull();
        assertThat(command.content()).isNull();
        assertThat(command.requestSummaryJson()).isNull();
        assertThat(command.toString())
            .contains("outboxId=91", "providerKey=notice.smtp.sandbox", "emailSnapshotId=101")
            .doesNotContain("alice@example.test", "snapshot subject", "snapshot body",
            canonicalMessageId());
    }

    @Test
    void staleEmailRecoveryRebuildsTheSameSnapshotIdentityAndSeventyCharacterKey() {
        NoticeOutbox outbox = emailOutbox();
        outbox.status = "SENDING";
        outbox.attemptCount = 1;
        when(emailDeliveryMapper.findByOutboxId(91L)).thenReturn(emailDelivery());

        NotificationDeliveryCommand first = factory.create(outbox, emailDescriptor());
        NotificationDeliveryCommand recovered = factory.create(outbox, emailDescriptor());

        assertThat(recovered.emailSnapshotId()).isEqualTo(first.emailSnapshotId());
        assertThat(recovered.messageIdHeader()).isEqualTo(first.messageIdHeader());
        assertThat(recovered.idempotencyKey()).isEqualTo(first.idempotencyKey()).hasSize(70);
        assertThat(recovered.idempotencyKey()).startsWith("EMAIL:");
    }

    @Test
    void emailCommandRejectsSnapshotTupleMismatchWithTheClosedError() {
        NoticeOutbox outbox = emailOutbox();
        NoticeEmailDelivery delivery = emailDelivery();
        delivery.recipientUserId = 8L;
        when(emailDeliveryMapper.findByOutboxId(91L)).thenReturn(delivery);

        assertThatThrownBy(() -> factory.create(outbox, emailDescriptor()))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("EMAIL_SNAPSHOT_INVALID")
            .hasMessageNotContaining("alice@example.test")
            .hasMessageNotContaining("snapshot subject")
            .hasMessageNotContaining("snapshot body");
    }

    @Test
    void rawAddressCanOnlyBeLoadedThroughTheMatchingSmtpSnapshotBoundary() {
        NoticeEmailDelivery delivery = emailDelivery();
        when(emailDeliveryMapper.findByOutboxId(91L)).thenReturn(delivery);

        EmailSnapshot snapshot = emailSnapshotService.loadForSmtp(91L, 101L);

        assertThat(snapshot.recipientAddress()).isEqualTo("alice@example.test");
        assertThat(snapshot.toString())
            .contains("snapshotId=101", "maskedRecipient=a***e@e***e.test")
            .doesNotContain("alice@example.test", "snapshot subject", "snapshot body");
        assertThatThrownBy(() -> emailSnapshotService.loadForSmtp(91L, 999L))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("EMAIL_SNAPSHOT_INVALID")
            .hasMessageNotContaining("alice@example.test");
    }

    @Test
    void snapshotCreateAndLoadRejectSyntacticallyValidNonSandboxRecipients() {
        EmailRecipientEligibility recipient = new EmailRecipientEligibility(
            7L, NotificationRecipientKind.USER, true, true,
            "alice@example.com", "a***e@e***e.com", true, null);
        var template = new EmailTemplateRegistry().require(
            "RESERVATION_APPROVED", NotificationRecipientKind.USER);

        assertThatThrownBy(() -> emailSnapshotService.create(
            91L, 7L, recipient, template,
            new RenderedEmail("snapshot subject", "snapshot body", "text/plain", "zh-CN"),
            canonicalMessageId()))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("EMAIL_ADDRESS_INVALID")
            .hasMessageNotContaining("alice@example.com");
        verify(emailDeliveryMapper, never()).insert(any(NoticeEmailDelivery.class));

        NoticeEmailDelivery legacy = emailDelivery();
        legacy.recipientAddress = "alice@example.com";
        when(emailDeliveryMapper.findByOutboxId(91L)).thenReturn(legacy);

        assertThatThrownBy(() -> emailSnapshotService.loadForSmtp(91L, 101L))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("EMAIL_ADDRESS_INVALID")
            .hasMessageNotContaining("alice@example.com");
    }

    private NoticeOutbox directOutbox(Long userId) {
        NoticeOutbox outbox = new NoticeOutbox();
        outbox.id = 91L;
        outbox.userId = userId;
        outbox.idempotencyKey = "NOTICE_MESSAGE:41";
        outbox.requestSummaryJson = "{\"messageId\":41,\"userId\":" + userId
            + ",\"title\":\"请求标题\",\"content\":\"请求正文\"}";
        return outbox;
    }

    private NoticeMessage message(Long id, Long userId) {
        NoticeMessage message = new NoticeMessage();
        message.id = id;
        message.userId = userId;
        message.businessId = 99L;
        message.type = "RESERVATION";
        message.title = "数据库标题";
        message.content = "数据库正文";
        return message;
    }

    private NoticeOutbox emailOutbox() {
        NoticeOutbox outbox = new NoticeOutbox();
        outbox.id = 91L;
        outbox.userId = 7L;
        outbox.eventType = "RESERVATION_APPROVED";
        outbox.eventKey = "RESERVATION_APPROVED:42";
        outbox.recipientKind = "USER";
        outbox.channel = "EMAIL";
        outbox.providerEnv = "SANDBOX";
        outbox.providerName = "SMTP_EMAIL";
        outbox.providerKey = "notice.smtp.sandbox";
        outbox.configVersion = "SMTP_SANDBOX_V1";
        outbox.templateKey = "reservation.approved";
        outbox.templateVersion = "v1";
        outbox.idempotencyKey = "EMAIL:" + "a".repeat(64);
        outbox.requestSummaryJson = "{\"title\":\"untrusted title\",\"content\":\"untrusted body\"}";
        outbox.metadataJson = "{\"providerKey\":\"notice.smtp.sandbox\",\"source\":\"BUSINESS_NOTIFICATION\"}";
        return outbox;
    }

    private NoticeEmailDelivery emailDelivery() {
        NoticeEmailDelivery delivery = new NoticeEmailDelivery();
        delivery.id = 101L;
        delivery.outboxId = 91L;
        delivery.recipientUserId = 7L;
        delivery.recipientAddress = "alice@example.test";
        delivery.recipientMasked = "a***e@e***e.test";
        delivery.templateKey = "reservation.approved";
        delivery.templateVersion = "v1";
        delivery.subjectSnapshot = "snapshot subject";
        delivery.bodyTextSnapshot = "snapshot body";
        delivery.messageIdHeader = canonicalMessageId();
        delivery.acceptanceStatus = "PENDING";
        delivery.downstreamStatus = "NOT_OBSERVED";
        return delivery;
    }

    private NotificationProviderDescriptor emailDescriptor() {
        return new NotificationProviderDescriptor(
            "notice.smtp.sandbox", "MESSAGE", "SANDBOX", "SMTP_EMAIL",
            "EMAIL", "SMTP 邮件沙箱", "SMTP_SANDBOX_V1", true);
    }

    private String canonicalMessageId() {
        return "<" + "b".repeat(64) + "@venue.local>";
    }
}
