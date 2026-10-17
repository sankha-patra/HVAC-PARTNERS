package com.invoicepayments.billing.provider;

public record ProviderCommand(String intentId, String idempotencyKey, long amountMinor,
                              String currency, String method, String returnUrl) {}
