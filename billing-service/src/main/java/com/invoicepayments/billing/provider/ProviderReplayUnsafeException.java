package com.invoicepayments.billing.provider;

/** Preserve the reservation and escalate; issuing another POST is no longer safe. */
public final class ProviderReplayUnsafeException extends ProviderUncertainException {
    public ProviderReplayUnsafeException() {
        super("Saved payment cannot be safely replayed; reconciliation required");
    }
    public ProviderReplayUnsafeException(String message) {
        super(message);
    }
}
