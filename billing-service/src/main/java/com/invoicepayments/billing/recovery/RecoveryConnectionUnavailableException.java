package com.invoicepayments.billing.recovery;

/** A bounded infrastructure failure before any recovery claim or provider call is admitted. */
public final class RecoveryConnectionUnavailableException extends RuntimeException {
    public RecoveryConnectionUnavailableException() {
        // Lambda serializes exception causes/suppressed failures. Driver text may contain credentials.
        super("Recovery database is unavailable before claim",null,false,true);
    }
}
