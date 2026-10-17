package com.invoicepayments.billing.provider;

/** Verified financial fact, or REVIEW evidence that must never directly post money. */
public record ProviderWebhookEvent(String eventId, String providerId, String intentId, String state,
                                   long amountMinor, String currency, String refundId) {}
