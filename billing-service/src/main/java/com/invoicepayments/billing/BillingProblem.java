package com.invoicepayments.billing;

public class BillingProblem extends RuntimeException {
    private final int status;
    private final String code;
    public BillingProblem(int status, String code, String message) {
        super(message); this.status=status; this.code=code;
    }
    public int status() { return status; }
    public String code() { return code; }
    public static BillingProblem missing() { return new BillingProblem(404,"NOT_FOUND","Record not found in your authorized accounts"); }
    public static BillingProblem conflict(String message) { return new BillingProblem(409,"CONFLICT",message); }
    public static BillingProblem invalid(String message) { return new BillingProblem(400,"INVALID_REQUEST",message); }
}
