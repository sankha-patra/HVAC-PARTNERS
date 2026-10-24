package com.invoicepayments.billing.provider;

/**
 * The provider refused the request before acting, or the request was never sent: nothing was performed.
 * It extends the uncertain type on purpose. Only a caller that knows no earlier attempt used the same
 * idempotency key may treat it as a definite failure; every other caller keeps the operation uncertain.
 */
public final class ProviderRejectedException extends ProviderUncertainException {
    public ProviderRejectedException(String message) { super(message); }
    public ProviderRejectedException(String message, ProviderDiagnostics diagnostics) { super(message, null, diagnostics); }
}
