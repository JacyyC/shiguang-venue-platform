package com.example.venue.notice.email;

import com.example.venue.common.BusinessException;
import com.example.venue.notice.routing.EmailRouteDefinition;
import com.example.venue.notice.routing.NotificationRecipientKind;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public final class EmailTemplateRegistry implements EmailTemplateRenderer {
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{([A-Za-z][A-Za-z0-9]*)}}");
    private static final String TEMPLATE_MODEL_INVALID = "NOTICE_TEMPLATE_MODEL_INVALID";
    private static final String HEADER_INVALID = "EMAIL_HEADER_INVALID";

    private final Map<RouteKey, EmailRouteDefinition> routes;
    private final Map<TemplateIdentity, EmailTemplate> templates;

    public EmailTemplateRegistry() {
        this(defaultTemplates(), defaultRoutes());
    }

    EmailTemplateRegistry(
        List<EmailTemplate> templateDefinitions,
        List<EmailRouteDefinition> routeDefinitions
    ) {
        this.templates = indexTemplates(templateDefinitions);
        this.routes = indexRoutes(routeDefinitions);
        validateRoutes();
    }

    public EmailRouteDefinition routeFor(
        String eventType, NotificationRecipientKind recipientKind
    ) {
        if (eventType == null || recipientKind == null) {
            return null;
        }
        return routes.get(new RouteKey(eventType, recipientKind));
    }

    public EmailTemplate require(
        String eventType, NotificationRecipientKind recipientKind
    ) {
        EmailRouteDefinition route = routeFor(eventType, recipientKind);
        if (route == null) {
            throw BusinessException.badRequest(
                TEMPLATE_MODEL_INVALID + ": email template route is not defined");
        }
        EmailTemplate template = templates.get(
            new TemplateIdentity(route.templateKey(), route.templateVersion()));
        if (template == null) {
            throw BusinessException.badRequest(
                TEMPLATE_MODEL_INVALID + ": email template definition is missing");
        }
        return template;
    }

    @Override
    public RenderedEmail render(EmailTemplate template, Map<String, String> model) {
        validateTemplate(template);
        if (model == null
            || model.entrySet().stream().anyMatch(
                entry -> entry.getKey() == null || entry.getValue() == null)
            || !template.allowedModelKeys().containsAll(model.keySet())
            || !model.keySet().containsAll(template.requiredModelKeys())) {
            throw BusinessException.badRequest(
                TEMPLATE_MODEL_INVALID + ": template model fields do not match the definition");
        }

        String subject = bind(template.subjectPattern(), model);
        String body = bind(template.textBodyPattern(), model);
        if (containsHeaderBreak(subject)) {
            throw BusinessException.badRequest(HEADER_INVALID + ": rendered subject contains CR or LF");
        }
        if (subject.length() > template.maxRenderedSubjectLength()
            || body.length() > template.maxRenderedBodyLength()) {
            throw BusinessException.badRequest(
                TEMPLATE_MODEL_INVALID + ": rendered template exceeds its length limit");
        }
        return new RenderedEmail(subject, body, template.contentType(), template.locale());
    }

    private Map<TemplateIdentity, EmailTemplate> indexTemplates(List<EmailTemplate> definitions) {
        if (definitions == null) {
            throw BusinessException.badRequest(TEMPLATE_MODEL_INVALID + ": template definitions are required");
        }
        Map<TemplateIdentity, EmailTemplate> indexed = new LinkedHashMap<>();
        for (EmailTemplate template : definitions) {
            validateTemplate(template);
            TemplateIdentity identity = new TemplateIdentity(
                template.templateKey(), template.templateVersion());
            if (indexed.putIfAbsent(identity, template) != null) {
                throw BusinessException.badRequest(
                    TEMPLATE_MODEL_INVALID + ": duplicate template key and version");
            }
        }
        return Map.copyOf(indexed);
    }

    private Map<RouteKey, EmailRouteDefinition> indexRoutes(List<EmailRouteDefinition> definitions) {
        if (definitions == null) {
            throw BusinessException.badRequest(TEMPLATE_MODEL_INVALID + ": route definitions are required");
        }
        Map<RouteKey, EmailRouteDefinition> indexed = new LinkedHashMap<>();
        for (EmailRouteDefinition definition : definitions) {
            if (definition == null || definition.eventType() == null
                || definition.recipientKind() == null || definition.templateKey() == null
                || definition.templateVersion() == null) {
                throw BusinessException.badRequest(TEMPLATE_MODEL_INVALID + ": invalid route definition");
            }
            RouteKey key = new RouteKey(definition.eventType(), definition.recipientKind());
            if (indexed.putIfAbsent(key, definition) != null) {
                throw BusinessException.badRequest(
                    TEMPLATE_MODEL_INVALID + ": duplicate event and recipient route");
            }
        }
        return Map.copyOf(indexed);
    }

    private void validateRoutes() {
        for (EmailRouteDefinition route : routes.values()) {
            EmailTemplate template = templates.get(
                new TemplateIdentity(route.templateKey(), route.templateVersion()));
            if (template == null || !route.eventType().equals(template.eventType())) {
                throw BusinessException.badRequest(
                    TEMPLATE_MODEL_INVALID + ": route and template do not match");
            }
        }
    }

    private void validateTemplate(EmailTemplate template) {
        if (template == null || isBlank(template.eventType()) || isBlank(template.templateKey())
            || isBlank(template.templateVersion()) || template.subjectPattern() == null
            || template.textBodyPattern() == null || template.allowedModelKeys() == null
            || template.requiredModelKeys() == null
            || !template.allowedModelKeys().containsAll(template.requiredModelKeys())
            || template.maxRenderedSubjectLength() != 200
            || template.maxRenderedBodyLength() != 10_000
            || !"zh-CN".equals(template.locale())
            || !"text/plain".equals(template.contentType())) {
            throw BusinessException.badRequest(TEMPLATE_MODEL_INVALID + ": invalid template definition");
        }
        if (containsHeaderBreak(template.subjectPattern())) {
            throw BusinessException.badRequest(HEADER_INVALID + ": subject pattern contains CR or LF");
        }
        Set<String> placeholders = new HashSet<>();
        collectPlaceholders(template.subjectPattern(), placeholders);
        collectPlaceholders(template.textBodyPattern(), placeholders);
        if (!template.allowedModelKeys().containsAll(placeholders)) {
            throw BusinessException.badRequest(
                TEMPLATE_MODEL_INVALID + ": template contains an unbound placeholder");
        }
        if (!placeholders.equals(template.requiredModelKeys())) {
            throw BusinessException.badRequest(
                TEMPLATE_MODEL_INVALID + ": required fields do not match template placeholders");
        }
    }

    private void collectPlaceholders(String pattern, Set<String> placeholders) {
        Matcher matcher = PLACEHOLDER.matcher(pattern);
        StringBuffer withoutPlaceholders = new StringBuffer();
        while (matcher.find()) {
            placeholders.add(matcher.group(1));
            matcher.appendReplacement(withoutPlaceholders, "");
        }
        matcher.appendTail(withoutPlaceholders);
        if (withoutPlaceholders.indexOf("{{") >= 0 || withoutPlaceholders.indexOf("}}") >= 0) {
            throw BusinessException.badRequest(
                TEMPLATE_MODEL_INVALID + ": template contains an invalid placeholder");
        }
    }

    private String bind(String pattern, Map<String, String> model) {
        Matcher matcher = PLACEHOLDER.matcher(pattern);
        StringBuffer rendered = new StringBuffer();
        while (matcher.find()) {
            String value = model.get(matcher.group(1));
            if (value == null) {
                throw BusinessException.badRequest(
                    TEMPLATE_MODEL_INVALID + ": template placeholder is missing");
            }
            matcher.appendReplacement(rendered, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(rendered);
        if (rendered.indexOf("{{") >= 0 || rendered.indexOf("}}") >= 0) {
            throw BusinessException.badRequest(
                TEMPLATE_MODEL_INVALID + ": rendered template contains an unbound placeholder");
        }
        return rendered.toString();
    }

    private boolean containsHeaderBreak(String value) {
        return value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static List<EmailRouteDefinition> defaultRoutes() {
        List<EmailRouteDefinition> routes = new ArrayList<>();
        routes.add(route("RESERVATION_APPROVED", NotificationRecipientKind.USER, "reservation.approved"));
        routes.add(route("RESERVATION_REJECTED", NotificationRecipientKind.USER, "reservation.rejected"));
        routes.add(route("RESERVATION_CANCELED", NotificationRecipientKind.USER, "reservation.canceled"));
        routes.add(route("PAYMENT_SUCCEEDED", NotificationRecipientKind.USER, "payment.succeeded"));
        routes.add(route("PAYMENT_TIMEOUT", NotificationRecipientKind.USER, "payment.timeout"));
        routes.add(route("REFUND_REVIEW_APPROVED", NotificationRecipientKind.USER, "refund.review-approved"));
        routes.add(route("REFUND_REVIEW_REJECTED", NotificationRecipientKind.USER, "refund.review-rejected"));
        routes.add(route("REFUND_SUCCEEDED", NotificationRecipientKind.USER, "refund.succeeded"));
        routes.add(route("CUSTOMER_SERVICE_REPLY_ADDED", NotificationRecipientKind.USER, "customer-service.reply-added"));
        routes.add(route("CUSTOMER_SERVICE_CASE_RESOLVED", NotificationRecipientKind.USER, "customer-service.resolved"));
        routes.add(route("RESERVATION_REVIEW_REQUIRED", NotificationRecipientKind.VENUE_ADMIN, "admin.reservation-review-required"));
        routes.add(route("REFUND_REVIEW_REQUIRED", NotificationRecipientKind.VENUE_ADMIN, "admin.refund-review-required"));
        routes.add(route("CUSTOMER_SERVICE_CASE_REVIEW_REQUIRED", NotificationRecipientKind.VENUE_ADMIN, "admin.customer-service-case-review-required"));
        return List.copyOf(routes);
    }

    private static List<EmailTemplate> defaultTemplates() {
        return List.of(
            template("RESERVATION_APPROVED", "reservation.approved",
                "预约 {{reservationId}} 已审核通过",
                "您的预约 {{reservationId}} 已审核通过，请按站内流程完成后续操作。",
                Set.of("reservationId"), Set.of("reservationId")),
            template("RESERVATION_REJECTED", "reservation.rejected",
                "预约 {{reservationId}} 未通过审核",
                "您的预约 {{reservationId}} 未通过审核。原因：{{reason}}",
                Set.of("reservationId", "reason"), Set.of("reservationId", "reason")),
            template("RESERVATION_CANCELED", "reservation.canceled",
                "预约 {{reservationId}} 已取消",
                "您的预约 {{reservationId}} 当前状态为 {{status}}。取消来源：{{source}}。",
                Set.of("reservationId", "status", "source"), Set.of("reservationId", "status", "source")),
            template("PAYMENT_SUCCEEDED", "payment.succeeded",
                "订单 {{orderNo}} 支付成功",
                "订单 {{orderNo}} 已支付成功。",
                Set.of("orderNo"), Set.of("orderNo")),
            template("PAYMENT_TIMEOUT", "payment.timeout",
                "订单 {{orderNo}} 支付超时",
                "订单 {{orderNo}} 已因支付超时关闭。",
                Set.of("orderNo"), Set.of("orderNo")),
            template("REFUND_REVIEW_APPROVED", "refund.review-approved",
                "退款 {{refundNo}} 审核通过",
                "退款申请 {{refundNo}} 已审核通过。",
                Set.of("refundNo"), Set.of("refundNo")),
            template("REFUND_REVIEW_REJECTED", "refund.review-rejected",
                "退款 {{refundNo}} 审核未通过",
                "退款申请 {{refundNo}} 审核未通过。原因：{{reason}}",
                Set.of("refundNo", "reason"), Set.of("refundNo", "reason")),
            template("REFUND_SUCCEEDED", "refund.succeeded",
                "退款 {{refundNo}} 已完成",
                "退款 {{refundNo}} 已完成。",
                Set.of("refundNo"), Set.of("refundNo")),
            template("CUSTOMER_SERVICE_REPLY_ADDED", "customer-service.reply-added",
                "客服工单 {{caseId}} 有新回复",
                "客服工单 {{caseId}} 回复：{{reply}}",
                Set.of("caseId", "reply"), Set.of("caseId", "reply")),
            template("CUSTOMER_SERVICE_CASE_RESOLVED", "customer-service.resolved",
                "客服工单 {{caseId}} 已解决",
                "客服工单 {{caseId}} 已解决。回复：{{reply}}",
                Set.of("caseId", "reply"), Set.of("caseId", "reply")),
            template("RESERVATION_REVIEW_REQUIRED", "admin.reservation-review-required",
                "预约 {{reservationId}} 待审核",
                "预约 {{reservationId}} 需要场馆管理员审核。",
                Set.of("reservationId"), Set.of("reservationId")),
            template("REFUND_REVIEW_REQUIRED", "admin.refund-review-required",
                "退款 {{refundNo}} 待审核",
                "退款申请 {{refundNo}} 需要场馆管理员审核。",
                Set.of("refundNo"), Set.of("refundNo")),
            template("CUSTOMER_SERVICE_CASE_REVIEW_REQUIRED", "admin.customer-service-case-review-required",
                "客服工单 {{caseId}} 待处理",
                "客服工单 {{caseId}} 需要场馆管理员处理。",
                Set.of("caseId"), Set.of("caseId"))
        );
    }

    private static EmailTemplate template(
        String eventType,
        String templateKey,
        String subjectPattern,
        String bodyPattern,
        Set<String> allowedKeys,
        Set<String> requiredKeys
    ) {
        return new EmailTemplate(
            eventType, templateKey, "v1", subjectPattern, bodyPattern,
            allowedKeys, requiredKeys, 200, 10_000, "zh-CN", "text/plain");
    }

    private static EmailRouteDefinition route(
        String eventType, NotificationRecipientKind kind, String templateKey
    ) {
        return new EmailRouteDefinition(eventType, kind, templateKey, "v1");
    }

    private record RouteKey(String eventType, NotificationRecipientKind recipientKind) {
    }

    private record TemplateIdentity(String templateKey, String templateVersion) {
    }
}
