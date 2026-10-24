package com.invoicepayments.billing.provider;

/**
 * CAPTURED means provider payment success, not final bank settlement.
 * {@code finding} explains an UNKNOWN release check: SEARCH_EMPTY or CREDENTIALS_CHANGED. Neither is proof of absence;
 * they only make a case eligible for release on recorded Stripe confirmation. Null otherwise.
 */
public record ProviderResult(String providerId, String state, String checkoutUrl, String message,
        ProviderDiagnostics diagnostics, String finding) {
    /** The provider searched the original account and request window and found no matching operation. */
    public static final String SEARCH_EMPTY = "SEARCH_EMPTY";
    /** The credentials changed since the request was sent, so the provider could not search the original account. */
    public static final String CREDENTIALS_CHANGED = "CREDENTIALS_CHANGED";

    public ProviderResult(String providerId, String state, String checkoutUrl, String message,
            ProviderDiagnostics diagnostics) {
        this(providerId, state, checkoutUrl, message, diagnostics, null);
    }
    public ProviderResult(String providerId, String state, String checkoutUrl, String message) {
        this(providerId, state, checkoutUrl, message, null, null);
    }
}
