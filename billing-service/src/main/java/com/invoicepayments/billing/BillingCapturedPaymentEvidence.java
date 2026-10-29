package com.invoicepayments.billing;

import java.util.*;
import static com.invoicepayments.billing.BillingStore.*;

/** One identity boundary for provider GET evidence and authenticated webhook evidence. Caller holds the intent lock. */
final class BillingCapturedPaymentEvidence {
    private BillingCapturedPaymentEvidence(){}
    static boolean primary(BillingStore db,Map<String,Object> payment,String order,String source) {
        requireTransaction();UUID intent=id(payment.get("id"));String provider=text(payment,"provider");
        if(source==null || !source.matches("pay_[A-Za-z0-9]{1,100}"))throw BillingProblem.conflict("Verified Razorpay capture requires its original payment identity");
        boolean first=!db.exists("SELECT 1 FROM billing.provider_payment_evidence WHERE intent_id=?",intent);
        db.update("INSERT INTO billing.provider_payment_evidence(provider,payment_id,intent_id,provider_order_id,amount_minor,currency,primary_capture) VALUES(?,?,?,?,?,?,?) ON CONFLICT DO NOTHING",
            provider,source,intent,order,amount(payment,"amountMinor"),text(payment,"currency"),first);
        var proof=db.one("SELECT * FROM billing.provider_payment_evidence WHERE provider=? AND payment_id=?",provider,source);
        if(!intent.toString().equals(text(proof,"intentId")) || !Objects.equals(order,text(proof,"providerOrderId"))
            || amount(proof,"amountMinor")!=amount(payment,"amountMinor") || !text(payment,"currency").equals(text(proof,"currency")))
            throw BillingProblem.conflict("Captured payment identity was reused with different evidence");
        if(Boolean.TRUE.equals(proof.get("primaryCapture")))return true;
        String effect="additional-capture:"+provider+":"+source;
        if(!db.exists("SELECT 1 FROM billing.journals WHERE effect_key=?",effect)) {
            db.journal(payment,effect,"PROVIDER_CLEARING","UNAPPLIED_CASH",amount(payment,"amountMinor"));
            db.caseFor(intent,"ADDITIONAL_CAPTURE_REQUIRES_RECONCILIATION",provider+":"+source);
            db.event(intent,"ADDITIONAL_CAPTURE_UNAPPLIED","A distinct captured payment was retained as unapplied cash; finance review is required");
        }
        return false;
    }
}
