package com.example.venue.payment;

import com.alipay.api.AlipayClient;
import com.alipay.api.DefaultAlipayClient;
import com.alipay.api.request.AlipayTradePagePayRequest;
import com.example.venue.payment.dto.PaymentAction;
import com.example.venue.payment.provider.alipay.AlipayPagePayActionParser;
import com.example.venue.payment.provider.alipay.AlipaySandboxException;
import com.example.venue.payment.provider.alipay.AlipaySandboxProperties;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Comment;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.parser.Parser;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AlipayPagePayActionParserTest {
    private static final URI SANDBOX_GATEWAY = URI.create(AlipaySandboxProperties.SANDBOX_GATEWAY_URL);
    private static String sdkForm;

    private final AlipayPagePayActionParser parser = new AlipayPagePayActionParser();

    @BeforeAll
    static void generateSdkFormInMemory() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair keyPair = generator.generateKeyPair();
        String privateKey = Base64.getEncoder().encodeToString(keyPair.getPrivate().getEncoded());
        String publicKey = Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded());
        AlipayClient client = new DefaultAlipayClient(
            SANDBOX_GATEWAY.toString(),
            "2021000000000000",
            privateKey,
            "json",
            "UTF-8",
            publicKey,
            "RSA2"
        );
        AlipayTradePagePayRequest request = new AlipayTradePagePayRequest();
        request.setNotifyUrl("https://payments.example.test/api/payments/callback/alipay/sandbox");
        request.setReturnUrl("http://127.0.0.1/me/payments/alipay/sandbox/return");
        request.setBizContent("{\"out_trade_no\":\"PAY001\",\"total_amount\":\"100.00\","
            + "\"subject\":\"Venue order PAY001\",\"product_code\":\"FAST_INSTANT_TRADE_PAY\"}");
        sdkForm = client.pageExecute(request, "POST").getBody();
    }

    @Test
    void parsesThePinnedSdkPagePayFormWithoutMovingSignatureQueryIntoFields() {
        PaymentAction action = parser.parse(sdkForm, SANDBOX_GATEWAY);
        URI actionUri = URI.create(action.url());

        assertThat(action.type()).isEqualTo("SUBMIT_FORM");
        assertThat(action.method()).isEqualTo("POST");
        assertThat(action.url()).isEqualTo(form(Jsoup.parse(sdkForm, "", Parser.htmlParser())).attr("action"));
        assertThat(actionUri.getScheme()).isEqualTo("https");
        assertThat(actionUri.getHost()).isEqualTo(SANDBOX_GATEWAY.getHost());
        assertThat(actionUri.getRawPath()).isEqualTo(SANDBOX_GATEWAY.getRawPath());
        assertThat(actionUri.getRawQuery()).isNotBlank();
        assertThat(action.fields()).containsKey("biz_content");
        assertThat(action.fields()).doesNotContainKeys("app_id", "charset", "sign", "sign_type");
    }

    @Test
    void rejectsChangedAutoSubmitScript() {
        assertInvalid(mutate(document -> document.selectFirst("script").text("document.forms[0].submit();alert(1);")));
    }

    @Test
    void rejectsSecondSubmitInput() {
        assertInvalid(mutate(document -> form(document).appendElement("input")
            .attr("type", "submit").attr("value", "立即支付").attr("style", "display:none")));
    }

    @Test
    void rejectsInteractiveInput() {
        assertInvalid(mutate(document -> form(document).selectFirst("input[type=hidden]").attr("type", "text")));
    }

    @Test
    void rejectsEventAttribute() {
        assertInvalid(mutate(document -> form(document).attr("onload", "ignored()")));
    }

    @Test
    void rejectsEventAttributeOnDocumentWrappers() {
        assertInvalid(mutate(document -> document.body().attr("onload", "ignored()")));
        assertInvalid(mutate(document -> document.head().attr("onload", "ignored()")));
        assertInvalid(mutate(document -> document.selectFirst("html").attr("onload", "ignored()")));
    }

    @Test
    void rejectsExternalResource() {
        assertInvalid(mutate(document -> document.body().appendElement("img").attr("src", "https://example.test/x")));
    }

    @Test
    void rejectsIframe() {
        assertInvalid(mutate(document -> document.body().appendElement("iframe").attr("src", "https://example.test/x")));
    }

    @Test
    void rejectsUnknownElement() {
        assertInvalid(mutate(document -> document.body().appendElement("section")));
    }

    @Test
    void rejectsDuplicateHiddenFieldName() {
        assertInvalid(mutate(document -> form(document).appendElement("input")
            .attr("type", "hidden").attr("name", "biz_content").attr("value", "duplicate")));
    }

    @Test
    void rejectsDuplicateQueryKey() {
        assertInvalid(mutate(document -> {
            Element form = form(document);
            form.attr("action", form.attr("action") + "&charset=duplicate");
        }));
    }

    @Test
    void rejectsSecondForm() {
        assertInvalid(mutate(document -> document.body().appendElement("form")
            .attr("name", "punchout_form").attr("method", "post").attr("action", SANDBOX_GATEWAY.toString())));
    }

    @Test
    void rejectsNonPostForm() {
        assertInvalid(mutate(document -> form(document).attr("method", "get")));
    }

    @Test
    void rejectsGatewayHostMismatch() {
        assertInvalid(mutate(document -> {
            Element form = form(document);
            URI action = URI.create(form.attr("action"));
            form.attr("action", "https://example.test" + action.getRawPath() + "?" + action.getRawQuery());
        }));
    }

    @Test
    void rejectsGatewayPathMismatch() {
        assertInvalid(mutate(document -> {
            Element form = form(document);
            URI action = URI.create(form.attr("action"));
            form.attr("action", "https://" + SANDBOX_GATEWAY.getHost() + "/other?" + action.getRawQuery());
        }));
    }

    @Test
    void rejectsTooManyHiddenFields() {
        assertInvalid(mutate(document -> {
            Element form = form(document);
            for (int index = 0; index < 65; index++) {
                form.appendElement("input").attr("type", "hidden")
                    .attr("name", "extra" + index).attr("value", "value");
            }
        }));
    }

    @Test
    void rejectsOversizedHiddenFieldName() {
        assertInvalid(mutate(document -> form(document).appendElement("input")
            .attr("type", "hidden").attr("name", "n".repeat(129)).attr("value", "value")));
    }

    @Test
    void rejectsOversizedHiddenFieldValue() {
        assertInvalid(mutate(document -> form(document).appendElement("input")
            .attr("type", "hidden").attr("name", "extra").attr("value", "v".repeat(8193))));
    }

    @Test
    void rejectsOversizedQueryValue() {
        assertInvalid(mutate(document -> {
            Element form = form(document);
            form.attr("action", form.attr("action") + "&extra=" + "v".repeat(8193));
        }));
    }

    @Test
    void rejectsMalformedUriEncoding() {
        assertInvalid(mutate(document -> {
            Element form = form(document);
            form.attr("action", form.attr("action") + "&extra=%ZZ");
        }));
    }

    @Test
    void rejectsUnexpectedTextNode() {
        assertInvalid(mutate(document -> document.body().appendText("unexpected")));
    }

    @Test
    void rejectsCommentsAndParseErrors() {
        assertInvalid(mutate(document -> document.body().appendChild(new Comment("unexpected"))));
        assertInvalid(sdkForm + "<");
    }

    @Test
    void rejectsDocumentLevelDeclarationsAndComments() {
        assertInvalid("<!doctype html>" + sdkForm);
        assertInvalid("<!-- unexpected -->" + sdkForm);
        assertInvalid("<?xml version=\"1.0\"?>" + sdkForm);
    }

    private String mutate(Consumer<Document> mutation) {
        Document document = Jsoup.parse(sdkForm, "", Parser.htmlParser());
        mutation.accept(document);
        return document.outerHtml();
    }

    private Element form(Document document) {
        Element form = document.selectFirst("form");
        assertThat(form).isNotNull();
        return form;
    }

    private void assertInvalid(String invalidForm) {
        assertThatThrownBy(() -> parser.parse(invalidForm, SANDBOX_GATEWAY))
            .isInstanceOf(AlipaySandboxException.class)
            .hasMessage("ALIPAY_PAYMENT_ACTION_INVALID");
    }
}
