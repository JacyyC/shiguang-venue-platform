package com.example.venue.notice.provider;

import com.example.venue.common.BusinessException;
import com.example.venue.notice.email.EmailSnapshot;
import com.example.venue.notice.email.EmailSnapshotService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.mail.Message;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Properties;

@Component
public class SmtpSandboxNotificationProvider implements NotificationProvider {
    private static final String PROVIDER_KEY = "notice.smtp.sandbox";
    private static final String CONFIG_VERSION = "SMTP_SANDBOX_V1";

    private final SmtpSandboxProperties properties;
    private final EmailSnapshotService snapshotService;
    private final SmtpFailureClassifier failureClassifier;
    private final ObjectMapper objectMapper;
    private final JavaMailSender mailSender;

    @Autowired
    public SmtpSandboxNotificationProvider(
        SmtpSandboxProperties properties,
        EmailSnapshotService snapshotService,
        SmtpFailureClassifier failureClassifier,
        ObjectMapper objectMapper
    ) {
        this(properties, snapshotService, failureClassifier, objectMapper, createMailSender(properties));
    }

    public SmtpSandboxNotificationProvider(
        SmtpSandboxProperties properties,
        EmailSnapshotService snapshotService,
        SmtpFailureClassifier failureClassifier,
        ObjectMapper objectMapper,
        JavaMailSender mailSender
    ) {
        this.properties = properties;
        this.snapshotService = snapshotService;
        this.failureClassifier = failureClassifier;
        this.objectMapper = objectMapper;
        this.mailSender = mailSender;
    }

    @Override
    public NotificationProviderDescriptor descriptor() {
        return new NotificationProviderDescriptor(
            PROVIDER_KEY, "MESSAGE", "SANDBOX", "SMTP_EMAIL", "EMAIL",
            "SMTP 邮件沙箱", CONFIG_VERSION, properties.isEnabled());
    }

    @Override
    public NotificationDeliveryResult send(NotificationDeliveryCommand command) {
        try {
            validateCommand(command);
            EmailSnapshot snapshot = snapshotService.loadForSmtp(
                command.outboxId(), command.emailSnapshotId());
            validateSnapshot(command, snapshot);

            MimeMessage message = mailSender.createMimeMessage();
            InternetAddress from = new InternetAddress(properties.getFromAddress(), true);
            from.setPersonal(properties.getFromName(), StandardCharsets.UTF_8.name());
            message.setFrom(from);
            message.setRecipient(
                Message.RecipientType.TO, new InternetAddress(snapshot.recipientAddress(), true));
            message.setSubject(snapshot.subjectSnapshot(), StandardCharsets.UTF_8.name());
            message.setHeader("Message-ID", snapshot.messageIdHeader());
            message.setHeader("X-Notification-Event", command.eventType());
            message.setHeader("X-Template-Key", command.templateKey());
            message.setHeader("X-Template-Version", command.templateVersion());
            message.setText(snapshot.bodyTextSnapshot(), StandardCharsets.UTF_8.name());
            mailSender.send(message);

            ObjectNode response = objectMapper.createObjectNode();
            response.put("status", NotificationDeliveryResult.ACCEPTED);
            response.put("maskedRecipient", command.maskedRecipient());
            response.put("templateKey", command.templateKey());
            response.put("templateVersion", command.templateVersion());
            response.put("messageIdHash", sha256(snapshot.messageIdHeader()));
            return new NotificationDeliveryResult(
                NotificationDeliveryResult.ACCEPTED, snapshot.messageIdHeader(), false,
                null, null, response.toString());
        } catch (Exception failure) {
            SmtpFailureClassifier.SmtpFailure classified = failureClassifier.classify(failure);
            ObjectNode response = objectMapper.createObjectNode();
            response.put("status", classified.providerStatus());
            response.put("failureCode", classified.failureCode());
            return new NotificationDeliveryResult(
                classified.providerStatus(), null, classified.retryable(), classified.failureCode(),
                classified.failureMessage(), response.toString());
        }
    }

    private void validateCommand(NotificationDeliveryCommand command) {
        if (command == null || command.provider() == null
            || !PROVIDER_KEY.equals(command.provider().providerKey())
            || !CONFIG_VERSION.equals(command.provider().configVersion())
            || !"EMAIL".equals(command.provider().channel())
            || command.outboxId() == null || command.emailSnapshotId() == null
            || command.userId() == null || isBlank(command.maskedRecipient())
            || isBlank(command.templateKey()) || isBlank(command.templateVersion())
            || command.messageIdHeader() == null
            || command.eventType() == null
            || !command.eventType().matches("[A-Z][A-Z0-9_]{0,63}")
            || containsHeaderBreak(command.templateKey())
            || containsHeaderBreak(command.templateVersion())) {
            throw BusinessException.badRequest(
                "EMAIL_SNAPSHOT_INVALID: email delivery command is invalid");
        }
    }

    private void validateSnapshot(NotificationDeliveryCommand command, EmailSnapshot snapshot) {
        if (snapshot == null || !command.emailSnapshotId().equals(snapshot.snapshotId())
            || !command.maskedRecipient().equals(snapshot.maskedRecipient())
            || !command.templateKey().equals(snapshot.templateKey())
            || !command.templateVersion().equals(snapshot.templateVersion())
            || !command.messageIdHeader().equals(snapshot.messageIdHeader())) {
            throw BusinessException.badRequest(
                "EMAIL_SNAPSHOT_INVALID: email delivery snapshot does not match command");
        }
    }

    private boolean containsHeaderBreak(String value) {
        return value == null || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static JavaMailSender createMailSender(SmtpSandboxProperties properties) {
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setProtocol("smtp");
        sender.setHost(properties.getHost());
        sender.setPort(properties.getPort());
        sender.setUsername(properties.getUsername());
        sender.setPassword(properties.getPassword());
        sender.setDefaultEncoding(StandardCharsets.UTF_8.name());
        Properties mailProperties = sender.getJavaMailProperties();
        mailProperties.setProperty("mail.smtp.auth", Boolean.toString(properties.isAuth()));
        mailProperties.setProperty(
            "mail.smtp.starttls.enable", Boolean.toString(properties.isStarttls()));
        mailProperties.setProperty(
            "mail.smtp.connectiontimeout", Integer.toString(properties.getConnectTimeoutMs()));
        mailProperties.setProperty(
            "mail.smtp.timeout", Integer.toString(properties.getReadTimeoutMs()));
        mailProperties.setProperty(
            "mail.smtp.writetimeout", Integer.toString(properties.getWriteTimeoutMs()));
        mailProperties.setProperty("mail.debug", "false");
        return sender;
    }
}
