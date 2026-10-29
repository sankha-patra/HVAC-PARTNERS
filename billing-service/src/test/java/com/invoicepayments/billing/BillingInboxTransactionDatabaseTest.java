package com.invoicepayments.billing;

import com.invoicepayments.billing.provider.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.mock.env.MockEnvironment;
import java.util.*;
import static com.invoicepayments.billing.BillingFinancialDatabaseTest.*;
import static com.invoicepayments.billing.BillingStore.*;
import static org.assertj.core.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="BILLING_TEST_JDBC_URL",matches="jdbc:postgresql://127\\.0\\.0\\.1:15432/invoice_payments_test")
class BillingInboxTransactionDatabaseTest {
    BillingFinancialDatabaseTest fixture;
    @BeforeAll static void migrate() throws Exception{migrateOnlyIsolatedDatabase();}
    @BeforeEach void seed(){fixture=new BillingFinancialDatabaseTest();fixture.cleanAndSeedDedicatedDatabase();}
    @AfterEach void cleanup(){fixture.removeOnlyTestFaultTrigger();}
    @Test void receiptAndProcessingAreSeparateCommitsAndFinancialEffectsJoinProcessing() throws Exception {
        try(var context=context()) {
            var payments=context.getBean(BillingService.class);var inbox=context.getBean(BillingInbox.class);
            assertThat(AopUtils.isAopProxy(context.getBean(Class.forName("com.invoicepayments.billing.BillingInboxCommands")))).isTrue();
            UUID intent=id(payments.create(PAYER,ACCOUNT,UUID.randomUUID().toString(),List.of(INVOICE),"CARD").get("id"));
            payments.submit(PAYER,intent);var paid=fixture.provider.simulateResult(intent.toString(),"CAPTURED");
            inbox.receive("SIMULATOR",new ProviderWebhookEvent("annotated-inbox",paid.providerId(),intent.toString(),"CAPTURED",10000,"USD",null));
            var receipt=fixture.db.one("SELECT state,xmin::text::bigint AS transaction_id FROM billing.webhook_inbox WHERE event_id='annotated-inbox'");
            assertThat(receipt).containsEntry("state","NEW");
            inbox.process("SIMULATOR","annotated-inbox");
            long processed=amount(fixture.db.one("SELECT xmin::text::bigint AS transaction_id FROM billing.webhook_inbox WHERE event_id='annotated-inbox' AND state='PROCESSED'"),"transactionId");
            long journal=amount(fixture.db.one("SELECT xmin::text::bigint AS transaction_id FROM billing.journals WHERE intent_id=?",intent),"transactionId");
            long event=amount(fixture.db.one("SELECT xmin::text::bigint AS transaction_id FROM billing.outbox WHERE aggregate_id=?",intent),"transactionId");
            assertThat(processed).isEqualTo(journal).isEqualTo(event).isNotEqualTo(amount(receipt,"transactionId"));
        }
    }
    @Test void processingRollbackPrecedesFailureBookkeepingAndLaterRetryPostsOnce() throws Exception {
        try(var context=context()) {
            var payments=context.getBean(BillingService.class);var inbox=context.getBean(BillingInbox.class);
            UUID intent=id(payments.create(PAYER,ACCOUNT,UUID.randomUUID().toString(),List.of(INVOICE),"CARD").get("id"));
            payments.submit(PAYER,intent);var paid=fixture.provider.simulateResult(intent.toString(),"CAPTURED");
            inbox.receive("SIMULATOR",new ProviderWebhookEvent("annotated-failure",paid.providerId(),intent.toString(),"CAPTURED",10000,"USD",null));
            fixture.injectTransientFault("CAPTURED");inbox.process("SIMULATOR","annotated-failure");
            assertThat(fixture.db.one("SELECT state,attempts FROM billing.webhook_inbox WHERE event_id='annotated-failure'"))
                .containsEntry("state","NEW").containsEntry("attempts",1);
            assertThat(fixture.db.rows("SELECT id FROM billing.journals WHERE intent_id=?",intent)).isEmpty();
            assertThat(fixture.db.rows("SELECT id FROM billing.outbox WHERE aggregate_id=?",intent)).isEmpty();
            fixture.removeTransientFault();inbox.process("SIMULATOR","annotated-failure");inbox.process("SIMULATOR","annotated-failure");
            assertThat(fixture.db.rows("SELECT id FROM billing.journals WHERE intent_id=?",intent)).hasSize(1);
            assertThat(fixture.db.rows("SELECT id FROM billing.outbox WHERE aggregate_id=?",intent)).hasSize(1);
        }
    }
    AnnotationConfigApplicationContext context() throws Exception {
        Class.forName("com.invoicepayments.billing.BillingInboxCommands");
        var context=BillingTestWiring.context(fixture.db,fixture.provider,new MockEnvironment(),true,"http://localhost:4200",MERCHANT.toString());
        context.registerBean(BillingService.class);context.registerBean(BillingRefundService.class);context.registerBean(BillingInbox.class);
        context.refresh();return context;
    }
}
