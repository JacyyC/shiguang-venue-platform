package com.example.venue.notice;

import com.example.venue.common.BusinessException;
import com.example.venue.notice.routing.DefaultNotificationRoutingPolicy;
import com.example.venue.notice.routing.NotificationRecipientKind;
import com.example.venue.notice.routing.NotificationEvent;
import com.example.venue.notice.routing.NotificationRoute;
import com.example.venue.notice.routing.NotificationRoutingDecision;
import com.example.venue.notice.routing.NotificationRoutingPolicy;
import com.example.venue.notice.email.EmailRecipientEligibility;
import com.example.venue.notice.email.EmailTemplateRegistry;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.HashMap;

import static org.assertj.core.api.Assertions.assertThat;

class NotificationRoutingPolicyTest {
    private static final NotificationRoute IN_APP_ROUTE = new NotificationRoute(
        "IN_APP", "notice.in_app.dev", "DEV", "IN_APP_MESSAGE", "IN_APP_MESSAGE_V1");
    private static final NotificationRoute SMTP_ROUTE = new NotificationRoute(
        "EMAIL", "notice.smtp.sandbox", "SANDBOX", "SMTP_EMAIL", "SMTP_SANDBOX_V1");

    @Test
    void legacyCampaignCallSiteRemainsInAppOnly() {
        NotificationRoutingPolicy policy = new DefaultNotificationRoutingPolicy();
        NotificationEvent event = NotificationEvent.businessMessage(
            "RESERVATION_APPROVED", 7L, "预约审核通过", "请完成支付", "RESERVATION", 99L);

        assertThat(event.recipientKind()).isEqualTo(NotificationRecipientKind.USER);
        assertThat(event.eventKey()).startsWith("LEGACY_IN_APP:RESERVATION_APPROVED:");
        assertThat(policy.routesFor(event)).containsExactly(IN_APP_ROUTE);
    }

    @Test
    void legacyCallSiteRemainsInAppOnlyWhenSmtpIsEnabled() {
        NotificationEvent event = NotificationEvent.businessMessage(
            "RESERVATION_APPROVED", 7L, "预约审核通过", "请完成支付", "RESERVATION", 99L);

        assertThat(enabledPolicy().routesFor(event)).containsExactly(IN_APP_ROUTE);
    }

    @Test
    void eligibleWhitelistedEventGetsExactlyOneInAppAndOneFixedSmtpRoute() {
        NotificationRoutingPolicy policy = enabledPolicy();

        NotificationRoutingDecision decision = policy.decide(
            reservationEvent("RESERVATION_APPROVED", NotificationRecipientKind.USER, Map.of("reservationId", "42")),
            eligibleRecipient(NotificationRecipientKind.USER));

        assertThat(decision.routes()).containsExactly(IN_APP_ROUTE, SMTP_ROUTE);
        assertThat(decision.emailExclusionCode()).isNull();
    }

    @Test
    void disabledSmtpKeepsWhitelistedEventInAppOnly() {
        NotificationRoutingPolicy policy = new DefaultNotificationRoutingPolicy();

        NotificationRoutingDecision decision = policy.decide(
            reservationEvent("RESERVATION_APPROVED", NotificationRecipientKind.USER, Map.of("reservationId", "42")),
            eligibleRecipient(NotificationRecipientKind.USER));

        assertThat(decision.routes()).containsExactly(IN_APP_ROUTE);
        assertThat(decision.emailExclusionCode()).isEqualTo("SMTP_DISABLED");
    }

    @Test
    void eventTitleCannotTurnAnUnlistedEventIntoEmail() {
        NotificationRoutingDecision decision = enabledPolicy().decide(
            reservationEvent("NOTICE_PUBLISHED", NotificationRecipientKind.USER, Map.of()),
            eligibleRecipient(NotificationRecipientKind.USER));

        assertThat(decision.routes()).containsExactly(IN_APP_ROUTE);
        assertThat(decision.emailExclusionCode()).isEqualTo("EMAIL_EVENT_NOT_WHITELISTED");
    }

    @Test
    void recipientKindMismatchCannotBroadcastEmail() {
        NotificationRoutingDecision decision = enabledPolicy().decide(
            reservationEvent("RESERVATION_APPROVED", NotificationRecipientKind.VENUE_ADMIN,
                Map.of("reservationId", "42")),
            eligibleRecipient(NotificationRecipientKind.VENUE_ADMIN));

        assertThat(decision.routes()).containsExactly(IN_APP_ROUTE);
        assertThat(decision.emailExclusionCode()).isEqualTo("NOTICE_ROUTE_RECIPIENT_MISMATCH");
    }

    @Test
    void ineligibleRecipientUsesOnlyItsSafeExclusionCode() {
        EmailRecipientEligibility recipient = EmailRecipientEligibility.inAppOnly("EMAIL_ADDRESS_INVALID");

        NotificationRoutingDecision decision = enabledPolicy().decide(
            reservationEvent("RESERVATION_APPROVED", NotificationRecipientKind.USER, Map.of("reservationId", "42")),
            recipient);

        assertThat(decision.routes()).containsExactly(IN_APP_ROUTE);
        assertThat(decision.emailExclusionCode()).isEqualTo("EMAIL_ADDRESS_INVALID");
    }

