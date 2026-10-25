package com.invoicepayments.billing.provider;

/** Verified financial fact, or REVIEW evidence that must never directly post money. A dispute also carries its details. */
public record ProviderWebhookEvent(String eventId, String providerId, String intentId, String state,
                                   long amountMinor, String currency, String refundId, ProviderDispute dispute) {
    public ProviderWebhookEvent(String eventId, String providerId, String intentId, String state,
                                long amountMinor, String currency, String refundId) {
        this(eventId, providerId, intentId, state, amountMinor, currency, refundId, null);
    }
}
