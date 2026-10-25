package com.invoicepayments.billing.provider;

/** Room reserved for one new payment before anything is saved. Close it once the provider call is finished. */
public interface ProviderPermit extends AutoCloseable {
    ProviderPermit NONE = () -> {};
    @Override void close();
}
