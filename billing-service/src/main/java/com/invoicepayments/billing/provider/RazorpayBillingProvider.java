package com.invoicepayments.billing.provider;

import com.invoicepayments.billing.BillingExecutionBudget;
import com.invoicepayments.billing.BillingStore;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;

/**
 * Test-mode Razorpay Orders. The financial core stores the immutable order ID, not a Checkout session or
 * an individual failed payment attempt. POST is used once by the initial owner; all recovery is read-only.
 */
public final class RazorpayBillingProvider implements BillingProvider {
    private static final URI API = URI.create("https://api.razorpay.com");
    private static final Duration OPERATION_TIMEOUT = Duration.ofSeconds(45);
    private static final int PAGE_SIZE = 100, MAX_PAGES = 10;
    private final String keyId, secret, fingerprint;
    private final Transport transport;
    private final Semaphore capacity = new Semaphore(8, true);
    private final ThreadLocal<Long> deadline = new ThreadLocal<>();

    public RazorpayBillingProvider(String keyId, String secret) { this(keyId, secret, new JdkTransport()); }
    RazorpayBillingProvider(String keyId, String secret, Transport transport) {
        if (keyId == null || !keyId.matches("rzp_test_[A-Za-z0-9]{3,100}") || secret == null || secret.isBlank()
                || !secret.equals(secret.trim()) || secret.length() > 512)
            throw new IllegalArgumentException("Razorpay requires a test-mode key pair");
        this.keyId = keyId; this.secret = secret; this.transport = Objects.requireNonNull(transport);
        this.fingerprint = digest(keyId + ":" + secret);
    }
    @Override public String name() { return "RAZORPAY"; }
    public String publicKeyId() { return keyId; }
    @Override public String paymentRequestVersion() { return "razorpay-orders-v1:" + fingerprint; }
    @Override public String refundRequestVersion() { return "razorpay-refunds-v1:" + fingerprint; }

