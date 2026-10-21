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
    ProviderResult refund(RefundCommand command);
    ProviderResult lookupRefund(String idempotencyKey, String providerId);
    /** Identifies the refund request format and provider configuration safe for a saved retry. */
    default String refundRequestVersion() { return null; }
    /** Providers opt in to bounded refund replay; the default remains a read-only lookup. */
    default ProviderResult recoverRefund(RefundCommand command, String version, Instant preparedAt, String providerId) {
        return lookupRefund(command.idempotencyKey(), providerId);
    }
}
