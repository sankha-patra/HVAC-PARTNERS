package com.invoicepayments.billing;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import com.zaxxer.hikari.*;
import javax.sql.DataSource;
import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import static com.invoicepayments.billing.BillingFinancialDatabaseTest.*;
import static org.assertj.core.api.Assertions.*;

/** Production transaction configuration, actual proxies and PostgreSQL; no outer test transaction/template. */
@EnabledIfEnvironmentVariable(named="BILLING_TEST_JDBC_URL",matches="jdbc:postgresql://127\\.0\\.0\\.1:15432/invoice_payments_test")
class BillingAnnotationHarnessDatabaseTest {
    BillingFinancialDatabaseTest fixture;
    @BeforeAll static void migrate() throws Exception { migrateOnlyIsolatedDatabase(); }
    @BeforeEach void seed() { fixture=new BillingFinancialDatabaseTest(); fixture.cleanAndSeedDedicatedDatabase(); }
    @AfterEach void cleanup() { fixture.removeOnlyTestFaultTrigger(); }

    @Test void productionConfigurationOwnsTheAnnotatedPhysicalTransaction() throws Exception {
        try(var pool=pool(); var context=context(pool)) {
            var command=context.getBean(ProbeCommand.class);
            assertThat(AopUtils.isAopProxy(command)).isTrue();
            assertThat(context.getBean("transactionManager").getClass().getSimpleName()).isEqualTo("BillingDeadlineTransactionManager");
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(command.state()).containsEntry("actual",true).containsEntry("synchronization",true);
            assertThat(pool.getHikariPoolMXBean().getActiveConnections()).isZero();
            assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isFalse();
        }
    }
    @Test void requiredAndMandatoryParticipantsJoinTheSamePhysicalTransaction() throws Exception {
        try(var pool=pool(); var context=context(pool)) {
            var ids=context.getBean(ProbeCommand.class).joinedIds();
            assertThat(ids).hasSize(2);assertThat(ids.get(0)).isEqualTo(ids.get(1));
            assertThatThrownBy(()->context.getBean(ProbeParticipant.class).transactionId())
                .isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
        }
    }
    @Test void checkedExceptionsRollBackTheWholeAnnotatedUnit() throws Exception {
        long version=BillingStore.amount(fixture.db.one("SELECT version FROM billing.invoices WHERE id=?",INVOICE),"version");
        try(var pool=pool();var context=context(pool)) {
            assertThatThrownBy(()->context.getBean(ProbeCommand.class).checkedFailure())
                .isInstanceOf(IOException.class);
        }
        assertThat(BillingStore.amount(fixture.db.one("SELECT version FROM billing.invoices WHERE id=?",INVOICE),"version")).isEqualTo(version);
    }
    @Test void labelledTransactionsRetainRequestShortenedPhysicalAndDatabaseLimits() throws Exception {
        try(var pool=pool();var context=context(pool);var budget=BillingExecutionBudget.limit(Duration.ofSeconds(2))) {
            var state=context.getBean(ProbeCommand.class).state();
            assertThat(((Number)state.get("ttl")).longValue()).isBetween(1L,2000L);
            assertThat(milliseconds(state.get("locks"))).isBetween(1L,2000L);
            assertThat(milliseconds(state.get("statements"))).isBetween(1L,2000L);
            assertThat(state).containsEntry("idle","20s");
        }
    }
    @Test void localLimitsDoNotLeakToTheNextPoolBorrower() throws Exception {
        try(var pool=pool();var context=context(pool)) {
            var jdbc=new JdbcTemplate(pool);
            var before=jdbc.queryForMap("SELECT current_setting('lock_timeout') AS locks,current_setting('statement_timeout') AS statements");
            context.getBean(ProbeCommand.class).state();
            assertThat(jdbc.queryForMap("SELECT current_setting('lock_timeout') AS locks,current_setting('statement_timeout') AS statements")).isEqualTo(before);
            try(var connection=pool.getConnection()) { assertThat(connection.getAutoCommit()).isTrue(); }
        }
    }
    @Test void anAmbientTransactionIsRejectedBeforeOrchestration() throws Exception {
        try(var pool=pool();var context=context(pool)) {
            assertThatThrownBy(()->context.getBean(ProbeCommand.class).guard())
                .isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
        }
    }


