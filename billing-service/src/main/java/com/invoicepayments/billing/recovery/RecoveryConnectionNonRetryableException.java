package com.invoicepayments.billing.recovery;

/** A pre-claim database failure excluded from the workflow's infrastructure retrier. */
public final class RecoveryConnectionNonRetryableException extends RuntimeException {
    public enum Reason { AUTHENTICATION, DATABASE, PERMISSION, UNCLASSIFIED }
    private final Reason reason;
    RecoveryConnectionNonRetryableException(Reason reason) {
        super("Recovery database access failed before claim: "+reason.name(),null,false,true);
        this.reason=reason;
    }
    public Reason getReason(){return reason;}
}