    @Test
    void untrustedRecipientExclusionIsReplacedWithASafeCode() {
        EmailRecipientEligibility recipient = EmailRecipientEligibility.inAppOnly("alice@example.com");

        NotificationRoutingDecision decision = enabledPolicy().decide(
            reservationEvent("RESERVATION_APPROVED", NotificationRecipientKind.USER, Map.of("reservationId", "42")),
            recipient);

        assertThat(decision.routes()).containsExactly(IN_APP_ROUTE);
        assertThat(decision.emailExclusionCode())
            .isEqualTo("EMAIL_RECIPIENT_INELIGIBLE")
            .doesNotContain("@");
    }

    @Test
    void missingRecipientExclusionIsReplacedWithASafeCode() {
        EmailRecipientEligibility recipient = new EmailRecipientEligibility(
            7L, NotificationRecipientKind.USER, true, false,
            null, null, false, null);

        NotificationRoutingDecision decision = enabledPolicy().decide(
            reservationEvent("RESERVATION_APPROVED", NotificationRecipientKind.USER, Map.of("reservationId", "42")),
            recipient);

        assertThat(decision.routes()).containsExactly(IN_APP_ROUTE);
        assertThat(decision.emailExclusionCode()).isEqualTo("EMAIL_RECIPIENT_INELIGIBLE");
    }

    @Test
    void untrustedRendererErrorIsReplacedWithASafeCode() {
        EmailTemplateRegistry registry = new EmailTemplateRegistry();
        NotificationRoutingPolicy policy = new DefaultNotificationRoutingPolicy(
            true, registry, (template, model) -> {
                throw BusinessException.badRequest("alice@example.com");
            });

        NotificationRoutingDecision decision = policy.decide(
            reservationEvent("RESERVATION_APPROVED", NotificationRecipientKind.USER, Map.of("reservationId", "42")),
            eligibleRecipient(NotificationRecipientKind.USER));

        assertThat(decision.routes()).containsExactly(IN_APP_ROUTE);
        assertThat(decision.emailExclusionCode())
            .isEqualTo("NOTICE_TEMPLATE_MODEL_INVALID")
            .doesNotContain("@");
    }

    @Test
    void eligibilityForAnotherUserCannotCreateAnEmailRoute() {
        EmailRecipientEligibility anotherUser = new EmailRecipientEligibility(
            8L, NotificationRecipientKind.USER, true, true,
            "other@example.com", "o***r@e***e.com", true, null);

        NotificationRoutingDecision decision = enabledPolicy().decide(
            reservationEvent("RESERVATION_APPROVED", NotificationRecipientKind.USER, Map.of("reservationId", "42")),
            anotherUser);

        assertThat(decision.routes()).containsExactly(IN_APP_ROUTE);
        assertThat(decision.emailExclusionCode()).isEqualTo("NOTICE_ROUTE_RECIPIENT_MISMATCH");
    }

    @Test
    void invalidTemplateModelIsSafelyExcludedWithoutDroppingInApp() {
        NotificationRoutingDecision decision = enabledPolicy().decide(
            reservationEvent("RESERVATION_APPROVED", NotificationRecipientKind.USER, Map.of()),
            eligibleRecipient(NotificationRecipientKind.USER));

        assertThat(decision.routes()).containsExactly(IN_APP_ROUTE);
        assertThat(decision.emailExclusionCode()).isEqualTo("NOTICE_TEMPLATE_MODEL_INVALID");
    }

    @Test
    void nullTemplateValueIsSafelyExcludedWithoutDroppingInApp() {
        Map<String, String> model = new HashMap<>();
        model.put("reservationId", null);

        NotificationRoutingDecision decision = enabledPolicy().decide(
            reservationEvent("RESERVATION_APPROVED", NotificationRecipientKind.USER, model),
            eligibleRecipient(NotificationRecipientKind.USER));

        assertThat(decision.routes()).containsExactly(IN_APP_ROUTE);
        assertThat(decision.emailExclusionCode()).isEqualTo("NOTICE_TEMPLATE_MODEL_INVALID");
    }

    @Test
    void invalidEventKeyCannotCreateAnEmailRoute() {
        NotificationEvent event = new NotificationEvent(
            "RESERVATION_APPROVED", null, 7L, NotificationRecipientKind.USER,
            "预约审核通过", "请完成支付", "RESERVATION", 42L, Map.of("reservationId", "42"));

        NotificationRoutingDecision decision = enabledPolicy().decide(
            event, eligibleRecipient(NotificationRecipientKind.USER));

        assertThat(decision.routes()).containsExactly(IN_APP_ROUTE);
        assertThat(decision.emailExclusionCode()).isEqualTo("EMAIL_EVENT_INVALID");
    }

    private NotificationRoutingPolicy enabledPolicy() {
        EmailTemplateRegistry registry = new EmailTemplateRegistry();
        return new DefaultNotificationRoutingPolicy(true, registry, registry);
    }

    private NotificationEvent reservationEvent(
        String eventType, NotificationRecipientKind kind, Map<String, String> model
    ) {
        return new NotificationEvent(
            eventType, eventType + ":42", 7L, kind,
            "预约审核通过", "请完成支付", "RESERVATION", 42L, model);
    }

    private EmailRecipientEligibility eligibleRecipient(NotificationRecipientKind kind) {
        return new EmailRecipientEligibility(
            7L, kind, true, true, "alice@example.com", "a***e@e***e.com", true, null);
    }
}
