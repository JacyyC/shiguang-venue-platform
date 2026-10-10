package com.example.venue.notice.routing;

import com.example.venue.common.BusinessException;
import com.example.venue.notice.email.EmailRecipientEligibility;
import com.example.venue.notice.email.EmailTemplate;
import com.example.venue.notice.email.EmailTemplateRegistry;
import com.example.venue.notice.email.EmailTemplateRenderer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;
import java.util.Set;

@Component
public class DefaultNotificationRoutingPolicy implements NotificationRoutingPolicy {
    private static final NotificationRoute IN_APP_ROUTE = new NotificationRoute(
        "IN_APP", "notice.in_app.dev", "DEV", "IN_APP_MESSAGE", "IN_APP_MESSAGE_V1");
    private static final NotificationRoute SMTP_SANDBOX_ROUTE = new NotificationRoute(
        "EMAIL", "notice.smtp.sandbox", "SANDBOX", "SMTP_EMAIL", "SMTP_SANDBOX_V1");
    private static final Set<String> SAFE_RECIPIENT_EXCLUSION_CODES = Set.of(
        "RECIPIENT_INVALID",
        "RECIPIENT_NOT_FOUND",
        "RECIPIENT_INACTIVE",
        "EMAIL_PREFERENCE_DISABLED",
        "EMAIL_ADDRESS_INVALID",
        "LEGACY_IN_APP_ONLY",
        "NO_ELIGIBLE_VENUE_ADMIN");
    private static final Set<String> SAFE_TEMPLATE_EXCLUSION_CODES = Set.of(
        "NOTICE_TEMPLATE_MODEL_INVALID", "EMAIL_HEADER_INVALID");

    private final boolean smtpEnabled;
    private final EmailTemplateRegistry templateRegistry;
    private final EmailTemplateRenderer templateRenderer;

    public DefaultNotificationRoutingPolicy() {
        this(false, new EmailTemplateRegistry());
    }

    private DefaultNotificationRoutingPolicy(boolean smtpEnabled, EmailTemplateRegistry registry) {
        this(smtpEnabled, registry, registry);
    }

    @Autowired
    public DefaultNotificationRoutingPolicy(
        @Value("${app.notice.smtp.enabled:false}") boolean smtpEnabled,
        EmailTemplateRegistry templateRegistry,
        EmailTemplateRenderer templateRenderer
    ) {
        this.smtpEnabled = smtpEnabled;
        this.templateRegistry = Objects.requireNonNull(templateRegistry, "templateRegistry");
        this.templateRenderer = Objects.requireNonNull(templateRenderer, "templateRenderer");
    }

    @Override
    public NotificationRoutingDecision decide(
        NotificationEvent event, EmailRecipientEligibility recipient
    ) {
        if (!smtpEnabled) {
            return inAppOnly("SMTP_DISABLED");
        }
        if (event == null || event.eventType() == null) {
            return inAppOnly("EMAIL_EVENT_INVALID");
        }
        if (event.eventKey() == null || event.eventKey().isBlank()
            || event.eventKey().length() > 128
            || event.recipientUserId() == null || event.recipientUserId() <= 0) {
            return inAppOnly("EMAIL_EVENT_INVALID");
        }

        EmailRouteDefinition definition = templateRegistry.routeFor(event.eventType(), event.recipientKind());
        if (definition == null) {
            return inAppOnly(hasEventDefinition(event.eventType())
                ? "NOTICE_ROUTE_RECIPIENT_MISMATCH"
                : "EMAIL_EVENT_NOT_WHITELISTED");
        }
        if (!recipientMatches(event, recipient)) {
            if (recipient != null && !recipient.emailEligible()) {
                return inAppOnly(safeRecipientExclusionCode(recipient.exclusionCode()));
            }
            return inAppOnly("NOTICE_ROUTE_RECIPIENT_MISMATCH");
        }

        try {
            EmailTemplate template = templateRegistry.require(event.eventType(), event.recipientKind());
            templateRenderer.render(template, event.templateModel());
        } catch (BusinessException ex) {
            return inAppOnly(exclusionCode(ex));
        }
        return new NotificationRoutingDecision(List.of(IN_APP_ROUTE, SMTP_SANDBOX_ROUTE), null);
    }

    private boolean hasEventDefinition(String eventType) {
        return templateRegistry.routeFor(eventType, NotificationRecipientKind.USER) != null
            || templateRegistry.routeFor(eventType, NotificationRecipientKind.VENUE_ADMIN) != null;
    }

    private boolean recipientMatches(NotificationEvent event, EmailRecipientEligibility recipient) {
        return recipient != null
            && recipient.emailEligible()
            && Objects.equals(event.recipientUserId(), recipient.userId())
            && event.recipientKind() == recipient.recipientKind();
    }

    private String exclusionCode(BusinessException exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            return "NOTICE_TEMPLATE_MODEL_INVALID";
        }
        int separator = message.indexOf(':');
        String candidate = separator < 0 ? message : message.substring(0, separator);
        return SAFE_TEMPLATE_EXCLUSION_CODES.contains(candidate)
            ? candidate
            : "NOTICE_TEMPLATE_MODEL_INVALID";
    }

    private String safeRecipientExclusionCode(String candidate) {
        return candidate != null && SAFE_RECIPIENT_EXCLUSION_CODES.contains(candidate)
            ? candidate
            : "EMAIL_RECIPIENT_INELIGIBLE";
    }

    private NotificationRoutingDecision inAppOnly(String exclusionCode) {
        return new NotificationRoutingDecision(List.of(IN_APP_ROUTE), exclusionCode);
    }
}
