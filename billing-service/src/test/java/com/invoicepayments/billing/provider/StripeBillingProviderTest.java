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
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
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

    // Services log these exceptions with their stack traces, so none may carry the key or a response body.
    @Test void providerFailuresCarryNoCredentialOrResponseBody() {
        String key = "sk_test_exampleOnly";
        transport.responses.add(new StripeBillingProvider.Response(401, "{\"error\":{\"message\":\"Invalid API Key provided: " + key + "\"}}"));
        assertCarriesNoSecret(catchThrowable(() -> provider.submit(command("CARD", 12345))), key);
        transport.responses.add(new StripeBillingProvider.Response(200, "not json " + key));
        assertCarriesNoSecret(catchThrowable(() -> provider.submit(command("CARD", 12345))), key);
        transport.failure = new IOException("connection reset while sending Bearer " + key);
        assertCarriesNoSecret(catchThrowable(() -> provider.submit(command("CARD", 12345))), key);
    }

    private static void assertCarriesNoSecret(Throwable failure, String key) {
        assertThat(failure).isInstanceOf(ProviderUncertainException.class);
        var trace = new java.io.StringWriter(); failure.printStackTrace(new java.io.PrintWriter(trace));
        assertThat(trace.toString()).doesNotContain(key).doesNotContain("Invalid API Key").doesNotContain("Bearer");
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

    @Test void recoveryReusesExactRequestThenFetchesCurrentStateInsteadOfCachedResponse() {
        Instant now=Instant.parse("2026-10-21T08:00:00Z");
        var adapter=new StripeBillingProvider("sk_test_exampleOnly",transport,Clock.fixed(now,ZoneOffset.UTC));
        ProviderCommand saved=command("CARD",12345);
        transport.failure=new IOException("Response lost after acceptance");
        assertThatThrownBy(()->adapter.submitSaved(saved,adapter.paymentRequestVersion(),now.minusSeconds(60)))
                .isInstanceOf(ProviderUncertainException.class);
        transport.failure=null;
        // Stripe replays the original open Checkout response, but it has since been paid.
        transport.reply(session(null,"unpaid","open"));
        transport.reply(session(PI,"paid","complete"));
        transport.reply(paymentIntent("succeeded",12345));
        ProviderResult recovered=adapter.recoverPayment(saved,adapter.paymentRequestVersion(),now.minusSeconds(60),null);
        assertThat(recovered.state()).isEqualTo("CAPTURED");
        assertThat(recovered.providerId()).isEqualTo(SESSION);
        assertThat(transport.requests).hasSize(4);
        assertThat(form(transport.requests.get(1))).isEqualTo(form(transport.requests.get(0)));
        assertThat(transport.requests.get(1).headers()).isEqualTo(transport.requests.get(0).headers());
        assertThat(transport.requests.subList(2,4)).allMatch(r->r.method().equals("GET"));
    }

    @Test void crashBeforeInitialNetworkCallCanRecoverSavedCheckout() {
        Instant now=Instant.parse("2026-10-21T08:00:00Z");
        var adapter=new StripeBillingProvider("sk_test_exampleOnly",transport,Clock.fixed(now,ZoneOffset.UTC));
        transport.reply(session(null,"unpaid","open"));
        transport.reply(session(null,"unpaid","open"));
        assertThat(adapter.recoverPayment(command("CARD",12345),adapter.paymentRequestVersion(),now.minusSeconds(60),null).state())
                .isEqualTo("REQUIRES_ACTION");
        assertThat(transport.requests).extracting(HttpRequest::method).containsExactly("POST","GET");
    }

    @ParameterizedTest @ValueSource(longs={0,1,60})
    void replayAtOrAfterTwentyThreeHoursNeverPosts(long secondsPastDeadline) {
        Instant now=Instant.parse("2026-10-21T08:00:00Z");
        var adapter=new StripeBillingProvider("sk_test_exampleOnly",transport,Clock.fixed(now,ZoneOffset.UTC));
        Instant prepared=now.minus(Duration.ofHours(23)).minusSeconds(secondsPastDeadline);
        assertThatThrownBy(()->adapter.recoverPayment(command("CARD",12345),adapter.paymentRequestVersion(),prepared,null))
                .isInstanceOf(ProviderReplayUnsafeException.class);
        assertThatThrownBy(()->adapter.submitSaved(command("CARD",12345),adapter.paymentRequestVersion(),prepared))
                .isInstanceOf(ProviderReplayUnsafeException.class);
        assertThat(transport.requests).isEmpty();
    }

    @Test void replayJustBeforeDeadlineIsAllowedAndReadsExpiredCheckout() {
        Instant now=Instant.parse("2026-10-21T08:00:00Z");
        var adapter=new StripeBillingProvider("sk_test_exampleOnly",transport,Clock.fixed(now,ZoneOffset.UTC));
        transport.reply(session(null,"unpaid","open"));
        transport.reply(session(null,"unpaid","expired").put("url",JSONObject.NULL));
        assertThat(adapter.recoverPayment(command("CARD",12345),adapter.paymentRequestVersion(),
                now.minus(Duration.ofHours(23)).plusSeconds(1),null).state()).isEqualTo("FAILED");
    }

    @Test void missingVersionChangedCredentialsOrFutureTimestampCannotReplay() {
        Instant now=Instant.parse("2026-10-21T08:00:00Z");
        var adapter=new StripeBillingProvider("sk_test_exampleOnly",transport,Clock.fixed(now,ZoneOffset.UTC));
        var rotated=new StripeBillingProvider("sk_test_differentAccount",transport,Clock.fixed(now,ZoneOffset.UTC));
        for(String version: new String[]{null,"previous-format",rotated.paymentRequestVersion()}) {
            assertThatThrownBy(()->adapter.recoverPayment(command("CARD",12345),version,now,null))
                    .isInstanceOf(ProviderReplayUnsafeException.class);
        }
        assertThatThrownBy(()->adapter.recoverPayment(command("CARD",12345),adapter.paymentRequestVersion(),now.plusSeconds(1),null))
                .isInstanceOf(ProviderReplayUnsafeException.class);
        assertThatThrownBy(()->adapter.recoverPayment(command("CARD",12345),adapter.paymentRequestVersion(),null,null))
                .isInstanceOf(ProviderReplayUnsafeException.class);
        assertThat(transport.requests).isEmpty();
    }

    @Test void knownIdStillAllowsReadOnlyRecoveryAfterReplayWindow() {
        transport.reply(session(PI,"paid","complete"));
        transport.reply(paymentIntent("succeeded",12345));
        assertThat(provider.recoverPayment(command("CARD",12345),null,Instant.EPOCH,SESSION).state()).isEqualTo("CAPTURED");
        assertThat(transport.requests).hasSize(2).allMatch(r->r.method().equals("GET"));
    }

    @Test void savedCommandRoundTripsWithoutChangingReturnUrlOrAmount() {
        ProviderCommand saved=command("ACH",12345);
        assertThat(ProviderCommand.fromJson(saved.toJson())).isEqualTo(saved);
    }

    @Test void failedReadAfterReplayPreservesVerifiedProviderIdForLaterLookup() {
        transport.reply(session(null,"unpaid","open"));
        transport.responses.add(new StripeBillingProvider.Response(503,"{}"));
        ProviderResult result=provider.recoverPayment(command("CARD",12345),provider.paymentRequestVersion(),Instant.now().minusSeconds(60),null);
        assertThat(result.state()).isEqualTo("UNKNOWN");
        assertThat(result.providerId()).isEqualTo(SESSION);
        assertThat(transport.requests).extracting(HttpRequest::method).containsExactly("POST","GET");
    }

    @Test void failedPaymentStateReadAfterSubmissionPreservesVerifiedSessionId() {
        transport.reply(session(PI,"paid","complete"));
        transport.responses.add(new StripeBillingProvider.Response(503,"{}"));
        ProviderResult result=provider.submit(command("CARD",12345));
        assertThat(result.state()).isEqualTo("UNKNOWN");
        assertThat(result.providerId()).isEqualTo(SESSION);
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

    @Test void refundRecoveryReplaysTheExactRefundThenReadsCurrentState() {
        Instant now = Instant.parse("2026-10-21T08:00:00Z");
        var adapter = new StripeBillingProvider("sk_test_exampleOnly", transport, Clock.fixed(now, ZoneOffset.UTC));
        transport.reply(session(PI, "paid", "complete"));
        transport.reply(paymentIntent("succeeded", 12345));
        transport.failOnRequest = 3; // the refund POST is accepted, but its response is lost
        assertThatThrownBy(() -> adapter.refund(refundCommand())).isInstanceOf(ProviderUncertainException.class);
        transport.failOnRequest = -1;
        transport.reply(session(PI, "paid", "complete"));
        transport.reply(paymentIntent("succeeded", 12345));
        transport.reply(refund("pending"));   // Stripe replays the original response for the same key
        transport.reply(refund("succeeded")); // the refund's current state
        ProviderResult recovered = adapter.recoverRefund(refundCommand(), adapter.refundRequestVersion(), now.minusSeconds(60), null);
        assertThat(recovered.state()).isEqualTo("REFUNDED");
        assertThat(recovered.providerId()).isEqualTo(REF);
        List<HttpRequest> posts = transport.requests.stream().filter(r -> r.method().equals("POST")).toList();
        assertThat(posts).hasSize(2);
        assertThat(form(posts.get(1))).isEqualTo(form(posts.get(0)));
        assertThat(posts.get(1).headers().firstValue("Idempotency-Key")).contains(REFUND_KEY);
        assertThat(posts.get(0).headers().firstValue("Idempotency-Key")).contains(REFUND_KEY);
        assertThat(transport.requests.get(transport.requests.size() - 1).method()).isEqualTo("GET");
    }

    @ParameterizedTest @ValueSource(longs = {0, 1, 60})
    void refundReplayAtOrAfterTwentyThreeHoursNeverPosts(long secondsPastDeadline) {
        Instant now = Instant.parse("2026-10-21T08:00:00Z");
        var adapter = new StripeBillingProvider("sk_test_exampleOnly", transport, Clock.fixed(now, ZoneOffset.UTC));
        Instant prepared = now.minus(Duration.ofHours(23)).minusSeconds(secondsPastDeadline);
        assertThatThrownBy(() -> adapter.recoverRefund(refundCommand(), adapter.refundRequestVersion(), prepared, null))
                .isInstanceOf(ProviderReplayUnsafeException.class);
        assertThat(transport.requests).isEmpty();
    }

    @Test void refundReplayRejectsPaymentFormatChangedCredentialsOrUntrustedTimes() {
        Instant now = Instant.parse("2026-10-21T08:00:00Z");
        var adapter = new StripeBillingProvider("sk_test_exampleOnly", transport, Clock.fixed(now, ZoneOffset.UTC));
        var rotated = new StripeBillingProvider("sk_test_differentAccount", transport, Clock.fixed(now, ZoneOffset.UTC));
        assertThat(adapter.refundRequestVersion()).isNotEqualTo(adapter.paymentRequestVersion()).isNotEqualTo(rotated.refundRequestVersion());
        for (String version : new String[]{null, "previous-format", adapter.paymentRequestVersion(), rotated.refundRequestVersion()}) {
            assertThatThrownBy(() -> adapter.recoverRefund(refundCommand(), version, now.minusSeconds(60), null))
                    .isInstanceOf(ProviderReplayUnsafeException.class);
        }
        assertThatThrownBy(() -> adapter.recoverRefund(refundCommand(), adapter.refundRequestVersion(), now.plusSeconds(1), null))
                .isInstanceOf(ProviderReplayUnsafeException.class);
        assertThatThrownBy(() -> adapter.recoverRefund(refundCommand(), adapter.refundRequestVersion(), null, null))
                .isInstanceOf(ProviderReplayUnsafeException.class);
        assertThat(transport.requests).isEmpty();
    }

    @Test void knownRefundIdOnlyReadsEvenAfterTheReplayWindow() {
        transport.reply(refund("succeeded"));
        assertThat(provider.recoverRefund(refundCommand(), null, Instant.EPOCH, REF).state()).isEqualTo("REFUNDED");
        assertThat(transport.requests).hasSize(1).allMatch(r -> r.method().equals("GET"));
    }

    @Test void savedRefundCommandRoundTripsExactly() {
        assertThat(RefundCommand.fromJson(refundCommand().toJson())).isEqualTo(refundCommand());
    }

    @ParameterizedTest @ValueSource(strings = {"amount", "intent", "currency", "key", "reference", "method", "multipleMethods", "missingMethod"})
    void manualPaymentReferenceRejectsMismatchedLocalEvidence(String mismatch) {
        JSONObject value = session(PI, "paid", "complete");
        switch (mismatch) {
            case "amount" -> value.put("amount_total", 1);
            case "intent" -> value.getJSONObject("metadata").put("intentId", REFUND);
            case "currency" -> value.put("currency", "eur");
            case "key" -> value.getJSONObject("metadata").put("idempotencyKey", "another-key");
            case "reference" -> value.put("id", "cs_test_other");
            case "method" -> value.put("payment_method_types", List.of("us_bank_account"));
            case "multipleMethods" -> value.put("payment_method_types", List.of("card", "us_bank_account"));
            case "missingMethod" -> value.remove("payment_method_types");
        }
        transport.reply(value);
        assertThat(provider.verifyPaymentReference(command("CARD", 12345), SESSION).state()).isEqualTo("UNKNOWN");
        assertThat(transport.requests).hasSize(1).allMatch(r -> r.method().equals("GET"));
    }

    @Test void manualPaymentReferenceAcceptsOnlyTheExpectedCapturedPayment() {
        transport.reply(session(PI, "paid", "complete"));
        transport.reply(paymentIntent("succeeded", 12345));
        assertThat(provider.verifyPaymentReference(command("CARD", 12345), SESSION))
                .satisfies(result -> {
                    assertThat(result.state()).isEqualTo("CAPTURED");
                    assertThat(result.providerId()).isEqualTo(SESSION);
                });
        assertThat(transport.requests).hasSize(2).allMatch(r -> r.method().equals("GET"));
    }

    @ParameterizedTest @ValueSource(strings = {"method", "multipleMethods", "missingMethod"})
    void manualPaymentReferenceAlsoChecksPaymentIntentMethod(String mismatch) {
        JSONObject intent = paymentIntent("succeeded", 12345);
        switch (mismatch) {
            case "method" -> intent.put("payment_method_types", List.of("us_bank_account"));
            case "multipleMethods" -> intent.put("payment_method_types", List.of("card", "us_bank_account"));
            case "missingMethod" -> intent.remove("payment_method_types");
        }
        transport.reply(session(PI, "paid", "complete"));
        transport.reply(intent);
        assertThat(provider.verifyPaymentReference(command("CARD", 12345), SESSION).state()).isEqualTo("UNKNOWN");
        assertThat(transport.requests).hasSize(2).allMatch(r -> r.method().equals("GET"));
    }

    @Test void manualAchReferenceAcceptsOnlyBankAccountEvidence() {
        transport.reply(session(PI, "unpaid", "complete").put("payment_method_types", List.of("us_bank_account")));
        transport.reply(paymentIntent("processing", 0).put("payment_method_types", List.of("us_bank_account")));
        assertThat(provider.verifyPaymentReference(command("ACH", 12345), SESSION).state()).isEqualTo("PROCESSING");
        assertThat(transport.requests).hasSize(2).allMatch(r -> r.method().equals("GET"));
    }

    @ParameterizedTest @ValueSource(strings = {"amount", "intent", "refund", "currency", "key", "reference"})
    void manualRefundReferenceRejectsMismatchedLocalEvidence(String mismatch) {
        JSONObject value = refund("succeeded");
        switch (mismatch) {
            case "amount" -> value.put("amount", 1);
            case "intent" -> value.getJSONObject("metadata").put("intentId", REFUND);
            case "refund" -> value.getJSONObject("metadata").put("refundId", INTENT);
            case "currency" -> value.put("currency", "eur");
            case "key" -> value.getJSONObject("metadata").put("idempotencyKey", "another-key");
            case "reference" -> value.put("id", "re_other");
        }
        transport.reply(value);
        assertThat(provider.verifyRefundReference(refundCommand(), REF).state()).isEqualTo("UNKNOWN");
        assertThat(transport.requests).hasSize(1).allMatch(r -> r.method().equals("GET"));
    }

    @ParameterizedTest @ValueSource(strings = {"paymentIntent", "checkout", "intent", "currency", "amount", "livemode"})
    void manualRefundReferenceMustBelongToTheOriginalLocalPayment(String mismatch) {
        JSONObject original = session(PI, "paid", "complete");
        switch (mismatch) {
            case "paymentIntent" -> original.put("payment_intent", "pi_other");
            case "checkout" -> original.put("id", "cs_test_other");
            case "intent" -> original.getJSONObject("metadata").put("intentId", REFUND);
            case "currency" -> original.put("currency", "eur");
            case "amount" -> original.put("amount_total", 499);
            case "livemode" -> original.put("livemode", true);
        }
        transport.reply(refund("succeeded"));
        transport.reply(original);
        assertThat(provider.verifyRefundReference(refundCommand(), REF).state()).isEqualTo("UNKNOWN");
        assertThat(transport.requests).hasSize(2).allMatch(r -> r.method().equals("GET"));
        assertThat(transport.requests.get(1).uri().getPath()).isEqualTo("/v1/checkout/sessions/" + SESSION);
    }

    @Test void manualRefundReferenceAcceptsTheExactOperationWithoutReposting() {
        transport.reply(refund("succeeded"));
        transport.reply(session(PI, "paid", "complete"));
        assertThat(provider.verifyRefundReference(refundCommand(), REF))
                .satisfies(result -> {
                    assertThat(result.state()).isEqualTo("REFUNDED");
                    assertThat(result.providerId()).isEqualTo(REF);
                });
        assertThat(transport.requests).hasSize(2).allMatch(r -> r.method().equals("GET"));
    }

    @Test void manualRefundReferenceCannotUseEvidenceWithoutTheOriginalPaymentLookup() {
        transport.reply(refund("succeeded"));
        transport.responses.add(new StripeBillingProvider.Response(503, "{}"));
        assertThatThrownBy(() -> provider.verifyRefundReference(refundCommand(), REF))
                .isInstanceOf(ProviderUncertainException.class);
        assertThat(transport.requests).hasSize(2).allMatch(r -> r.method().equals("GET"));
    }

    @Test void manualReferencesRejectInvalidIdsBeforeNetwork() {
        assertThat(provider.verifyPaymentReference(command("CARD", 12345), "https://evil.example").state()).isEqualTo("UNKNOWN");
        assertThat(provider.verifyRefundReference(refundCommand(), "../other").state()).isEqualTo("UNKNOWN");
        assertThat(transport.requests).isEmpty();
    }

    static ProviderCommand command(String method, long amount) {
        return new ProviderCommand(INTENT, KEY, amount, "USD", method, "http://localhost:4200/billing");
    }
    static RefundCommand refundCommand() { return new RefundCommand(REFUND, INTENT, SESSION, REFUND_KEY, 500, "USD"); }
    static JSONObject metadata(String key) { return new JSONObject().put("intentId", INTENT).put("idempotencyKey", key); }
    static JSONObject session(String pi, String paymentStatus, String status) {
        return new JSONObject().put("id", SESSION).put("object", "checkout.session").put("livemode", false)
                .put("payment_method_types", List.of("card"))
                .put("mode", "payment").put("metadata", metadata(KEY)).put("amount_total", 12345).put("currency", "usd")
                .put("payment_intent", pi == null ? JSONObject.NULL : pi).put("payment_status", paymentStatus)
                .put("status", status).put("url", "https://checkout.stripe.com/c/pay/example");
    }
    static JSONObject paymentIntent(String status, long received) {
        return new JSONObject().put("id", PI).put("object", "payment_intent").put("livemode", false)
                .put("payment_method_types", List.of("card"))
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
        int failOnRequest = -1; // 1-based: the request reaches the provider, its response is lost
        void reply(JSONObject json) { responses.add(new StripeBillingProvider.Response(200, json.toString())); }
        @Override public StripeBillingProvider.Response send(HttpRequest request) throws IOException {
            requests.add(request);
            if (failure != null) throw failure;
            if (requests.size() == failOnRequest) throw new IOException("Response lost after acceptance");
            return responses.removeFirst();
        }
    }
}
