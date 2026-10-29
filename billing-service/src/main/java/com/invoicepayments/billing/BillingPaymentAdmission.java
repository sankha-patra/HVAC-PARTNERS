package com.invoicepayments.billing;

import com.invoicepayments.billing.provider.ProviderPermit;
import java.util.UUID;

/** Local capacity admission only. Implementations must not issue provider network requests. */
@FunctionalInterface
public interface BillingPaymentAdmission { ProviderPermit admit(UUID intent); }
