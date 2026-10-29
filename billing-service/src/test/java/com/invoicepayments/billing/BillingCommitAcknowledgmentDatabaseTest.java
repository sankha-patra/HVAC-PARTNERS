package com.invoicepayments.billing;

import com.invoicepayments.billing.provider.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import javax.sql.DataSource;
import java.lang.reflect.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import static com.invoicepayments.billing.BillingFinancialDatabaseTest.*;
import static com.invoicepayments.billing.BillingStore.*;
import static org.assertj.core.api.Assertions.*;

/** A COMMIT that succeeded before its acknowledgment was lost is not a rollback. */
@EnabledIfEnvironmentVariable(named="BILLING_TEST_JDBC_URL",matches="jdbc:postgresql://127\\.0\\.0\\.1:15432/invoice_payments_test")
class BillingCommitAcknowledgmentDatabaseTest {
    BillingFinancialDatabaseTest fixture;
    @BeforeAll static void migrate() throws Exception{migrateOnlyIsolatedDatabase();}
    @BeforeEach void seed(){fixture=new BillingFinancialDatabaseTest();fixture.cleanAndSeedDedicatedDatabase();}
    @AfterEach void cleanup(){fixture.removeOnlyTestFaultTrigger();}
    @Test void lostPreparationCommitAckKeepsTheSavedOperationAndDoesNotDispatch() throws Exception {
        var sent=new AtomicInteger();var closed=new AtomicInteger();
        var observed=new DelegateProvider(fixture.provider){
            @Override public ProviderPermit admitNewPayment(){return closed::incrementAndGet;}
            @Override public ProviderResult submit(ProviderCommand command){sent.incrementAndGet();return super.submit(command);}
        };
        try(var pool=BillingAnnotationHarnessDatabaseTest.pool()) {
            var source=new LostAckSource(pool);
            try(var context=BillingTransactionalProofDatabaseTest.context(source,observed)) {
                var service=context.getBean(BillingService.class);
                UUID intent=id(service.create(PAYER,ACCOUNT,UUID.randomUUID().toString(),List.of(INVOICE),"CARD").get("id"));
                source.loseNext.set(true);
                assertThatThrownBy(()->service.submit(PAYER,intent)).isInstanceOf(RuntimeException.class)
                    .hasRootCauseMessage("simulated lost COMMIT acknowledgment after commit");
                assertThat(fixture.db.one("SELECT status FROM billing.intents WHERE id=?",intent)).containsEntry("status","SUBMITTING");
                assertThat(fixture.db.one("SELECT operation_key FROM billing.attempts WHERE intent_id=?",intent)).containsEntry("operationKey",BillingService.operationKey(intent));
                assertThat(closed).hasValue(1);assertThat(sent).hasValue(0);
                assertThat(service.submit(PAYER,intent)).containsEntry("status","SUBMITTING");
                assertThat(sent).hasValue(0);
                assertThat(pool.getHikariPoolMXBean().getActiveConnections()).isZero();
                assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isFalse();
            }
        }
    }
    @Test void lostRecordingCommitAckKeepsThePostedMoneyAndRepeatedEvidenceDoesNotDuplicateIt() throws Exception {
        try(var pool=BillingAnnotationHarnessDatabaseTest.pool()) {
            var source=new LostAckSource(pool);
            var observed=new DelegateProvider(fixture.provider){
                @Override public ProviderResult submit(ProviderCommand command){
                    super.submit(command);var paid=delegate.simulateResult(command.intentId(),"CAPTURED");source.loseNext.set(true);return paid;
                }
            };
            try(var context=BillingTransactionalProofDatabaseTest.context(source,observed)) {
                var service=context.getBean(BillingService.class);
                UUID intent=id(service.create(PAYER,ACCOUNT,UUID.randomUUID().toString(),List.of(INVOICE),"CARD").get("id"));
                assertThatThrownBy(()->service.submit(PAYER,intent)).isInstanceOf(RuntimeException.class)
                    .hasRootCauseMessage("simulated lost COMMIT acknowledgment after commit");
                assertThat(fixture.db.one("SELECT status FROM billing.intents WHERE id=?",intent)).containsEntry("status","CAPTURED");
                var paid=fixture.provider.lookup(BillingService.operationKey(intent),null);
                service.recordResult(intent,null,paid);service.recordResult(intent,null,paid);
                assertThat(fixture.db.rows("SELECT id FROM billing.journals WHERE intent_id=?",intent)).hasSize(1);
                assertThat(fixture.db.rows("SELECT id FROM billing.outbox WHERE aggregate_id=?",intent)).hasSize(1);
                assertThat(fixture.db.one("SELECT paid_minor,reserved_minor FROM billing.invoices WHERE id=?",INVOICE))
                    .containsEntry("paidMinor",10000L).containsEntry("reservedMinor",0L);
                assertThat(pool.getHikariPoolMXBean().getActiveConnections()).isZero();
            }
        }
    }
    static final class LostAckSource extends DelegatingDataSource {
        final AtomicBoolean loseNext=new AtomicBoolean();LostAckSource(DataSource source){super(source);}
        @Override public Connection getConnection() throws SQLException {
            var connection=super.getConnection();
            return (Connection)Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{Connection.class},(proxy,method,args)->{
                try {
                    Object result=method.invoke(connection,args);
                    if(method.getName().equals("commit") && loseNext.getAndSet(false))
                        throw new SQLRecoverableException("simulated lost COMMIT acknowledgment after commit","08006");
                    return result;
                }catch(InvocationTargetException failure){throw failure.getCause();}
            });
        }
    }
}
