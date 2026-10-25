package com.invoicepayments.billing.provider;

/** A new payment was refused before anything was saved or sent: the provider is busy or failing. Nothing was charged. */
public class ProviderBusyException extends RuntimeException {
    public ProviderBusyException(String message) { super(message); }
}
