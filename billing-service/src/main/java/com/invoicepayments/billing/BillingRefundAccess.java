package com.invoicepayments.billing;

import com.invoicepayments.billing.provider.RefundCommand;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import java.util.*;
import static com.invoicepayments.billing.BillingStore.*;

/** Read-only refund views and checks; no transaction scope is created here. Inside a command they join its transaction. */
@Component
@Profile("billing")
public class BillingRefundAccess {
    private final BillingStore db;
    public BillingRefundAccess(BillingStore db) {this.db=db;}
    public static String operationKey(UUID refund) { return "refund_"+refund; }
    public List<Map<String,Object>> list(String user) {
        requireAnyStaff(user);
        return db.rows("""
            SELECT r.id,r.intent_id,i.provider,i.currency,r.amount_minor,r.reason,r.requested_by,r.approved_by,r.status,r.created_at,
            (r.status='REQUESTED' AND r.requested_by<>? AND EXISTS(SELECT 1 FROM billing.staff p WHERE p.merchant_id=a.merchant_id AND p.user_id=? AND p.role='REFUND_APPROVER')
              AND NOT EXISTS(SELECT 1 FROM billing.cases c WHERE c.intent_id=i.id AND c.reason='REFUND_EVIDENCE_CONFLICT' AND c.status='OPEN')) AS can_approve,
            ((r.status='REQUESTED' AND ((r.requested_by=? AND EXISTS(SELECT 1 FROM billing.staff f WHERE f.merchant_id=a.merchant_id AND f.user_id=? AND f.role='FINANCE'))
              OR EXISTS(SELECT 1 FROM billing.staff q WHERE q.merchant_id=a.merchant_id AND q.user_id=? AND q.role='REFUND_APPROVER')))
              OR (r.status='APPROVED' AND r.original_request IS NULL AND r.submitted_at IS NULL AND r.provider_id IS NULL AND r.requested_by<>?
                AND EXISTS(SELECT 1 FROM billing.staff q WHERE q.merchant_id=a.merchant_id AND q.user_id=? AND q.role='REFUND_APPROVER'))) AS can_reject
            FROM billing.refunds r JOIN billing.intents i ON i.id=r.intent_id JOIN billing.accounts a ON a.id=i.account_id
            WHERE EXISTS(SELECT 1 FROM billing.staff s WHERE s.merchant_id=a.merchant_id AND s.user_id=? AND s.role IN ('FINANCE','REFUND_APPROVER'))
            ORDER BY r.created_at DESC LIMIT 200
            """,user,user,user,user,user,user,user,user);
    }
    public Map<String,Object> raw(UUID refund) { return db.one("SELECT * FROM billing.refunds WHERE id=?",refund); }
    public UUID merchant(Map<String,Object> intent) { return id(db.one("SELECT merchant_id FROM billing.accounts WHERE id=?",id(intent.get("accountId"))).get("merchantId")); }
    public void requireAnyStaff(String user) {
        if(!db.exists("SELECT 1 FROM billing.staff WHERE user_id=?",user)) throw new BillingProblem(403,"FORBIDDEN","Merchant finance access is required");
    }
    public boolean hasRefundConflict(UUID intent) {
        return db.exists("SELECT 1 FROM billing.cases WHERE intent_id=? AND reason='REFUND_EVIDENCE_CONFLICT' AND status='OPEN'",intent);
    }
    public void requireNoRefundConflict(UUID intent) {
        if(hasRefundConflict(intent)) throw BillingProblem.conflict("Conflicting refund evidence must be reconciled with the provider before another refund can be requested or sent");
    }
    /** The exact refund request saved before the first provider call, or, for legacy refunds, its identity. */
    public RefundCommand savedCommand(Map<String,Object> claim) {
        if(claim.get("originalRequest")!=null) return RefundCommand.fromJson(text(claim,"originalRequest"));
        UUID refund=id(claim.get("id"));
        String currency=text(db.one("SELECT currency FROM billing.intents WHERE id=?",id(claim.get("intentId"))),"currency");
        return new RefundCommand(refund.toString(),text(claim,"intentId"),text(claim,"paymentProviderId"),operationKey(refund),amount(claim,"amountMinor"),currency);
    }
}
