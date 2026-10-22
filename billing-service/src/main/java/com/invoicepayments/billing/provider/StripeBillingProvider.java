package com.invoicepayments.billing.provider;

import org.json.JSONObject;

import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

/**
 * Hosted Stripe Checkout in TEST MODE only. No card or bank account data enters this service.
 * API schema is pinned; update request fields and contract tests when changing the API version.
 */
public final class StripeBillingProvider implements BillingProvider {
    static final String API_VERSION = "2026-09-30.endive";
    private static final String API = "https://api.stripe.com";
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);
    private final String secretKey;
    private final Transport transport;
    private final Clock clock;

    public StripeBillingProvider(String secretKey) {
        this(secretKey, new JdkTransport());
    }

    StripeBillingProvider(String secretKey, Transport transport) {
        this(secretKey, transport, Clock.systemUTC());
    }

    StripeBillingProvider(String secretKey, Transport transport, Clock clock) {
        this.secretKey = secretKey == null ? "" : secretKey.trim();
        if (!this.secretKey.isEmpty() && !this.secretKey.matches("sk_test_[A-Za-z0-9]+")) {
            throw new IllegalArgumentException("Only a Stripe test secret key is supported");
        }
        this.transport = Objects.requireNonNull(transport);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override public String name() { return "STRIPE"; }

    @Override public String paymentRequestVersion() {
        // A credential change may select a different Stripe account. Fail closed on replay
        // even for same-account key rotation; never persist the secret itself.
        return API_VERSION + ":checkout-v1:" + credentialFingerprint();
    }

    @Override public String refundRequestVersion() {
        return API_VERSION + ":refund-v1:" + credentialFingerprint();
    }

    @Override public ProviderResult submitSaved(ProviderCommand command, String version, Instant preparedAt) {
        requireReplayWindow(paymentRequestVersion(), version, preparedAt,
                "Saved payment cannot be safely replayed; reconciliation required");
        return submit(command);
    }

    @Override public ProviderResult recoverRefund(RefundCommand command, String version, Instant preparedAt, String providerId) {
        if (providerId != null) return lookupRefund(command.idempotencyKey(), providerId);
        requireReplayWindow(refundRequestVersion(), version, preparedAt,
                "Saved refund cannot be safely replayed; reconciliation required");
        // Same key and same parameters: Stripe returns the original refund if it was created,
        // or performs the refund the lost request never delivered. It never creates a second one.
        ProviderResult replay = refund(command);
        // A replay returns the original response. Read the refund's current state before
        // applying evidence; keep the verified identity even if that read fails.
        try { return lookupRefund(command.idempotencyKey(), replay.providerId()); }
        catch (ProviderUncertainException ex) { return unknown(replay.providerId()); }
    }

    private void requireReplayWindow(String expectedVersion, String version, Instant preparedAt, String message) {
        Instant now = clock.instant();
        // Stripe v1 may prune keys after 24 hours. Keep an hour of safety margin.
        if (!expectedVersion.equals(version) || preparedAt == null || preparedAt.isAfter(now)
                || !now.isBefore(preparedAt.plus(Duration.ofHours(23)))) {
            throw new ProviderReplayUnsafeException(message);
        }
    }

    private String credentialFingerprint() {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(secretKey.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }

    @Override public ProviderResult recoverPayment(ProviderCommand command, String version, Instant preparedAt, String providerId) {
        if (providerId != null) return lookup(command.idempotencyKey(), providerId);
        ProviderResult replay = submitSaved(command, version, preparedAt);
        // A replay returns the original response. Read current Checkout state before
        // applying evidence: the customer may have paid or the session may have expired.
        try { return lookup(command.idempotencyKey(), replay.providerId()); }
        catch (ProviderUncertainException ex) {
            // Persist the verified identity even if this read fails, so later recovery
            // can use GET after the safe POST window has elapsed.
            return unknown(replay.providerId());
        }
    }

    @Override public ProviderResult submit(ProviderCommand command) {
        ProviderValidation.payment(command);
        if (command.amountMinor() < 50) throw new IllegalArgumentException("Stripe USD payments must be at least 50 cents");
        validateReturnUrl(command.returnUrl());
        Map<String, String> form = new LinkedHashMap<>();
        form.put("mode", "payment");
        form.put("ui_mode", "hosted_page");
        form.put("success_url", command.returnUrl());
        form.put("cancel_url", command.returnUrl());
        // Endive replaced payment_method_types with this dynamic eligibility filter.
        form.put("allowed_payment_method_types[0]", "ACH".equals(command.method()) ? "us_bank_account" : "card");
        form.put("line_items[0][price_data][currency]", "usd");
        form.put("line_items[0][price_data][unit_amount]", Long.toString(command.amountMinor()));
        form.put("line_items[0][price_data][product_data][name]", "Invoice payment");
        form.put("line_items[0][quantity]", "1");
        form.put("client_reference_id", command.intentId());
        metadata(form, "metadata", command.intentId(), command.idempotencyKey());
        metadata(form, "payment_intent_data[metadata]", command.intentId(), command.idempotencyKey());
        JSONObject session = request("POST", "/v1/checkout/sessions", command.idempotencyKey(), form);
        try {
            validateSession(session, command.idempotencyKey(), null);
            if (!command.intentId().equals(intentId(session)) || amount(session, "amount_total") != command.amountMinor()) {
                throw new IllegalArgumentException();
            }
            return sessionResult(session, command.idempotencyKey());
        } catch (ProviderUncertainException ex) {
            // Session identity and amount were verified before fetching payment state.
            return unknown(session.getString("id"));
        } catch (RuntimeException ex) {
            throw new ProviderUncertainException("Stripe returned an uncorrelated payment; reconcile before retrying");
        }
    }

    @Override public ProviderResult lookup(String idempotencyKey, String providerId) {
        ProviderValidation.key(idempotencyKey);
        if (!validId(providerId, "cs_test_")) return unknown(providerId);
        JSONObject session = request("GET", "/v1/checkout/sessions/" + providerId, null, null);
        try {
            validateSession(session, idempotencyKey, providerId);
            return sessionResult(session, idempotencyKey);
        } catch (ProviderUncertainException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            return unknown(providerId);
        }
    }

    @Override public ProviderResult refund(RefundCommand command) {
        ProviderValidation.refund(command);
        if (!validId(command.providerPaymentId(), "cs_test_")) throw new IllegalArgumentException("A Stripe test Checkout Session is required");
        JSONObject session = request("GET", "/v1/checkout/sessions/" + command.providerPaymentId(), null, null);
        String paymentIntentId;
        try {
            validateSession(session, null, command.providerPaymentId());
            if (!command.intentId().equals(intentId(session)) || amount(session, "amount_total") < command.amountMinor()) {
                throw new IllegalArgumentException();
            }
            paymentIntentId = session.getString("payment_intent");
            JSONObject paymentIntent = fetchPaymentIntent(session, null);
            if (!"succeeded".equals(paymentIntent.getString("status"))
                    || amount(paymentIntent, "amount_received") != amount(session, "amount_total")) {
                throw new IllegalArgumentException();
            }
        } catch (RuntimeException ex) {
            throw new ProviderUncertainException("Original captured Stripe payment could not be confirmed");
        }
        Map<String, String> form = new LinkedHashMap<>();
        form.put("payment_intent", paymentIntentId);
        form.put("amount", Long.toString(command.amountMinor()));
        metadata(form, "metadata", command.intentId(), command.idempotencyKey());
        form.put("metadata[refundId]", command.refundId());
        JSONObject refund = request("POST", "/v1/refunds", command.idempotencyKey(), form);
        try {
            validateRefund(refund, command.idempotencyKey(), null);
            if (!command.intentId().equals(intentId(refund))
                    || !command.refundId().equals(refund.getJSONObject("metadata").getString("refundId"))
                    || !paymentIntentId.equals(refund.getString("payment_intent"))
                    || command.amountMinor() != amount(refund, "amount")) throw new IllegalArgumentException();
            return refundResult(refund);
        } catch (RuntimeException ex) {
            throw new ProviderUncertainException("Stripe returned an uncorrelated refund; reconcile before retrying");
        }
    }

    @Override public ProviderResult verifyPaymentReference(ProviderCommand expected, String providerId) {
        ProviderValidation.payment(expected);
        if (!validId(providerId, "cs_test_")) return unknown(providerId);
        JSONObject session = request("GET", "/v1/checkout/sessions/" + providerId, null, null);
        try {
            validateSession(session, expected.idempotencyKey(), providerId);
            if (!expected.intentId().equals(intentId(session))
                    || expected.amountMinor() != amount(session, "amount_total")
                    || !expected.currency().equalsIgnoreCase(session.getString("currency"))) return unknown(providerId);
            // This also checks the PaymentIntent's identity, metadata, currency and received amount.
            return sessionResult(session, expected.idempotencyKey(), expected.method());
        } catch (ProviderUncertainException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            return unknown(providerId);
        }
    }

    @Override public ProviderResult verifyRefundReference(RefundCommand expected, String providerId) {
        ProviderValidation.refund(expected);
        if (!validId(providerId, "re_") || !validId(expected.providerPaymentId(), "cs_test_")) return unknown(providerId);
        JSONObject refund = request("GET", "/v1/refunds/" + providerId, null, null);
        try {
            validateRefund(refund, expected.idempotencyKey(), providerId);
            if (!expected.intentId().equals(intentId(refund))
                    || !expected.refundId().equals(refund.getJSONObject("metadata").getString("refundId"))
                    || expected.amountMinor() != amount(refund, "amount")
                    || !expected.currency().equalsIgnoreCase(refund.getString("currency"))) return unknown(providerId);
            // The local payment stores a Checkout ID; Stripe refunds refer to its PaymentIntent.
            // Resolve that link with GET instead of trusting metadata on the submitted refund alone.
            JSONObject session = request("GET", "/v1/checkout/sessions/" + expected.providerPaymentId(), null, null);
            validateSession(session, null, expected.providerPaymentId());
            String paymentIntentId = session.getString("payment_intent");
            if (!expected.intentId().equals(intentId(session))
                    || !validId(paymentIntentId, "pi_")
                    || !paymentIntentId.equals(refund.getString("payment_intent"))
                    || expected.amountMinor() > amount(session, "amount_total")
                    || !expected.currency().equalsIgnoreCase(session.getString("currency"))) return unknown(providerId);
            return refundResult(refund);
        } catch (ProviderUncertainException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            return unknown(providerId);
        }
    }

    @Override public ProviderResult lookupRefund(String idempotencyKey, String providerId) {
        ProviderValidation.key(idempotencyKey);
        // Stripe does not offer a GET lookup by idempotency key. Never blindly POST to discover it.
        if (!validId(providerId, "re_")) return unknown(providerId);
        JSONObject refund = request("GET", "/v1/refunds/" + providerId, null, null);
        try {
            validateRefund(refund, idempotencyKey, providerId);
            return refundResult(refund);
        } catch (RuntimeException ex) {
            return unknown(providerId);
        }
    }

    private ProviderResult sessionResult(JSONObject session, String key) {
        return sessionResult(session, key, null);
    }

    private ProviderResult sessionResult(JSONObject session, String key, String expectedMethod) {
        String id = session.getString("id");
        if (expectedMethod != null) validatePaymentMethod(session, expectedMethod);
        String url = checkoutUrl(session.optString("url", null));
        if (session.isNull("payment_intent") || !session.has("payment_intent")) {
            return new ProviderResult(id, "expired".equals(session.optString("status")) ? "FAILED" : "REQUIRES_ACTION",
                    url, "Awaiting hosted Checkout");
        }
        JSONObject intent = fetchPaymentIntent(session, key);
        if (expectedMethod != null) validatePaymentMethod(intent, expectedMethod);
        String state = switch (intent.getString("status")) {
            case "succeeded" -> amount(intent, "amount_received") == amount(session, "amount_total") ? "CAPTURED" : "UNKNOWN";
            case "processing", "requires_capture" -> "PROCESSING";
            case "requires_action", "requires_confirmation" -> "REQUIRES_ACTION";
            case "requires_payment_method" -> "expired".equals(session.optString("status")) ? "FAILED" : "REQUIRES_ACTION";
            case "canceled" -> "FAILED";
            default -> "UNKNOWN";
        };
        return new ProviderResult(id, state, "REQUIRES_ACTION".equals(state) ? url : null,
                "Stripe test payment " + state.toLowerCase(java.util.Locale.ROOT));
    }

    private static void validatePaymentMethod(JSONObject object, String expectedMethod) {
        // The pinned API changes the creation parameter to allowed_payment_method_types,
        // but still returns payment_method_types. This app submits exactly one allowed type.
        var methods = object.getJSONArray("payment_method_types");
        String expected = "ACH".equals(expectedMethod) ? "us_bank_account" : "card";
        if (methods.length() != 1 || !expected.equals(methods.getString(0))) throw new IllegalArgumentException();
    }

    private JSONObject fetchPaymentIntent(JSONObject session, String key) {
        String id = session.getString("payment_intent");
        if (!validId(id, "pi_")) throw new IllegalArgumentException();
        JSONObject intent = request("GET", "/v1/payment_intents/" + id, null, null);
        if (!id.equals(intent.getString("id")) || !"payment_intent".equals(intent.getString("object"))
                || !Boolean.FALSE.equals(intent.get("livemode")) || !intentId(session).equals(intentId(intent))
                || !session.getJSONObject("metadata").getString("idempotencyKey")
                    .equals(intent.getJSONObject("metadata").getString("idempotencyKey"))
                || (key != null && !key.equals(intent.getJSONObject("metadata").getString("idempotencyKey")))
                || amount(session, "amount_total") != amount(intent, "amount")) throw new IllegalArgumentException();
        return intent;
    }

    private static void validateSession(JSONObject object, String key, String id) {
        if (!"checkout.session".equals(object.getString("object")) || !Boolean.FALSE.equals(object.get("livemode"))
                || !"payment".equals(object.getString("mode")) || !validId(object.getString("id"), "cs_test_")
                || (id != null && !id.equals(object.getString("id")))
                || (key != null && !key.equals(object.getJSONObject("metadata").getString("idempotencyKey")))) {
            throw new IllegalArgumentException();
        }
        intentId(object);
        ProviderValidation.key(object.getJSONObject("metadata").getString("idempotencyKey"));
        amount(object, "amount_total");
    }

    private static void validateRefund(JSONObject object, String key, String id) {
        if (!"refund".equals(object.getString("object")) || !validId(object.getString("id"), "re_")
                || (id != null && !id.equals(object.getString("id")))
                || !key.equals(object.getJSONObject("metadata").getString("idempotencyKey"))) throw new IllegalArgumentException();
        intentId(object);
        ProviderValidation.uuid(object.getJSONObject("metadata").getString("refundId"));
        amount(object, "amount");
    }

    static String refundState(JSONObject object) {
        return switch (object.getString("status")) {
            case "succeeded" -> "REFUNDED";
            case "pending" -> "PROCESSING";
            case "requires_action" -> "REQUIRES_ACTION";
            case "failed", "canceled" -> "FAILED";
            default -> "UNKNOWN";
        };
    }

    private static ProviderResult refundResult(JSONObject refund) {
        String state = refundState(refund);
        return new ProviderResult(refund.getString("id"), state, null,
                "Stripe test refund " + state.toLowerCase(java.util.Locale.ROOT));
    }

    static String intentId(JSONObject object) {
        return ProviderValidation.uuid(object.getJSONObject("metadata").getString("intentId"));
    }

    static long amount(JSONObject object, String field) {
        if (!(object.get(field) instanceof Number)) throw new IllegalArgumentException("Provider amount must be a JSON number");
        long value = object.getBigDecimal(field).longValueExact();
        ProviderValidation.amount(value, object.getString("currency").toUpperCase(java.util.Locale.ROOT));
        return value;
    }

    private static void metadata(Map<String, String> form, String prefix, String intentId, String key) {
        form.put(prefix + "[intentId]", intentId);
        form.put(prefix + "[idempotencyKey]", key);
    }

    static boolean validId(String id, String prefix) {
        return id != null && id.startsWith(prefix) && id.length() > prefix.length()
                && id.length() <= 255 && id.substring(prefix.length()).matches("[A-Za-z0-9]+");
    }

    static String checkoutUrl(String value) {
        if (value == null || value.isBlank()) return null;
        URI uri = URI.create(value);
        String host = uri.getHost();
        if (!"https".equals(uri.getScheme()) || host == null || uri.getUserInfo() != null
                || (uri.getPort() != -1 && uri.getPort() != 443)
                || !(host.equals("checkout.stripe.com") || host.endsWith(".checkout.stripe.com"))) {
            throw new IllegalArgumentException("Invalid hosted Checkout URL");
        }
        return value;
    }

    private static void validateReturnUrl(String value) {
        if (value == null) throw new IllegalArgumentException("A return URL is required");
        URI uri = URI.create(value);
        boolean local = "http".equals(uri.getScheme()) && ("localhost".equals(uri.getHost()) || "127.0.0.1".equals(uri.getHost()));
        if ((!local && !"https".equals(uri.getScheme())) || uri.getHost() == null || uri.getUserInfo() != null || uri.getFragment() != null) {
            throw new IllegalArgumentException("Return URL must use HTTPS, or loopback HTTP in test mode");
        }
    }

    private JSONObject request(String method, String path, String key, Map<String, String> form) {
        if (secretKey.isEmpty()) throw new ProviderUncertainException("Stripe test provider is not configured");
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(API + path))
                .timeout(REQUEST_TIMEOUT).header("Authorization", "Bearer " + secretKey)
                .header("Stripe-Version", API_VERSION).header("Accept", "application/json");
        if ("POST".equals(method)) {
            builder.header("Idempotency-Key", key).header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(form.entrySet().stream()
                            .map(e -> encode(e.getKey()) + "=" + encode(e.getValue())).collect(Collectors.joining("&"))));
        } else builder.GET();
        try {
            Response response = transport.send(builder.build());
            if (response.status() < 200 || response.status() >= 300) {
                throw new ProviderUncertainException("Stripe did not confirm this operation (HTTP " + response.status() + "); reconcile before retrying");
            }
            return new JSONObject(response.body());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new ProviderUncertainException("Stripe operation interrupted; acceptance is unknown");
        } catch (IOException ex) {
            throw new ProviderUncertainException("Stripe operation timed out or was unavailable; acceptance is unknown");
        } catch (ProviderUncertainException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new ProviderUncertainException("Stripe response could not be verified; acceptance is unknown");
        }
    }

    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
    private static ProviderResult unknown(String id) {
        return new ProviderResult(id, "UNKNOWN", null, "Provider correlation unavailable; manual reconciliation required");
    }

    @FunctionalInterface interface Transport {
        Response send(HttpRequest request) throws IOException, InterruptedException;
    }
    record Response(int status, String body) {}

    private static final class JdkTransport implements Transport {
        private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
                .followRedirects(HttpClient.Redirect.NEVER).build();
        @Override public Response send(HttpRequest request) throws IOException, InterruptedException {
            CompletableFuture<HttpResponse<String>> pending = client.sendAsync(request, info -> new LimitedBody());
            try {
                HttpResponse<String> response = pending.get(REQUEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
                return new Response(response.statusCode(), response.body());
            } catch (TimeoutException ex) {
                pending.cancel(true);
                throw new IOException("Provider response timed out");
            } catch (InterruptedException ex) {
                pending.cancel(true);
                throw ex;
            } catch (ExecutionException ex) {
                throw new IOException("Provider transport failed");
            }
        }
    }

    /** Caps both total body memory and total request time (including slow response bodies). */
    private static final class LimitedBody implements HttpResponse.BodySubscriber<String> {
        private final CompletableFuture<String> result = new CompletableFuture<>();
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private Flow.Subscription subscription;
        @Override public CompletionStage<String> getBody() { return result; }
        @Override public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            subscription.request(1);
        }
        @Override public void onNext(List<ByteBuffer> chunks) {
            for (ByteBuffer chunk : chunks) {
                if (chunk.remaining() > 1_048_576 - bytes.size()) {
                    subscription.cancel();
                    result.completeExceptionally(new IOException("Response exceeds limit"));
                    return;
                }
                byte[] copy = new byte[chunk.remaining()];
                chunk.get(copy);
                bytes.writeBytes(copy);
            }
            subscription.request(1);
        }
        @Override public void onError(Throwable error) { result.completeExceptionally(error); }
        @Override public void onComplete() { result.complete(bytes.toString(StandardCharsets.UTF_8)); }
    }
}
