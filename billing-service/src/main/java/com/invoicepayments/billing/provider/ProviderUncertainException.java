package com.invoicepayments.billing.provider;

/** The operation may have been accepted. Reconcile; do not replace its idempotency key. */
public class ProviderUncertainException extends RuntimeException {
    public ProviderUncertainException(String message) { super(message); }
    public ProviderUncertainException(String message, Throwable cause) { super(message, cause); }
}
