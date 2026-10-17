package com.invoicepayments.billing.provider;

/** CAPTURED means provider payment success, not final bank settlement. */
public record ProviderResult(String providerId, String state, String checkoutUrl, String message) {}
