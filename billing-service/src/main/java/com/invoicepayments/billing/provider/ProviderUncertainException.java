package com.invoicepayments.billing.provider;

/** The operation may have been accepted. Reconcile; do not replace its idempotency key. */
public class ProviderUncertainException extends RuntimeException {
    private ProviderDiagnostics diagnostics;
    public ProviderUncertainException(String message) { this(message, null, null); }
    public ProviderUncertainException(String message, Throwable cause) { this(message, cause, null); }
    public ProviderUncertainException(String message, Throwable cause, ProviderDiagnostics diagnostics) {
        super(message, cause);
        this.diagnostics = diagnostics;
    }
    public ProviderDiagnostics diagnostics() { return diagnostics; }
    public ProviderDiagnostics getDiagnostics() { return diagnostics; }
    void attachDiagnostics(ProviderDiagnostics value) {
        if (diagnostics == null) diagnostics = value;
    }
    void forbidReplay() {
        diagnostics = diagnostics == null
                ? new ProviderDiagnostics(null, null, null, null, null, null, false, null)
                : diagnostics.withRetryForbidden();
    }
}
