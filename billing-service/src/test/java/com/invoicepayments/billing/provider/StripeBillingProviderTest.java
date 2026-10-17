package com.invoicepayments.billing.provider;

import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.net.URLDecoder;
import java.net.http.HttpRequest;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Flow;

import static org.assertj.core.api.Assertions.*;

class StripeBillingProviderTest {
    static final String INTENT = "98cb0236-8414-452f-9bb3-99aba85ed955";
    static final String REFUND = "e0ac57bb-8336-4598-9a0e-52f435707aac";
    static final String KEY = "billing:pay:" + INTENT;
    static final String REFUND_KEY = "billing:refund:" + REFUND;
    static final String SESSION = "cs_test_payment123";
    static final String PI = "pi_payment123";
    static final String REF = "re_refund123";
    final FakeTransport transport = new FakeTransport();
    final StripeBillingProvider provider = new StripeBillingProvider("sk_test_exampleOnly", transport);

    @Test void cardUsesHostedCheckoutServerAmountStableKeyAndPinnedFixedHost() {
        transport.reply(session(null, "unpaid", "open"));
        ProviderResult result = provider.submit(command("CARD", 12345));
        assertThat(result.state()).isEqualTo("REQUIRES_ACTION");
        assertThat(result.checkoutUrl()).isEqualTo("https://checkout.stripe.com/c/pay/example");
        HttpRequest request = transport.requests.get(0);
        assertThat(request.uri().toString()).isEqualTo("https://api.stripe.com/v1/checkout/sessions");
        assertThat(request.method()).isEqualTo("POST");
        assertThat(request.headers().firstValue("Idempotency-Key")).contains(KEY);
        assertThat(request.headers().firstValue("Stripe-Version")).contains("2026-09-30.endive");
        assertThat(request.timeout()).hasValue(java.time.Duration.ofSeconds(10));
        Map<String, String> fields = form(request);
        assertThat(fields).containsEntry("allowed_payment_method_types[0]", "card")
                .containsEntry("line_items[0][price_data][unit_amount]", "12345")
                .containsEntry("line_items[0][price_data][currency]", "usd")
                .containsEntry("metadata[intentId]", INTENT)
                .containsEntry("metadata[idempotencyKey]", KEY)
                .containsEntry("payment_intent_data[metadata][intentId]", INTENT)
                .containsEntry("payment_intent_data[metadata][idempotencyKey]", KEY)
                .containsEntry("ui_mode", "hosted_page");
        assertThat(fields.keySet()).noneMatch(k -> k.contains("card[number]") || k.startsWith("payment_method_types["));
    }

    @Test void achSelectsBankAccountAndDoesNotPretendPaymentSucceeded() {
        transport.reply(session(null, "unpaid", "open"));
        assertThat(provider.submit(command("ACH", 12345)).state()).isEqualTo("REQUIRES_ACTION");
        assertThat(form(transport.requests.get(0))).containsEntry("allowed_payment_method_types[0]", "us_bank_account");
    }

    @ParameterizedTest @ValueSource(strings = {"sk_live_forbidden", "rk_test_forbidden", "not-a-key"})
    void liveOrUnsupportedKeysNeverConstructProvider(String key) {
        assertThatThrownBy(() -> new StripeBillingProvider(key, transport)).isInstanceOf(IllegalArgumentException.class);
        assertThat(transport.requests).isEmpty();
    }

    @Test void missingAccountDoesNotPretendConfigured() {
        var absent = new StripeBillingProvider("", transport);
        assertThatThrownBy(() -> absent.submit(command("CARD", 12345))).isInstanceOf(ProviderUncertainException.class)
                .hasMessage("Stripe test provider is not configured");
        assertThat(transport.requests).isEmpty();
    }

    @ParameterizedTest @ValueSource(longs = {-1, 0, 49, 100000000})
    void invalidUsdAmountsFailBeforeNetwork(long amount) {
        assertThatThrownBy(() -> provider.submit(command("CARD", amount))).isInstanceOf(IllegalArgumentException.class);
        assertThat(transport.requests).isEmpty();
    }

