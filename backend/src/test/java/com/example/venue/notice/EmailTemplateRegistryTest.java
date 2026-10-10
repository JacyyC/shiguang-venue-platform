package com.example.venue.notice.email;

import com.example.venue.common.BusinessException;
import com.example.venue.notice.routing.EmailRouteDefinition;
import com.example.venue.notice.routing.NotificationRecipientKind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EmailTemplateRegistryTest {
    private final EmailTemplateRegistry registry = new EmailTemplateRegistry();

    @ParameterizedTest
    @MethodSource("emailRoutes")
    void eachApprovedEventHasExactlyOneMatchingRecipientAndTemplate(
        String eventType, NotificationRecipientKind kind, String templateKey
    ) {
        assertThat(registry.routeFor(eventType, kind))
            .extracting(EmailRouteDefinition::templateKey)
            .isEqualTo(templateKey);
        NotificationRecipientKind oppositeKind = kind == NotificationRecipientKind.USER
            ? NotificationRecipientKind.VENUE_ADMIN
            : NotificationRecipientKind.USER;
        assertThat(registry.routeFor(eventType, oppositeKind)).isNull();

        EmailTemplate template = registry.require(eventType, kind);
        assertThat(template.templateKey()).isEqualTo(templateKey);
        assertThat(template.templateVersion()).isEqualTo("v1");
        assertThat(template.locale()).isEqualTo("zh-CN");
        assertThat(template.contentType()).isEqualTo("text/plain");
        assertThat(template.maxRenderedSubjectLength()).isEqualTo(200);
        assertThat(template.maxRenderedBodyLength()).isEqualTo(10_000);
        assertThat(template.allowedModelKeys()).containsAll(template.requiredModelKeys());
        assertThatThrownBy(() -> template.allowedModelKeys().add("unexpected"))
            .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void unlistedEventAndWrongRecipientKindHaveNoEmailRoute() {
        assertThat(registry.routeFor("NOTICE_PUBLISHED", NotificationRecipientKind.USER)).isNull();
        assertThat(registry.routeFor("RESERVATION_APPROVED", NotificationRecipientKind.VENUE_ADMIN)).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"NOTICE_PUBLISHED", "COUPON_GRANTED", "EXPERIMENT_ASSIGNED", "MARKETING_SENT"})
    void excludedBusinessFamiliesHaveNoEmailRoute(String eventType) {
        assertThat(registry.routeFor(eventType, NotificationRecipientKind.USER)).isNull();
        assertThat(registry.routeFor(eventType, NotificationRecipientKind.VENUE_ADMIN)).isNull();
    }

    @Test
    void rendererRejectsUnknownAndMissingModelFields() {
        EmailTemplate template = registry.require("RESERVATION_APPROVED", NotificationRecipientKind.USER);

        assertThatThrownBy(() -> registry.render(template, Map.of("reservationId", "42", "email", "raw@example.com")))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("NOTICE_TEMPLATE_MODEL_INVALID");
        assertThatThrownBy(() -> registry.render(template, Map.of()))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("NOTICE_TEMPLATE_MODEL_INVALID");
    }

    @Test
    void rendererRejectsNullModelKeysWithTheContractError() {
        EmailTemplate template = registry.require("RESERVATION_APPROVED", NotificationRecipientKind.USER);
        Map<String, String> model = new HashMap<>();
        model.put(null, "42");

        assertThatThrownBy(() -> registry.render(template, model))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("NOTICE_TEMPLATE_MODEL_INVALID");
    }

    @Test
    void rendererRejectsHeaderInjectionAndOverlengthBody() {
        EmailTemplate reservation = registry.require("RESERVATION_APPROVED", NotificationRecipientKind.USER);
        EmailTemplate reply = registry.require("CUSTOMER_SERVICE_REPLY_ADDED", NotificationRecipientKind.USER);

        assertThatThrownBy(() -> registry.render(reservation, Map.of("reservationId", "42\r\nBcc:test@example.com")))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("EMAIL_HEADER_INVALID");
        assertThatThrownBy(() -> registry.render(reply, Map.of("caseId", "21", "reply", "x".repeat(10_001))))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("NOTICE_TEMPLATE_MODEL_INVALID");
    }

    @Test
    void rendererProducesBoundPlainTextOnly() {
        EmailTemplate template = registry.require("REFUND_REVIEW_REJECTED", NotificationRecipientKind.USER);

        RenderedEmail rendered = registry.render(template, Map.of("refundNo", "RF-42", "reason", "材料不完整"));

        assertThat(rendered.subject()).contains("RF-42").doesNotContain("{{");
        assertThat(rendered.body()).contains("RF-42", "材料不完整").doesNotContain("{{");
        assertThat(rendered.contentType()).isEqualTo("text/plain");
        assertThat(rendered.locale()).isEqualTo("zh-CN");
        assertThat(rendered.toString()).doesNotContain("RF-42", "材料不完整");
    }

    @ParameterizedTest
    @ValueSource(strings = {"USER", "ADMIN"})
    void reservationCanceledTemplateRequiresAndRendersServerDerivedSource(String source) {
        EmailTemplate template = registry.require("RESERVATION_CANCELED", NotificationRecipientKind.USER);

        assertThat(template.allowedModelKeys()).containsExactlyInAnyOrder("reservationId", "status", "source");
        assertThat(template.requiredModelKeys()).containsExactlyInAnyOrder("reservationId", "status", "source");
        RenderedEmail rendered = registry.render(template, Map.of(
            "reservationId", "42", "status", "CANCELED", "source", source));

        assertThat(rendered.body()).contains("42", "CANCELED", source);
        assertThat(rendered.contentType()).isEqualTo("text/plain");
    }

    @Test
    void registryRejectsDuplicateTemplateIdentityAndUnboundPlaceholders() {
        EmailTemplate first = template("EVENT_ONE", "duplicate.key", "编号 {{id}}", Set.of("id"));
        EmailTemplate second = template("EVENT_TWO", "duplicate.key", "编号 {{id}}", Set.of("id"));

        assertThatThrownBy(() -> new EmailTemplateRegistry(
            List.of(first, second),
            List.of(
                new EmailRouteDefinition("EVENT_ONE", NotificationRecipientKind.USER, "duplicate.key", "v1"),
                new EmailRouteDefinition("EVENT_TWO", NotificationRecipientKind.USER, "duplicate.key", "v1"))))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("NOTICE_TEMPLATE_MODEL_INVALID");

        EmailTemplate unbound = template("EVENT_ONE", "unbound.key", "编号 {{missing}}", Set.of("id"));
        assertThatThrownBy(() -> new EmailTemplateRegistry(
            List.of(unbound),
            List.of(new EmailRouteDefinition(
                "EVENT_ONE", NotificationRecipientKind.USER, "unbound.key", "v1"))))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("NOTICE_TEMPLATE_MODEL_INVALID");
    }

    @Test
    void registryRejectsPlaceholderAndRequiredKeyMismatch() {
        EmailTemplate optionalPlaceholder = new EmailTemplate(
            "EVENT_ONE", "optional.key", "v1", "编号 {{id}}", "正文 {{id}}",
            Set.of("id"), Set.of(), 200, 10_000, "zh-CN", "text/plain");

        assertThatThrownBy(() -> new EmailTemplateRegistry(
            List.of(optionalPlaceholder),
            List.of(new EmailRouteDefinition(
                "EVENT_ONE", NotificationRecipientKind.USER, "optional.key", "v1"))))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("NOTICE_TEMPLATE_MODEL_INVALID");
    }

    @Test
    void templateConstructionRejectsNullModelKeySets() {
        assertThatThrownBy(() -> new EmailTemplate(
            "EVENT_ONE", "null.allowed", "v1", "编号 {{id}}", "正文 {{id}}",
            null, Set.of("id"), 200, 10_000, "zh-CN", "text/plain"))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("allowedModelKeys");

        assertThatThrownBy(() -> new EmailTemplate(
            "EVENT_ONE", "null.required", "v1", "编号 {{id}}", "正文 {{id}}",
            Set.of("id"), null, 200, 10_000, "zh-CN", "text/plain"))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("requiredModelKeys");
    }

    private EmailTemplate template(String eventType, String templateKey, String subject, Set<String> keys) {
        return new EmailTemplate(
            eventType, templateKey, "v1", subject, "正文 {{id}}", keys, keys,
            200, 10_000, "zh-CN", "text/plain");
    }

    private static Stream<Arguments> emailRoutes() {
        return Stream.of(
            Arguments.of("RESERVATION_APPROVED", NotificationRecipientKind.USER, "reservation.approved"),
            Arguments.of("RESERVATION_REJECTED", NotificationRecipientKind.USER, "reservation.rejected"),
            Arguments.of("RESERVATION_CANCELED", NotificationRecipientKind.USER, "reservation.canceled"),
            Arguments.of("PAYMENT_SUCCEEDED", NotificationRecipientKind.USER, "payment.succeeded"),
            Arguments.of("PAYMENT_TIMEOUT", NotificationRecipientKind.USER, "payment.timeout"),
            Arguments.of("REFUND_REVIEW_APPROVED", NotificationRecipientKind.USER, "refund.review-approved"),
            Arguments.of("REFUND_REVIEW_REJECTED", NotificationRecipientKind.USER, "refund.review-rejected"),
            Arguments.of("REFUND_SUCCEEDED", NotificationRecipientKind.USER, "refund.succeeded"),
            Arguments.of("CUSTOMER_SERVICE_REPLY_ADDED", NotificationRecipientKind.USER, "customer-service.reply-added"),
            Arguments.of("CUSTOMER_SERVICE_CASE_RESOLVED", NotificationRecipientKind.USER, "customer-service.resolved"),
            Arguments.of("RESERVATION_REVIEW_REQUIRED", NotificationRecipientKind.VENUE_ADMIN, "admin.reservation-review-required"),
            Arguments.of("REFUND_REVIEW_REQUIRED", NotificationRecipientKind.VENUE_ADMIN, "admin.refund-review-required"),
            Arguments.of("CUSTOMER_SERVICE_CASE_REVIEW_REQUIRED", NotificationRecipientKind.VENUE_ADMIN, "admin.customer-service-case-review-required")
        );
    }
}
