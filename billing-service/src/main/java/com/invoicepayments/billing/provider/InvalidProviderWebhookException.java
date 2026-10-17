package com.invoicepayments.billing.provider;

public class InvalidProviderWebhookException extends RuntimeException {
    public InvalidProviderWebhookException() { super("Invalid provider webhook"); }
}