    @ParameterizedTest @ValueSource(strings = {"http://evil.example/return", "https://user:password@example.com/return", "javascript:alert(1)"})
    void unsafeReturnUrlsFailBeforeNetwork(String url) {
        assertThatThrownBy(() -> provider.submit(new ProviderCommand(INTENT, KEY, 12345, "USD", "CARD", url)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(transport.requests).isEmpty();
    }

    @Test void timeoutLeavesOutcomeUnknownAndDoesNotRetrySubmit() {
        transport.failure = new IOException("sensitive provider transport details");
        assertThatThrownBy(() -> provider.submit(command("CARD", 12345))).isInstanceOf(ProviderUncertainException.class)
                .hasMessageNotContaining("sensitive");
        assertThat(transport.requests).hasSize(1);
        assertThat(provider.lookup(KEY, null).state()).isEqualTo("UNKNOWN");
        assertThat(transport.requests).hasSize(1);
    }

    @Test void apiErrorsAreSafeAndNotRetried() {
        transport.responses.add(new StripeBillingProvider.Response(429, "{\"error\":\"sensitive details\"}"));
        assertThatThrownBy(() -> provider.submit(command("CARD", 12345))).isInstanceOf(ProviderUncertainException.class)
                .hasMessageContaining("HTTP 429").hasMessageNotContaining("sensitive");
        assertThat(transport.requests).hasSize(1);
    }

    @Test void mismatchedCreatedAmountIsNotTrusted() {
        transport.reply(session(null, "unpaid", "open").put("amount_total", 12346));
        assertThatThrownBy(() -> provider.submit(command("CARD", 12345))).isInstanceOf(ProviderUncertainException.class);
    }

    @ParameterizedTest @ValueSource(strings = {"http://checkout.stripe.com/pay", "https://checkout.stripe.com.evil.example/pay", "https://stripe.com@evil.example/pay"})
    void checkoutRedirectCannotEscapeProvider(String url) {
        transport.reply(session(null, "unpaid", "open").put("url", url));
        assertThatThrownBy(() -> provider.submit(command("CARD", 12345))).isInstanceOf(ProviderUncertainException.class);
    }

    @Test void lookupWithoutKnownSessionNeverCreatesAnOperation() {
        assertThat(provider.lookup(KEY, null).state()).isEqualTo("UNKNOWN");
        assertThat(provider.lookup(KEY, "https://evil.example").state()).isEqualTo("UNKNOWN");
        assertThat(transport.requests).isEmpty();
    }

    @Test void wrongLookupCorrelationStopsBeforeFetchingPaymentIntent() {
        JSONObject session = session(PI, "paid", "complete");
        session.getJSONObject("metadata").put("idempotencyKey", "another-key");
        transport.reply(session);
        assertThat(provider.lookup(KEY, SESSION).state()).isEqualTo("UNKNOWN");
        assertThat(transport.requests).hasSize(1);
    }

    @Test void achProcessingDoesNotBecomeCapturedOnCheckoutCompletion() {
        transport.reply(session(PI, "unpaid", "complete"));
        transport.reply(paymentIntent("processing", 0));
        assertThat(provider.lookup(KEY, SESSION).state()).isEqualTo("PROCESSING");
        assertThat(transport.requests).hasSize(2).allMatch(r -> r.method().equals("GET"));
    }

    @Test void authorizationIsNotCapture() {
        transport.reply(session(PI, "unpaid", "complete"));
        transport.reply(paymentIntent("requires_capture", 0));
        assertThat(provider.lookup(KEY, SESSION).state()).isEqualTo("PROCESSING");
    }

    @Test void canonicalSucceededIntentAndExactReceivedAmountConfirmCapture() {
        transport.reply(session(PI, "paid", "complete"));
        transport.reply(paymentIntent("succeeded", 12345));
        ProviderResult result = provider.lookup(KEY, SESSION);
        assertThat(result.state()).isEqualTo("CAPTURED");
        assertThat(result.providerId()).isEqualTo(SESSION);
        assertThat(result.checkoutUrl()).isNull();
    }

    @Test void succeededWithWrongReceivedAmountIsUnknown() {
        transport.reply(session(PI, "paid", "complete"));
        transport.reply(paymentIntent("succeeded", 100));
        assertThat(provider.lookup(KEY, SESSION).state()).isEqualTo("UNKNOWN");
    }

    @Test void paymentIntentMetadataMustMatchSession() {
        transport.reply(session(PI, "paid", "complete"));
        JSONObject intent = paymentIntent("succeeded", 12345);
        intent.getJSONObject("metadata").put("intentId", REFUND);
        transport.reply(intent);
        assertThat(provider.lookup(KEY, SESSION).state()).isEqualTo("UNKNOWN");
    }

    @Test void refundUsesOriginalCapturedPaymentIntentAndItsOwnStableKey() {
        transport.reply(session(PI, "paid", "complete"));
        transport.reply(paymentIntent("succeeded", 12345));
        transport.reply(refund("pending"));
        ProviderResult result = provider.refund(refundCommand());
        assertThat(result.state()).isEqualTo("PROCESSING");
        assertThat(result.providerId()).isEqualTo(REF);
        HttpRequest request = transport.requests.get(2);
        assertThat(request.uri().toString()).isEqualTo("https://api.stripe.com/v1/refunds");
        assertThat(request.headers().firstValue("Idempotency-Key")).contains(REFUND_KEY);
        assertThat(form(request)).containsEntry("payment_intent", PI).containsEntry("amount", "500")
                .containsEntry("metadata[refundId]", REFUND).containsEntry("metadata[intentId]", INTENT);
    }

    @Test void refundCannotPostAgainstUncapturedPayment() {
        transport.reply(session(PI, "unpaid", "complete"));
        transport.reply(paymentIntent("processing", 0));
        assertThatThrownBy(() -> provider.refund(refundCommand())).isInstanceOf(ProviderUncertainException.class);
        assertThat(transport.requests).hasSize(2).allMatch(r -> r.method().equals("GET"));
    }

    @Test void refundLookupRequiresKnownIdAndVerifiesMetadata() {
        assertThat(provider.lookupRefund(REFUND_KEY, null).state()).isEqualTo("UNKNOWN");
        assertThat(transport.requests).isEmpty();
        transport.reply(refund("succeeded"));
        assertThat(provider.lookupRefund(REFUND_KEY, REF).state()).isEqualTo("REFUNDED");
        transport.reply(refund("succeeded"));
        assertThat(provider.lookupRefund("wrong-key", REF).state()).isEqualTo("UNKNOWN");
    }

    @Test void refundTimeoutDoesNotRetryThePost() {
        transport.reply(session(PI, "paid", "complete"));
        transport.reply(paymentIntent("succeeded", 12345));
        transport.responses.add(new StripeBillingProvider.Response(503, "{}"));
        assertThatThrownBy(() -> provider.refund(refundCommand())).isInstanceOf(ProviderUncertainException.class);
        assertThat(transport.requests).hasSize(3).filteredOn(r -> r.method().equals("POST")).hasSize(1);
    }

    static ProviderCommand command(String method, long amount) {
        return new ProviderCommand(INTENT, KEY, amount, "USD", method, "http://localhost:4200/billing");
    }
    static RefundCommand refundCommand() { return new RefundCommand(REFUND, INTENT, SESSION, REFUND_KEY, 500, "USD"); }
    static JSONObject metadata(String key) { return new JSONObject().put("intentId", INTENT).put("idempotencyKey", key); }
    static JSONObject session(String pi, String paymentStatus, String status) {
        return new JSONObject().put("id", SESSION).put("object", "checkout.session").put("livemode", false)
                .put("mode", "payment").put("metadata", metadata(KEY)).put("amount_total", 12345).put("currency", "usd")
                .put("payment_intent", pi == null ? JSONObject.NULL : pi).put("payment_status", paymentStatus)
                .put("status", status).put("url", "https://checkout.stripe.com/c/pay/example");
    }
    static JSONObject paymentIntent(String status, long received) {
        return new JSONObject().put("id", PI).put("object", "payment_intent").put("livemode", false)
                .put("metadata", metadata(KEY)).put("amount", 12345).put("amount_received", received)
                .put("currency", "usd").put("status", status);
    }
    static JSONObject refund(String status) {
        return new JSONObject().put("id", REF).put("object", "refund").put("metadata", metadata(REFUND_KEY).put("refundId", REFUND))
                .put("payment_intent", PI).put("amount", 500).put("currency", "usd").put("status", status);
    }
    static Map<String, String> form(HttpRequest request) {
        StringBuilder body = new StringBuilder();
        request.bodyPublisher().orElseThrow().subscribe(new Flow.Subscriber<>() {
            @Override public void onSubscribe(Flow.Subscription subscription) { subscription.request(Long.MAX_VALUE); }
            @Override public void onNext(ByteBuffer item) { body.append(StandardCharsets.UTF_8.decode(item)); }
            @Override public void onError(Throwable throwable) { throw new AssertionError(throwable); }
            @Override public void onComplete() {}
        });
        Map<String, String> fields = new LinkedHashMap<>();
        for (String part : body.toString().split("&")) {
            String[] pair = part.split("=", 2);
            fields.put(URLDecoder.decode(pair[0], StandardCharsets.UTF_8), URLDecoder.decode(pair[1], StandardCharsets.UTF_8));
        }
        return fields;
    }
    static final class FakeTransport implements StripeBillingProvider.Transport {
        final List<HttpRequest> requests = new ArrayList<>();
        final Deque<StripeBillingProvider.Response> responses = new ArrayDeque<>();
        IOException failure;
        void reply(JSONObject json) { responses.add(new StripeBillingProvider.Response(200, json.toString())); }
        @Override public StripeBillingProvider.Response send(HttpRequest request) throws IOException {
            requests.add(request);
            if (failure != null) throw failure;
            return responses.removeFirst();
        }
    }
}
