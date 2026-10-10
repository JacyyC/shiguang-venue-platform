package com.example.venue.payment.provider.alipay;

import com.example.venue.payment.dto.PaymentAction;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.parser.Parser;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Component
public class AlipayPagePayActionParser {
    private static final String INVALID_ACTION = "ALIPAY_PAYMENT_ACTION_INVALID";
    private static final String FORM_NAME = "punchout_form";
    private static final String SUBMIT_VALUE = "\u7acb\u5373\u652f\u4ed8";
    private static final String SUBMIT_STYLE = "display:none";
    private static final String AUTO_SUBMIT_SCRIPT = "document.forms[0].submit();";
    private static final int MAX_ACTION_LENGTH = 16_384;
    private static final int MAX_QUERY_PARAMETERS = 64;
    private static final int MAX_QUERY_KEY_LENGTH = 128;
    private static final int MAX_QUERY_VALUE_LENGTH = 8_192;
    private static final int MAX_HIDDEN_FIELDS = 64;
    private static final int MAX_FIELD_NAME_LENGTH = 128;
    private static final int MAX_FIELD_VALUE_LENGTH = 8_192;

    public PaymentAction parse(String sdkForm, URI configuredGateway) {
        if (sdkForm == null || sdkForm.isBlank() || sdkForm.length() > MAX_ACTION_LENGTH || configuredGateway == null) {
            throw invalidAction();
        }

        Parser parser = Parser.htmlParser().setTrackErrors(1);
        Document document = Jsoup.parse(sdkForm, "", parser);
        if (!parser.getErrors().isEmpty()) {
            throw invalidAction();
        }

        validateDocumentShell(document);
        validateHead(document.head());
        List<Node> bodyNodes = document.body().childNodes();
        Element form = null;
        Element script = null;
        for (Node node : bodyNodes) {
            if (isWhitespace(node)) {
                continue;
            }
            if (!(node instanceof Element element)) {
                throw invalidAction();
            }
            if ("form".equals(element.tagName()) && form == null) {
                form = element;
            } else if ("script".equals(element.tagName()) && script == null) {
                script = element;
            } else {
                throw invalidAction();
            }
        }
        if (form == null || script == null) {
            throw invalidAction();
        }

        String action = validateFormAndExtractAction(form, configuredGateway);
        validateAutoSubmitScript(script);
        Map<String, String> fields = extractHiddenFields(form);
        return new PaymentAction("SUBMIT_FORM", "POST", action, fields);
    }

    private void validateHead(Element head) {
        for (Node node : head.childNodes()) {
            if (!isWhitespace(node)) {
                throw invalidAction();
            }
        }
    }

    private void validateDocumentShell(Document document) {
        Element html = document.selectFirst("html");
        Element head = document.head();
        Element body = document.body();
        if (html == null || head == null || body == null) {
            throw invalidAction();
        }
        requireExactAttributes(html, Set.of());
        requireExactAttributes(head, Set.of());
        requireExactAttributes(body, Set.of());

        int htmlCount = 0;
        for (Node node : document.childNodes()) {
            if (isWhitespace(node)) {
                continue;
            }
            if (!(node instanceof Element element) || !"html".equals(element.tagName()) || ++htmlCount > 1) {
                throw invalidAction();
            }
        }
        if (htmlCount != 1) {
            throw invalidAction();
        }

        int headCount = 0;
        int bodyCount = 0;
        for (Node node : html.childNodes()) {
            if (isWhitespace(node)) {
                continue;
            }
            if (!(node instanceof Element element)) {
                throw invalidAction();
            }
            if ("head".equals(element.tagName()) && ++headCount == 1) {
                continue;
            }
            if ("body".equals(element.tagName()) && ++bodyCount == 1) {
                continue;
            }
            throw invalidAction();
        }
        if (headCount != 1 || bodyCount != 1) {
            throw invalidAction();
        }
    }

    private String validateFormAndExtractAction(Element form, URI configuredGateway) {
        requireExactAttributes(form, Set.of("name", "action", "method"));
        if (!FORM_NAME.equals(form.attr("name")) || !"post".equalsIgnoreCase(form.attr("method"))) {
            throw invalidAction();
        }
        String action = form.attr("action");
        if (action.isBlank() || !action.equals(action.trim()) || action.length() > MAX_ACTION_LENGTH) {
            throw invalidAction();
        }
        URI actionUri;
        try {
            actionUri = URI.create(action);
        } catch (IllegalArgumentException ex) {
            throw invalidAction();
        }
        if (actionUri.getRawUserInfo() != null || actionUri.getRawFragment() != null
            || !equalsIgnoreCase(actionUri.getScheme(), configuredGateway.getScheme())
            || !equalsIgnoreCase(actionUri.getHost(), configuredGateway.getHost())
            || effectivePort(actionUri) != effectivePort(configuredGateway)
            || !Objects.equals(actionUri.getRawPath(), configuredGateway.getRawPath())) {
            throw invalidAction();
        }
        validateQuery(actionUri.getRawQuery());
        return action;
    }

