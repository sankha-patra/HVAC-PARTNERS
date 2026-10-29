package com.invoicepayments.billing;

import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.interceptor.TransactionAttribute;
import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

/** Annotation-owned transactions with billing's existing request/lease and PostgreSQL limits. */
public class BillingDeadlineTransactionManager extends JdbcTransactionManager {
    public BillingDeadlineTransactionManager(DataSource source) { super(source); }
    private static boolean billing(TransactionDefinition definition) {
        return definition instanceof TransactionAttribute attribute && attribute.getLabels().contains("billing");
    }
    @Override protected int determineTimeout(TransactionDefinition definition) {
        int configured=super.determineTimeout(definition);
        if(!billing(definition)) return configured;
        int ceiling=configured==TransactionDefinition.TIMEOUT_DEFAULT ? BillingStore.TRANSACTION_TIMEOUT_SECONDS
            : Math.min(configured,BillingStore.TRANSACTION_TIMEOUT_SECONDS);
        var remaining=BillingExecutionBudget.remaining();
        return remaining==null ? ceiling : (int)Math.max(1,Math.min(ceiling,remaining.toSeconds()));
    }
    @Override protected void prepareTransactionalConnection(Connection connection,TransactionDefinition definition) throws SQLException {
        try {
            super.prepareTransactionalConnection(connection,definition);
            if(!billing(definition)) return;
            var remaining=BillingExecutionBudget.remaining();
            long millis=remaining==null ? 10000 : Math.max(1,Math.min(10000,remaining.toMillis()));
            // Spring installs its ConnectionHolder deadline AFTER this hook. Bound setup explicitly.
            try(var statement=connection.prepareStatement("SELECT set_config('lock_timeout',?,true),set_config('statement_timeout',?,true),set_config('idle_in_transaction_session_timeout',?,true)")) {
                statement.setQueryTimeout((int)Math.max(1,(millis+999)/1000));
                statement.setString(1,Math.min(BillingStore.LOCK_TIMEOUT_MILLIS,millis)+"ms");
                statement.setString(2,millis+"ms");
                statement.setString(3,BillingStore.IDLE_IN_TRANSACTION_TIMEOUT);
                try(var result=statement.executeQuery()) { result.next(); }
            }
        } catch(SQLException|RuntimeException|Error failure) {
            // A failed begin has no bound holder yet. End the aborted transaction before pool release.
            try { connection.rollback(); } catch(SQLException|RuntimeException|Error cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }
}
