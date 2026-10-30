package com.invoicepayments.billing.recovery;

import com.zaxxer.hikari.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class RecoveryConnectionGateTest {
    @Test void transientRefillFailureCannotClaimAndALaterConnectionCanProceed() throws Exception {
        var pool=mock(HikariDataSource.class);var mx=mock(HikariPoolMXBean.class);var connection=mock(Connection.class);var task=mock(RecoveryTask.class);
        when(pool.getHikariPoolMXBean()).thenReturn(mx);when(connection.isValid(2)).thenReturn(true);
        when(pool.getConnection()).thenThrow(new SQLTransientConnectionException("fixture refill timeout")).thenAnswer(call->{
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isFalse();return connection;
        });
        UUID operation=UUID.randomUUID();var admissions=new AtomicInteger();when(task.run("PAYMENT",operation)).thenReturn(Map.of("ran",true));
        assertThatThrownBy(()->RecoveryLambdaRuntime.runGuarded(pool,task,"PAYMENT",operation,()->{admissions.incrementAndGet();return true;}))
            .isExactlyInstanceOf(RecoveryConnectionUnavailableException.class).hasNoCause();
        verifyNoInteractions(task);assertThat(admissions).hasValue(0);
        assertThat(RecoveryLambdaRuntime.runGuarded(pool,task,"PAYMENT",operation,()->true)).containsEntry("ran",true);
        verify(task).run("PAYMENT",operation);verify(mx,never()).softEvictConnections();verify(connection).close();
    }
    @Test void invalidConnectionIsTypedAndClosedBeforeClaim() throws Exception {
        var pool=mock(HikariDataSource.class);var connection=mock(Connection.class);var task=mock(RecoveryTask.class);
        when(pool.getConnection()).thenReturn(connection);when(connection.isValid(2)).thenReturn(false);
        assertThatThrownBy(()->RecoveryLambdaRuntime.runGuarded(pool,task,"PAYMENT",UUID.randomUUID(),()->true))
            .isExactlyInstanceOf(RecoveryConnectionUnavailableException.class).hasMessage("Recovery database is unavailable before claim");
        verify(pool,times(2)).evictConnection(connection);verify(connection,times(2)).close();verifyNoInteractions(task);
    }
    @Test void deadSocketHandedOutUnvalidatedIsEvictedAndReplacedOnce() throws Exception {
        var pool=mock(HikariDataSource.class);var dead=mock(Connection.class);var fresh=mock(Connection.class);var task=mock(RecoveryTask.class);UUID operation=UUID.randomUUID();
        when(pool.getConnection()).thenReturn(dead,fresh);when(dead.isValid(2)).thenReturn(false);when(fresh.isValid(2)).thenReturn(true);
        when(task.run("PAYMENT",operation)).thenReturn(Map.of("ran",true));
        assertThat(RecoveryLambdaRuntime.runGuarded(pool,task,"PAYMENT",operation,()->true)).containsEntry("ran",true);
        verify(pool).evictConnection(dead);verify(pool,never()).evictConnection(fresh);verify(dead).close();verify(fresh).close();
    }
    @Test void borrowedConnectionInvariantIsNotAnInfrastructureRetry() throws Exception {
        var pool=mock(HikariDataSource.class);var mx=mock(HikariPoolMXBean.class);when(pool.getHikariPoolMXBean()).thenReturn(mx);when(mx.getActiveConnections()).thenReturn(1);
        assertThatThrownBy(()->RecoveryConnectionGate.beforeInvocation(pool)).isExactlyInstanceOf(IllegalStateException.class).hasMessageContaining("borrowed");
        verify(pool,never()).getConnection();verify(mx,never()).softEvictConnections();
    }
    @Test void activeTransactionInvariantRemainsNonRetryable() {
        var pool=mock(HikariDataSource.class);TransactionSynchronizationManager.setActualTransactionActive(true);
        try {assertThatThrownBy(()->RecoveryConnectionGate.beforeInvocation(pool)).isExactlyInstanceOf(IllegalTransactionStateException.class);verifyNoInteractions(pool);}
        finally {TransactionSynchronizationManager.clear();}
    }
    @Test void budgetConsumedByValidationDefersClaimAfterTheGate() throws Exception {
        var pool=mock(HikariDataSource.class);var connection=mock(Connection.class);var task=mock(RecoveryTask.class);var remaining=new AtomicInteger(21000);UUID operation=UUID.randomUUID();
        when(pool.getConnection()).thenReturn(connection);when(connection.isValid(2)).thenAnswer(call->{remaining.set(19000);return true;});
        when(task.state("PAYMENT",operation,false)).thenReturn(Map.of("ran",false,"status","UNKNOWN"));
        assertThat(RecoveryLambdaRuntime.runGuarded(pool,task,"PAYMENT",operation,()->remaining.get()>=20000)).containsEntry("ran",false);
        verify(task).state("PAYMENT",operation,false);verify(task,never()).run(anyString(),any());verify(connection).close();
    }

    @ParameterizedTest @ValueSource(strings={"28P01","28000","3D000","42501"})
    void permanentDriverFailureInsideHikariTimeoutCannotClaimOrRetry(String state) throws Exception {
        var pool=mock(HikariDataSource.class);var task=mock(RecoveryTask.class);var admissions=new AtomicInteger();
        var driver=new SQLException("jdbc:fixture password=private-fixture",state);
        var wrapper=new SQLTransientConnectionException("pool fixture timeout",null,driver);
        when(pool.getConnection()).thenThrow(wrapper);
        var failure=catchThrowable(()->RecoveryLambdaRuntime.runGuarded(pool,task,"PAYMENT",UUID.randomUUID(),()->{admissions.incrementAndGet();return true;}));
        assertThat(failure).isExactlyInstanceOf(RecoveryConnectionNonRetryableException.class).hasNoCause();
        assertThat(((RecoveryConnectionNonRetryableException)failure).getReason()).isNotEqualTo(RecoveryConnectionNonRetryableException.Reason.UNCLASSIFIED);
        assertThat(admissions).hasValue(0);verifyNoInteractions(task);
        assertNoDriverText(failure);
    }

    @ParameterizedTest @ValueSource(strings={"08001","08006"})
    void transientDriverConnectionFailureIsSanitizedAndCannotClaim(String state) throws Exception {
        var pool=mock(HikariDataSource.class);var task=mock(RecoveryTask.class);
        when(pool.getConnection()).thenThrow(new SQLException("jdbc:fixture password=private-fixture",state));
        var failure=catchThrowable(()->RecoveryLambdaRuntime.runGuarded(pool,task,"PAYMENT",UUID.randomUUID(),()->true));
        assertThat(failure).isExactlyInstanceOf(RecoveryConnectionUnavailableException.class).hasNoCause();
        assertNoDriverText(failure);verifyNoInteractions(task);
    }

    @Test void permanentNextExceptionOverridesOuterConnectionState() {
        var wrapper=new SQLTransientConnectionException("pool fixture timeout","08001");
        wrapper.setNextException(new SQLException("private-fixture","28P01"));
        var failure=RecoveryConnectionFailures.sanitized(wrapper);
        assertThat(failure).isExactlyInstanceOf(RecoveryConnectionNonRetryableException.class);
        assertThat(((RecoveryConnectionNonRetryableException)failure).getReason()).isEqualTo(RecoveryConnectionNonRetryableException.Reason.AUTHENTICATION);
    }

    @Test void invalidConnectionAndCloseFailureCannotLeakSuppressedDriverText() throws Exception {
        var pool=mock(HikariDataSource.class);var connection=mock(Connection.class);var task=mock(RecoveryTask.class);
        when(pool.getConnection()).thenReturn(connection);when(connection.isValid(2)).thenReturn(false);
        doThrow(new SQLException("jdbc:fixture password=private-fixture","08006")).when(connection).close();
        var failure=catchThrowable(()->RecoveryLambdaRuntime.runGuarded(pool,task,"PAYMENT",UUID.randomUUID(),()->true));
        assertThat(failure).isExactlyInstanceOf(RecoveryConnectionUnavailableException.class).hasNoCause();
        assertThat(failure.getSuppressed()).isEmpty();assertNoDriverText(failure);verifyNoInteractions(task);
    }

    @Test void validationFailureClosesConnectionAndKeepsPermanentClassification() throws Exception {
        var pool=mock(HikariDataSource.class);var connection=mock(Connection.class);var task=mock(RecoveryTask.class);
        when(pool.getConnection()).thenReturn(connection);
        when(connection.isValid(2)).thenThrow(new SQLException("private-fixture","42501"));
        var failure=catchThrowable(()->RecoveryLambdaRuntime.runGuarded(pool,task,"PAYMENT",UUID.randomUUID(),()->true));
        assertThat(failure).isExactlyInstanceOf(RecoveryConnectionNonRetryableException.class).hasNoCause();
        verify(connection).close();verifyNoInteractions(task);assertNoDriverText(failure);
    }

    @Test void unknownSqlFailureDoesNotBecomeAnAutomaticRetry() {
        assertThat(RecoveryConnectionFailures.sanitized(new SQLException("private-fixture","42000")))
            .isExactlyInstanceOf(RecoveryConnectionNonRetryableException.class).hasNoCause();
        assertThat(RecoveryConnectionFailures.sanitized(new SQLException("private-fixture")))
            .isExactlyInstanceOf(RecoveryConnectionNonRetryableException.class).hasNoCause();
    }

    @Test void cyclicDriverExceptionsAreBoundedAndPermanentFailureWins() {
        var wrapper=new SQLTransientConnectionException("private-fixture");
        var permanent=new SQLException("private-fixture","28P01");
        wrapper.setNextException(permanent);permanent.setNextException(wrapper);
        assertThat(RecoveryConnectionFailures.sanitized(wrapper)).isExactlyInstanceOf(RecoveryConnectionNonRetryableException.class);
    }

    private static void assertNoDriverText(Throwable failure) {
        var rendered=new java.io.StringWriter();failure.printStackTrace(new java.io.PrintWriter(rendered));
        assertThat(rendered.toString()).doesNotContain("jdbc:fixture","private-fixture","pool fixture timeout");
    }
}