    private Map<String, String> extractHiddenFields(Element form) {
        Map<String, String> fields = new LinkedHashMap<>();
        int submitCount = 0;
        for (Node node : form.childNodes()) {
            if (isWhitespace(node)) {
                continue;
            }
            if (!(node instanceof Element input) || !"input".equals(input.tagName())) {
                throw invalidAction();
            }
            String type = input.attr("type");
            if ("hidden".equals(type)) {
                requireExactAttributes(input, Set.of("type", "name", "value"));
                String name = input.attr("name");
                String value = input.attr("value");
                if (name.isBlank() || name.length() > MAX_FIELD_NAME_LENGTH || value.length() > MAX_FIELD_VALUE_LENGTH
                    || fields.putIfAbsent(name, value) != null || fields.size() > MAX_HIDDEN_FIELDS) {
                    throw invalidAction();
                }
            } else if ("submit".equals(type)) {
                requireExactAttributes(input, Set.of("type", "value", "style"));
                if (!SUBMIT_VALUE.equals(input.attr("value")) || !SUBMIT_STYLE.equals(input.attr("style"))
                    || ++submitCount > 1) {
                    throw invalidAction();
                }
            } else {
                throw invalidAction();
            }
        }
        if (fields.isEmpty() || submitCount != 1) {
            throw invalidAction();
        }
        return fields;
    }

    private void validateAutoSubmitScript(Element script) {
        requireExactAttributes(script, Set.of());
        if (!AUTO_SUBMIT_SCRIPT.equals(script.data().trim())) {
            throw invalidAction();
        }
    }

    private void validateQuery(String rawQuery) {
        if (rawQuery == null || rawQuery.isBlank() || rawQuery.length() > MAX_ACTION_LENGTH) {
            throw invalidAction();
        }
        Set<String> keys = new HashSet<>();
        String[] pairs = rawQuery.split("&", -1);
        if (pairs.length > MAX_QUERY_PARAMETERS) {
            throw invalidAction();
        }
        for (String pair : pairs) {
            int separator = pair.indexOf('=');
            if (separator <= 0) {
                throw invalidAction();
            }
            String key = decodeQueryComponent(pair.substring(0, separator));
            String value = decodeQueryComponent(pair.substring(separator + 1));
            if (key.isBlank() || key.length() > MAX_QUERY_KEY_LENGTH || value.length() > MAX_QUERY_VALUE_LENGTH
                || !keys.add(key)) {
                throw invalidAction();
            }
        }
    }

    private String decodeQueryComponent(String value) {
        StringBuilder decoded = new StringBuilder(value.length());
        for (int index = 0; index < value.length();) {
            char current = value.charAt(index);
            if (current == '%') {
                ByteArrayOutputStream encoded = new ByteArrayOutputStream();
                while (index < value.length() && value.charAt(index) == '%') {
                    if (index + 2 >= value.length()) {
                        throw invalidAction();
                    }
                    int high = Character.digit(value.charAt(index + 1), 16);
                    int low = Character.digit(value.charAt(index + 2), 16);
                    if (high < 0 || low < 0) {
                        throw invalidAction();
                    }
                    encoded.write((high << 4) + low);
                    index += 3;
                }
                try {
                    decoded.append(StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(encoded.toByteArray())));
                } catch (CharacterCodingException ex) {
                    throw invalidAction();
                }
            } else {
                decoded.append(current == '+' ? ' ' : current);
                index++;
            }
        }
        return decoded.toString();
    }

    private void requireExactAttributes(Element element, Set<String> allowedAttributes) {
        if (element.attributes().size() != allowedAttributes.size()) {
            throw invalidAction();
        }
        for (org.jsoup.nodes.Attribute attribute : element.attributes()) {
            if (!allowedAttributes.contains(attribute.getKey().toLowerCase(Locale.ROOT))) {
                throw invalidAction();
            }
        }
    }

    private boolean isWhitespace(Node node) {
        return node instanceof TextNode textNode && textNode.isBlank();
    }

    private int effectivePort(URI uri) {
        if (uri.getPort() >= 0) {
            return uri.getPort();
        }
        if ("https".equalsIgnoreCase(uri.getScheme())) {
            return 443;
        }
        if ("http".equalsIgnoreCase(uri.getScheme())) {
            return 80;
        }
        return -1;
    }

    private boolean equalsIgnoreCase(String left, String right) {
        return left != null && right != null && left.equalsIgnoreCase(right);
    }

    private AlipaySandboxException invalidAction() {
        return AlipaySandboxException.config(INVALID_ACTION);
    }
}
