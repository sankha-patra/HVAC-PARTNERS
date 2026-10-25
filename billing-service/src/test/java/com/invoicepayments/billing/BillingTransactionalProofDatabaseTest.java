package com.invoicepayments.billing;

import com.invoicepayments.billing.provider.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.mock.env.MockEnvironment;
import java.io.IOException;
import java.time.Duration;
import java.util.*;
import java.util.function.Supplier;
import static com.invoicepayments.billing.BillingStore.*;
import static com.invoicepayments.billing.BillingFinancialDatabaseTest.*;
import static org.assertj.core.api.Assertions.*;

/** One real payment flow with real Spring proxies. The existing bounded template owns the physical transaction;
 * REQUIRED write methods join it, so request-shortened deadlines and PostgreSQL local limits are preserved.
 * This is an executable proof, not a production-wide transaction migration.
 */
@EnabledIfEnvironmentVariable(named="BILLING_TEST_JDBC_URL", matches="jdbc:postgresql://127\\.0\\.0\\.1:15432/invoice_payments_test")
class BillingTransactionalProofDatabaseTest {
    BillingFinancialDatabaseTest fixture;
    @BeforeAll static void migrate() throws Exception { migrateOnlyIsolatedDatabase(); }
    @BeforeEach void seed() { fixture=new BillingFinancialDatabaseTest(); fixture.cleanAndSeedDedicatedDatabase(); }
    @AfterEach void cleanup() { fixture.removeOnlyTestFaultTrigger(); }

    @Test void requiredWriteMethodReallyUsesASpringTransactionProxy() {
        try(var pool=pool(); var context=context(pool)) {
            var step=context.getBean(WriteStep.class);
            assertThat(step.run(TransactionSynchronizationManager::isActualTransactionActive)).isTrue();
            assertThat(AopUtils.isAopProxy(step)).isTrue();
        }
    }

    @Test void checkedFailureRollsBackTheAnnotatedWrite() {
        long before=amount(fixture.db.one("SELECT version FROM billing.invoices WHERE id=?",INVOICE),"version");
        try(var pool=pool(); var context=context(pool)) {
            var step=context.getBean(WriteStep.class);
            assertThatThrownBy(()->step.checkedFailure(INVOICE)).isInstanceOf(IOException.class);
        }
        assertThat(amount(fixture.db.one("SELECT version FROM billing.invoices WHERE id=?",INVOICE),"version")).isEqualTo(before);
    }

    @Test void paymentPreparationCommitsAndReleasesItsConnectionBeforeProviderIO() {
        try(var pool=pool(); var context=context(pool)) {
            var step=context.getBean(WriteStep.class);
            assertThat(AopUtils.isAopProxy(step)).isTrue();
            var store=new ProxiedStore(new JdbcTemplate(pool),new DataSourceTransactionManager(pool),step);
            var observed=new BillingFinancialDatabaseTest.DelegateProvider(fixture.provider) {
                @Override public ProviderResult submit(ProviderCommand command) {
                    assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                    assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isFalse();
                    assertThat(pool.getHikariPoolMXBean().getActiveConnections()).isZero();
                    assertThat(fixture.db.one("SELECT status FROM billing.attempts WHERE intent_id=?",UUID.fromString(command.intentId())))
                        .containsEntry("status","SUBMITTING");
                    super.submit(command);
                    return delegate.simulateResult(command.intentId(),"CAPTURED");
                }
            };
            var service=new BillingService(store,observed,new MockEnvironment(),true,"http://localhost:4200",MERCHANT.toString());
            UUID intent=id(service.create(PAYER,ACCOUNT,UUID.randomUUID().toString(),List.of(INVOICE),"CARD").get("id"));
            step.transactionIds().clear();
            assertThat(service.submit(PAYER,intent)).containsEntry("status","CAPTURED");
            assertThat(step.transactionIds().stream().distinct().count()).isEqualTo(2);
            assertThat(fixture.db.rows("SELECT id FROM billing.journals WHERE intent_id=?",intent)).hasSize(1);
            assertThat(pool.getHikariPoolMXBean().getActiveConnections()).isZero();
        }
    }

