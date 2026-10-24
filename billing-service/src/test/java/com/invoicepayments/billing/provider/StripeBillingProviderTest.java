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
import java.util.UUID;
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
        // Stripe reports the bank-debit method on an ACH session; the adapter checks it.
        transport.reply(session(null, "unpaid", "open").put("payment_method_types", List.of("us_bank_account")));
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

    // Nothing was sent, so the caller may treat these as definite failures.
    @ParameterizedTest @ValueSource(longs = {-1, 0, 49, 100000000})
    void invalidUsdAmountsFailBeforeNetwork(long amount) {
        assertThatThrownBy(() -> provider.submit(command("CARD", amount))).isInstanceOf(ProviderRejectedException.class);
        assertThat(transport.requests).isEmpty();
    }

    @ParameterizedTest @ValueSource(strings = {"http://evil.example/return", "https://user:password@example.com/return", "javascript:alert(1)"})
    void unsafeReturnUrlsFailBeforeNetwork(String url) {
        assertThatThrownBy(() -> provider.submit(new ProviderCommand(INTENT, KEY, 12345, "USD", "CARD", url)))
                .isInstanceOf(ProviderRejectedException.class);
        assertThat(transport.requests).isEmpty();
    }

    // A failed bank debit after a completed Checkout: the session stays complete and unpaid, so only the debit's own
    // failed charge proves the payment failed.
    @Test void failedAchDebitAfterCompletedCheckoutFailsOnlyWithItsFailedCharge() {
        var ach = List.of("us_bank_account");
        JSONObject completed = session(PI, "unpaid", "complete").put("payment_method_types", ach);
        JSONObject retry = paymentIntent("requires_payment_method", 0).put("payment_method_types", ach).put("latest_charge", "ch_3TestFailed1");
        JSONObject charge = new JSONObject().put("id", "ch_3TestFailed1").put("object", "charge").put("livemode", false)
                .put("payment_intent", PI).put("status", "failed").put("paid", false).put("amount", 12345).put("currency", "usd")
                .put("failure_code", "insufficient_funds").put("payment_method_details", new JSONObject().put("type", "us_bank_account"));
        transport.reply(completed); transport.reply(retry); transport.reply(charge);
        assertThat(provider.lookup(KEY, SESSION).state()).isEqualTo("FAILED");
        // A debit that is still settling is not a failure, even though the session is complete and unpaid.
        transport.reply(completed); transport.reply(retry); transport.reply(new JSONObject(charge.toString()).put("status", "pending"));
        assertThat(provider.lookup(KEY, SESSION).state()).isEqualTo("UNKNOWN");
        // A completed but unpaid card session is never treated as a failed bank debit.
        transport.reply(session(PI, "unpaid", "complete")); transport.reply(paymentIntent("requires_payment_method", 0));
        assertThat(provider.lookup(KEY, SESSION).state()).isEqualTo("UNKNOWN");
    }

    // The provider call gets only the time left on the worker's lease: an expired lease sends nothing.
    @Test void anExpiredLeaseStopsTheProviderCallBeforeAnythingIsSent() {
        try (var expired = com.invoicepayments.billing.BillingExecutionBudget.lease(Instant.now().minusSeconds(1))) {
            assertThatThrownBy(() -> provider.submit(command("CARD", 12345))).isInstanceOf(ProviderUncertainException.class)
                    .isNotInstanceOf(ProviderRejectedException.class);
        }
        assertThat(transport.requests).isEmpty();
    }

    @Test void onlyResponsesThatProveNothingHappenedAreRejections() {
        transport.responses.add(new StripeBillingProvider.Response(401, "{}"));
        assertThatThrownBy(() -> provider.submit(command("CARD", 12345))).isInstanceOf(ProviderRejectedException.class);
        transport.responses.add(new StripeBillingProvider.Response(403, "{}"));
        assertThatThrownBy(() -> provider.submit(command("CARD", 12345))).isInstanceOf(ProviderRejectedException.class);
        // A broad invalid_request_error, a reused key with other parameters, a conflict, a rate limit, a server error
        // or an unreadable body prove nothing about whether Stripe began executing the request.
        for (var response : List.of(new StripeBillingProvider.Response(400, "{\"error\":{\"type\":\"invalid_request_error\"}}"),
                new StripeBillingProvider.Response(400, "{\"error\":{\"type\":\"idempotency_error\"}}"),
                new StripeBillingProvider.Response(400, "not json"), new StripeBillingProvider.Response(409, "{}"),
                new StripeBillingProvider.Response(429, "{}"), new StripeBillingProvider.Response(500, "{}"))) {
            transport.responses.add(response);
            assertThatThrownBy(() -> provider.submit(command("CARD", 12345))).isInstanceOf(ProviderUncertainException.class)
                    .isNotInstanceOf(ProviderRejectedException.class);
        }
    }

    // A release must first make the session unpayable, then prove nothing was paid.
    @Test void releaseCheckExpiresAnOpenSessionBeforeReportingNoPayment() {
        transport.reply(session(null, "unpaid", "open"));
        transport.reply(session(null, "unpaid", "expired"));
        ProviderResult result = provider.closePayment(command("CARD", 12345), provider.paymentRequestVersion(), Instant.now(), SESSION);
        assertThat(result.state()).isEqualTo("ABSENT");
        assertThat(result.providerId()).isEqualTo(SESSION);
        assertThat(transport.requests).extracting(r -> r.method() + " " + r.uri().getPath())
                .containsExactly("GET /v1/checkout/sessions/" + SESSION, "POST /v1/checkout/sessions/" + SESSION + "/expire");
    }

    @Test void releaseCheckReportsAPaymentThatCompletedBeforeTheSessionCouldExpire() {
        transport.reply(session(null, "unpaid", "open"));
        transport.responses.add(new StripeBillingProvider.Response(400, "{\"error\":{\"type\":\"invalid_request_error\"}}"));
        transport.reply(session(PI, "paid", "complete"));
        transport.reply(paymentIntent("succeeded", 12345));
        ProviderResult result = provider.closePayment(command("CARD", 12345), provider.paymentRequestVersion(), Instant.now(), SESSION);
        assertThat(result.state()).isEqualTo("CAPTURED");
        assertThat(result.providerId()).isEqualTo(SESSION);
    }

    @Test void releaseCheckWithoutASessionIdSearchesTheWholeReplayWindow() {
        Instant prepared = Instant.parse("2026-10-21T08:00:00Z");
        transport.reply(list(true, otherSession("cs_test_other1")));
        transport.reply(list(false));
        ProviderResult result = provider.closePayment(command("CARD", 12345), provider.paymentRequestVersion(), prepared, null);
        // An empty search is not proof of absence; it only makes the case eligible for a recorded Stripe confirmation.
        assertThat(result.state()).isEqualTo("UNKNOWN");
        assertThat(result.finding()).isEqualTo(ProviderResult.SEARCH_EMPTY);
        assertThat(transport.requests.get(0).uri().getRawQuery())
                .contains("created%5Bgte%5D=" + prepared.minusSeconds(300).getEpochSecond())
                .contains("created%5Blte%5D=" + prepared.plus(Duration.ofHours(24)).getEpochSecond());
        assertThat(transport.requests.get(1).uri().getRawQuery()).contains("starting_after=cs_test_other1");
        transport.requests.clear();
        transport.reply(list(false, otherSession("cs_test_other2"), session(null, "unpaid", "open")));
        transport.reply(session(null, "unpaid", "expired"));
        assertThat(provider.closePayment(command("CARD", 12345), provider.paymentRequestVersion(), prepared, null))
                .satisfies(found -> assertThat(found.state()).isEqualTo("ABSENT"))
                .satisfies(found -> assertThat(found.providerId()).isEqualTo(SESSION));
        assertThat(transport.requests).extracting(HttpRequest::method).containsExactly("GET", "POST");
    }

    @Test void releaseCheckGivesUpRatherThanClaimingAbsenceItCouldNotCheck() {
        for (int page = 0; page < 8; page++) transport.reply(list(true, otherSession("cs_test_page" + page)));
        assertThatThrownBy(() -> provider.closePayment(command("CARD", 12345), provider.paymentRequestVersion(), Instant.now(), null))
                .isInstanceOf(ProviderUncertainException.class);
        assertThat(transport.requests).hasSize(8);
    }

    @Test void refundReleaseCheckListsTheOriginalPaymentsRefunds() {
        transport.reply(session(PI, "paid", "complete"));
        transport.reply(list(false, refund("succeeded")));
        assertThat(provider.findRefund(refundCommand(), null).state()).isEqualTo("REFUNDED");
        assertThat(transport.requests.get(1).uri().getRawQuery()).contains("payment_intent=" + PI);
        transport.reply(session(PI, "paid", "complete"));
        transport.reply(list(false, refund("succeeded").put("id", "re_other").put("metadata", metadata(REFUND_KEY).put("refundId", UUID.randomUUID().toString()))));
        assertThat(provider.findRefund(refundCommand(), null))
                .satisfies(found -> assertThat(found.state()).isEqualTo("UNKNOWN"))
                .satisfies(found -> assertThat(found.finding()).isEqualTo(ProviderResult.SEARCH_EMPTY));
    }

    // Stripe saves a first server error with its idempotency key and repeats it on every replay: search instead.
    @Test void replayThatKeepsGettingTheSavedServerErrorFindsTheSessionBySearching() {
        Instant now = Instant.parse("2026-10-21T08:00:00Z");
        var adapter = new StripeBillingProvider("sk_test_exampleOnly", transport, Clock.fixed(now, ZoneOffset.UTC));
        transport.responses.add(new StripeBillingProvider.Response(500, "{}"));
        transport.reply(list(false, session(null, "unpaid", "open")));
        transport.reply(session(null, "unpaid", "open"));
        ProviderResult result = adapter.recoverPayment(command("CARD", 12345), adapter.paymentRequestVersion(), now.minusSeconds(60), null);
        assertThat(result.state()).isEqualTo("REQUIRES_ACTION");
        assertThat(result.providerId()).isEqualTo(SESSION);
        assertThat(transport.requests).extracting(HttpRequest::method).containsExactly("POST", "GET", "GET");
        transport.responses.add(new StripeBillingProvider.Response(500, "{}"));
        transport.reply(list(false));
        assertThatThrownBy(() -> adapter.recoverPayment(command("CARD", 12345), adapter.paymentRequestVersion(), now.minusSeconds(60), null))
                .isInstanceOf(ProviderUncertainException.class);
    }

    @Test void refundReplayThatKeepsGettingTheSavedServerErrorFindsTheRefundBySearching() {
        Instant now = Instant.parse("2026-10-21T08:00:00Z");
        var adapter = new StripeBillingProvider("sk_test_exampleOnly", transport, Clock.fixed(now, ZoneOffset.UTC));
        transport.reply(session(PI, "paid", "complete"));
        transport.reply(paymentIntent("succeeded", 12345));
        transport.responses.add(new StripeBillingProvider.Response(500, "{}"));
        transport.reply(session(PI, "paid", "complete"));
        transport.reply(list(false, refund("succeeded")));
        ProviderResult result = adapter.recoverRefund(refundCommand(), adapter.refundRequestVersion(), now.minusSeconds(60), null);
        assertThat(result.state()).isEqualTo("REFUNDED");
        assertThat(result.providerId()).isEqualTo(REF);
    }

    // Gap A: an empty search proves nothing about an account the current key may not reach.
    @Test void releaseSearchIsRefusedWhenTheCredentialsChangedSinceThePaymentWasSent() {
        var other = new StripeBillingProvider("sk_test_differentAccount", transport);
        transport.reply(list(false));
        transport.reply(list(false));
        // A request sent with other credentials, or with no recorded version at all, cannot be searched for here.
        for (String version : new String[]{other.paymentRequestVersion(), null}) {
            assertThat(provider.closePayment(command("CARD", 12345), version, Instant.now(), null))
                    .satisfies(found -> assertThat(found.state()).isEqualTo("UNKNOWN"))
                    .satisfies(found -> assertThat(found.finding()).isEqualTo(ProviderResult.CREDENTIALS_CHANGED));
        }
        assertThat(transport.requests).isEmpty();
    }

    @Test void releaseByKnownSessionStillWorksAfterACredentialChange() {
        // A lookup by ID on the wrong account fails, so reaching the session proves it is the right account.
        var other = new StripeBillingProvider("sk_test_differentAccount", transport);
        transport.reply(session(null, "unpaid", "open"));
        transport.reply(session(null, "unpaid", "expired"));
        assertThat(provider.closePayment(command("CARD", 12345), other.paymentRequestVersion(), Instant.now(), SESSION).state())
                .isEqualTo("ABSENT");
    }

    // Gap C: one provider operation, including its searches, stays inside a budget well under the 120-second lease.
    @Test void releaseSearchStopsWhenTheOperationBudgetIsUsed() {
        var clock = new MutableClock(Instant.parse("2026-10-21T08:00:00Z"));
        var adapter = new StripeBillingProvider("sk_test_exampleOnly", transport, clock);
        transport.onSend = () -> clock.advance(Duration.ofSeconds(10));
        for (int page = 0; page < 8; page++) transport.reply(list(true, otherSession("cs_test_slow" + page)));
        assertThatThrownBy(() -> adapter.closePayment(command("CARD", 12345), adapter.paymentRequestVersion(), clock.instant(), null))
                .isInstanceOf(ProviderUncertainException.class);
        assertThat(StripeBillingProvider.OPERATION_BUDGET).isEqualTo(Duration.ofSeconds(60));
        assertThat(transport.requests).hasSize(6);
        // Each request may only wait for the time the operation has left.
        assertThat(transport.requests.get(5).timeout()).hasValue(Duration.ofSeconds(10));
    }

    @Test void refundRecoveryFallbackSearchStopsWhenTheOperationBudgetIsUsed() {
        var clock = new MutableClock(Instant.parse("2026-10-21T08:00:00Z"));
        var adapter = new StripeBillingProvider("sk_test_exampleOnly", transport, clock);
        transport.onSend = () -> clock.advance(Duration.ofSeconds(10));
        transport.reply(session(PI, "paid", "complete"));
        transport.reply(paymentIntent("succeeded", 12345));
        transport.responses.add(new StripeBillingProvider.Response(500, "{}"));
        transport.reply(session(PI, "paid", "complete"));
        for (int page = 0; page < 8; page++) {
            transport.reply(list(true, refund("succeeded").put("id", "re_slow" + page)
                    .put("metadata", metadata(REFUND_KEY).put("refundId", UUID.randomUUID().toString()))));
        }
        assertThatThrownBy(() -> adapter.recoverRefund(refundCommand(), adapter.refundRequestVersion(), clock.instant().minusSeconds(60), null))
                .isInstanceOf(ProviderUncertainException.class);
        assertThat(transport.requests).hasSize(6);
    }

    /** A clock the test moves forward, to act out slow provider responses without waiting. */
    static final class MutableClock extends Clock {
        private Instant now;
        MutableClock(Instant start) { now = start; }
        void advance(Duration step) { now = now.plus(step); }
        @Override public Instant instant() { return now; }
        @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
    }

    static JSONObject list(boolean hasMore, JSONObject... items) {
        return new JSONObject().put("object", "list").put("has_more", hasMore).put("data", List.of(items));
    }
    static JSONObject otherSession(String id) {
        return session(null, "unpaid", "open").put("id", id)
                .put("metadata", new JSONObject().put("intentId", UUID.randomUUID().toString()).put("idempotencyKey", "billing_other"));
    }

    @Test void refundRefusedBeforeItsPostIsARejection() {
        transport.responses.add(new StripeBillingProvider.Response(401, "{}"));
        assertThatThrownBy(() -> provider.refund(refundCommand())).isInstanceOf(ProviderRejectedException.class);
        assertThat(transport.requests).hasSize(1);
        assertThat(transport.requests.get(0).method()).isEqualTo("GET");
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
        Runnable onSend = () -> {}; // for example, advance a test clock as if Stripe were slow
        void reply(JSONObject json) { responses.add(new StripeBillingProvider.Response(200, json.toString())); }
        @Override public StripeBillingProvider.Response send(HttpRequest request) throws IOException {
            requests.add(request);
            onSend.run();
            if (failure != null) throw failure;
            if (requests.size() == failOnRequest) throw new IOException("Response lost after acceptance");
            return responses.removeFirst();
        }
    }
}
