package com.invoicepayments.billing.provider;

import org.json.JSONObject;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Optional;
import java.util.Set;

/** HMAC is over the original bytes. Razorpay has no Stripe-style signed timestamp; inbox identity handles replay. */
public final class RazorpayWebhookVerifier {
    private final String secret;
    private final String expectedAccount;
    public RazorpayWebhookVerifier(String secret, String expectedAccount) {
        this.secret = secret == null ? "" : secret;
        this.expectedAccount = expectedAccount == null ? "" : expectedAccount;
        if (!this.secret.isBlank() && this.expectedAccount.isBlank())
            throw new IllegalArgumentException("Razorpay webhook signing requires its expected merchant account identifier");
        if (!this.expectedAccount.isEmpty() && !RazorpayBillingProvider.validId(this.expectedAccount, "acc_"))
            throw new IllegalArgumentException("Invalid Razorpay merchant account identifier");
    }
    public Optional<ProviderWebhookEvent> verify(byte[] body, String signature, String eventId) {
        if (secret.isBlank()) throw new IllegalStateException("Razorpay test webhook signing is not configured");
        try {
            if (body == null || body.length == 0 || body.length > 1_048_576 || signature == null
                    || !signature.matches("[0-9a-fA-F]{64}") || eventId == null || !eventId.matches("[A-Za-z0-9_\\-]{1,200}"))
                throw new InvalidProviderWebhookException();
            Mac hmac = Mac.getInstance("HmacSHA256");
            hmac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            if (!MessageDigest.isEqual(hmac.doFinal(body), HexFormat.of().parseHex(signature))) throw new InvalidProviderWebhookException();
            String json = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(body)).toString();
            JSONObject envelope = new JSONObject(json);
            if (!"event".equals(envelope.getString("entity"))
                    || !RazorpayBillingProvider.validId(envelope.optString("account_id"), "acc_")
                    || (!expectedAccount.isEmpty() && !expectedAccount.equals(envelope.optString("account_id"))))
                throw new InvalidProviderWebhookException();
            String type = envelope.getString("event");
            if (Set.of("payment.captured", "payment.authorized", "payment.failed", "order.paid").contains(type))
                return Optional.of(payment(envelope, eventId, type));
            if (Set.of("refund.created", "refund.processed", "refund.failed").contains(type))
                return Optional.of(refund(envelope, eventId));
            return Optional.empty();
        } catch (InvalidProviderWebhookException ex) { throw ex; }
        catch (Exception invalid) { throw new InvalidProviderWebhookException(); }
    }
    private ProviderWebhookEvent payment(JSONObject event, String eventId, String type) {
        JSONObject payment = event.getJSONObject("payload").getJSONObject("payment").getJSONObject("entity");
        String orderId = payment.optString("order_id");
        if (!"payment".equals(payment.optString("entity")) || !RazorpayBillingProvider.validId(payment.optString("id"), "pay_")
                || !RazorpayBillingProvider.validId(orderId, "order_")) throw new InvalidProviderWebhookException();
        long amount = RazorpayBillingProvider.minor(payment, "amount");
        String currency = payment.getString("currency");
        if (!currency.matches("[A-Z]{3}")) throw new InvalidProviderWebhookException();
        String status = payment.optString("status");
        String state = "REVIEW";
        if (("payment.captured".equals(type) || "order.paid".equals(type)) && "captured".equals(status)
                && Boolean.TRUE.equals(payment.opt("captured"))) state = "CAPTURED";
        else if ("payment.authorized".equals(type) && "authorized".equals(status)) state = "PROCESSING";
        else if ("payment.failed".equals(type) && "failed".equals(status)) state = "REQUIRES_ACTION";
        if ("order.paid".equals(type)) {
            JSONObject order = event.getJSONObject("payload").getJSONObject("order").getJSONObject("entity");
            if (!orderId.equals(order.optString("id")) || !"order".equals(order.optString("entity"))
                    || !"paid".equals(order.optString("status")) || amount != RazorpayBillingProvider.minor(order, "amount")
                    || amount != RazorpayBillingProvider.minor(order, "amount_paid")
                    || RazorpayBillingProvider.nonnegative(order, "amount_due") != 0 || !currency.equals(order.optString("currency"))) state = "REVIEW";
        }
        // Checkout notes can be supplied by the browser. Resolve the order ID to our intent in the durable inbox.
        return new ProviderWebhookEvent(eventId, orderId, null, state, amount, currency, null, null, payment.getString("id"));
    }
    private ProviderWebhookEvent refund(JSONObject event, String eventId) {
        JSONObject refund = event.getJSONObject("payload").getJSONObject("refund").getJSONObject("entity");
        if (!"refund".equals(refund.optString("entity")) || !RazorpayBillingProvider.validId(refund.optString("id"), "rfnd_")
                || !RazorpayBillingProvider.validId(refund.optString("payment_id"), "pay_")) throw new InvalidProviderWebhookException();
        String currency = refund.getString("currency");
        if (!currency.matches("[A-Z]{3}")) throw new InvalidProviderWebhookException();
        JSONObject notes = refund.optJSONObject("notes");
        String intent = correlation(notes, "intentId"), localRefund = correlation(notes, "refundId");
        // The payment ID and parent order must be checked through the provider API before moving money.
        // This signed receipt is retained; guarded refund recovery performs that read-only verification.
        return new ProviderWebhookEvent(eventId, refund.getString("id"), intent, "REVIEW",
                RazorpayBillingProvider.minor(refund, "amount"), currency, localRefund);
    }
    private static String correlation(JSONObject notes, String key) {
        try { return ProviderValidation.uuid(notes.getString(key)); }
        catch (RuntimeException invalid) { return null; }
    }
}