    @Test void recordingThroughTheProxyRollsBackAllEffectsAndCanBeReconciledOnce() {
        try(var pool=pool(); var context=context(pool)) {
            var step=context.getBean(WriteStep.class);
            var store=new ProxiedStore(new JdbcTemplate(pool),new DataSourceTransactionManager(pool),step);
            var observed=new BillingFinancialDatabaseTest.DelegateProvider(fixture.provider) {
                @Override public ProviderResult submit(ProviderCommand command) {
                    super.submit(command); return delegate.simulateResult(command.intentId(),"CAPTURED");
                }
            };
            var service=new BillingService(store,observed,new MockEnvironment(),true,"http://localhost:4200",MERCHANT.toString());
            UUID intent=id(service.create(PAYER,ACCOUNT,UUID.randomUUID().toString(),List.of(INVOICE),"CARD").get("id"));
            step.failIf(()->store.exists("SELECT 1 FROM billing.journals WHERE effect_key=?","capture:"+intent));
            assertThatThrownBy(()->service.submit(PAYER,intent)).isInstanceOf(IllegalStateException.class)
                .hasMessage("Proof failure after recording effects");
            assertThat(fixture.db.one("SELECT status FROM billing.intents WHERE id=?",intent)).containsEntry("status","SUBMITTING");
            assertThat(fixture.db.rows("SELECT id FROM billing.journals WHERE intent_id=?",intent)).isEmpty();
            assertThat(fixture.db.rows("SELECT id FROM billing.outbox WHERE aggregate_id=?",intent)).isEmpty();
            assertThat(fixture.db.one("SELECT state FROM billing.allocations WHERE intent_id=?",intent)).containsEntry("state","RESERVED");
            step.failIf(()->false);
            var captured=fixture.provider.lookup(BillingService.operationKey(intent),null);
            assertThat(captured.state()).isEqualTo("CAPTURED");
            service.recordResult(intent,null,captured); service.recordResult(intent,null,captured);
            assertThat(fixture.db.one("SELECT status FROM billing.intents WHERE id=?",intent)).containsEntry("status","CAPTURED");
            assertThat(fixture.db.rows("SELECT id FROM billing.journals WHERE intent_id=?",intent)).hasSize(1);
        }
    }

    @Test void annotatedStepsKeepTheRequestShortenedPhysicalDeadlineAndLocalLimits() {
        try(var pool=pool(); var context=context(pool); var budget=BillingExecutionBudget.limit(Duration.ofSeconds(2))) {
            var step=context.getBean(WriteStep.class);
            var store=new ProxiedStore(new JdbcTemplate(pool),new DataSourceTransactionManager(pool),step);
            var limits=store.tx(()->{
                var holder=(org.springframework.jdbc.datasource.ConnectionHolder)TransactionSynchronizationManager.getResource(pool);
                assertThat(holder).isNotNull();
                assertThat(holder.getTimeToLiveInMillis()).isBetween(1L,2000L);
                return store.one("SELECT current_setting('lock_timeout') AS locks,current_setting('statement_timeout') AS statements");
            });
            long lockMillis=Long.parseLong(limits.get("locks").toString().replace("ms",""));
            long statementMillis=Long.parseLong(limits.get("statements").toString().replace("ms",""));
            assertThat(lockMillis).isBetween(1L,2000L);
            assertThat(statementMillis).isBetween(1L,2000L);
        }
    }

    static com.zaxxer.hikari.HikariDataSource pool() {
        var config=new com.zaxxer.hikari.HikariConfig();
        config.setJdbcUrl(System.getenv("BILLING_TEST_JDBC_URL"));
        config.setUsername(System.getenv("BILLING_TEST_DB_USER"));
        config.setPassword(System.getenv("BILLING_TEST_DB_PASSWORD"));
        config.setMaximumPoolSize(1); config.setMinimumIdle(0);
        return new com.zaxxer.hikari.HikariDataSource(config);
    }
    static AnnotationConfigApplicationContext context(javax.sql.DataSource source) {
        var context=new AnnotationConfigApplicationContext();
        context.registerBean(PlatformTransactionManager.class,()->new DataSourceTransactionManager(source));
        context.registerBean(WriteStep.class,()->new WriteStep(new JdbcTemplate(source)));
        context.register(ProofConfiguration.class); context.refresh(); return context;
    }
    @Configuration(proxyBeanMethods=false)
    @EnableTransactionManagement(proxyTargetClass=true)
    static class ProofConfiguration {}

    public static class WriteStep {
        final JdbcTemplate jdbc;
        final List<Long> transactions=new ArrayList<>();
        java.util.function.BooleanSupplier fail=()->false;
        WriteStep(JdbcTemplate jdbc) { this.jdbc=jdbc; }
        public List<Long> transactionIds() { return transactions; }
        public void failIf(java.util.function.BooleanSupplier fail) { this.fail=fail; }
        @Transactional(propagation=Propagation.REQUIRED, rollbackFor=Exception.class)
        public <T> T run(Supplier<T> action) {
            if(TransactionSynchronizationManager.isActualTransactionActive())
                transactions.add(jdbc.queryForObject("SELECT txid_current()",Long.class));
            T answer=action.get();
            if(fail.getAsBoolean()) throw new IllegalStateException("Proof failure after recording effects");
            return answer;
        }
        @Transactional(propagation=Propagation.REQUIRED, rollbackFor=Exception.class)
        public void checkedFailure(UUID invoice) throws IOException {
            jdbc.update("UPDATE billing.invoices SET version=version+1 WHERE id=?",invoice);
            throw new IOException("Checked failure after a write");
        }
    }
    static class ProxiedStore extends BillingStore {
        final WriteStep step;
        ProxiedStore(JdbcTemplate jdbc,PlatformTransactionManager manager,WriteStep step) { super(jdbc,manager); this.step=step; }
        @Override public <T> T tx(Supplier<T> action) { return super.tx(()->step.run(action)); }
    }
}
