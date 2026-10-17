package com.invoicepayments.billing.provider;

/** Network operations are invoked outside the caller's financial database transaction. */
public interface BillingProvider {
    String name();
    ProviderResult submit(ProviderCommand command);
    ProviderResult lookup(String idempotencyKey, String providerId);
    ProviderResult refund(RefundCommand command);
    ProviderResult lookupRefund(String idempotencyKey, String providerId);
}
