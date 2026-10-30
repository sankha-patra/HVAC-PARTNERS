package com.invoicepayments.billing;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.sql.SQLTransientConnectionException;
import java.util.*;

/**
 * One log line per operational alert, in the fixed shape CloudWatch metric filters match:
 * {@code ALERT code=<CODE> severity=<SEVERITY> key=value ... reason="..."}. Infra alarms turn each code into an email.
 * Inside a transaction the line is written only after commit, so a rolled-back change never raises an alert.
 * Identifiers and short reason text only: never provider messages, request bodies, credentials or personal data.
 */
public final class OperationalAlert {
    private static final Logger log=LoggerFactory.getLogger("operational-alerts");
    public enum Severity { CRITICAL, WARNING }
    private OperationalAlert(){}

    /** fields are key/value pairs, e.g. "intentId",intent. */
    public static void raise(String code,Severity severity,String reason,Object... fields) {
        String line=format(code,severity,reason,fields);
        if(TransactionSynchronizationManager.isSynchronizationActive())
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit(){emit(severity,line);}
            });
        else emit(severity,line);
    }
    static String format(String code,Severity severity,String reason,Object... fields) {
        if(code==null||!code.matches("[A-Z][A-Z0-9_]{2,63}"))throw new IllegalArgumentException("Alert code must be UPPER_SNAKE_CASE");
        if(fields.length%2!=0)throw new IllegalArgumentException("Alert fields are key/value pairs");
        var line=new StringBuilder("ALERT code=").append(code).append(" severity=").append(severity);
        for(int i=0;i<fields.length;i+=2)
            if(fields[i+1]!=null)line.append(' ').append(fields[i]).append('=').append(token(String.valueOf(fields[i+1])));
        return line.append(" reason=\"").append(text(reason)).append('"').toString();
    }
    /** The database could not be reached at all, as opposed to a query or constraint failure. */
    public static boolean databaseUnavailable(Throwable failure) {
        Set<Throwable> seen=Collections.newSetFromMap(new IdentityHashMap<>());
        for(Throwable t=failure;t!=null&&seen.add(t);t=t.getCause())
            if(t instanceof DataAccessResourceFailureException||t instanceof TransientDataAccessResourceException
                ||t instanceof SQLTransientConnectionException)return true;
        return false;
    }
    private static void emit(Severity severity,String line){if(severity==Severity.CRITICAL)log.error(line);else log.warn(line);}
    private static String token(String value){return value.replaceAll("[^A-Za-z0-9_.:@/-]","_");}
    private static String text(String value) {
        String clean=value==null?"":value.replaceAll("[\\r\\n\\t\"]"," ").trim();
        return clean.length()>200?clean.substring(0,200):clean;
    }
}
