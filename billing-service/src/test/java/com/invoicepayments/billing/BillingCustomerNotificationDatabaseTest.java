package com.invoicepayments.billing;

import com.invoicepayments.billing.notification.*;
import org.json.JSONObject;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static com.invoicepayments.billing.BillingFinancialDatabaseTest.*;
import static com.invoicepayments.billing.BillingStore.*;
import static org.assertj.core.api.Assertions.*;

/** Actual PostgreSQL consumer atomicity/tenant/idempotency. Broker transport is a separate E2E check. */
@EnabledIfEnvironmentVariable(named="BILLING_TEST_JDBC_URL",matches="jdbc:postgresql://127\\.0\\.0\\.1:15432/invoice_payments_test")
class BillingCustomerNotificationDatabaseTest {
    BillingFinancialDatabaseTest fixture;BillingNotificationCommands commands;BillingNotifications listener;FinanceNotificationStore sender;UUID intent;
    @BeforeAll static void migrate() throws Exception{migrateOnlyIsolatedDatabase();}
    @BeforeEach void seed(){
        fixture=new BillingFinancialDatabaseTest();fixture.cleanAndSeedDedicatedDatabase();
        // The disposable fixture resets outbox identity sequences; production never resets the activation fence.
        jdbc.update("UPDATE billing.notification_activation SET minimum_sequence=1 WHERE consumer='CUSTOMER_EMAIL_V1'");
        var ctx=BillingTestWiring.context(fixture.db,fixture.provider,new org.springframework.mock.env.MockEnvironment(),true,"http://localhost:4200",MERCHANT.toString());ctx.refresh();contexts.add(ctx);
        commands=ctx.getBean(BillingNotificationCommands.class);listener=new BillingNotifications(commands);sender=new FinanceNotificationStore(fixture.db,transactions);
        contact(ACCOUNT,PAYER,"payer@example.test");intent=id(fixture.payments.create(PAYER,ACCOUNT,UUID.randomUUID().toString(),List.of(INVOICE),"CARD").get("id"));
    }
    final List<org.springframework.context.annotation.AnnotationConfigApplicationContext> contexts=new ArrayList<>();
    @AfterEach void close(){for(var c:contexts)c.close();BillingTestWiring.closeContexts();}
    void contact(UUID account,String user,String address){jdbc.update("INSERT INTO billing.customer_notification_contacts(account_id,user_id,recipient,enabled,verified_at,eligible_from) VALUES(?,?,?,true,NOW()-INTERVAL '1 second',NOW()-INTERVAL '1 second')",account,user,address);}
    JSONObject capture(){fixture.payments.submit(PAYER,intent);fixture.payments.simulate(PAYER,intent,"CAPTURED");return event("PAYMENT_CAPTURED");}
    JSONObject event(String kind){return new JSONObject(text(fixture.db.one("SELECT payload::text AS payload FROM billing.outbox WHERE aggregate_id=? AND type=? ORDER BY publish_sequence DESC LIMIT 1",intent,kind),"payload"));}
    List<Map<String,Object>> deliveries(){return fixture.db.rows("SELECT * FROM billing.notification_deliveries WHERE audience='CUSTOMER' ORDER BY created_at,id");}
    @Test void authenticatedCreationSavesOwnerAndReplayCannotChangeTheRecipient(){
        jdbc.update("INSERT INTO billing.memberships(account_id,user_id,role) VALUES(?,?,'PAYER')",ACCOUNT,SECOND_PAYER);
        String key=text(fixture.db.one("SELECT idempotency_key FROM billing.intents WHERE id=?",intent),"idempotencyKey");
        assertThat(fixture.payments.create(SECOND_PAYER,ACCOUNT,key,List.of(INVOICE),"CARD").get("id")).isEqualTo(intent.toString());
        assertThat(fixture.db.one("SELECT * FROM billing.payment_notification_owners WHERE intent_id=?",intent)).containsEntry("userId",PAYER).containsEntry("accountId",ACCOUNT.toString());
    }
    @Test void capturedEventCreatesOneCorrectlyScopedEmailAfterMoneyCommit(){
        var event=capture();commands.receive(event);assertThat(deliveries()).hasSize(1);assertThat(deliveries().get(0)).containsEntry("recipient","payer@example.test").containsEntry("audience","CUSTOMER").containsEntry("eventType","CUSTOMER_PAYMENT_RECEIVED").containsEntry("state","PENDING").containsEntry("currency","USD").containsEntry("amountMinor",10000L);
        assertThat(fixture.db.rows("SELECT * FROM billing.consumer_inbox WHERE consumer='CUSTOMER_EMAIL_V1'")).hasSize(1);
    }
    @Test void captureConsumedAfterAnAchReturnDoesNotSendAStaleSuccessEmail(){
        fixture.payments.cancel(PAYER,intent);
        intent=id(fixture.payments.create(PAYER,ACCOUNT,UUID.randomUUID().toString(),List.of(INVOICE),"ACH").get("id"));
        var e=capture();fixture.payments.simulate(PAYER,intent,"RETURNED");commands.receive(e);
        assertThat(deliveries()).hasSize(1);assertThat(deliveries().get(0)).containsEntry("state","SUPPRESSED").containsEntry("lastError","PAYMENT_RETURNED").containsEntry("recipient",null);
    }
    @Test void anAchReturnAfterClaimStopsTheStaleReceiptBeforeSmtp(){
        fixture.payments.cancel(PAYER,intent);
        intent=id(fixture.payments.create(PAYER,ACCOUNT,UUID.randomUUID().toString(),List.of(INVOICE),"ACH").get("id"));
        commands.receive(capture());var row=sender.claim("CUSTOMER");fixture.payments.simulate(PAYER,intent,"RETURNED");
        assertThat(sender.beforeSend(id(row.get("id")),id(row.get("leaseToken")))).isFalse();
        assertThat(deliveries().get(0)).containsEntry("state","SUPPRESSED").containsEntry("lastError","PAYMENT_RETURNED");
    }
    @Test void browserWaitingAndFailedStatesNeverProduceSuccessEmails(){fixture.payments.submit(PAYER,intent);assertThat(deliveries()).isEmpty();fixture.payments.simulate(PAYER,intent,"FAILED");listener.apply(event("PAYMENT_FAILED").toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));assertThat(deliveries()).isEmpty();}
    @Test void duplicateAndLostAcknowledgementReplaysHaveOneDeliveryIntent(){var e=capture();for(int n=0;n<10;n++)commands.receive(new JSONObject(e.toString()));assertThat(deliveries()).hasSize(1);assertThat(fixture.db.rows("SELECT * FROM billing.consumer_inbox WHERE consumer='CUSTOMER_EMAIL_V1'")).hasSize(1);}
    @Test void twoConsumersRacingHaveOneDelivery() throws Exception {
        String e=capture().toString();var pool=Executors.newFixedThreadPool(2);try{var a=pool.submit(()->commands.receive(new JSONObject(e)));var b=pool.submit(()->commands.receive(new JSONObject(e)));a.get(15,TimeUnit.SECONDS);b.get(15,TimeUnit.SECONDS);assertThat(deliveries()).hasSize(1);}finally{pool.shutdownNow();}
    }
    @Test void tamperedAmountOrRecipientCannotOverrideCanonicalEvidence(){var e=capture();e.put("amountMinor",1);assertThatThrownBy(()->commands.receive(e)).isInstanceOf(IllegalArgumentException.class);assertThat(deliveries()).isEmpty();e.put("amountMinor",10000).put("recipient","attacker@example.test");assertThatThrownBy(()->commands.receive(e)).isInstanceOf(IllegalArgumentException.class);assertThat(deliveries()).isEmpty();}
    @Test void transactionFailureRollsBackReceiptAndEmailTogether(){var e=capture();new TransactionTemplate(transactions).executeWithoutResult(s->{commands.receive(e);s.setRollbackOnly();});assertThat(deliveries()).isEmpty();assertThat(fixture.db.rows("SELECT * FROM billing.consumer_inbox WHERE consumer='CUSTOMER_EMAIL_V1'")).isEmpty();commands.receive(e);assertThat(deliveries()).hasSize(1);}
    @Test void kafkaAcknowledgementOccursAfterTheDatabaseCommit(){var e=capture();var ack=new AtomicInteger();listener.receive(new org.apache.kafka.clients.consumer.ConsumerRecord<>("billing.events.v1",0,1,intent.toString(),e.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)),()->{assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();assertThat(deliveries()).hasSize(1);ack.incrementAndGet();});assertThat(ack).hasValue(1);}
    @Test void invalidEvidenceIsNeverAcknowledged(){var e=capture().put("currency","INR");var ack=new AtomicInteger();assertThatThrownBy(()->listener.receive(new org.apache.kafka.clients.consumer.ConsumerRecord<>("billing.events.v1",0,1,intent.toString(),e.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)),()->ack.incrementAndGet())).isInstanceOf(IllegalArgumentException.class);assertThat(ack).hasValue(0);}
    @Test void missingOrUnverifiedContactIsSuppressed(){jdbc.update("UPDATE billing.customer_notification_contacts SET verified_at=NULL");commands.receive(capture());assertThat(deliveries().get(0)).containsEntry("state","SUPPRESSED").containsEntry("recipient",null);assertThat(sender.claim()).isNull();}
    @Test void anotherUsersOrCompanysContactCannotReceiveThisReceipt(){jdbc.update("DELETE FROM billing.customer_notification_contacts");contact(OTHER_ACCOUNT,OTHER_PAYER,"other@example.test");commands.receive(capture());assertThat(deliveries().get(0)).containsEntry("state","SUPPRESSED").containsEntry("recipient",null);}
    @Test void laterContactEnableDoesNotBlastOldEvents(){var e=capture();jdbc.update("UPDATE billing.customer_notification_contacts SET eligible_from=NOW()+INTERVAL '1 second',verified_at=NOW()+INTERVAL '1 second'");commands.receive(e);assertThat(deliveries().get(0)).containsEntry("state","SUPPRESSED");}
    @Test void noLegacyOwnerNeverGuessesAnAddressFromTheAccount(){intent=UUID.randomUUID();jdbc.update("INSERT INTO billing.intents(id,account_id,idempotency_key,request_hash,amount_minor,currency,method,status,provider) VALUES(?,?,?,'legacy-fixture',10000,'USD','CARD','CREATED','SIMULATOR')",intent,ACCOUNT,UUID.randomUUID().toString());commands.receive(capture());assertThat(deliveries().get(0)).containsEntry("state","SUPPRESSED").containsEntry("lastError","NO_SAVED_PAYER");}
    @Test void preActivationEventsAreNotSentToLongEstablishedContacts(){var e=capture();long sequence=amount(fixture.db.one("SELECT publish_sequence FROM billing.outbox WHERE id=?",id(e.getString("eventId"))),"publishSequence");jdbc.update("UPDATE billing.notification_activation SET minimum_sequence=? WHERE consumer='CUSTOMER_EMAIL_V1'",sequence+1);commands.receive(e);assertThat(deliveries().get(0)).containsEntry("state","SUPPRESSED").containsEntry("lastError","PRE_ACTIVATION");}
    @Test void ownerMetadataCannotBeReassigned(){assertThatThrownBy(()->jdbc.update("UPDATE billing.payment_notification_owners SET user_id=? WHERE intent_id=?",SECOND_PAYER,intent)).isInstanceOf(org.springframework.dao.DataAccessException.class);}
    @Test void changingThenRestoringAnAddressDoesNotResurrectTheOldDelivery(){commands.receive(capture());var row=sender.claim();jdbc.update("UPDATE billing.customer_notification_contacts SET recipient='changed@example.test'");jdbc.update("UPDATE billing.customer_notification_contacts SET recipient='payer@example.test'");assertThat(sender.beforeSend(id(row.get("id")),id(row.get("leaseToken")))).isFalse();assertThat(deliveries().get(0)).containsEntry("state","SUPPRESSED");}
    @Test void financeDeliveryHasItsOwnPreferredLane(){commands.receive(capture());jdbc.update("INSERT INTO billing.notification_routes(merchant_id,recipient,enabled) VALUES(?,'staff@example.test',true)",MERCHANT);fixture.refunds.request(FINANCE,intent,UUID.randomUUID().toString(),100,"Fictional refund");assertThat(sender.claim("FINANCE")).containsEntry("audience","FINANCE");assertThat(sender.claim("CUSTOMER")).containsEntry("audience","CUSTOMER");}
    @Test void expiredLeaseWithIntactRouteIsRetriedNotSuppressed(){commands.receive(capture());var row=sender.claim("CUSTOMER");UUID id=id(row.get("id"));jdbc.update("UPDATE billing.notification_deliveries SET lease_until=NOW()-INTERVAL '1 second' WHERE id=?",id);assertThat(sender.beforeSend(id,id(row.get("leaseToken")))).isFalse();assertThat(deliveries().get(0)).containsEntry("state","IN_FLIGHT");assertThat(sender.claim("CUSTOMER")).containsEntry("id",row.get("id")).containsEntry("attempts",2);}
    @Test void deletingAndRecreatingAContactCannotResurrectAClaimedDelivery(){commands.receive(capture());var row=sender.claim("CUSTOMER");jdbc.update("DELETE FROM billing.customer_notification_contacts");contact(ACCOUNT,PAYER,"payer@example.test");assertThat(sender.beforeSend(id(row.get("id")),id(row.get("leaseToken")))).isFalse();}
    @Test void backdatedContactRecreationCannotReceiveAnUnconsumedHistoricalEvent(){
        var historical=capture();jdbc.update("DELETE FROM billing.customer_notification_contacts");
        jdbc.update("INSERT INTO billing.customer_notification_contacts(account_id,user_id,recipient,enabled,verified_at,eligible_from) VALUES(?,?,'replacement@example.test',true,NOW()-INTERVAL '1 day',NOW()-INTERVAL '1 day')",ACCOUNT,PAYER);
        commands.receive(historical);
        assertThat(deliveries().get(0)).containsEntry("state","SUPPRESSED").containsEntry("recipient",null);
        assertThat(sender.claim("CUSTOMER")).isNull();
    }
    @Test void contactEligibilityCannotBeBackdatedByAnUpdate(){
        var historical=capture();jdbc.update("UPDATE billing.customer_notification_contacts SET eligible_from=NOW()+INTERVAL '1 day'");
        Object eligible=fixture.db.one("SELECT eligible_from FROM billing.customer_notification_contacts").get("eligibleFrom");
        jdbc.update("UPDATE billing.customer_notification_contacts SET eligible_from=NOW()-INTERVAL '1 day'");
        assertThat(fixture.db.one("SELECT eligible_from FROM billing.customer_notification_contacts").get("eligibleFrom")).isEqualTo(eligible);
        commands.receive(historical);
        assertThat(deliveries().get(0)).containsEntry("state","SUPPRESSED").containsEntry("recipient",null);
    }
    @Test void changedAddressNeedsFreshVerification(){jdbc.update("UPDATE billing.customer_notification_contacts SET recipient='unverified@example.test'");commands.receive(capture());assertThat(deliveries().get(0)).containsEntry("state","SUPPRESSED").containsEntry("recipient",null);}
    @Test void revokedRouteAfterUnconfirmedSendPreservesTheUncertainty(){commands.receive(capture());sender.claim("CUSTOMER");jdbc.update("UPDATE billing.notification_deliveries SET lease_until=NOW()-INTERVAL '1 second' WHERE audience='CUSTOMER'");jdbc.update("DELETE FROM billing.memberships WHERE account_id=? AND user_id=?",ACCOUNT,PAYER);assertThat(sender.claim("CUSTOMER")).isNull();assertThat(deliveries().get(0)).containsEntry("state","SUPPRESSED").containsEntry("lastError","ROUTE_REVOKED_AFTER_UNCONFIRMED_SEND");}
    @Test void routeChangeOnASecondClaimRetainsThePriorUncertainSubmission(){
        commands.receive(capture());var first=sender.claim("CUSTOMER");sender.failed(id(first.get("id")),id(first.get("leaseToken")));
        jdbc.update("UPDATE billing.notification_deliveries SET next_at=NOW()-INTERVAL '1 second' WHERE audience='CUSTOMER'");
        var second=sender.claim("CUSTOMER");jdbc.update("UPDATE billing.customer_notification_contacts SET recipient='changed@example.test'");
        assertThat(sender.beforeSend(id(second.get("id")),id(second.get("leaseToken")))).isFalse();
        assertThat(deliveries().get(0)).containsEntry("state","SUPPRESSED").containsEntry("lastError","ROUTE_REVOKED_AFTER_UNCONFIRMED_SEND");
    }
    @Test void reenablingAContactAfterTheEventDoesNotSendHistory(){var e=capture();jdbc.update("UPDATE billing.customer_notification_contacts SET enabled=false");jdbc.update("UPDATE billing.customer_notification_contacts SET enabled=true");commands.receive(e);assertThat(deliveries().get(0)).containsEntry("state","SUPPRESSED");}
    @Test void requestedUnconfirmedRefundCannotSendSuccess(){commands.receive(capture());UUID refund=id(fixture.refunds.request(FINANCE,intent,UUID.randomUUID().toString(),100,"Fictional refund").get("id"));UUID eventId=UUID.randomUUID();var e=new JSONObject().put("schemaVersion",1).put("eventId",eventId.toString()).put("eventType","PAYMENT_REFUNDED").put("intentId",intent.toString()).put("refundId",refund.toString()).put("accountId",ACCOUNT.toString()).put("amountMinor",100).put("currency","USD");jdbc.update("INSERT INTO billing.outbox(id,aggregate_id,type,payload) VALUES(?,?,'PAYMENT_REFUNDED',?::jsonb)",eventId,intent,e.toString());assertThatThrownBy(()->commands.receive(e)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("no confirmed money evidence");assertThat(deliveries()).hasSize(1);}
    @Test void lostAcceptanceCommitCanRepeatOnlyTheSameLogicalMessage(){commands.receive(capture());var sent=new ArrayList<Object>();var lost=new FinanceNotificationStore(fixture.db,transactions){@Override public void accepted(UUID id,UUID token){throw new IllegalStateException("Synthetic uncertain acknowledgement");}};new FinanceNotificationWorker(lost,n->sent.add(n.get("id"))).tick();jdbc.update("UPDATE billing.notification_deliveries SET lease_until=NOW()-INTERVAL '1 second' WHERE audience='CUSTOMER'");new FinanceNotificationWorker(sender,n->sent.add(n.get("id"))).tick();assertThat(sent).hasSize(2);assertThat(sent.get(1)).isEqualTo(sent.get(0));assertThat(deliveries().get(0)).containsEntry("state","SENT");}
    @Test void historicalRefundReplayDoesNotDependOnLaterMutableState(){commands.receive(capture());UUID refund=id(fixture.refunds.request(FINANCE,intent,UUID.randomUUID().toString(),100,"Fictional refund").get("id"));fixture.refunds.approve(APPROVER,refund);var e=event("PAYMENT_REFUNDED");commands.receive(e);jdbc.update("UPDATE billing.refunds SET status='FAILED' WHERE id=?",refund);commands.receive(e);assertThat(deliveries()).hasSize(2);}
    @Test void financeRouteCannotRedirectACustomerEmail(){jdbc.update("INSERT INTO billing.notification_routes(merchant_id,recipient,enabled) VALUES(?,'staff@example.test',true)",MERCHANT);commands.receive(capture());assertThat(sender.claim()).containsEntry("recipient","payer@example.test");}
    @Test void membershipRevocationAfterClaimPreventsSmtp(){commands.receive(capture());var row=sender.claim();jdbc.update("DELETE FROM billing.memberships WHERE account_id=? AND user_id=?",ACCOUNT,PAYER);assertThat(sender.beforeSend(id(row.get("id")),id(row.get("leaseToken")))).isFalse();assertThat(deliveries().get(0)).containsEntry("state","SUPPRESSED");}
    @Test void confirmedRefundSendsASeparateCustomerReceipt(){commands.receive(capture());UUID refund=id(fixture.refunds.request(FINANCE,intent,UUID.randomUUID().toString(),100,"Fictional refund").get("id"));assertThat(deliveries()).hasSize(1);fixture.refunds.approve(APPROVER,refund);commands.receive(event("PAYMENT_REFUNDED"));assertThat(deliveries()).hasSize(2);assertThat(deliveries()).anySatisfy(n->assertThat(n).containsEntry("eventType","CUSTOMER_REFUND_CONFIRMED").containsEntry("operationId",refund.toString()).containsEntry("amountMinor",100L));}
    @Test void canonicalEventWithoutMatchingFinancialJournalCannotSend(){UUID eventId=UUID.randomUUID();var e=new JSONObject().put("schemaVersion",1).put("eventId",eventId.toString()).put("eventType","PAYMENT_CAPTURED").put("intentId",intent.toString()).put("accountId",ACCOUNT.toString()).put("amountMinor",10000).put("intentAmountMinor",10000).put("currency","USD");jdbc.update("INSERT INTO billing.outbox(id,aggregate_id,type,payload) VALUES(?,?,'PAYMENT_CAPTURED',?::jsonb)",eventId,intent,e.toString());assertThatThrownBy(()->commands.receive(e)).isInstanceOf(IllegalArgumentException.class);assertThat(deliveries()).isEmpty();}
}

