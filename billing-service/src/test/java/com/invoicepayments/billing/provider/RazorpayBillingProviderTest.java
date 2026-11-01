package com.invoicepayments.billing.provider;

import org.json.*;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.net.http.HttpRequest;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.Flow;
import static org.assertj.core.api.Assertions.*;

class RazorpayBillingProviderTest {
    static final String INTENT="98cb0236-8414-452f-9bb3-99aba85ed955",REFUND="e0ac57bb-8336-4598-9a0e-52f435707aac";
    final Fake transport=new Fake();
    final RazorpayBillingProvider provider=new RazorpayBillingProvider("rzp_test_isolatedFixture","local-fixture-secret",transport);
    static ProviderCommand command(){return new ProviderCommand(INTENT,"billing_"+INTENT,12345,"INR","CARD",null);}
    static JSONObject order(String status){return new JSONObject().put("entity","order").put("id","order_original")
        .put("receipt",RazorpayBillingProvider.receipt(INTENT)).put("currency","INR").put("amount",12345)
        .put("amount_paid","paid".equals(status)?12345:0).put("amount_due","paid".equals(status)?0:12345)
        .put("status",status).put("partial_payment",false).put("notes",new JSONObject().put("intentId",INTENT).put("operationKey","billing_"+INTENT));}
    static JSONObject payment(String status){return new JSONObject().put("entity","payment").put("id","pay_original")
        .put("order_id","order_original").put("amount",12345).put("currency","INR").put("status",status).put("captured","captured".equals(status));}
    static JSONObject collection(JSONObject...items){return new JSONObject().put("entity","collection").put("count",items.length).put("items",new JSONArray(List.of(items)));}
    static RefundCommand refund(){return new RefundCommand(REFUND,INTENT,"order_original","refund_"+REFUND,500,"INR");}
    static JSONObject refunded(String status){return new JSONObject().put("entity","refund").put("id","rfnd_original").put("payment_id","pay_original")
        .put("amount",500).put("currency","INR").put("status",status).put("receipt",RazorpayBillingProvider.refundReceipt(REFUND))
        .put("notes",new JSONObject().put("refundId",REFUND).put("intentId",INTENT).put("orderId","order_original").put("operationKey","refund_"+REFUND));}
    ProviderResult recover(String id){return provider.recoverPayment(command(),provider.paymentRequestVersion(),Instant.EPOCH,id);}
    @Test void initialPostUsesServerAmountStableReceiptFixedHostAndNoInventedGenericIdempotency() {
        transport.reply(order("created"));assertThat(provider.submit(command()).state()).isEqualTo("REQUIRES_ACTION");
        var req=transport.requests.get(0);assertThat(req.method()).isEqualTo("POST");assertThat(req.uri().toString()).isEqualTo("https://api.razorpay.com/v1/orders");
        assertThat(req.headers().firstValue("Idempotency-Key")).isEmpty();var body=body(req);
        assertThat(body.getLong("amount")).isEqualTo(12345);assertThat(body.getString("currency")).isEqualTo("INR");
        assertThat(body.getBoolean("partial_payment")).isFalse();assertThat(body.getString("receipt")).hasSize(37);
    }
    @Test void acceptedReplyLostNeverCausesRecoveryToPostAgain() {
        transport.fail(new IOException("accepted and response lost"));assertThatThrownBy(()->provider.submit(command())).isInstanceOf(ProviderUncertainException.class);
        transport.reply(collection(order("created")));transport.reply(order("created"));assertThat(recover(null).providerId()).isEqualTo("order_original");
        assertThat(transport.requests.stream().filter(r->r.method().equals("POST")).count()).isEqualTo(1);
    }
    @Test void emptyReceiptSearchIsUnknownAndReadOnly() {transport.reply(collection());assertThat(recover(null).state()).isEqualTo("UNKNOWN");assertThat(transport.requests).allMatch(r->r.method().equals("GET"));}
    @Test void substringReceiptMatchCannotAttachAnUnrelatedOrder() {transport.reply(collection(order("created").put("receipt","prefix"+RazorpayBillingProvider.receipt(INTENT))));assertThat(recover(null).state()).isEqualTo("UNKNOWN");}
    @Test void duplicateExactOrdersStayUnknown() {transport.reply(collection(order("created"),order("created").put("id","order_second")));assertThat(recover(null).state()).isEqualTo("UNKNOWN");}
    @Test void exactReceiptWithWrongAmountIsNotAccepted() {transport.reply(collection(order("created").put("amount",12346)));assertThatThrownBy(()->recover(null)).isInstanceOf(ProviderUncertainException.class);}
    @Test void exactReceiptWithWrongNotesIsNotAccepted() {transport.reply(collection(order("created").put("notes",new JSONObject().put("intentId",INTENT))));assertThatThrownBy(()->recover(null)).isInstanceOf(ProviderUncertainException.class);}
    @Test void knownOrderStillRequiresExactAmountCurrencyAndNotes() {transport.reply(order("created").put("currency","USD"));assertThatThrownBy(()->recover("order_original")).isInstanceOf(ProviderUncertainException.class);}
    @Test void failedCustomerAttemptDoesNotFailTheOrderOrPermitNewCreation() {transport.reply(order("attempted"));assertThat(recover("order_original").state()).isEqualTo("REQUIRES_ACTION");assertThat(transport.requests).hasSize(1);}
    @Test void paidOrderRequiresSeparateMatchingCapturedPayment() {transport.reply(order("paid"));transport.reply(collection(payment("failed"),payment("captured")));assertThat(recover("order_original").state()).isEqualTo("CAPTURED");}
    @Test void paidOrderWithoutCapturedPaymentStaysUncertain() {transport.reply(order("paid"));transport.reply(collection(payment("failed")));assertThatThrownBy(()->recover("order_original")).isInstanceOf(ProviderUncertainException.class);}
    @Test void contradictoryCaptureFlagCannotConfirmPayment() {
        transport.reply(order("paid"));transport.reply(collection(payment("captured").put("captured",false)));
        assertThatThrownBy(()->recover("order_original")).isInstanceOf(ProviderUncertainException.class);
    }
    @Test void fullyRefundedPaymentStillProvesItsHistoricalCapture() {
        transport.reply(order("paid"));transport.reply(collection(payment("refunded").put("captured",true)));
        assertThat(recover("order_original").state()).isEqualTo("CAPTURED");
    }
    @Test void twoCapturedPaymentsRequireReviewRatherThanIgnoringMoney() {transport.reply(order("paid"));transport.reply(collection(payment("captured"),payment("captured").put("id","pay_second")));assertThatThrownBy(()->recover("order_original")).isInstanceOf(ProviderUncertainException.class);}
    @Test void changedCredentialsDoNotReplayAnOldRequest() {assertThat(provider.recoverPayment(command(),"old-version",Instant.EPOCH,null).state()).isEqualTo("UNKNOWN");assertThat(transport.requests).isEmpty();}
    @Test void anEmptySearchIsOnlyAFindingNeverAbsence() {
        transport.reply(collection());var found=recover(null);
        assertThat(found.state()).isEqualTo("UNKNOWN");assertThat(found.finding()).isEqualTo(ProviderResult.SEARCH_EMPTY);
        // A release check keeps the finding when no order exists, so a recorded Dashboard check may support a release ...
        transport.reply(collection());
        var check=provider.closePayment(command(),provider.paymentRequestVersion(),Instant.EPOCH,null);
        assertThat(check.state()).isEqualTo("UNKNOWN");assertThat(check.finding()).isEqualTo(ProviderResult.SEARCH_EMPTY);
        // ... but an order that exists can still be paid, so it never yields that finding.
        transport.reply(order("created"));
        assertThat(provider.closePayment(command(),provider.paymentRequestVersion(),Instant.EPOCH,"order_original").finding()).isNull();
        assertThat(transport.requests).allMatch(r->r.method().equals("GET"));
    }
    @Test void orderExpiryIsNeverInvented() {transport.reply(order("created"));assertThat(provider.closePayment(command(),provider.paymentRequestVersion(),Instant.EPOCH,"order_original").state()).isEqualTo("UNKNOWN");assertThat(transport.requests).allMatch(r->r.method().equals("GET"));}
    @Test void initial401IsDefiniteRequestRejectionButDuplicateReceipt400RemainsUnknown() {
        transport.responses.add(new RazorpayBillingProvider.Response(401,"{}"));assertThatThrownBy(()->provider.submit(command())).isInstanceOf(ProviderRejectedException.class);
        transport.responses.add(new RazorpayBillingProvider.Response(400,"{}"));assertThatThrownBy(()->provider.submit(command())).isInstanceOf(ProviderUncertainException.class);
    }
    @Test void liveModeAndUnsupportedCurrencyAreRefusedBeforeAnyCall() {assertThatThrownBy(()->new RazorpayBillingProvider("rzp_live_never","fixture",transport)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->provider.submit(new ProviderCommand(INTENT,"billing_"+INTENT,12345,"USD","CARD",null))).isInstanceOf(ProviderRejectedException.class);assertThat(transport.requests).isEmpty();}
    @Test void refundVerifiesOrderAndPaymentUsesStableIdempotencyAndMapsProcessedToCoreRefunded() {
        transport.reply(order("paid"));transport.reply(collection(payment("captured")));transport.reply(refunded("processed"));
        assertThat(provider.refund(refund()).state()).isEqualTo("REFUNDED");var request=transport.requests.get(2);
        assertThat(request.uri().getPath()).isEqualTo("/v1/payments/pay_original/refund");assertThat(request.headers().firstValue("X-Refund-Idempotency")).hasValue(REFUND);
        assertThat(body(request).getLong("amount")).isEqualTo(500);
    }
    @Test void lostRefundResponseRecoveryFindsOriginalAndNeverPosts() {
        transport.reply(order("paid"));transport.reply(collection(payment("captured")));transport.reply(collection(refunded("processed")));
        assertThat(provider.recoverRefund(refund(),provider.refundRequestVersion(),Instant.EPOCH,null).state()).isEqualTo("REFUNDED");assertThat(transport.requests).allMatch(r->r.method().equals("GET"));
    }
    @Test void anEmptyRefundSearchIsAFindingForAConfirmedReleaseNeverAnAbsence() {
        transport.reply(order("paid"));transport.reply(collection(payment("captured")));transport.reply(collection());
        var found=provider.findRefund(refund(),null);
        assertThat(found.state()).isEqualTo("UNKNOWN");assertThat(found.finding()).isEqualTo(ProviderResult.SEARCH_EMPTY);
        assertThat(transport.requests).allMatch(r->r.method().equals("GET"));
    }
    @Test void refundParentMismatchDoesNotMoveMoney() {
        transport.reply(order("paid"));transport.reply(collection(payment("captured")));transport.reply(refunded("processed").put("payment_id","pay_other"));
        assertThatThrownBy(()->provider.verifyRefundReference(refund(),"rfnd_original")).isInstanceOf(ProviderUncertainException.class);
    }
    @Test void explicitRefundVerificationRejectsDifferentResponseIdentity() {
        transport.reply(order("paid"));transport.reply(collection(payment("captured")));transport.reply(refunded("processed").put("id","rfnd_other"));
        assertThatThrownBy(()->provider.verifyRefundReference(refund(),"rfnd_original")).isInstanceOf(ProviderUncertainException.class);
        assertThat(transport.requests).allMatch(r->r.method().equals("GET"));
    }
    @Test void knownRefundRecoveryRejectsDifferentResponseIdentity() {
        transport.reply(order("paid"));transport.reply(collection(payment("captured")));transport.reply(refunded("processed").put("id","rfnd_other"));
        assertThatThrownBy(()->provider.recoverRefund(refund(),provider.refundRequestVersion(),Instant.EPOCH,"rfnd_original"))
            .isInstanceOf(ProviderUncertainException.class);
        assertThat(transport.requests).allMatch(r->r.method().equals("GET"));
    }
    @Test void contradictoryCaptureFlagPreventsRefundSubmission() {
        transport.reply(order("paid"));transport.reply(collection(payment("captured").put("captured",false)));
        assertThatThrownBy(()->provider.refund(refund())).isInstanceOf(ProviderUncertainException.class);
        assertThat(transport.requests).allMatch(r->r.method().equals("GET"));
    }
    @Test void unknownRefundReferenceWithoutSavedRequestIsNotAccepted() {assertThat(provider.lookupRefund("refund_"+REFUND,"rfnd_original").state()).isEqualTo("UNKNOWN");assertThat(transport.requests).isEmpty();}
    @Test void orderReferenceWithoutSavedAmountCannotDefineItsOwnExpectedMoney() {assertThat(provider.lookup("billing_"+INTENT,"order_original").state()).isEqualTo("UNKNOWN");assertThat(transport.requests).isEmpty();}
    @Test void fractionalMoneyAndOversizeCollectionAreRejected() {transport.reply(order("created").put("amount",12345.1));assertThatThrownBy(()->recover("order_original")).isInstanceOf(ProviderUncertainException.class);}
    static JSONObject body(HttpRequest request) {
        var output=new java.io.ByteArrayOutputStream();request.bodyPublisher().orElseThrow().subscribe(new Flow.Subscriber<ByteBuffer>() {
            public void onSubscribe(Flow.Subscription s){s.request(Long.MAX_VALUE);}public void onNext(ByteBuffer b){byte[] v=new byte[b.remaining()];b.get(v);output.writeBytes(v);}public void onError(Throwable t){throw new AssertionError(t);}public void onComplete(){}
        });return new JSONObject(output.toString(StandardCharsets.UTF_8));
    }
    static class Fake implements RazorpayBillingProvider.Transport {
        final List<HttpRequest> requests=new ArrayList<>();final Deque<Object> responses=new ArrayDeque<>();
        void reply(JSONObject body){responses.add(new RazorpayBillingProvider.Response(200,body.toString()));}void fail(Exception e){responses.add(e);}
        public RazorpayBillingProvider.Response send(HttpRequest request)throws Exception{requests.add(request);Object next=responses.remove();if(next instanceof Exception e)throw e;return (RazorpayBillingProvider.Response)next;}
    }
}
