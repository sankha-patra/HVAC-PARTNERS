package com.invoicepayments.billing.provider;

import java.time.Instant;

/** Network operations are invoked outside the caller's financial database transaction. */
public interface BillingProvider {
    String name();
    ProviderResult submit(ProviderCommand command);
    /** Identifies the request format and provider configuration safe for a saved retry. */
    default String paymentRequestVersion() { return null; }
    default ProviderResult submitSaved(ProviderCommand command, String version, Instant preparedAt) {
        return submit(command);
    }
    /** Providers opt in to bounded replay; the default remains a read-only lookup. */
    default ProviderResult recoverPayment(ProviderCommand command, String version, Instant preparedAt, String providerId) {
        return lookup(command.idempotencyKey(), providerId);
    }
    ProviderResult lookup(String idempotencyKey, String providerId);
    /** A staff-supplied reference is untrusted until all expected financial fields match. */
    default ProviderResult verifyPaymentReference(ProviderCommand expected, String providerId) {
        return new ProviderResult(providerId, "UNKNOWN", null, "Provider cannot verify this payment reference");
    }
    ProviderResult refund(RefundCommand command);
    ProviderResult lookupRefund(String idempotencyKey, String providerId);
    /** Must also verify that the refund belongs to the expected original provider payment. */
    default ProviderResult verifyRefundReference(RefundCommand expected, String providerId) {
        return new ProviderResult(providerId, "UNKNOWN", null, "Provider cannot verify this refund reference");
    }
    /** Identifies the refund request format and provider configuration safe for a saved retry. */
    default String refundRequestVersion() { return null; }
    /** Providers opt in to bounded refund replay; the default remains a read-only lookup. */
    default ProviderResult recoverRefund(RefundCommand command, String version, Instant preparedAt, String providerId) {
        return lookupRefund(command.idempotencyKey(), providerId);
    }
    /**
     * Before a two-person release of a stuck payment: make the provider unable to take this payment (expire
     * its hosted page), then report what exists. "ABSENT" means proven: no payment exists or can appear.
     * Any other state is evidence to record instead of releasing. The default proves nothing.
     */
    default ProviderResult closePayment(ProviderCommand saved, String version, Instant preparedAt, String providerId) {
        return new ProviderResult(providerId, "UNKNOWN", null, "Provider cannot prove that no payment exists");
    }
    /**
     * Before a two-person release of a refund that can no longer be replayed: "ABSENT" means the provider
     * shows no refund with this refund ID on the original payment. The default proves nothing.
     */
    default ProviderResult findRefund(RefundCommand saved, String providerId) {
        return new ProviderResult(providerId, "UNKNOWN", null, "Provider cannot prove that no refund exists");
    }
}
