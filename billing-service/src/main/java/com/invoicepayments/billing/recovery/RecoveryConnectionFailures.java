package com.invoicepayments.billing.recovery;

import java.sql.*;
import java.util.*;
import static com.invoicepayments.billing.recovery.RecoveryConnectionNonRetryableException.Reason;

/** Hikari may wrap a permanent driver failure in SQLTransientConnectionException. */
final class RecoveryConnectionFailures {
    private RecoveryConnectionFailures(){}
    static RuntimeException sanitized(SQLException failure) {
        var pending=new ArrayDeque<Throwable>();pending.add(failure);
        Set<Throwable> seen=Collections.newSetFromMap(new IdentityHashMap<>());
        boolean transientConnection=false,unknownSqlState=false;
        Reason permanent=null;
        while(!pending.isEmpty()&&seen.size()<64) {
            var current=pending.removeFirst();if(!seen.add(current))continue;
            if(current instanceof SQLException sql) {
                String state=sql.getSQLState();
                if(state!=null&&!state.isBlank()) {
                    if(state.startsWith("28"))permanent=Reason.AUTHENTICATION;
                    else if(state.startsWith("3D")&&permanent!=Reason.AUTHENTICATION)permanent=Reason.DATABASE;
                    else if(state.equals("42501")&&permanent==null)permanent=Reason.PERMISSION;
                    else if(state.startsWith("08"))transientConnection=true;
                    else unknownSqlState=true;
                }else if(sql instanceof SQLTimeoutException||sql instanceof SQLTransientConnectionException)
                    transientConnection=true;
                if(sql.getNextException()!=null)pending.add(sql.getNextException());
            }
            if(current.getCause()!=null)pending.add(current.getCause());
            Collections.addAll(pending,current.getSuppressed());
        }
        if(permanent!=null)return new RecoveryConnectionNonRetryableException(permanent);
        // Unclassified SQL failures or an oversized failure graph must not become automatic retries.
        if(!pending.isEmpty()||unknownSqlState||!transientConnection)
            return new RecoveryConnectionNonRetryableException(Reason.UNCLASSIFIED);
        return new RecoveryConnectionUnavailableException();
    }
}
