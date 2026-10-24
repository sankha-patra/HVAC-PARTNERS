package com.invoicepayments.billing;

import java.time.Duration;
import java.time.Instant;

/** A cooperative deadline shared by synchronous request, database and provider work. */
public final class BillingExecutionBudget {
    private static final ThreadLocal<Long> DEADLINE = new ThreadLocal<>();
    private static final Duration RECORDING_RESERVE = Duration.ofSeconds(5);
    private BillingExecutionBudget() {}

    public static Scope limit(Duration duration) {
        Long previous = DEADLINE.get();
        long candidate = System.nanoTime() + Math.max(0, duration.toNanos());
        DEADLINE.set(previous == null ? candidate : Math.min(previous, candidate));
        return () -> { if (previous == null) DEADLINE.remove(); else DEADLINE.set(previous); };
    }

    /** Convert the persisted lease once; elapsed work thereafter uses a monotonic clock. */
    public static Scope lease(Instant expiresAt) {
        return limit(Duration.between(Instant.now(), expiresAt));
    }

    public static Duration remaining() {
        Long end = DEADLINE.get();
        return end == null ? null : Duration.ofNanos(Math.max(0, end - System.nanoTime()));
    }

    public static Duration providerRemaining() {
        Duration left = remaining();
        return left == null ? null : left.compareTo(RECORDING_RESERVE) <= 0
            ? Duration.ZERO : left.minus(RECORDING_RESERVE);
    }

    public static void requireTime() {
        Duration left = remaining();
        if (left != null && left.toMillis() < 100)
            throw new BillingProblem(503, "OUTCOME_UNCERTAIN", "The request time budget was reached. Refresh the operation status before retrying.");
    }

    public interface Scope extends AutoCloseable { @Override void close(); }
}
