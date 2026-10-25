package com.invoicepayments.billing.provider;

import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;

import static com.invoicepayments.billing.provider.StripeBillingProviderTest.*;
import static org.assertj.core.api.Assertions.*;

class StripeWebhookVerifierTest {
    static final String SECRET = "whsec_onlyForIsolatedTests";
    static final long NOW = 1_792_000_000L;
    final StripeWebhookVerifier verifier = new StripeWebhookVerifier(SECRET, Clock.fixed(Instant.ofEpochSecond(NOW), ZoneOffset.UTC));

    @Test void signedPaidSessionProducesExactCanonicalIdentityAndAmount() throws Exception {
        byte[] raw = bytes(event("checkout.session.completed", session(PI, "paid", "complete")));
        ProviderWebhookEvent normalized = verifier.verify(raw, signature(raw, NOW)).orElseThrow();
        assertThat(normalized).isEqualTo(new ProviderWebhookEvent("evt_signed123", SESSION, INTENT, "CAPTURED", 12345, "USD", null));
    }

    @Test void achCheckoutCompletionIsOnlyProcessingUntilAsyncSuccess() throws Exception {
        byte[] raw = bytes(event("checkout.session.completed", session(PI, "unpaid", "complete")));
        assertThat(verifier.verify(raw, signature(raw, NOW)).orElseThrow().state()).isEqualTo("PROCESSING");
        raw = bytes(event("checkout.session.async_payment_succeeded", session(PI, "paid", "complete")));
        assertThat(verifier.verify(raw, signature(raw, NOW)).orElseThrow().state()).isEqualTo("CAPTURED");
    }

    @Test void asynchronousFailureUsesSessionId() throws Exception {
        byte[] raw = bytes(event("checkout.session.async_payment_failed", session(PI, "unpaid", "complete")));
        var normalized = verifier.verify(raw, signature(raw, NOW)).orElseThrow();
        assertThat(normalized.state()).isEqualTo("FAILED");
        assertThat(normalized.providerId()).isEqualTo(SESSION);
    }

    // Stripe tells us when a payment page closes unused. Nothing was paid, so it is a failed payment and frees the invoice.
    @Test void anExpiredUnpaidPaymentPageIsAFailedPayment() throws Exception {
        byte[] raw = bytes(event("checkout.session.expired", session(null, "unpaid", "expired")));
        var normalized = verifier.verify(raw, signature(raw, NOW)).orElseThrow();
        assertThat(normalized).isEqualTo(new ProviderWebhookEvent("evt_signed123", SESSION, INTENT, "FAILED", 12345, "USD", null));
        raw = bytes(event("checkout.session.expired", session(PI, "paid", "complete")));
        assertThat(verifier.verify(raw, signature(raw, NOW)).orElseThrow().state()).as("contradictory evidence is reviewed").isEqualTo("REVIEW");
    }

