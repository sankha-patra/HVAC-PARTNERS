package com.invoicepayments.billing.provider;

import org.json.JSONObject;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Verifies the unmodified request bytes before JSON parsing. Durable replay detection belongs to the inbox. */
public final class StripeWebhookVerifier {
    private final String secret;
    private final Clock clock;

    public StripeWebhookVerifier(String webhookSecret) { this(webhookSecret, Clock.systemUTC()); }

    public StripeWebhookVerifier(String webhookSecret, Clock clock) {
        secret = webhookSecret == null ? "" : webhookSecret.trim();
        if (!secret.isEmpty() && !secret.startsWith("whsec_")) throw new IllegalArgumentException("A Stripe webhook signing secret is required");
        this.clock = Objects.requireNonNull(clock);
    }

    public Optional<ProviderWebhookEvent> verify(byte[] rawBody, String signatureHeader) {
        if (secret.isEmpty()) throw new IllegalStateException("Stripe webhook signing is not configured");
        try {
            if (rawBody == null || rawBody.length == 0 || rawBody.length > 1_048_576
                    || signatureHeader == null || signatureHeader.length() > 8192) throw new InvalidProviderWebhookException();
            String timestamp = null;
            List<byte[]> signatures = new ArrayList<>();
            for (String part : signatureHeader.split(",")) {
                String[] pair = part.trim().split("=", 2);
                if (pair.length != 2) continue;
                if ("t".equals(pair[0])) {
                    if (timestamp != null || !pair[1].matches("[0-9]{1,19}")) throw new InvalidProviderWebhookException();
                    timestamp = pair[1];
                } else if ("v1".equals(pair[0]) && pair[1].matches("[a-fA-F0-9]{64}")) {
                    signatures.add(HexFormat.of().parseHex(pair[1]));
                }
            }
            if (timestamp == null || signatures.isEmpty()) throw new InvalidProviderWebhookException();
            long signedAt = Long.parseLong(timestamp);
            long now = clock.instant().getEpochSecond();
            if (signedAt < now - 300 || signedAt > now + 300) throw new InvalidProviderWebhookException();
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            mac.update((timestamp + ".").getBytes(StandardCharsets.US_ASCII));
            byte[] expected = mac.doFinal(rawBody);
            boolean valid = false;
            for (byte[] signature : signatures) valid |= MessageDigest.isEqual(expected, signature);
            if (!valid) throw new InvalidProviderWebhookException();
            String json = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(rawBody)).toString();
            return normalize(new JSONObject(json));
        } catch (InvalidProviderWebhookException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new InvalidProviderWebhookException();
        }
    }

    private Optional<ProviderWebhookEvent> normalize(JSONObject event) {
        if (!"event".equals(event.getString("object")) || !Boolean.FALSE.equals(event.get("livemode"))
                || !StripeBillingProvider.validId(event.getString("id"), "evt_")) throw new InvalidProviderWebhookException();
        String type = event.getString("type");
        boolean payment = type.equals("checkout.session.completed") || type.equals("checkout.session.async_payment_succeeded")
                || type.equals("checkout.session.async_payment_failed") || type.equals("checkout.session.expired");
        boolean refund = type.equals("refund.created") || type.equals("refund.updated") || type.equals("refund.failed");
        boolean dispute = type.startsWith("charge.dispute.");
        // PI identifiers differ from the persisted Checkout Session identity. Disputes require review.
        if (!payment && !refund && !dispute) return Optional.empty();
        JSONObject object = event.getJSONObject("data").getJSONObject("object");
        String prefix = payment ? "cs_test_" : refund ? "re_" : "dp_";
        String providerId = object.getString("id");
        if (!StripeBillingProvider.validId(providerId, prefix)) throw new InvalidProviderWebhookException();
        String intentId = correlation(object, "intentId");
        String refundId = refund ? correlation(object, "refundId") : null;
        long amount = evidenceAmount(object, payment ? "amount_total" : "amount");
        String state = "REVIEW";
        // Retain signed but unmatched/contradictory evidence for operations review, never invent money.
        if (dispute || amount == 0 || intentId == null || (refund && refundId == null)) {
            return Optional.of(new ProviderWebhookEvent(event.getString("id"), providerId, intentId,
                    state, amount, "USD", refundId));
        }
        if (payment) {
            if (!"checkout.session".equals(object.optString("object"))
                    || !Boolean.FALSE.equals(object.opt("livemode")) || !"payment".equals(object.optString("mode"))) {
                return Optional.of(new ProviderWebhookEvent(event.getString("id"), providerId, intentId, "REVIEW", amount, "USD", null));
            }
            String paymentStatus = object.optString("payment_status");
            if (type.equals("checkout.session.async_payment_failed")) {
                state = "unpaid".equals(paymentStatus) ? "FAILED" : "REVIEW";
            } else if (type.equals("checkout.session.expired")) {
                // The payment page closed unused: nothing was paid, so the invoice can be freed. Anything else is reviewed.
                state = "expired".equals(object.optString("status")) && "unpaid".equals(paymentStatus) ? "FAILED" : "REVIEW";
            } else {
                // Completing the hosted form is not a successful ACH debit.
                state = "paid".equals(paymentStatus) ? "CAPTURED" : "unpaid".equals(paymentStatus) ? "PROCESSING" : "REVIEW";
                if (type.equals("checkout.session.async_payment_succeeded") && !"CAPTURED".equals(state)) {
                    state = "REVIEW";
                }
            }
        } else {
            if ("refund".equals(object.optString("object"))) {
                try { state = StripeBillingProvider.refundState(object); }
                catch (RuntimeException ignored) { state = "REVIEW"; }
            }
            if ("UNKNOWN".equals(state) || (type.equals("refund.failed") && !"FAILED".equals(state))) state = "REVIEW";
        }
        return Optional.of(new ProviderWebhookEvent(event.getString("id"), providerId, intentId, state, amount, "USD", refundId));
    }

    private static String correlation(JSONObject object, String key) {
        try { return ProviderValidation.uuid(object.getJSONObject("metadata").getString(key)); }
        catch (RuntimeException ex) { return null; }
    }

    private static long evidenceAmount(JSONObject object, String key) {
        try { return StripeBillingProvider.amount(object, key); }
        catch (RuntimeException ex) { return 0; }
    }
}