    @Test void unlabelledIndependentProviderStorageRetainsItsOwnTimeout() throws Exception {
        try(var pool=pool();var context=context(pool);var budget=BillingExecutionBudget.limit(Duration.ofSeconds(2))) {
            var state=context.getBean(UnlabelledProbe.class).state();
            assertThat(((Number)state.get("ttl")).longValue()).isGreaterThan(2000L);
            assertThat(state).containsEntry("locks","0").containsEntry("statements","0");
        }
    }
    @Test void failedSetupRollsBackWritesAndReturnsACleanConnection() throws Exception {
        try(var pool=pool()) {
            var timeout=new java.util.concurrent.atomic.AtomicInteger();
            var fail=new java.util.concurrent.atomic.AtomicBoolean(true);
            var source=new org.springframework.jdbc.datasource.DelegatingDataSource(pool) {
                @Override public java.sql.Connection getConnection() throws java.sql.SQLException {
                    var connection=super.getConnection();
                    return (java.sql.Connection)java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{java.sql.Connection.class},(proxy,method,args)->{
                        try {
                            Object result=method.invoke(connection,args);
                            if(method.getName().equals("prepareStatement") && args[0].toString().contains("set_config")) {
                                var statement=(java.sql.PreparedStatement)result;
                                return java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{java.sql.PreparedStatement.class},(p,m,a)->{
                                    if(m.getName().equals("setQueryTimeout"))timeout.set((Integer)a[0]);
                                    if(m.getName().equals("executeQuery") && fail.getAndSet(false)) {
                                        try(var raw=connection.createStatement()) {
                                            raw.execute("SELECT set_config('lock_timeout','777ms',true)");
                                            raw.execute("UPDATE billing.invoices SET version=version+1 WHERE id='"+INVOICE+"'");
                                        }
                                        throw new java.sql.SQLException("injected setup failure","XX000");
                                    }
                                    try{return m.invoke(statement,a);}catch(java.lang.reflect.InvocationTargetException ex){throw ex.getCause();}
                                });
                            }
                            return result;
                        }catch(java.lang.reflect.InvocationTargetException ex){throw ex.getCause();}
                    });
                }
            };
            long before=BillingStore.amount(fixture.db.one("SELECT version FROM billing.invoices WHERE id=?",INVOICE),"version");
            try(var context=context(source)) {
                assertThatThrownBy(()->context.getBean(ProbeCommand.class).state())
                    .isInstanceOf(org.springframework.transaction.CannotCreateTransactionException.class)
                    .hasRootCauseMessage("injected setup failure");
                assertThat(timeout).hasPositiveValue();
                assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isFalse();
                assertThat(pool.getHikariPoolMXBean().getActiveConnections()).isZero();
                assertThat(BillingStore.amount(fixture.db.one("SELECT version FROM billing.invoices WHERE id=?",INVOICE),"version")).isEqualTo(before);
                try(var connection=pool.getConnection()) {
                    assertThat(connection.getAutoCommit()).isTrue();
                    try(var statement=connection.createStatement();var result=statement.executeQuery("SHOW lock_timeout")){result.next();assertThat(result.getString(1)).isEqualTo("0");}
                }
                assertThat(context.getBean(ProbeCommand.class).state()).containsEntry("actual",true);
            }
        }
    }

    static long milliseconds(Object setting) {
        String value=setting.toString();
        return value.endsWith("ms") ? Long.parseLong(value.substring(0,value.length()-2)) : Long.parseLong(value.substring(0,value.length()-1))*1000;
    }
    static HikariDataSource pool() {
        var config=new HikariConfig();
        config.setJdbcUrl(System.getenv("BILLING_TEST_JDBC_URL"));
        config.setUsername(System.getenv("BILLING_TEST_DB_USER"));
        config.setPassword(System.getenv("BILLING_TEST_DB_PASSWORD"));
        config.setMaximumPoolSize(1);config.setMinimumIdle(0);config.setConnectionTimeout(2000);
        return new HikariDataSource(config);
    }
    static AnnotationConfigApplicationContext context(DataSource source) throws Exception {
        var context=new AnnotationConfigApplicationContext();
        context.getEnvironment().setActiveProfiles("billing");
        context.registerBean(DataSource.class,()->source);
        context.registerBean(JdbcTemplate.class,()->new JdbcTemplate(source));
        // Reflection makes the initial red run a missing-foundation failure rather than uncompilable test code.
        context.register(Class.forName("com.invoicepayments.billing.BillingTransactionConfiguration"));
        context.registerBean(UnlabelledProbe.class);
        context.registerBean(ProbeParticipant.class);
        context.registerBean(ProbeCommand.class);
        context.refresh();return context;
    }
    @Transactional(transactionManager="transactionManager",propagation=Propagation.REQUIRED,rollbackFor=Exception.class,timeout=10,label="billing")
    public static class ProbeCommand {
        final JdbcTemplate jdbc;final DataSource source;final ProbeParticipant participant;Long lastId;
        ProbeCommand(JdbcTemplate jdbc,DataSource source,ProbeParticipant participant){this.jdbc=jdbc;this.source=source;this.participant=participant;}
        public Map<String,Object> state() {
            BillingExecutionBudget.requireTime();
            var state=jdbc.queryForMap("SELECT current_setting('lock_timeout') AS locks,current_setting('statement_timeout') AS statements,current_setting('idle_in_transaction_session_timeout') AS idle");
            state.put("actual",TransactionSynchronizationManager.isActualTransactionActive());
            state.put("synchronization",TransactionSynchronizationManager.isSynchronizationActive());
            state.put("ttl",((ConnectionHolder)TransactionSynchronizationManager.getResource(source)).getTimeToLiveInMillis());
            return state;
        }
        public java.util.List<Long> joinedIds(){lastId=jdbc.queryForObject("SELECT txid_current()",Long.class);return java.util.List.of(lastId,participant.transactionId());}
        public void checkedFailure() throws IOException {jdbc.update("UPDATE billing.invoices SET version=version+1 WHERE id=?",INVOICE);throw new IOException("checked failure");}
        public void guard(){BillingStore.requireNoTransaction();}
    }
    @Transactional(transactionManager="transactionManager",propagation=Propagation.MANDATORY,rollbackFor=Exception.class,label="billing")
    public static class ProbeParticipant {
        final JdbcTemplate jdbc;ProbeParticipant(JdbcTemplate jdbc){this.jdbc=jdbc;}
        public Long transactionId(){BillingStore.requireTransaction();return jdbc.queryForObject("SELECT txid_current()",Long.class);}
    }

    public static class UnlabelledProbe {
        final JdbcTemplate jdbc;final DataSource source;
        UnlabelledProbe(JdbcTemplate jdbc,DataSource source){this.jdbc=jdbc;this.source=source;}
        @Transactional(transactionManager="transactionManager",propagation=Propagation.REQUIRES_NEW,rollbackFor=Exception.class,timeout=10)
        public Map<String,Object> state(){
            var result=jdbc.queryForMap("SELECT current_setting('lock_timeout') AS locks,current_setting('statement_timeout') AS statements");
            result.put("ttl",((ConnectionHolder)TransactionSynchronizationManager.getResource(source)).getTimeToLiveInMillis());return result;
        }
    }

}
