package com.invoicepayments.billing.provider;

import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import static org.assertj.core.api.Assertions.*;
import static com.invoicepayments.billing.provider.RazorpayBillingProviderTest.*;

class RazorpayWebhookVerifierTest {
    static final String SECRET="only-local-webhook-fixture";
    final RazorpayWebhookVerifier verifier=new RazorpayWebhookVerifier(SECRET,"acc_fixture");
    static JSONObject event(String type,String status) {return new JSONObject().put("entity","event").put("account_id","acc_fixture").put("event",type)
        .put("payload",new JSONObject().put("payment",new JSONObject().put("entity",payment(status))));}
    static byte[] bytes(JSONObject event){return event.toString().getBytes(StandardCharsets.UTF_8);}
    static String signature(byte[] raw)throws Exception {Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));return HexFormat.of().formatHex(mac.doFinal(raw));}
    @Test void captureUsesSignedOrderIdentityAndIgnoresBrowserNotes()throws Exception {
        JSONObject raw=event("payment.captured","captured");raw.getJSONObject("payload").getJSONObject("payment").getJSONObject("entity").put("notes",new JSONObject().put("intentId","forged-browser-value"));byte[] body=bytes(raw);
        assertThat(verifier.verify(body,signature(body),"evt_capture").orElseThrow()).isEqualTo(new ProviderWebhookEvent("evt_capture","order_original",null,"CAPTURED",12345,"INR",null,null,"pay_original"));
    }
    @Test void failedCustomerAttemptLeavesOrderPayable()throws Exception{byte[] raw=bytes(event("payment.failed","failed"));assertThat(verifier.verify(raw,signature(raw),"evt_failed").orElseThrow().state()).isEqualTo("REQUIRES_ACTION");}
    @Test void authorizationDoesNotPretendCapture()throws Exception{byte[] raw=bytes(event("payment.authorized","authorized"));assertThat(verifier.verify(raw,signature(raw),"evt_auth").orElseThrow().state()).isEqualTo("PROCESSING");}
    @Test void bodyTamperingAndWrongSecretAreRejected()throws Exception{byte[] raw=bytes(event("payment.captured","captured"));String sig=signature(raw);
        assertThatThrownBy(()->verifier.verify((new String(raw,StandardCharsets.UTF_8)+" ").getBytes(StandardCharsets.UTF_8),sig,"evt_one")).isInstanceOf(InvalidProviderWebhookException.class);
        assertThatThrownBy(()->new RazorpayWebhookVerifier("different","acc_fixture").verify(raw,sig,"evt_one")).isInstanceOf(InvalidProviderWebhookException.class);}
    @Test void wrongMerchantAndMissingEventIdentityAreRejected()throws Exception{byte[] raw=bytes(event("payment.captured","captured").put("account_id","acc_other"));String sig=signature(raw);
        assertThatThrownBy(()->verifier.verify(raw,sig,"evt_one")).isInstanceOf(InvalidProviderWebhookException.class);
        assertThatThrownBy(()->verifier.verify(raw,sig,"")).isInstanceOf(InvalidProviderWebhookException.class);}
    @Test void signedContradictoryCaptureStaysReview()throws Exception{byte[] raw=bytes(event("payment.captured","failed"));assertThat(verifier.verify(raw,signature(raw),"evt_bad").orElseThrow().state()).isEqualTo("REVIEW");}
    @Test void signedOrderPaidChecksItsPaymentAndOrderAmounts()throws Exception{var event=event("order.paid","captured");event.getJSONObject("payload").put("order",new JSONObject().put("entity",order("paid").put("amount_paid",1)));byte[] raw=bytes(event);
        assertThat(verifier.verify(raw,signature(raw),"evt_paid").orElseThrow().state()).isEqualTo("REVIEW");}
    @Test void unsupportedSignedEventIsIgnored()throws Exception{byte[] raw=bytes(event("subscription.activated","captured"));assertThat(verifier.verify(raw,signature(raw),"evt_other")).isEmpty();}
    @Test void configuredSigningSecretRequiresAnExplicitMerchantScope(){assertThatThrownBy(()->new RazorpayWebhookVerifier(SECRET,"")).isInstanceOf(IllegalArgumentException.class);}
    @Test void signedRefundIsRetainedForParentVerificationNotPostedFromNotes()throws Exception{JSONObject rawEvent=new JSONObject().put("entity","event").put("account_id","acc_fixture").put("event","refund.processed")
        .put("payload",new JSONObject().put("refund",new JSONObject().put("entity",refunded("processed"))));byte[] raw=bytes(rawEvent);
        assertThat(verifier.verify(raw,signature(raw),"evt_refund").orElseThrow().state()).isEqualTo("REVIEW");}
}