    @Override public ProviderResult submit(ProviderCommand command) {
        return operation(() -> {
            validate(command);
            JSONObject request = new JSONObject().put("amount", command.amountMinor()).put("currency", command.currency())
                    .put("partial_payment", false).put("receipt", receipt(command.intentId()))
                    .put("notes", new JSONObject().put("intentId", command.intentId()).put("operationKey", command.idempotencyKey()));
            JSONObject order = request("POST", "/v1/orders", request, true);
            verifyOrder(order, command);
            return orderResult(order);
        });
    }
    @Override public ProviderResult submitSaved(ProviderCommand command, String version, Instant preparedAt) {
        if (!paymentRequestVersion().equals(version)) return unknown(null, "Provider configuration changed; original operation must be reconciled");
        return submit(command);
    }
    @Override public ProviderResult recoverPayment(ProviderCommand saved, String version, Instant preparedAt, String providerId) {
        return operation(() -> {
            if (!paymentRequestVersion().equals(version)) return unknown(providerId, "Original provider configuration is required");
            validate(saved);
            return providerId == null ? discover(saved) : verifiedOrder(saved, providerId);
        });
    }
    @Override public ProviderResult discoverPayment(ProviderCommand saved, String version, Instant preparedAt) {
        return recoverPayment(saved, version, preparedAt, null);
    }
    @Override public ProviderResult verifyPaymentReference(ProviderCommand expected, String providerId) {
        return operation(() -> { validate(expected); return verifiedOrder(expected, providerId); });
    }
    @Override public ProviderResult lookup(String operationKey, String providerId) {
        // A reference alone does not carry the authorized amount or currency. Never derive expectations from the response.
        return unknown(providerId, "The original saved amount and currency are required for Razorpay verification");
    }
    private ProviderResult verifiedOrder(ProviderCommand command, String orderId) {
        requireId(orderId, "order_");
        JSONObject order = request("GET", "/v1/orders/" + orderId, null, false);
        if (!orderId.equals(order.optString("id"))) throw uncertain();
        verifyOrder(order, command);
        return orderResult(order);
    }
    private ProviderResult discover(ProviderCommand saved) {
        JSONObject match = null;
        String receipt = receipt(saved.intentId());
        Set<String> seen = new HashSet<>();
        for (int page = 0; page < MAX_PAGES; page++) {
            JSONArray items = collection(request("GET", "/v1/orders?receipt=" + encode(receipt) + "&count=" + PAGE_SIZE + "&skip=" + page * PAGE_SIZE, null, false));
            for (Object value : items) {
                JSONObject candidate = (JSONObject) value;
                if (!receipt.equals(candidate.optString("receipt"))) continue;
                verifyOrder(candidate, saved);
                if (!seen.add(candidate.getString("id"))) return unknown(null, "Repeated search rows require manual reconciliation");
                if (match != null) return unknown(null, "Multiple matching orders require manual reconciliation");
                match = candidate;
            }
            if (items.length() < PAGE_SIZE) return match == null
                    ? unknown(null, "No exact order was found; this does not prove that creation failed")
                    : verifiedOrder(saved, match.getString("id"));
        }
        return unknown(null, "Order discovery exceeded its bound; no new order was submitted");
    }
    private static void verifyOrder(JSONObject order, ProviderCommand expected) {
        JSONObject notes = order.optJSONObject("notes");
        if (!"order".equals(order.optString("entity")) || !validId(order.optString("id"), "order_")
                || !receipt(expected.intentId()).equals(order.optString("receipt")) || notes == null
                || !expected.intentId().equals(notes.optString("intentId")) || !expected.idempotencyKey().equals(notes.optString("operationKey"))
                || expected.amountMinor() != minor(order, "amount") || !expected.currency().equals(order.optString("currency"))
                || Boolean.TRUE.equals(order.opt("partial_payment"))) throw uncertain();
    }
    private ProviderResult orderResult(JSONObject order) {
        String id = order.getString("id"), state = order.optString("status");
        long total = minor(order, "amount"), paid = nonnegative(order, "amount_paid"), due = nonnegative(order, "amount_due");
        if (paid + due != total) throw uncertain();
        if ("paid".equals(state) && paid == total && due == 0) {
            JSONObject payment=capturedPayment(order);
            return new ProviderResult(id, "CAPTURED", null, "Razorpay capture verified; settlement is separate",null,null,payment.getString("id"));
        }
        if (Set.of("created", "attempted").contains(state) && paid == 0 && due == total)
            return new ProviderResult(id, "REQUIRES_ACTION", null, "Use the existing Razorpay order for checkout");
        return unknown(id, "Razorpay order state requires reconciliation");
    }
    private JSONObject capturedPayment(JSONObject order) {
        String orderId = order.getString("id");
        JSONObject capture = null;
        for (int page = 0; page < MAX_PAGES; page++) {
            JSONArray items = collection(request("GET", "/v1/orders/" + orderId + "/payments?count=" + PAGE_SIZE + "&skip=" + page * PAGE_SIZE, null, false));
            for (Object item : items) {
                JSONObject payment = (JSONObject) item;
                if (!"payment".equals(payment.optString("entity")) || !validId(payment.optString("id"), "pay_")
                        || !orderId.equals(payment.optString("order_id"))) throw uncertain();
                if (!Set.of("captured", "refunded").contains(payment.optString("status"))) continue;
                if (capture != null || !Boolean.TRUE.equals(payment.opt("captured"))
                        || minor(payment, "amount") != minor(order, "amount")
                        || !order.getString("currency").equals(payment.optString("currency"))) throw uncertain();
                capture = payment;
            }
            if (items.length() < PAGE_SIZE) {
                if (capture == null) throw uncertain();
                return capture;
            }
        }
        throw uncertain();
    }
    @Override public ProviderResult closePayment(ProviderCommand saved, String version, Instant preparedAt, String providerId) {
        // Orders have no Checkout Session expiry endpoint. Never manufacture ABSENT or permit release.
        ProviderResult result = recoverPayment(saved, version, preparedAt, providerId);
        return "CAPTURED".equals(result.state()) ? result : unknown(result.providerId(), "Razorpay orders cannot be expired by this adapter; release requires review");
    }
    @Override public ProviderResult refund(RefundCommand command) {
        return operation(() -> {
            JSONObject payment = originalPayment(command);
            JSONObject body = new JSONObject().put("amount", command.amountMinor()).put("speed", "normal")
                    .put("receipt", refundReceipt(command.refundId())).put("notes", new JSONObject()
                    .put("refundId", command.refundId()).put("intentId", command.intentId())
                    .put("orderId", command.providerPaymentId()).put("operationKey", command.idempotencyKey()));
            JSONObject refund = request("POST", "/v1/payments/" + payment.getString("id") + "/refund", body, true);
            return verifyRefund(refund, command, payment.getString("id"));
        });
    }
    @Override public ProviderResult recoverRefund(RefundCommand saved, String version, Instant preparedAt, String providerId) {
        return operation(() -> {
            if (!refundRequestVersion().equals(version)) return unknown(providerId, "Original refund provider configuration is required");
            return findRefund(saved, providerId);
        });
    }
    @Override public ProviderResult verifyRefundReference(RefundCommand expected, String providerId) {
        return operation(() -> {
            requireId(providerId, "rfnd_");
            JSONObject payment = originalPayment(expected);
            return fetchedRefund(providerId, expected, payment.getString("id"));
        });
    }
    @Override public ProviderResult lookupRefund(String operationKey, String providerId) {
        // Without the saved original order, amount and refund identity, a reference alone is insufficient.
        return unknown(providerId, "Use the saved refund request to verify its original payment");
    }
    @Override public ProviderResult findRefund(RefundCommand saved, String providerId) {
        return operation(() -> {
            JSONObject payment = originalPayment(saved);
            String paymentId = payment.getString("id");
            if (providerId != null) {
                requireId(providerId, "rfnd_");
                return fetchedRefund(providerId, saved, paymentId);
            }
            JSONObject found = null;
            for (int page = 0; page < MAX_PAGES; page++) {
                JSONArray items = collection(request("GET", "/v1/payments/" + paymentId + "/refunds?count=" + PAGE_SIZE + "&skip=" + page * PAGE_SIZE, null, false));
                for (Object item : items) {
                    JSONObject refund = (JSONObject) item;
                    if (!refundReceipt(saved.refundId()).equals(refund.optString("receipt"))) continue;
                    verifyRefund(refund, saved, paymentId);
                    if (found != null) return unknown(null, "Multiple matching refunds require review");
                    found = refund;
                }
                if (items.length() < PAGE_SIZE) return found == null
                        ? unknown(null, "No matching refund found; do not submit a replacement") : verifyRefund(found, saved, paymentId);
            }
            return unknown(null, "Refund search exceeded its bound");
        });
    }
    private JSONObject originalPayment(RefundCommand saved) {
        ProviderValidation.uuid(saved.intentId()); ProviderValidation.uuid(saved.refundId());
        if(!("refund_"+saved.refundId()).equals(saved.idempotencyKey()))throw new ProviderRejectedException("A stable original refund identity is required");
        requireId(saved.providerPaymentId(), "order_");
        if (saved.amountMinor() < 1 || saved.amountMinor() > 99_999_999 || !"INR".equals(saved.currency()))
            throw new ProviderRejectedException("Unsupported Razorpay INR refund amount");
        JSONObject order = request("GET", "/v1/orders/" + saved.providerPaymentId(), null, false);
        ProviderCommand expected = new ProviderCommand(saved.intentId(), "billing_" + saved.intentId(), minor(order, "amount"), saved.currency(), "CARD", null);
        verifyOrder(order, expected);
        if (!saved.providerPaymentId().equals(order.optString("id")) || !"paid".equals(order.optString("status"))
                || saved.amountMinor() > minor(order, "amount")) throw uncertain();
        return capturedPayment(order);
    }
    private ProviderResult fetchedRefund(String providerId, RefundCommand expected, String paymentId) {
        JSONObject refund = request("GET", "/v1/refunds/" + providerId, null, false);
        if (!providerId.equals(refund.optString("id"))) throw uncertain();
        return verifyRefund(refund, expected, paymentId);
    }
    private ProviderResult verifyRefund(JSONObject refund, RefundCommand expected, String paymentId) {
        JSONObject notes = refund.optJSONObject("notes");
        if (!"refund".equals(refund.optString("entity")) || !validId(refund.optString("id"), "rfnd_")
                || !paymentId.equals(refund.optString("payment_id")) || expected.amountMinor() != minor(refund, "amount")
                || !expected.currency().equals(refund.optString("currency")) || !refundReceipt(expected.refundId()).equals(refund.optString("receipt"))
                || notes == null || !expected.refundId().equals(notes.optString("refundId"))
                || !expected.intentId().equals(notes.optString("intentId")) || !expected.providerPaymentId().equals(notes.optString("orderId"))
                || !expected.idempotencyKey().equals(notes.optString("operationKey"))) throw uncertain();
        return new ProviderResult(refund.getString("id"), switch (refund.optString("status")) {
            case "processed" -> "REFUNDED";
            case "failed" -> "FAILED";
            case "pending" -> "PROCESSING";
            default -> "UNKNOWN";
        }, null, "Razorpay refund evidence verified against the original order and payment");
    }
    private ProviderResult operation(Supplier<ProviderResult> action) {
        if (deadline.get() != null) return action.get();
        BillingStore.requireNoTransaction();
        long nanos = OPERATION_TIMEOUT.toNanos();
        Duration caller = BillingExecutionBudget.providerRemaining();
        if (caller != null) nanos = Math.min(nanos, Math.max(0, caller.toNanos()));
        deadline.set(System.nanoTime() + nanos);
        boolean acquired = false;
        try {
            acquired = capacity.tryAcquire(Math.min(nanos, Duration.ofMillis(100).toNanos()), TimeUnit.NANOSECONDS);
            if (!acquired) throw uncertain();
            return action.get();
        } catch (ProviderUncertainException ex) { throw ex; }
        catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw uncertain(); }
        catch (RuntimeException malformed) { throw uncertain(); }
        finally { deadline.remove(); if (acquired) capacity.release(); }
    }
    private JSONObject request(String method, String path, JSONObject body, boolean initial) {
        BillingStore.requireNoTransaction();
        long remaining = deadline.get() - System.nanoTime();
        if (remaining < Duration.ofMillis(100).toNanos()) throw uncertain();
        Duration timeout = Duration.ofNanos(Math.min(remaining, Duration.ofSeconds(10).toNanos()));
        HttpRequest.Builder builder = HttpRequest.newBuilder(API.resolve(path)).timeout(timeout)
                .header("Authorization", "Basic " + Base64.getEncoder().encodeToString((keyId + ":" + secret).getBytes(StandardCharsets.UTF_8)))
                .header("Accept", "application/json");
        if("POST".equals(method) && path.endsWith("/refund") && body!=null)
            builder.header("X-Refund-Idempotency",body.getJSONObject("notes").getString("refundId"));
        if (body == null) builder.GET();
        else builder.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(body.toString()));
        Response response;
        try { response = transport.send(builder.build()); }
        catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw uncertain(); }
        catch (Exception ex) { throw uncertain(); }
        if (response.status() < 200 || response.status() >= 300) {
            ProviderDiagnostics diagnostics = new ProviderDiagnostics(response.status(), null, null, null, null, null, false, null);
            // A generic 400 can describe a duplicate receipt after an earlier acceptance. It is not absence proof.
            if (initial && (response.status() == 401 || response.status() == 403))
                throw new ProviderRejectedException("Razorpay rejected the initial credentials before this operation", diagnostics);
            throw new ProviderUncertainException("Razorpay did not return verifiable operation evidence", null, diagnostics);
        }
        if (response.body().length() > 1_048_576) throw uncertain();
        return new JSONObject(response.body());
    }
    private static void validate(ProviderCommand command) {
        try { ProviderValidation.uuid(command.intentId()); }
        catch (RuntimeException invalid) { throw new ProviderRejectedException("A saved payment identity is required"); }
        if (!Objects.equals("billing_" + command.intentId(), command.idempotencyKey()) || !"INR".equals(command.currency())
                || !"CARD".equals(command.method()) || command.amountMinor() < 100 || command.amountMinor() > 99_999_999)
            throw new ProviderRejectedException("Razorpay lab supports INR card orders from 100 to 99999999 paise");
    }
    static String receipt(String intent) { return "bill_" + ProviderValidation.uuid(intent).replace("-", ""); }
    static String refundReceipt(String id) { return "ref_" + ProviderValidation.uuid(id).replace("-", ""); }
    private static String intentFromKey(String key) {
        if (key == null || !key.startsWith("billing_")) throw uncertain();
        return ProviderValidation.uuid(key.substring(8));
    }
    static boolean validId(String value, String prefix) { return value != null && value.matches(prefix + "[A-Za-z0-9]{1,100}"); }
    private static void requireId(String value, String prefix) { if (!validId(value, prefix)) throw uncertain(); }
    static long minor(JSONObject object, String key) { long value = nonnegative(object, key); if (value < 1) throw uncertain(); return value; }
    static long nonnegative(JSONObject object, String key) {
        Object value = object.get(key);
        if (!(value instanceof Number) || value instanceof Double || value instanceof Float) throw uncertain();
        long result = object.getBigDecimal(key).longValueExact(); if (result < 0) throw uncertain(); return result;
    }
    private static JSONArray collection(JSONObject value) {
        JSONArray items = value.getJSONArray("items");
        if (!"collection".equals(value.optString("entity")) || items.length() > PAGE_SIZE || nonnegative(value, "count") != items.length()) throw uncertain();
        return items;
    }
    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
    private static ProviderResult unknown(String id, String message) { return new ProviderResult(id, "UNKNOWN", null, message); }
    private static ProviderUncertainException uncertain() { return new ProviderUncertainException("Razorpay evidence is uncertain; do not create a replacement operation"); }
    private static String digest(String text) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception failure) { throw new IllegalStateException("Cannot identify provider configuration"); }
    }
    @FunctionalInterface interface Transport { Response send(HttpRequest request) throws Exception; }
    record Response(int status, String body) {}
    static final class JdkTransport implements Transport {
        private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
                .followRedirects(HttpClient.Redirect.NEVER).build();
        @Override public Response send(HttpRequest request) throws Exception {
            CompletableFuture<HttpResponse<byte[]>> pending = client.sendAsync(request, info -> new LimitedBody());
            try {
                HttpResponse<byte[]> response = pending.get(request.timeout().orElseThrow().toNanos(), TimeUnit.NANOSECONDS);
                return new Response(response.statusCode(), new String(response.body(), StandardCharsets.UTF_8));
            } finally { if (!pending.isDone()) pending.cancel(true); }
        }
    }
    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private Flow.Subscription subscription;
        @Override public CompletionStage<byte[]> getBody() { return result; }
        @Override public void onSubscribe(Flow.Subscription value) { subscription = value; value.request(1); }
        @Override public void onNext(List<ByteBuffer> chunks) {
            for (ByteBuffer chunk : chunks) {
                if (bytes.size() + chunk.remaining() > 1_048_576) { subscription.cancel(); result.completeExceptionally(uncertain()); return; }
                byte[] value = new byte[chunk.remaining()]; chunk.get(value); bytes.writeBytes(value);
            }
            subscription.request(1);
        }
        @Override public void onError(Throwable failure) { result.completeExceptionally(failure); }
        @Override public void onComplete() { result.complete(bytes.toByteArray()); }
    }
}
