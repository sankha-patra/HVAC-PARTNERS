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
    @Test void watchWritesTheConditionEveryCheckWhileItHoldsThenResolvesOnce() {
        var registry=new SimpleMeterRegistry();var outbox=new double[]{0};
        Gauge.builder("billing.outbox.oldest.seconds",outbox,v->v[0]).register(registry);
        var watch=new OperationalWatch(registry,Clock.systemUTC());
        watch.check();assertThat(alerts()).isEmpty();
        assertThat(lines.list).extracting(ILoggingEvent::getFormattedMessage).containsExactly("HEARTBEAT service=billing db=up");
        outbox[0]=125;watch.check();outbox[0]=190;watch.check();
        assertThat(alerts()).containsExactly(
            "ALERT code=OUTBOX_NOT_DRAINING severity=WARNING oldestSeconds=125 reason=\"Committed events are not reaching Kafka; payments are safe, order updates and emails wait\"",
            "ALERT code=OUTBOX_NOT_DRAINING severity=WARNING oldestSeconds=190 reason=\"Committed events are not reaching Kafka; payments are safe, order updates and emails wait\"");
        outbox[0]=0;watch.check();watch.check();
        assertThat(lines.list).extracting(ILoggingEvent::getFormattedMessage).filteredOn(m->m.startsWith("ALERT_RESOLVED")).containsExactly("ALERT_RESOLVED code=OUTBOX_NOT_DRAINING");
    }
    @Test void anUnreadableValueKeepsARaisedConditionInsteadOfReportingItCleared() {
        var registry=new SimpleMeterRegistry();var outbox=new double[]{125};
        Gauge.builder("billing.outbox.oldest.seconds",outbox,v->v[0]).register(registry);
        var watch=new OperationalWatch(registry,Clock.systemUTC());
        watch.check();outbox[0]=Double.NaN;watch.check();watch.check();
        assertThat(alerts()).hasSize(3).allMatch(m->m.contains("oldestSeconds=125"));
        assertThat(lines.list).extracting(ILoggingEvent::getFormattedMessage).noneMatch(m->m.startsWith("ALERT_RESOLVED"));
    }
    @Test void stuckMoneyAndDeadLettersAreConditionsWithTheirCount() {
        var registry=new SimpleMeterRegistry();
        Gauge.builder("billing.payments.stuck.unowned",()->2).register(registry);Gauge.builder("billing.refunds.stuck.unowned",()->1).register(registry);
        Gauge.builder("billing.deadletters.open",()->4).register(registry);
        new OperationalWatch(registry,Clock.systemUTC()).check();
        assertThat(alerts()).anyMatch(m->m.startsWith("ALERT code=MONEY_OPERATION_STUCK severity=WARNING count=3 "))
            .anyMatch(m->m.startsWith("ALERT code=DEAD_LETTER_OPEN severity=WARNING count=4 "));
    }
    @Test void anOutageAlertRaisedOnEveryRequestIsWrittenAtMostOncePerInterval() {
        OperationalAlert.resetThrottle();
        var now=new AtomicReference<>(Instant.parse("2026-11-01T04:51:00Z"));
        OperationalAlert.clock=new Clock(){public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId z){return this;}public Instant instant(){return now.get();}};
        try {
            for(int i=0;i<5;i++)OperationalAlert.raiseAtMostEvery(Duration.ofMinutes(1),"http","DATABASE_UNAVAILABLE",OperationalAlert.Severity.CRITICAL,"down","source","http");
            OperationalAlert.raiseAtMostEvery(Duration.ofMinutes(1),"watch","DATABASE_UNAVAILABLE",OperationalAlert.Severity.CRITICAL,"down","source","watch");
            assertThat(alerts()).hasSize(2);
            now.set(now.get().plusSeconds(61));
            OperationalAlert.raiseAtMostEvery(Duration.ofMinutes(1),"http","DATABASE_UNAVAILABLE",OperationalAlert.Severity.CRITICAL,"down","source","http");
            assertThat(alerts()).hasSize(3);
        } finally {OperationalAlert.clock=Clock.systemUTC();OperationalAlert.resetThrottle();}
    }
    @Test void transientDatabaseFailuresAreWaitedOutByConsumers() {
        assertThat(OperationalAlert.transientDatabaseFailure(new org.springframework.transaction.CannotCreateTransactionException("no connection"))).isTrue();
        assertThat(OperationalAlert.transientDatabaseFailure(new RuntimeException(new org.springframework.dao.QueryTimeoutException("slow")))).isTrue();
        assertThat(OperationalAlert.transientDatabaseFailure(new IllegalStateException("Paid order has no active stock reservation"))).isFalse();
    }
    private List<String> alerts(){return lines.list.stream().map(ILoggingEvent::getFormattedMessage).filter(m->m.startsWith("ALERT code=")).toList();}
}
