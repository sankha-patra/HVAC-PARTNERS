package com.invoicepayments.billing;

import com.invoicepayments.commerce.*;
import com.invoicepayments.billing.provider.*;
import org.flywaydb.core.Flyway;
import org.json.JSONObject;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.env.MockEnvironment;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static com.invoicepayments.billing.BillingStore.*;
import static org.assertj.core.api.Assertions.*;

/** Real PostgreSQL invariants on the allowlisted disposable database; no live customer/provider actions. */
@EnabledIfEnvironmentVariable(named="BILLING_TEST_JDBC_URL",matches="jdbc:postgresql://127\\.0\\.0\\.1:15432/invoice_payments_test")
class CommerceDatabaseTest {
    static final UUID MERCHANT=UUID.fromString("10000000-0000-0000-0000-000000000001"),ACCOUNT=UUID.fromString("20000000-0000-0000-0000-000000000001"),OTHER=UUID.fromString("20000000-0000-0000-0000-000000000002"),LIST=UUID.fromString("50000000-0000-0000-0000-000000000001"),PRODUCT=UUID.fromString("60000000-0000-0000-0000-000000000001");
    static JdbcTemplate jdbc;BillingStore db;BillingService payments;CommerceCommands commerce;AnnotationConfigApplicationContext context;
    @BeforeAll static void migrate(){
        assertThat(System.getenv("BILLING_TEST_DB_USER")).isEqualTo("billing_test");
        var source=new DriverManagerDataSource(System.getenv("BILLING_TEST_JDBC_URL"),System.getenv("BILLING_TEST_DB_USER"),System.getenv("BILLING_TEST_DB_PASSWORD"));
        Flyway.configure().dataSource(source).schemas("billing").defaultSchema("billing").locations("classpath:db/billing").cleanDisabled(true).load().migrate();jdbc=new JdbcTemplate(source);
    }
    @BeforeEach void seed(){
        jdbc.execute("TRUNCATE billing.merchants,billing.provider_operations,billing.outbox,billing.consumer_inbox RESTART IDENTITY CASCADE");db=new BillingStore(jdbc);
        var provider=BillingTestWiring.simulator(jdbc,null);
        context=BillingTestWiring.context(db,provider,new MockEnvironment(),true,"http://localhost:4200",MERCHANT.toString());
        context.register(CommerceCommands.class);context.refresh();payments=context.getBean(BillingService.class);commerce=context.getBean(CommerceCommands.class);
        db.update("INSERT INTO billing.merchants(id,name) VALUES(?,'Fictional merchant')",MERCHANT);
        for(UUID account:List.of(ACCOUNT,OTHER)){
            db.update("INSERT INTO billing.accounts(id,merchant_id,name,external_reference) VALUES(?,?,'Test company',?)",account,MERCHANT,account.toString());
            db.update("INSERT INTO billing.memberships(account_id,user_id,role) VALUES(?,?,'PAYER')",account,account.equals(ACCOUNT)?"payer":"other");
        }
        db.update("INSERT INTO commerce.price_lists(id,merchant_id,name,currency) VALUES(?,?,'INR test catalog','INR')",LIST,MERCHANT);
        for(UUID account:List.of(ACCOUNT,OTHER))db.update("INSERT INTO commerce.account_price_lists(account_id,merchant_id,price_list_id) VALUES(?,?,?)",account,MERCHANT,LIST);
        db.update("INSERT INTO commerce.products(id,merchant_id,sku,name,category) VALUES(?,?,'SKU-1','Filter','Parts')",PRODUCT,MERCHANT);
        db.update("INSERT INTO commerce.prices(price_list_id,product_id,merchant_id,unit_price_minor) VALUES(?,?,?,100)",LIST,PRODUCT,MERCHANT);
        db.update("INSERT INTO commerce.stock(product_id,saleable_qty) VALUES(?,5)",PRODUCT);
    }
    @AfterEach void close(){if(context!=null)context.close();BillingTestWiring.closeContexts();}
    List<CommerceCommands.Line> lines(int quantity){return List.of(new CommerceCommands.Line(PRODUCT,quantity,1L));}
    CommerceCommands.Checkout saved(String user,UUID account,int quantity){
        long current=amount(commerce.cart(user,account),"version");
        long version=amount(commerce.saveCart(user,account,current,lines(quantity)),"version");
        String token=commerce.quote(user,account,lines(quantity)).get("quoteToken").toString();
        return new CommerceCommands.Checkout(lines(quantity),quantity*100L,"CARD",version,token);
    }
    Map<String,Object> checkout(){return commerce.checkout("payer",ACCOUNT,key(),saved("payer",ACCOUNT,1));}
    UUID intent(Map<String,Object> order){return id(((Map<?,?>)order.get("payment")).get("id"));}
    String key(){return UUID.randomUUID().toString();}
    long count(String table){return jdbc.queryForObject("SELECT COUNT(*) FROM "+table,Long.class);}
    long stock(String column){return amount(db.one("SELECT "+column+" FROM commerce.stock WHERE product_id=?",PRODUCT),column.equals("reserved_qty")?"reservedQty":"saleableQty");}
    @Test void checkoutAtomicallyCreatesTheBillPaymentLinesAndStockHoldInINR(){
        var order=checkout();assertThat(order.get("currency")).isEqualTo("INR");assertThat(order.get("status")).isEqualTo("PAYMENT_PENDING");
        assertThat(count("commerce.orders")).isEqualTo(1);assertThat(count("billing.invoices")).isEqualTo(1);assertThat(count("billing.intents")).isEqualTo(1);
        assertThat(stock("reserved_qty")).isEqualTo(1);assertThat(payments.get("payer",intent(order)).get("currency")).isEqualTo("INR");
        assertThat(((List<?>)commerce.cart("payer",ACCOUNT).get("lines"))).isEmpty();
        assertThatThrownBy(()->jdbc.update("UPDATE commerce.order_lines SET unit_price_minor=50 WHERE order_id=?",id(order.get("id")))).isInstanceOf(org.springframework.dao.DataAccessException.class);
    }
    @Test void buyerOrderProjectionExcludesBillingDiagnosticsAndInternalCommandFields(){
        var order=checkout();
        assertThat(order).doesNotContainKeys("accountId","createdBy","checkoutKey","requestHash","cartVersion","version","paymentReference");
        @SuppressWarnings("unchecked") var payment=(Map<String,Object>)order.get("payment");
        assertThat(payment).containsKeys("id","status","amountMinor","currency","simulatorEnabled")
            .doesNotContainKeys("accountId","allocations","timeline","message","leaseToken","operationKey");
        assertThat(summary(order)).containsEntry("status","NOT_PAID").containsEntry("capturedMinor",0L).containsEntry("refundedMinor",0L);
        assertThat(commerce.orders("payer",ACCOUNT).get(0)).doesNotContainKeys("accountId","checkoutKey","requestHash","paymentReference","payment");
    }
    @Test void paymentSummaryShowsCommittedMoneyBeforeTheOrderConsumerCatchesUp(){
        var order=checkout();UUID orderId=id(order.get("id")),intent=intent(order);payments.submit("payer",intent);
        assertThat(db.one("SELECT original_request->>'returnUrl' AS return_url FROM billing.attempts WHERE intent_id=?",intent))
            .containsEntry("returnUrl","http://localhost:4200/orders");
        assertThat(summary(commerce.order("payer",orderId))).containsEntry("status","PENDING").containsEntry("capturedMinor",0L);
        payments.simulate("payer",intent,"CAPTURED");
        var pending=commerce.order("payer",orderId);
        assertThat(pending).containsEntry("status","PAYMENT_PENDING").containsEntry("paymentReceivedOrderPending",true);
        assertThat(summary(pending)).containsEntry("status","PAID").containsEntry("capturedMinor",100L);
        assertThat(summary(commerce.orders("payer",ACCOUNT).get(0))).isEqualTo(summary(pending));
    }
    @Test void orderListDistinguishesBuyerActionFromProviderConfirmation(){
        var order=checkout();UUID orderId=id(order.get("id")),intent=intent(order);payments.submit("payer",intent);
        String providerId=text(db.one("SELECT provider_id FROM billing.intents WHERE id=?",intent),"providerId");
        payments.recordResult(intent,null,new ProviderResult(providerId,"REQUIRES_ACTION",null,"Waiting for buyer checkout"));
        assertThat(summary(commerce.order("payer",orderId))).containsEntry("status","ACTION_REQUIRED").containsEntry("capturedMinor",0L);
        assertThat(summary(commerce.orders("payer",ACCOUNT).get(0))).containsEntry("status","ACTION_REQUIRED");
    }
    @Test void outOfStockRollsBackEveryOrderBillPaymentAllocationAndCartClear(){
        var request=saved("payer",ACCOUNT,2);jdbc.update("UPDATE commerce.stock SET saleable_qty=1 WHERE product_id=?",PRODUCT);
        assertThatThrownBy(()->commerce.checkout("payer",ACCOUNT,key(),request)).isInstanceOf(BillingProblem.class).hasMessageContaining("stock");
        for(String table:List.of("commerce.orders","billing.invoices","billing.intents","billing.allocations","commerce.order_bills"))assertThat(count(table)).as(table).isZero();
        assertThat(stock("reserved_qty")).isZero();assertThat((List<?>)commerce.cart("payer",ACCOUNT).get("lines")).hasSize(1);
    }
    @Test void changedPriceEvenWithSameTotalCannotReuseTheOldQuote(){
        var request=saved("payer",ACCOUNT,1);jdbc.update("UPDATE commerce.prices SET unit_price_minor=unit_price_minor WHERE product_id=?",PRODUCT);
        assertThatThrownBy(()->commerce.checkout("payer",ACCOUNT,key(),request)).isInstanceOfSatisfying(BillingProblem.class,e->assertThat(e.code()).isEqualTo("PRICE_CHANGED"));assertThat(count("commerce.orders")).isZero();
    }
    @Test void differentHttpKeysForTheSameBasketInTwoTabsCreateExactlyOneOrder() throws Exception {
        var request=saved("payer",ACCOUNT,1);var start=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
        try{Callable<Map<String,Object>> action=()->{start.await();return commerce.checkout("payer",ACCOUNT,key(),request);};var a=pool.submit(action);var b=pool.submit(action);start.countDown();
            assertThat(a.get(15,TimeUnit.SECONDS).get("id")).isEqualTo(b.get(15,TimeUnit.SECONDS).get("id"));
            assertThat(count("commerce.orders")).isEqualTo(1);assertThat(count("billing.intents")).isEqualTo(1);assertThat(stock("reserved_qty")).isEqualTo(1);
        }finally{pool.shutdownNow();}
    }
    @Test void sameHttpKeyWithDifferentSavedBasketIsAConflict(){
        var request=saved("payer",ACCOUNT,1);String key=key();commerce.checkout("payer",ACCOUNT,key,request);
        assertThatThrownBy(()->commerce.checkout("payer",ACCOUNT,key,new CommerceCommands.Checkout(lines(2),200,"CARD",request.cartVersion(),request.quoteToken()))).isInstanceOfSatisfying(BillingProblem.class,e->assertThat(e.code()).isEqualTo("IDEMPOTENCY_CONFLICT"));
    }
    @Test void twoCompaniesBuyingTheLastItemHaveOneWinnerAndNoPartialLosingOrder() throws Exception {
        jdbc.update("UPDATE commerce.stock SET saleable_qty=1 WHERE product_id=?",PRODUCT);
        var first=saved("payer",ACCOUNT,1);var second=saved("other",OTHER,1);var start=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
        try{var a=pool.submit(()->attempt(start,"payer",ACCOUNT,first));var b=pool.submit(()->attempt(start,"other",OTHER,second));start.countDown();
            assertThat(List.of(a.get(15,TimeUnit.SECONDS),b.get(15,TimeUnit.SECONDS))).containsExactlyInAnyOrder("SAVED","OUT_OF_STOCK");
            assertThat(count("commerce.orders")).isEqualTo(1);assertThat(count("billing.invoices")).isEqualTo(1);assertThat(count("billing.intents")).isEqualTo(1);assertThat(stock("reserved_qty")).isEqualTo(1);
        }finally{pool.shutdownNow();}
    }
    String attempt(CountDownLatch start,String user,UUID account,CommerceCommands.Checkout request)throws Exception{start.await();try{commerce.checkout(user,account,key(),request);return "SAVED";}catch(BillingProblem failure){return failure.code();}}
    @Test void orderCancellationReleasesBothReservationsAndDirectPaymentCancellationIsRefused(){
        var order=checkout();UUID intent=intent(order);assertThatThrownBy(()->payments.cancel("payer",intent)).isInstanceOfSatisfying(BillingProblem.class,e->assertThat(e.code()).isEqualTo("ORDER_ACTION_REQUIRED"));
        var cancelled=commerce.cancel("payer",id(order.get("id")));assertThat(cancelled.get("status")).isEqualTo("CANCELLED");assertThat(stock("reserved_qty")).isZero();
        assertThat(payments.get("payer",intent).get("status")).isEqualTo("CANCELLED");assertThat(amount(db.one("SELECT reserved_minor FROM billing.invoices"),"reservedMinor")).isZero();
        assertThat(payments.submit("payer",intent).get("status")).isEqualTo("CANCELLED");assertThat(count("billing.attempts")).isZero();
    }
    @Test void sentOrUnknownPaymentCannotLoseItsStockHold(){
        var order=checkout();UUID intent=intent(order);payments.submit("payer",intent);
        jdbc.update("UPDATE commerce.orders SET hold_until=NOW()-INTERVAL '1 hour'");
        assertThat(commerce.expireHolds(20)).isZero();assertThat(stock("reserved_qty")).isEqualTo(1);
        assertThatThrownBy(()->commerce.cancel("payer",id(order.get("id")))).isInstanceOf(BillingProblem.class);
    }
    @Test void unsentHoldExpiryClosesTheOrderAndPaymentTogether(){
        var order=checkout();jdbc.update("UPDATE commerce.orders SET hold_until=NOW()-INTERVAL '1 hour'");
        assertThat(commerce.expireHolds(20)).isEqualTo(1);assertThat(commerce.order("payer",id(order.get("id"))).get("status")).isEqualTo("EXPIRED");assertThat(stock("reserved_qty")).isZero();assertThat(payments.get("payer",intent(order)).get("status")).isEqualTo("CANCELLED");
    }
    @Test void duplicateCapturedEventsConsumeStockAndCreateOneFulfilmentOnly(){
        var order=checkout();UUID intent=intent(order);payments.submit("payer",intent);payments.simulate("payer",intent,"CAPTURED");
        var payload=new JSONObject(text(db.one("SELECT payload FROM billing.outbox WHERE type='PAYMENT_CAPTURED'"),"payload"));
        commerce.applyPaymentEvent(payload);commerce.applyPaymentEvent(payload);
        assertThatThrownBy(()->commerce.applyPaymentEvent(new JSONObject(payload.toString()).put("eventId",UUID.randomUUID().toString()))).isInstanceOf(IllegalArgumentException.class);
        assertThat(commerce.order("payer",id(order.get("id"))).get("status")).isEqualTo("PAID");assertThat(stock("saleable_qty")).isEqualTo(4);assertThat(stock("reserved_qty")).isZero();assertThat(count("commerce.fulfilments")).isEqualTo(1);assertThat(count("commerce.order_event_receipts")).isEqualTo(1);
    }
    @Test void fabricatedCapturedEventCannotFulfilAnUnpaidOrder(){
        var order=checkout();var payment=payments.get("payer",intent(order));var event=new JSONObject().put("schemaVersion",1).put("eventId",key()).put("eventType","PAYMENT_CAPTURED").put("intentId",payment.get("id")).put("accountId",ACCOUNT.toString()).put("currency","INR").put("intentAmountMinor",100);
        assertThatThrownBy(()->commerce.applyPaymentEvent(event)).isInstanceOf(IllegalArgumentException.class);assertThat(count("commerce.fulfilments")).isZero();assertThat(stock("reserved_qty")).isEqualTo(1);
    }
    @Test void anotherCompanyCannotSeeTheOrderOrReadCacheBeforeAuthorization(){
        var order=checkout();assertThatThrownBy(()->commerce.order("other",id(order.get("id")))).isInstanceOf(BillingProblem.class);
        assertThatThrownBy(()->commerce.orders("other",ACCOUNT)).isInstanceOf(BillingProblem.class);
        assertThatThrownBy(()->commerce.cart("other",ACCOUNT)).isInstanceOf(BillingProblem.class);
        assertThatThrownBy(()->commerce.quote("other",ACCOUNT,lines(1))).isInstanceOf(BillingProblem.class);
        assertThatThrownBy(()->commerce.saveCart("other",ACCOUNT,0,lines(1))).isInstanceOf(BillingProblem.class);
        assertThatThrownBy(()->commerce.cancel("other",id(order.get("id")))).isInstanceOf(BillingProblem.class);
        var calls=new AtomicInteger();var cache=new CommerceCatalog.Cache(){public String get(String k){calls.incrementAndGet();return null;}public void put(String k,String v,Duration t){calls.incrementAndGet();}};
        assertThatThrownBy(()->new CommerceCatalog(db,payments,cache).catalog("other",ACCOUNT,"",0)).isInstanceOf(BillingProblem.class);assertThat(calls).hasValue(0);
    }
    @Test void cartOptimisticVersionProtectsAnotherTabsChanges(){
        commerce.saveCart("payer",ACCOUNT,0,lines(1));assertThatThrownBy(()->commerce.saveCart("payer",ACCOUNT,0,lines(2))).isInstanceOfSatisfying(BillingProblem.class,e->assertThat(e.code()).isEqualTo("CART_CHANGED"));assertThat((List<?>)commerce.cart("payer",ACCOUNT).get("lines")).hasSize(1);
    }
    @Test void unavailableSavedItemsCanBeReducedAndRemovedButCannotBeAddedOrIncreased(){
        UUID second=UUID.randomUUID();
        db.update("INSERT INTO commerce.products(id,merchant_id,sku,name,category) VALUES(?,?,'OLD-PART','Previous part','Parts')",second,MERCHANT);
        db.update("INSERT INTO commerce.prices(price_list_id,product_id,merchant_id,unit_price_minor) VALUES(?,?,?,100)",LIST,second,MERCHANT);
        var original=List.of(new CommerceCommands.Line(PRODUCT,2,null),new CommerceCommands.Line(second,1,null));
        long version=amount(commerce.saveCart("payer",ACCOUNT,0,original),"version");
        db.update("UPDATE commerce.products SET active=false WHERE id IN (?,?)",PRODUCT,second);
        final long oldVersion=version;
        assertThatThrownBy(()->commerce.saveCart("payer",ACCOUNT,oldVersion,List.of(new CommerceCommands.Line(PRODUCT,3,null),new CommerceCommands.Line(second,1,null))))
            .isInstanceOfSatisfying(BillingProblem.class,e->assertThat(e.code()).isEqualTo("PRODUCT_UNAVAILABLE"));
        var reduced=List.of(new CommerceCommands.Line(PRODUCT,1,null),new CommerceCommands.Line(second,1,null));
        version=amount(commerce.saveCart("payer",ACCOUNT,version,reduced),"version");
        assertThatThrownBy(()->commerce.quote("payer",ACCOUNT,reduced)).isInstanceOf(BillingProblem.class);
        var oneLeft=List.of(new CommerceCommands.Line(second,1,null));
        version=amount(commerce.saveCart("payer",ACCOUNT,version,oneLeft),"version");
        final long oneLeftVersion=version;
        assertThatThrownBy(()->commerce.saveCart("payer",ACCOUNT,oneLeftVersion,reduced))
            .isInstanceOfSatisfying(BillingProblem.class,e->assertThat(e.code()).isEqualTo("PRODUCT_UNAVAILABLE"));
        assertThat((List<?>)commerce.saveCart("payer",ACCOUNT,version,List.of()).get("lines")).isEmpty();
        assertThat(count("commerce.orders")).isZero();assertThat(count("billing.intents")).isZero();
    }
    @Test void staleCacheFillAfterPriceCommitIsAddressedByAnUnreadOldRevision() throws Exception {
        var values=new ConcurrentHashMap<String,String>();var loaded=new CountDownLatch(1);var release=new CountDownLatch(1);var first=new AtomicBoolean(true);
        var cache=new CommerceCatalog.Cache(){public String get(String key){return values.get(key);}public void put(String key,String value,Duration ttl){if(first.getAndSet(false)){loaded.countDown();try{if(!release.await(5,TimeUnit.SECONDS))throw new IllegalStateException("test barrier");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}}values.put(key,value);}};
        var catalog=new CommerceCatalog(db,payments,cache);var pool=Executors.newSingleThreadExecutor();
        try{var old=pool.submit(()->catalog.catalog("payer",ACCOUNT,"",0));assertThat(loaded.await(5,TimeUnit.SECONDS)).isTrue();jdbc.update("UPDATE commerce.prices SET unit_price_minor=200 WHERE product_id=?",PRODUCT);release.countDown();old.get(5,TimeUnit.SECONDS);
            @SuppressWarnings("unchecked") var items=(List<Map<String,Object>>)catalog.catalog("payer",ACCOUNT,"",0).get("items");assertThat(amount(items.get(0),"unitPriceMinor")).isEqualTo(200);assertThat(values).hasSize(2);
        }finally{release.countDown();pool.shutdownNow();}
    }
    @Test void cacheOutageFallsThroughAndPoisonedCatalogDoesNotSetCheckoutPrice(){
        var down=new CommerceCatalog.Cache(){public String get(String k){throw new IllegalStateException("Redis down");}public void put(String k,String v,Duration t){throw new IllegalStateException("Redis down");}};
        assertThat((List<?>)new CommerceCatalog(db,payments,down).catalog("payer",ACCOUNT,"",0).get("items")).hasSize(1);
        var request=saved("payer",ACCOUNT,1);assertThatThrownBy(()->commerce.checkout("payer",ACCOUNT,key(),new CommerceCommands.Checkout(request.lines(),1,"CARD",request.cartVersion(),request.quoteToken()))).isInstanceOfSatisfying(BillingProblem.class,e->assertThat(e.code()).isEqualTo("PRICE_CHANGED"));assertThat(count("commerce.orders")).isZero();
    }
    @Test void concurrentHotKeyMissesShareOneDatabaseFill()throws Exception{
        var entered=new CountDownLatch(8);var filled=new CountDownLatch(1);var release=new CountDownLatch(1);var writes=new AtomicInteger();
        var cache=new CommerceCatalog.Cache(){public String get(String key){entered.countDown();return null;}public void put(String key,String value,Duration ttl){writes.incrementAndGet();filled.countDown();try{if(!release.await(5,TimeUnit.SECONDS))throw new IllegalStateException("test barrier");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}}};
        var catalog=new CommerceCatalog(db,payments,cache);var pool=Executors.newFixedThreadPool(8);
        try{List<Future<Map<String,Object>>> reads=new ArrayList<>();reads.add(pool.submit(()->catalog.catalog("payer",ACCOUNT,"",0)));assertThat(filled.await(5,TimeUnit.SECONDS)).isTrue();
            for(int n=1;n<8;n++)reads.add(pool.submit(()->catalog.catalog("payer",ACCOUNT,"",0)));
            assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();release.countDown();for(var read:reads)assertThat((List<?>)read.get(5,TimeUnit.SECONDS).get("items")).hasSize(1);assertThat(writes).hasValue(1);
        }finally{release.countDown();pool.shutdownNow();}
    }
    @Test void cacheOutageFallbackIsBoundedToFourConcurrentDifferentKeyLoads()throws Exception{
        var filling=new CountDownLatch(4);var release=new CountDownLatch(1);
        var cache=new CommerceCatalog.Cache(){public String get(String k){throw new IllegalStateException("Redis down");}public void put(String k,String v,Duration ttl){filling.countDown();try{if(!release.await(5,TimeUnit.SECONDS))throw new IllegalStateException("test barrier");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}}};
        var catalog=new CommerceCatalog(db,payments,cache);var pool=Executors.newFixedThreadPool(4);
        try{List<Future<Map<String,Object>>> reads=new ArrayList<>();for(int n=0;n<4;n++){int page=n;reads.add(pool.submit(()->catalog.catalog("payer",ACCOUNT,"",page)));}
            assertThat(filling.await(5,TimeUnit.SECONDS)).isTrue();assertThatThrownBy(()->catalog.catalog("payer",ACCOUNT,"",4)).isInstanceOfSatisfying(BillingProblem.class,e->assertThat(e.code()).isEqualTo("CATALOG_BUSY"));
            release.countDown();for(var read:reads)read.get(5,TimeUnit.SECONDS);
        }finally{release.countDown();pool.shutdownNow();}
    }
    @Test void cancelRacingPayNeverReleasesStockForASentPayment()throws Exception{
        var order=checkout();UUID orderId=id(order.get("id")),intent=intent(order);var start=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
        try{var pay=pool.submit(()->{start.await();try{return payments.submit("payer",intent).get("status").toString();}catch(BillingProblem closed){return closed.code();}});
            var cancel=pool.submit(()->{start.await();try{return commerce.cancel("payer",orderId).get("status").toString();}catch(BillingProblem sent){return sent.code();}});start.countDown();pay.get(15,TimeUnit.SECONDS);cancel.get(15,TimeUnit.SECONDS);
            var saved=commerce.order("payer",orderId);String status=payments.get("payer",intent).get("status").toString();
            if("CANCELLED".equals(saved.get("status"))){assertThat(status).isEqualTo("CANCELLED");assertThat(count("billing.attempts")).isZero();assertThat(stock("reserved_qty")).isZero();}
            else{assertThat(saved.get("status")).isEqualTo("PAYMENT_PENDING");assertThat(status).isEqualTo("PROCESSING");assertThat(count("billing.attempts")).isEqualTo(1);assertThat(stock("reserved_qty")).isEqualTo(1);}
        }finally{pool.shutdownNow();}
    }
    @Test void lateMoneyAfterOrderCancellationIsRecordedForReviewWithoutStockOrFulfilment(){
        var order=checkout();UUID orderId=id(order.get("id")),intent=intent(order);commerce.cancel("payer",orderId);
        payments.recordResult(intent,null,new ProviderResult("sim_late_"+intent,"CAPTURED",null,"Controlled late payment evidence"));
        var payload=new JSONObject(text(db.one("SELECT payload FROM billing.outbox WHERE type='PAYMENT_CAPTURED'"),"payload"));commerce.applyPaymentEvent(payload);
        assertThat(commerce.order("payer",orderId).get("status")).isEqualTo("NEEDS_REVIEW");assertThat(stock("saleable_qty")).isEqualTo(5);assertThat(stock("reserved_qty")).isZero();assertThat(count("commerce.fulfilments")).isZero();
        assertThat(amount(db.one("SELECT COUNT(*) AS count FROM billing.journals j JOIN billing.ledger_entries l ON l.journal_id=j.id WHERE j.effect_key=? AND l.account='UNAPPLIED_CASH' AND l.side='CREDIT'","capture:"+intent),"count")).isEqualTo(1);
        assertThat(db.rows("SELECT reason FROM billing.cases WHERE intent_id=?",intent)).anySatisfy(c->assertThat(c).containsEntry("reason","LATE_CAPTURE_UNAPPLIED"));
    }
    @Test void confirmedFailureRetryKeepsOneOrderAndOneStockHold(){
        var order=checkout();UUID orderId=id(order.get("id")),first=intent(order);payments.submit("payer",first);payments.simulate("payer",first,"FAILED");
        commerce.applyPaymentEvent(new JSONObject(text(db.one("SELECT payload FROM billing.outbox WHERE type='PAYMENT_FAILED'"),"payload")));
        var retry=BillingTestWiring.retry(db,payments).retry("payer",first,key(),"CARD");UUID second=id(retry.get("id"));
        assertThat(second).isNotEqualTo(first);assertThat(retry.get("paymentReference")).isEqualTo(first.toString());assertThat(stock("reserved_qty")).isEqualTo(1);assertThat(count("commerce.orders")).isEqualTo(1);
        payments.submit("payer",second);payments.simulate("payer",second,"CAPTURED");
        commerce.applyPaymentEvent(new JSONObject(text(db.one("SELECT payload FROM billing.outbox WHERE type='PAYMENT_CAPTURED'"),"payload")));
        assertThat(commerce.order("payer",orderId).get("status")).isEqualTo("PAID");assertThat(stock("saleable_qty")).isEqualTo(4);assertThat(count("commerce.fulfilments")).isEqualTo(1);
    }
    @Test @EnabledIfEnvironmentVariable(named="COMMERCE_TEST_REDIS",matches="true")
    void realRedisColdMissWarmHitPoisonAndCommittedInvalidationKeepCheckoutAuthoritative(){
        var connection=new org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory("127.0.0.1",6379);connection.afterPropertiesSet();connection.start();
        var redis=new org.springframework.data.redis.core.StringRedisTemplate(connection);redis.afterPropertiesSet();
        Set<String> before=new HashSet<>(Objects.requireNonNull(redis.keys("cat:v2:*")));
        try{
            var beans=new org.springframework.beans.factory.support.StaticListableBeanFactory();beans.addBean("testRedis",redis);
            var catalog=new CommerceCatalog(db,payments,beans.getBeanProvider(org.springframework.data.redis.core.StringRedisTemplate.class),true);
            var first=catalog.catalog("payer",ACCOUNT,"",0);
            @SuppressWarnings("unchecked") var firstItems=(List<Map<String,Object>>)first.get("items");assertThat(amount(firstItems.get(0),"unitPriceMinor")).isEqualTo(100);
            var created=new HashSet<>(Objects.requireNonNull(redis.keys("cat:v2:*")));created.removeAll(before);assertThat(created).hasSize(1);String key=created.iterator().next();
            var stored=new JSONObject(Objects.requireNonNull(redis.opsForValue().get(key)));stored.getJSONArray("items").getJSONObject(0).put("unitPriceMinor",1);redis.opsForValue().set(key,stored.toString(),Duration.ofMinutes(1));
            @SuppressWarnings("unchecked") var warm=(List<Map<String,Object>>)catalog.catalog("payer",ACCOUNT,"",0).get("items");assertThat(amount(warm.get(0),"unitPriceMinor")).isEqualTo(1);
            var request=saved("payer",ACCOUNT,1);assertThat(commerce.checkout("payer",ACCOUNT,key(),request).get("totalMinor")).isEqualTo(100L);
            jdbc.update("UPDATE commerce.prices SET unit_price_minor=200 WHERE product_id=?",PRODUCT);
            @SuppressWarnings("unchecked") var fresh=(List<Map<String,Object>>)catalog.catalog("payer",ACCOUNT,"",0).get("items");assertThat(amount(fresh.get(0),"unitPriceMinor")).isEqualTo(200);assertThat(redis.opsForValue().get(key)).contains("unitPriceMinor\":1");
        }finally{var created=new HashSet<>(Objects.requireNonNull(redis.keys("cat:v2:*")));created.removeAll(before);if(!created.isEmpty())redis.delete(created);connection.destroy();}
    }
    @Test void aLaterLineStockFailureRollsBackEarlierStockAndItsCacheInvalidation(){
        UUID unavailable=UUID.fromString("60000000-0000-0000-0000-000000000002");
        db.update("INSERT INTO commerce.products(id,merchant_id,sku,name,category) VALUES(?,?,'SKU-2','Unavailable part','Parts')",unavailable,MERCHANT);
        db.update("INSERT INTO commerce.prices(price_list_id,product_id,merchant_id,unit_price_minor) VALUES(?,?,?,100)",LIST,unavailable,MERCHANT);db.update("INSERT INTO commerce.stock(product_id,saleable_qty) VALUES(?,0)",unavailable);
        long revision=count("commerce.catalog_versions");var lines=List.of(new CommerceCommands.Line(PRODUCT,1,1L),new CommerceCommands.Line(unavailable,1,1L));
        long version=amount(commerce.saveCart("payer",ACCOUNT,0,lines),"version");
        String token=commerce.quote("payer",ACCOUNT,lines).get("quoteToken").toString();
        assertThatThrownBy(()->commerce.checkout("payer",ACCOUNT,key(),new CommerceCommands.Checkout(lines,200,"CARD",version,token))).isInstanceOfSatisfying(BillingProblem.class,e->assertThat(e.code()).isEqualTo("OUT_OF_STOCK"));
        assertThat(stock("reserved_qty")).isZero();assertThat(count("commerce.stock_reservations")).isZero();assertThat(count("commerce.catalog_versions")).isEqualTo(revision);assertThat(count("billing.intents")).isZero();assertThat(count("commerce.orders")).isZero();
    }
    private void partialRefund(UUID intent){
        db.update("INSERT INTO billing.staff(merchant_id,user_id,role) VALUES(?,'commerce-finance','FINANCE'),(?,'commerce-checker','REFUND_APPROVER')",MERCHANT,MERCHANT);
        var refunds=BillingTestWiring.refunds(db,payments,payments.configuredProvider());
        UUID refund=id(refunds.request("commerce-finance",intent,key(),50,"Controlled partial return before shipment").get("id"));
        assertThat(summary(commerce.orders("payer",ACCOUNT).get(0))).containsEntry("status","PAID").containsEntry("refundedMinor",0L);
        assertThat(refunds.approve("commerce-checker",refund).get("status")).isEqualTo("REFUNDED");
    }
    @SuppressWarnings("unchecked") private Map<String,Object> summary(Map<String,Object> order){return (Map<String,Object>)order.get("paymentSummary");}
    private JSONObject canonical(UUID intent,String type){return new JSONObject(text(db.one("SELECT payload FROM billing.outbox WHERE aggregate_id=? AND type=?",intent,type),"payload"));}
    @Test void refundBeforeShipmentHoldsTheHandOffWithoutRestockingOrErasingHistory(){
        var order=checkout();UUID intent=intent(order),orderId=id(order.get("id"));payments.submit("payer",intent);payments.simulate("payer",intent,"CAPTURED");
        commerce.applyPaymentEvent(canonical(intent,"PAYMENT_CAPTURED"));partialRefund(intent);
        var event=canonical(intent,"PAYMENT_REFUNDED");commerce.applyPaymentEvent(event);commerce.applyPaymentEvent(event);
        assertThat(commerce.order("payer",orderId).get("status")).isEqualTo("NEEDS_REVIEW");
        assertThat(db.one("SELECT state FROM commerce.fulfilments WHERE order_id=?",orderId)).containsEntry("state","ON_HOLD");
        assertThat(stock("saleable_qty")).isEqualTo(4);assertThat(stock("reserved_qty")).isZero();assertThat(count("commerce.fulfilments")).isEqualTo(1);
        assertThat(summary(commerce.order("payer",orderId))).containsEntry("status","PARTIALLY_REFUNDED").containsEntry("capturedMinor",100L).containsEntry("refundedMinor",50L);
        assertThat(summary(commerce.orders("payer",ACCOUNT).get(0))).containsEntry("status","PARTIALLY_REFUNDED").containsEntry("refundedMinor",50L);
        var refunds=BillingTestWiring.refunds(db,payments,payments.configuredProvider());
        UUID remainder=id(refunds.request("commerce-finance",intent,key(),50,"Controlled remaining refund").get("id"));
        assertThat(refunds.approve("commerce-checker",remainder).get("status")).isEqualTo("REFUNDED");
        commerce.applyPaymentEvent(new JSONObject(text(db.one("SELECT payload FROM billing.outbox WHERE aggregate_id=? AND type='PAYMENT_REFUNDED' AND payload->>'refundId'=?",intent,remainder.toString()),"payload")));
        assertThat(db.one("SELECT state FROM commerce.fulfilments WHERE order_id=?",orderId)).containsEntry("state","ON_HOLD");
        assertThat(count("commerce.fulfilments")).isEqualTo(1);assertThat(stock("saleable_qty")).isEqualTo(4);
        assertThat(summary(commerce.order("payer",orderId))).containsEntry("status","REFUNDED").containsEntry("refundedMinor",100L);
    }
    @Test void delayedCaptureEventCannotCreateFulfilmentAfterMoneyWasAlreadyRefunded(){
        var order=checkout();UUID intent=intent(order),orderId=id(order.get("id"));payments.submit("payer",intent);payments.simulate("payer",intent,"CAPTURED");partialRefund(intent);
        commerce.applyPaymentEvent(canonical(intent,"PAYMENT_REFUNDED"));commerce.applyPaymentEvent(canonical(intent,"PAYMENT_CAPTURED"));
        assertThat(commerce.order("payer",orderId).get("status")).isEqualTo("NEEDS_REVIEW");assertThat(count("commerce.fulfilments")).isZero();
        assertThat(stock("saleable_qty")).isEqualTo(5);assertThat(stock("reserved_qty")).isEqualTo(1);
    }
    @Test void unresolvedOldHoldsCannotStarveALaterEligibleExpiry(){
        jdbc.update("UPDATE commerce.stock SET saleable_qty=20 WHERE product_id=?",PRODUCT);
        for(int n=0;n<6;n++) {
            var stuck=checkout();
            jdbc.update("UPDATE billing.intents SET status='REQUIRES_ACTION' WHERE id=?",intent(stuck));
            jdbc.update("UPDATE commerce.orders SET hold_until=NOW()-INTERVAL '2 hours' WHERE id=?",id(stuck.get("id")));
        }
        var abandoned=checkout();UUID abandonedId=id(abandoned.get("id"));
        jdbc.update("UPDATE commerce.orders SET hold_until=NOW()-INTERVAL '1 hour' WHERE id=?",abandonedId);
        assertThat(commerce.expireHolds(5)).isEqualTo(1);
        assertThat(commerce.order("payer",abandonedId).get("status")).isEqualTo("EXPIRED");
        assertThat(stock("reserved_qty")).isEqualTo(6);
    }
    @Test void sameAmountAndVersionInAnotherCurrencyCannotReuseAnOldQuote(){
        var request=saved("payer",ACCOUNT,1);UUID usd=UUID.randomUUID();
        db.update("INSERT INTO commerce.price_lists(id,merchant_id,name,currency) VALUES(?,?,'Other currency','USD')",usd,MERCHANT);
        db.update("INSERT INTO commerce.prices(price_list_id,product_id,merchant_id,unit_price_minor) VALUES(?,?,?,100)",usd,PRODUCT,MERCHANT);
        db.update("UPDATE commerce.account_price_lists SET price_list_id=? WHERE account_id=?",usd,ACCOUNT);
        assertThatThrownBy(()->commerce.checkout("payer",ACCOUNT,key(),request)).isInstanceOfSatisfying(BillingProblem.class,e->assertThat(e.code()).isEqualTo("PRICE_CHANGED"));
        assertThat(count("commerce.orders")).isZero();assertThat(count("billing.intents")).isZero();assertThat(stock("reserved_qty")).isZero();
    }
    @Test void aCommittedOlderCapturePreventsCancellationOfANewerUnsubmittedAttempt(){
        var order=checkout();UUID orderId=id(order.get("id")),first=intent(order);
        payments.submit("payer",first);payments.simulate("payer",first,"FAILED");
        var retry=BillingTestWiring.retry(db,payments).retry("payer",first,key(),"CARD");
        String originalProviderId=text(db.one("SELECT provider_id FROM billing.intents WHERE id=?",first),"providerId");
        payments.recordResult(first,null,new ProviderResult(originalProviderId,"CAPTURED",null,"Controlled late capture"));
        assertThatThrownBy(()->payments.submit("payer",id(retry.get("id")))).isInstanceOfSatisfying(BillingProblem.class,e->assertThat(e.code()).isEqualTo("ALREADY_PAID"));
        assertThatThrownBy(()->commerce.cancel("payer",orderId)).isInstanceOf(BillingProblem.class).hasMessageContaining("received");
        assertThat(payments.get("payer",id(retry.get("id"))).get("status")).isEqualTo("CREATED");
        assertThat(commerce.order("payer",orderId).get("status")).isEqualTo("PAYMENT_PENDING");
        assertThat(commerce.order("payer",orderId)).containsEntry("paymentReceivedOrderPending",true);
        assertThat(summary(commerce.order("payer",orderId))).containsEntry("status","PAID").containsEntry("capturedMinor",100L);
        assertThat(stock("reserved_qty")).isEqualTo(1);
    }
}
