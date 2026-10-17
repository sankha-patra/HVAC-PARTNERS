package com.invoicepayments.billing.provider;

public record RefundCommand(String refundId, String intentId, String providerPaymentId,
                            String idempotencyKey, long amountMinor, String currency) {}
