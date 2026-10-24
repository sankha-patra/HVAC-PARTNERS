package com.invoicepayments.billing.provider;

import java.util.Set;

/** Staff-only correlation hints. Never includes response messages, bodies, credentials or payment details. */
public record ProviderDiagnostics(Integer httpStatus, String requestId, String errorType, String errorCode,
        String declineCode, Integer retryAfterSeconds, Boolean shouldRetry, String rateLimitReason) {
    private static final Set<String> RATE_LIMIT_REASONS = Set.of(
            "global-rate", "endpoint-rate", "global-concurrency", "endpoint-concurrency", "resource-specific", "local-admission");

    public ProviderDiagnostics {
        httpStatus = httpStatus != null && httpStatus >= 100 && httpStatus <= 599 ? httpStatus : null;
        requestId = requestId != null && requestId.matches("req_[A-Za-z0-9]{1,120}") ? requestId : null;
        errorType = safeCode(errorType);
        errorCode = safeCode(errorCode);
        declineCode = safeCode(declineCode);
        retryAfterSeconds = retryAfterSeconds != null && retryAfterSeconds >= 0 && retryAfterSeconds <= 86400
                ? retryAfterSeconds : null;
        rateLimitReason = RATE_LIMIT_REASONS.contains(rateLimitReason == null ? "" : rateLimitReason) ? rateLimitReason : null;
    }

    /** A later read cannot authorize replay of an earlier operation whose provider explicitly forbade retry. */
    public ProviderDiagnostics withRetryForbidden() {
        return new ProviderDiagnostics(httpStatus, requestId, errorType, errorCode, declineCode,
                retryAfterSeconds, false, rateLimitReason);
    }

    static String safeCode(String value) {
        if (value == null || !value.matches("[a-z][a-z0-9_]{0,79}") || value.startsWith("sk_")
                || value.startsWith("rk_") || value.startsWith("pk_") || value.startsWith("whsec_")
                || value.contains("secret")) return null;
        return value;
    }
}
