package com.invoicepayments.billing.provider;

import java.time.Instant;

/**
 * What the provider reports about a bank dispute or inquiry. It is never money evidence: Carrier finance answers it
 * in the provider's dashboard before evidenceDueBy. Anything the provider sent that could not be read stays null,
 * and occurredAt is the provider's own event time (null if it sent none), never the time we received it.
 */
public record ProviderDispute(String paymentReference, String chargeReference, Long amountMinor, String currency,
                              String reason, String status, Instant evidenceDueBy, Instant occurredAt) {}
