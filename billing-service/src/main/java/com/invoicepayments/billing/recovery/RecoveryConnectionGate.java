package com.invoicepayments.billing.recovery;

import com.zaxxer.hikari.HikariDataSource;
import java.sql.SQLException;
import static com.invoicepayments.billing.BillingStore.requireNoTransaction;

/** At an invocation boundary, prove the database is reachable before claiming any work. */
public final class RecoveryConnectionGate {
    private RecoveryConnectionGate(){}
    public static void beforeInvocation(HikariDataSource pool) {
        requireNoTransaction();
        var mx=pool.getHikariPoolMXBean();
        if(mx!=null&&mx.getActiveConnections()!=0)throw new IllegalStateException("Recovery invocation still has a borrowed database connection");
        // No blanket eviction: Hikari validates a connection idle past its bypass window on borrow and replaces a socket
        // broken by freeze/thaw within connectionTimeout. Evicting healthy ones forced a fresh tunnel handshake every time.
        // A socket that died inside the bypass window is handed out unvalidated, so evict only that one and retry once.
        // Translate only after close completes: neither causes nor suppressed driver errors may escape.
        for(int attempt=1;;attempt++) {
            try(var connection=pool.getConnection()) {
                if(connection.isValid(2))return;
                pool.evictConnection(connection);
                if(attempt==2)throw new SQLException("Connection failed validation","08006");
            }catch(SQLException failure){throw RecoveryConnectionFailures.sanitized(failure);}
        }
    }
}