    @Test void anyRawWhitespaceChangeInvalidatesSignature() throws Exception {
        byte[] raw = bytes(event("checkout.session.completed", session(PI, "paid", "complete")));
        String originalSignature = signature(raw, NOW);
        byte[] changed = (new String(raw, StandardCharsets.UTF_8) + "\n").getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> verifier.verify(changed, originalSignature)).isInstanceOf(InvalidProviderWebhookException.class);
    }

    @Test void wrongSigningKeyIsRejected() throws Exception {
        byte[] raw = bytes(event("checkout.session.completed", session(PI, "paid", "complete")));
        var wrong = new StripeWebhookVerifier("whsec_other", Clock.fixed(Instant.ofEpochSecond(NOW), ZoneOffset.UTC));
        String signed = signature(raw, NOW);
        assertThatThrownBy(() -> wrong.verify(raw, signed)).isInstanceOf(InvalidProviderWebhookException.class);
    }

    @ParameterizedTest @ValueSource(longs = {-301, 301, -10000, 10000})
    void expiredAndFutureSignaturesRejected(long offset) throws Exception {
        byte[] raw = bytes(event("checkout.session.completed", session(PI, "paid", "complete")));
        String signed = signature(raw, NOW + offset);
        assertThatThrownBy(() -> verifier.verify(raw, signed)).isInstanceOf(InvalidProviderWebhookException.class);
    }

    @Test void acceptsOneValidV1AmongRotatedSignaturesButNotDuplicateTimestamps() throws Exception {
        byte[] raw = bytes(event("checkout.session.completed", session(PI, "paid", "complete")));
        String valid = signature(raw, NOW);
        assertThat(verifier.verify(raw, valid + ",v1=" + "0".repeat(64))).isPresent();
        assertThatThrownBy(() -> verifier.verify(raw, valid + ",t=" + NOW)).isInstanceOf(InvalidProviderWebhookException.class);
    }

    @ParameterizedTest @ValueSource(strings = {"", "v1=123", "t=abc,v1=123", "t=9223372036854775807,v1=0000000000000000000000000000000000000000000000000000000000000000"})
    void malformedHeadersAreRejected(String header) {
        assertThatThrownBy(() -> verifier.verify("{}".getBytes(StandardCharsets.UTF_8), header))
                .isInstanceOf(InvalidProviderWebhookException.class).hasMessage("Invalid provider webhook");
    }

    @Test void testAdapterRejectsLiveEventsEvenWithValidSignature() throws Exception {
        byte[] raw = bytes(event("checkout.session.completed", session(PI, "paid", "complete")).put("livemode", true));
        String signed = signature(raw, NOW);
        assertThatThrownBy(() -> verifier.verify(raw, signed)).isInstanceOf(InvalidProviderWebhookException.class);
    }

    // checkout.session.expired is no longer ignored: it frees the invoice (see anExpiredUnpaidPaymentPageIsAFailedPayment).
    @ParameterizedTest @ValueSource(strings = {"payment_intent.succeeded", "charge.refunded"})
    void unsupportedObjectsAreIgnoredWithoutInventingReturnOrMismatchingProviderIds(String type) throws Exception {
        byte[] raw = bytes(event(type, new JSONObject()));
        assertThat(verifier.verify(raw, signature(raw, NOW))).isEmpty();
    }

    @Test void fractionalSignedAmountIsNeverTruncated() throws Exception {
        byte[] raw = bytes(event("checkout.session.completed", session(PI, "paid", "complete").put("amount_total", new java.math.BigDecimal("12345.1"))));
        String signed = signature(raw, NOW);
        assertThat(verifier.verify(raw, signed).orElseThrow().state()).isEqualTo("REVIEW");
        assertThat(verifier.verify(raw, signed).orElseThrow().amountMinor()).isZero();
    }

    @Test void missingAmountNeverProducesFinancialFact() throws Exception {
        JSONObject object = session(PI, "paid", "complete");
        object.remove("amount_total");
        byte[] raw = bytes(event("checkout.session.completed", object));
        String signed = signature(raw, NOW);
        assertThat(verifier.verify(raw, signed).orElseThrow().state()).isEqualTo("REVIEW");
    }

    @Test void unrelatedCurrencyOrMissingIntentCannotEnterInboxAsFinancialFact() throws Exception {
        JSONObject object = session(PI, "paid", "complete").put("currency", "eur");
        byte[] raw = bytes(event("checkout.session.completed", object));
        String signed = signature(raw, NOW);
        assertThat(verifier.verify(raw, signed).orElseThrow().state()).isEqualTo("REVIEW");
        object.put("currency", "usd").getJSONObject("metadata").remove("intentId");
        byte[] noIntent = bytes(event("checkout.session.completed", object));
        String missingSigned = signature(noIntent, NOW);
        var missing = verifier.verify(noIntent, missingSigned).orElseThrow();
        assertThat(missing.state()).isEqualTo("REVIEW");
        assertThat(missing.intentId()).isNull();
    }

    @Test void asyncSuccessWithUnpaidStatusIsRetainedForReview() throws Exception {
        byte[] raw = bytes(event("checkout.session.async_payment_succeeded", session(PI, "unpaid", "complete")));
        String signed = signature(raw, NOW);
        assertThat(verifier.verify(raw, signed).orElseThrow().state()).isEqualTo("REVIEW");
    }

    @Test void succeededRefundUsesRefundProviderIdAndOwnCorrelation() throws Exception {
        byte[] raw = bytes(event("refund.updated", refund("succeeded")));
        var normalized = verifier.verify(raw, signature(raw, NOW)).orElseThrow();
        assertThat(normalized).isEqualTo(new ProviderWebhookEvent("evt_signed123", REF, INTENT, "REFUNDED", 500, "USD", REFUND));
    }

    @Test void pendingAndLaterFailedRefundAreNotReportedAsRefunded() throws Exception {
        byte[] pending = bytes(event("refund.created", refund("pending")));
        assertThat(verifier.verify(pending, signature(pending, NOW)).orElseThrow().state()).isEqualTo("PROCESSING");
        byte[] failed = bytes(event("refund.failed", refund("failed")));
        assertThat(verifier.verify(failed, signature(failed, NOW)).orElseThrow().state()).isEqualTo("FAILED");
    }

    @Test void refundMissingOwnCorrelationNeverBecomesPaymentFailure() throws Exception {
        JSONObject object = refund("failed");
        object.getJSONObject("metadata").remove("refundId");
        byte[] raw = bytes(event("refund.failed", object));
        var normalized = verifier.verify(raw, signature(raw, NOW)).orElseThrow();
        assertThat(normalized.state()).isEqualTo("REVIEW");
        assertThat(normalized.intentId()).isEqualTo(INTENT);
        assertThat(normalized.refundId()).isNull();
        assertThat(normalized.providerId()).isEqualTo(REF);
    }

    @Test void disputeIsReviewEvidenceAndNeverInventsAchReturn() throws Exception {
        JSONObject object = new JSONObject().put("object", "dispute").put("id", "dp_example123")
                .put("amount", 500).put("currency", "usd");
        byte[] raw = bytes(event("charge.dispute.created", object));
        var normalized = verifier.verify(raw, signature(raw, NOW)).orElseThrow();
        assertThat(normalized.state()).isEqualTo("REVIEW");
        assertThat(normalized.intentId()).isNull();
        assertThat(normalized.amountMinor()).isEqualTo(500);
    }

    @Test void numericStringAmountIsRetainedForReviewRatherThanCoercedToMoney() throws Exception {
        byte[] raw = bytes(event("checkout.session.completed", session(PI, "paid", "complete").put("amount_total", "12345")));
        var normalized = verifier.verify(raw, signature(raw, NOW)).orElseThrow();
        assertThat(normalized.state()).isEqualTo("REVIEW");
        assertThat(normalized.amountMinor()).isZero();
    }

    static JSONObject event(String type, JSONObject object) {
        return new JSONObject().put("id", "evt_signed123").put("object", "event").put("livemode", false)
                .put("type", type).put("data", new JSONObject().put("object", object));
    }
    static byte[] bytes(JSONObject event) { return event.toString().getBytes(StandardCharsets.UTF_8); }
    static String signature(byte[] raw, long timestamp) throws Exception {
        Mac hmac = Mac.getInstance("HmacSHA256");
        hmac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        hmac.update((timestamp + ".").getBytes(StandardCharsets.US_ASCII));
        return "t=" + timestamp + ",v1=" + HexFormat.of().formatHex(hmac.doFinal(raw));
    }
}
