package com.invoicepayments.billing.provider;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.time.Instant;

/**
 * Deterministic provider emulator. Its independent commits emulate an external provider:
 * rolling back the application's transaction cannot undo an accepted provider operation.
 * Selection and simulation endpoints must be disabled in production by the application.
 */
public final class SimulatorBillingProvider implements BillingProvider {
    private static final String COLUMNS = "idempotency_key,provider_id,intent_id,amount_minor,currency,method,state,operation_kind,reference_id,parent_provider_id";
    private static final RowMapper<Operation> MAPPER = (rs, row) -> new Operation(
            rs.getString("idempotency_key"), rs.getString("provider_id"), rs.getString("intent_id"),
            rs.getLong("amount_minor"), rs.getString("currency"), rs.getString("method"),
            rs.getString("state"), rs.getString("operation_kind"), rs.getString("reference_id"),
            rs.getString("parent_provider_id"));
    private final JdbcTemplate jdbc;
    private final TransactionTemplate independent;

    public SimulatorBillingProvider(JdbcTemplate jdbc, PlatformTransactionManager transactions) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.independent = new TransactionTemplate(Objects.requireNonNull(transactions));
        independent.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        independent.setTimeout(10);
    }

    @Override public String name() { return "SIMULATOR"; }

    @Override public String paymentRequestVersion() { return "simulator:payment-v1"; }

    @Override public ProviderResult recoverPayment(ProviderCommand command, String version, Instant preparedAt, String providerId) {
        if (providerId != null) return lookup(command.idempotencyKey(), providerId);
        if (!paymentRequestVersion().equals(version)) throw new ProviderReplayUnsafeException();
        // The emulator retains operation keys indefinitely; Stripe has a bounded window.
        return submit(command);
    }

    @Override public ProviderResult submit(ProviderCommand command) {
        ProviderValidation.payment(command);
        return independent.execute(tx -> {
            // Both idempotency_key and the deterministic provider_id are unique.
            // Concurrent equal requests can race on either index; arbitrate both,
            // then load by key and validate the complete request before returning.
            jdbc.update("""
                    INSERT INTO billing.provider_operations
                    (idempotency_key,provider_id,intent_id,amount_minor,currency,method,state,operation_kind,updated_at)
                    VALUES (?,?,?::uuid,?,?,?,'PROCESSING','PAYMENT',now())
                    ON CONFLICT DO NOTHING
                    """, command.idempotencyKey(), providerId("pay", command.idempotencyKey()),
                    command.intentId(), command.amountMinor(), command.currency(), command.method());
            Operation operation = byKey(command.idempotencyKey(), true);
            if (!operation.matches(command)) throw new IllegalArgumentException("Idempotency key was already used for a different operation");
            return result(operation);
        });
    }

    @Override public ProviderResult lookup(String idempotencyKey, String providerId) {
        return lookupOperation(idempotencyKey, providerId, "PAYMENT");
    }

    @Override public ProviderResult refund(RefundCommand command) {
        ProviderValidation.refund(command);
        return independent.execute(tx -> {
            // All refund commands for a payment serialize on its provider row.
            List<Operation> originals = jdbc.query("SELECT " + COLUMNS +
                    " FROM billing.provider_operations WHERE provider_id=? AND operation_kind='PAYMENT' FOR UPDATE",
                    MAPPER, command.providerPaymentId());
            if (originals.size() != 1) throw new IllegalArgumentException("Original provider payment was not found");
            Operation original = originals.get(0);
            if (!original.intentId().equals(command.intentId()) || !original.currency().equals(command.currency())) {
                throw new IllegalArgumentException("Refund does not match the original payment");
            }
            Operation previous = byKey(command.idempotencyKey(), false);
            if (previous != null) {
                if (!previous.matches(command)) throw new IllegalArgumentException("Idempotency key was already used for a different operation");
                return result(previous);
            }
            if (!"CAPTURED".equals(original.state())) throw new IllegalArgumentException("Only a captured payment can be refunded");
            Long refunded = jdbc.queryForObject("""
                    SELECT COALESCE(SUM(amount_minor),0) FROM billing.provider_operations
                    WHERE operation_kind='REFUND' AND parent_provider_id=? AND state='REFUNDED'
                    """, Long.class, original.providerId());
            if (command.amountMinor() > original.amountMinor() - Objects.requireNonNull(refunded)) {
                throw new IllegalArgumentException("Refund exceeds the captured amount remaining");
            }
            jdbc.update("""
                    INSERT INTO billing.provider_operations
                    (idempotency_key,provider_id,intent_id,amount_minor,currency,method,state,operation_kind,
                     reference_id,parent_provider_id,updated_at)
                    VALUES (?,?,?::uuid,?,?,?,'REFUNDED','REFUND',?,?,now())
                    ON CONFLICT DO NOTHING
                    """, command.idempotencyKey(), providerId("refund", command.idempotencyKey()),
                    command.intentId(), command.amountMinor(), command.currency(), original.method(),
                    command.refundId(), original.providerId());
            Operation operation = byKey(command.idempotencyKey(), true);
            if (!operation.matches(command)) throw new IllegalArgumentException("Idempotency key was already used for a different operation");
            return result(operation);
        });
    }

    @Override public ProviderResult lookupRefund(String idempotencyKey, String providerId) {
        return lookupOperation(idempotencyKey, providerId, "REFUND");
    }

    @Override public ProviderResult verifyPaymentReference(ProviderCommand expected, String providerId) {
        ProviderValidation.payment(expected);
        Operation operation = byKey(expected.idempotencyKey(), false);
        if (operation == null || !operation.providerId().equals(providerId) || !operation.matches(expected))
            return new ProviderResult(providerId, "UNKNOWN", null, "Payment reference does not match the expected operation");
        return result(operation);
    }

    @Override public ProviderResult verifyRefundReference(RefundCommand expected, String providerId) {
        ProviderValidation.refund(expected);
        Operation operation = byKey(expected.idempotencyKey(), false);
        if (operation == null || !operation.providerId().equals(providerId) || !operation.matches(expected))
            return new ProviderResult(providerId, "UNKNOWN", null, "Refund reference does not match the expected operation");
        return result(operation);
    }

    @Override public String refundRequestVersion() { return "simulator:refund-v1"; }

    @Override public ProviderResult recoverRefund(RefundCommand command, String version, Instant preparedAt, String providerId) {
        if (providerId != null) return lookupRefund(command.idempotencyKey(), providerId);
        if (!refundRequestVersion().equals(version)) {
            throw new ProviderReplayUnsafeException("Saved refund cannot be safely replayed; reconciliation required");
        }
        // The emulator retains operation keys indefinitely; Stripe has a bounded window.
        return refund(command);
    }

    /**
     * Authenticated, explicitly enabled nonproduction fault control. The application owns that gate.
     * TIMEOUT_AFTER_ACCEPT commits the provider success before throwing, so reconciliation can recover it.
     */
    public ProviderResult simulateResult(String intentId, String outcome) {
        ProviderValidation.uuid(intentId);
        if (outcome == null) throw new IllegalArgumentException("Simulation outcome is required");
        String target = switch (outcome) {
            case "SUCCESS", "CAPTURED", "TIMEOUT_AFTER_ACCEPT" -> "CAPTURED";
            case "FAILED", "RETURNED", "PROCESSING" -> outcome;
            default -> throw new IllegalArgumentException("Unsupported simulation outcome");
        };
        ProviderResult result = independent.execute(tx -> {
            List<Operation> operations = jdbc.query("SELECT " + COLUMNS + """
                     FROM billing.provider_operations
                     WHERE intent_id=?::uuid AND operation_kind='PAYMENT' FOR UPDATE
                    """, MAPPER, intentId);
            if (operations.size() != 1) throw new IllegalArgumentException("Exactly one simulated payment must exist");
            Operation operation = operations.get(0);
            if (!operation.state().equals(target)) {
                boolean ordinary = "PROCESSING".equals(operation.state()) && !"RETURNED".equals(target);
                boolean bankReturn = "CAPTURED".equals(operation.state()) && "RETURNED".equals(target)
                        && "ACH".equals(operation.method());
                if (!ordinary && !bankReturn) throw new IllegalArgumentException("Simulation cannot reverse this terminal state");
                jdbc.update("UPDATE billing.provider_operations SET state=?,updated_at=now() WHERE idempotency_key=?",
                        target, operation.key());
            }
            return new ProviderResult(operation.providerId(), target, null, "Simulated provider " + target.toLowerCase(java.util.Locale.ROOT));
        });
        if ("TIMEOUT_AFTER_ACCEPT".equals(outcome)) {
            throw new ProviderUncertainException("Simulated timeout after provider acceptance; reconcile this operation");
        }
        return result;
    }

    private ProviderResult lookupOperation(String key, String providerId, String kind) {
        ProviderValidation.key(key);
        Operation operation = byKey(key, false);
        if (operation == null || !kind.equals(operation.kind())
                || (providerId != null && !providerId.isBlank() && !providerId.equals(operation.providerId()))) {
            return new ProviderResult(providerId, "UNKNOWN", null, "No matching provider operation");
        }
        return result(operation);
    }

    private Operation byKey(String key, boolean lock) {
        List<Operation> operations = jdbc.query("SELECT " + COLUMNS +
                " FROM billing.provider_operations WHERE idempotency_key=?" + (lock ? " FOR UPDATE" : ""), MAPPER, key);
        return operations.isEmpty() ? null : operations.get(0);
    }

    private static String providerId(String kind, String key) {
        return "sim_" + kind + "_" + UUID.nameUUIDFromBytes((kind + ":" + key).getBytes(StandardCharsets.UTF_8));
    }

    private static ProviderResult result(Operation op) {
        return new ProviderResult(op.providerId(), op.state(), null, "Simulated provider " + op.state().toLowerCase(java.util.Locale.ROOT));
    }

    private record Operation(String key, String providerId, String intentId, long amountMinor, String currency,
                             String method, String state, String kind, String referenceId, String parentProviderId) {
        boolean matches(ProviderCommand c) {
            return "PAYMENT".equals(kind) && intentId.equals(c.intentId()) && amountMinor == c.amountMinor()
                    && currency.equals(c.currency()) && method.equals(c.method());
        }
        boolean matches(RefundCommand c) {
            return "REFUND".equals(kind) && intentId.equals(c.intentId()) && amountMinor == c.amountMinor()
                    && currency.equals(c.currency()) && Objects.equals(referenceId, c.refundId())
                    && Objects.equals(parentProviderId, c.providerPaymentId());
        }
    }
}
