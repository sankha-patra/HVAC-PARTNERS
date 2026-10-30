package com.invoicepayments.billing;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.*;

class OperationalAlertTest {
    private final ListAppender<ILoggingEvent> lines=new ListAppender<>();
    private final Logger logger=(Logger)LoggerFactory.getLogger("operational-alerts");
    @BeforeEach void capture(){lines.start();logger.addAppender(lines);}
    @AfterEach void release(){logger.detachAppender(lines);TransactionSynchronizationManager.clear();}

    @Test void lineHasTheShapeCloudWatchFiltersMatch() {
        UUID intent=UUID.fromString("9da3f8cc-1143-4e36-8c4e-ec8ddf4390c3");
        OperationalAlert.raise("PAYMENT_OUTCOME_UNKNOWN",OperationalAlert.Severity.WARNING,"Provider \"timed\"\nout","intentId",intent,"refundId",null);
        assertThat(lines.list).singleElement().satisfies(line->{
            assertThat(line.getLevel()).isEqualTo(Level.WARN);
            assertThat(line.getFormattedMessage()).isEqualTo(
                "ALERT code=PAYMENT_OUTCOME_UNKNOWN severity=WARNING intentId=9da3f8cc-1143-4e36-8c4e-ec8ddf4390c3 reason=\"Provider  timed  out\"");
        });
    }
    @Test void criticalIsAnErrorAndFieldsCannotBreakTheLine() {
        OperationalAlert.raise("DATABASE_UNAVAILABLE",OperationalAlert.Severity.CRITICAL,"x".repeat(500),"reference","a b\"c");
        assertThat(lines.list).singleElement().satisfies(line->{
            assertThat(line.getLevel()).isEqualTo(Level.ERROR);
            assertThat(line.getFormattedMessage()).startsWith("ALERT code=DATABASE_UNAVAILABLE severity=CRITICAL reference=a_b_c reason=\"").hasSize(74+200+1).endsWith("x\"");
        });
        assertThatThrownBy(()->OperationalAlert.raise("lower",OperationalAlert.Severity.WARNING,"r")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->OperationalAlert.raise("ODD_FIELDS",OperationalAlert.Severity.WARNING,"r","key")).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void insideATransactionTheLineWaitsForCommitAndARollbackRaisesNothing() {
        TransactionSynchronizationManager.initSynchronization();
        OperationalAlert.raise("FINANCE_CASE_OPENED",OperationalAlert.Severity.WARNING,"needs a decision","caseReason","PAYMENT_UNRESOLVED");
        assertThat(lines.list).isEmpty();
        List<TransactionSynchronization> pending=TransactionSynchronizationManager.getSynchronizations();
        pending.forEach(s->s.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
        assertThat(lines.list).isEmpty();
        pending.forEach(TransactionSynchronization::afterCommit);
        assertThat(lines.list).singleElement().extracting(ILoggingEvent::getFormattedMessage).asString().contains("code=FINANCE_CASE_OPENED","caseReason=PAYMENT_UNRESOLVED");
    }
    @Test void onlyAnUnreachableDatabaseCountsAsUnavailable() {
        assertThat(OperationalAlert.databaseUnavailable(new CannotGetJdbcConnectionException("pool",new SQLException("refused")))).isTrue();
        assertThat(OperationalAlert.databaseUnavailable(new RuntimeException(new SQLTransientConnectionException("timeout")))).isTrue();
        assertThat(OperationalAlert.databaseUnavailable(new org.springframework.dao.DataIntegrityViolationException("constraint"))).isFalse();
        assertThat(OperationalAlert.databaseUnavailable(new IllegalStateException("bug"))).isFalse();
    }
    @Test void watchRaisesOnStartRepeatsEveryFifteenMinutesAndResolvesOnce() {
        var registry=new SimpleMeterRegistry();var outbox=new double[]{0};
        Gauge.builder("billing.outbox.oldest.seconds",outbox,v->v[0]).register(registry);
        var now=new AtomicReference<>(Instant.parse("2026-10-30T04:51:00Z"));
        var watch=new OperationalWatch(registry,new Clock(){
            public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId z){return this;}public Instant instant(){return now.get();}});
        watch.check();assertThat(lines.list).isEmpty();
        outbox[0]=125;watch.check();
        assertThat(lines.list).singleElement().extracting(ILoggingEvent::getFormattedMessage).asString()
            .startsWith("ALERT code=OUTBOX_NOT_DRAINING severity=WARNING oldestSeconds=125 reason=");
        now.set(now.get().plusSeconds(600));watch.check();assertThat(lines.list).hasSize(1);
        now.set(now.get().plusSeconds(300));watch.check();assertThat(lines.list).hasSize(2);
        outbox[0]=0;watch.check();watch.check();
        assertThat(lines.list).hasSize(3).last().extracting(ILoggingEvent::getFormattedMessage).isEqualTo("RESOLVED alert code=OUTBOX_NOT_DRAINING");
    }
}
