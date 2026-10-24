package com.invoicepayments.billing;

import com.invoicepayments.billing.provider.*;
import org.springframework.context.annotation.Profile;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.util.*;
import static com.invoicepayments.billing.BillingStore.*;

@RestController
@Profile("billing")
@RequestMapping("/api/v1/billing")
public class BillingController {
    private final BillingService payments; private final BillingRefundService refunds;
    private final BillingInbox inbox; private final StripeWebhookVerifier verifier; private final BillingStore db;
    private final BillingReconciliation reconciliation;
    private final BillingCaseService cases;
    private final org.springframework.beans.factory.ObjectProvider<BillingEvents> events;
    public BillingController(BillingService payments,BillingRefundService refunds,BillingInbox inbox,StripeWebhookVerifier verifier,BillingStore db,
        BillingReconciliation reconciliation,BillingCaseService cases,org.springframework.beans.factory.ObjectProvider<BillingEvents> events) {
        this.payments=payments;this.refunds=refunds;this.inbox=inbox;this.verifier=verifier;this.db=db;
        this.reconciliation=reconciliation;this.cases=cases;this.events=events;
    }
    public record CreateIntent(List<UUID> invoiceIds,String method) {}
    public record Simulation(String outcome) {}
    public record RefundRequest(long amountMinor,String reason) {}
    public record CaseNote(String note) {}
    public record ProviderReference(String reference,String note) {}
    @GetMapping("/accounts") public Object accounts(Authentication auth) { return payments.accounts(user(auth)); }
    @GetMapping("/accounts/{account}/invoices") public Object invoices(Authentication auth,@PathVariable UUID account) {return payments.invoices(user(auth),account);}
    @PostMapping("/accounts/{account}/intents") public Object create(Authentication auth,@PathVariable UUID account,
        @RequestHeader("Idempotency-Key") String key,@RequestBody CreateIntent request) {
        return payments.create(user(auth),account,key,request.invoiceIds(),request.method());
    }
    @GetMapping("/intents/{intent}") public Object intent(Authentication auth,@PathVariable UUID intent) {return payments.get(user(auth),intent);}
    @GetMapping("/accounts/{account}/intents") public Object history(Authentication auth,@PathVariable UUID account) {return payments.history(user(auth),account);}
    @PostMapping("/intents/{intent}/cancel") public Object cancel(Authentication auth,@PathVariable UUID intent) {return payments.cancel(user(auth),intent);}
    @GetMapping("/intents/{intent}/receipt") public ResponseEntity<?> receipt(Authentication auth,@PathVariable UUID intent) {
        var payment=payments.get(user(auth),intent);
        if(!payments.hasCapture(intent)) throw BillingProblem.conflict("A receipt is available only after capture evidence is recorded");
        return ResponseEntity.ok().header("Content-Disposition","attachment; filename=receipt_"+intent+".json")
            .body(Map.of("receipt",payment,"meaning","Historical capture receipt. Current returns/refunds are shown; capture does not establish bank settlement."));
    }
    @PostMapping("/intents/{intent}/submit") public Object submit(Authentication auth,@PathVariable UUID intent) {return payments.submit(user(auth),intent);}
    @PostMapping("/intents/{intent}/simulate") public Object simulate(Authentication auth,@PathVariable UUID intent,@RequestBody Simulation request) {
        if(!Set.of("CAPTURED","FAILED","PROCESSING","RETURNED","TIMEOUT_AFTER_ACCEPT").contains(Objects.toString(request.outcome(),""))) throw BillingProblem.invalid("Unsupported simulator outcome");
        return payments.simulate(user(auth),intent,request.outcome());
    }
    @PostMapping("/webhooks/stripe") public ResponseEntity<?> webhook(@RequestBody byte[] raw,@RequestHeader("Stripe-Signature") String signature) {
        var event=verifier.verify(raw,signature);
        event.ifPresent(value -> inbox.receive("STRIPE",value));
        return ResponseEntity.accepted().body(Map.of("outcome",event.isPresent()?"DURABLY_RECEIVED":"IGNORED"));
    }
    @GetMapping("/finance/cases") public Object cases(Authentication auth) { return cases.list(user(auth)); }
    @GetMapping("/finance/cases/{caseId}/actions") public Object caseActions(Authentication auth,@PathVariable UUID caseId) {return cases.history(user(auth),caseId);}
    @PostMapping("/finance/cases/{caseId}/provider-reference") public Object verifyCaseReference(Authentication auth,@PathVariable UUID caseId,@RequestBody ProviderReference request) {
        return cases.verifyProviderReference(user(auth),caseId,request.reference(),request.note());
    }
    @PostMapping("/finance/cases/{caseId}/release-request") public Object requestCaseRelease(Authentication auth,@PathVariable UUID caseId,@RequestBody CaseNote request) {
        return cases.requestRelease(user(auth),caseId,request.note());
    }
    @PostMapping("/finance/cases/{caseId}/release-approval") public Object approveCaseRelease(Authentication auth,@PathVariable UUID caseId) {
        return cases.approveRelease(user(auth),caseId);
    }
    @PostMapping("/finance/cases/{caseId}/release-rejection") public Object rejectCaseRelease(Authentication auth,@PathVariable UUID caseId,@RequestBody CaseNote request) {
        return cases.rejectRelease(user(auth),caseId,request.note());
    }
    @PostMapping("/finance/cases/{caseId}/close") public Object closeCase(Authentication auth,@PathVariable UUID caseId,@RequestBody CaseNote request) {
        return cases.close(user(auth),caseId,request.note());
    }
    @PostMapping("/finance/cases/{caseId}/notes") public Object addCaseNote(Authentication auth,@PathVariable UUID caseId,@RequestBody CaseNote request) {
        return cases.addNote(user(auth),caseId,request.note());
    }
    @PostMapping("/finance/cases/{caseId}/external-confirmation") public Object recordExternalConfirmation(Authentication auth,@PathVariable UUID caseId,
            @RequestBody BillingCaseService.ExternalConfirmation request) {
        return cases.recordExternalConfirmation(user(auth),caseId,request);
    }
    @GetMapping("/finance/payments") public Object financePayments(Authentication auth) {
        String user=user(auth); refunds.requireAnyStaff(user);
        var rows=db.rows("""
            SELECT i.id,i.account_id,a.merchant_id,a.name AS account_name,i.provider,i.amount_minor,i.refunded_minor,i.currency,i.status,i.method,i.created_at,
            EXISTS(SELECT 1 FROM billing.cases c WHERE c.intent_id=i.id AND c.reason='REFUND_EVIDENCE_CONFLICT' AND c.status='OPEN') AS refund_conflict,
            i.amount_minor-COALESCE((SELECT SUM(r.amount_minor) FROM billing.refunds r WHERE r.intent_id=i.id AND r.status NOT IN ('FAILED','REJECTED','RELEASED')),0) AS available_refund_minor,
            (i.method='CARD' AND i.status='CAPTURED' AND EXISTS(SELECT 1 FROM billing.staff p WHERE p.merchant_id=a.merchant_id AND p.user_id=? AND p.role='FINANCE')) AS can_request_refund
            FROM billing.intents i JOIN billing.accounts a ON a.id=i.account_id
            WHERE EXISTS(SELECT 1 FROM billing.staff s WHERE s.user_id=? AND s.merchant_id=a.merchant_id AND s.role IN ('FINANCE','REFUND_APPROVER'))
            ORDER BY i.created_at DESC LIMIT 200
            """,user,user);
        for(var row:rows) if(Boolean.TRUE.equals(row.remove("refundConflict"))) {
            row.put("availableRefundMinor",0L);
            row.put("canRequestRefund",false);
        }
        return rows;
    }
    @GetMapping("/finance/payments/{intent}/provider-evidence") public Object providerEvidence(Authentication auth,@PathVariable UUID intent) {
        String user=user(auth);
        // Never expose diagnostics through customer payment/timeline endpoints.
        if(!db.exists("SELECT 1 FROM billing.intents i JOIN billing.accounts a ON a.id=i.account_id JOIN billing.staff s ON s.merchant_id=a.merchant_id WHERE i.id=? AND s.user_id=? AND s.role IN ('OPS','FINANCE','REFUND_APPROVER')",intent,user))
            throw BillingProblem.missing();
        return db.rows("SELECT id,refund_id,diagnostics::text AS diagnostics,created_at FROM billing.provider_evidence WHERE intent_id=? ORDER BY created_at DESC,id DESC LIMIT 50",intent);
    }
    @GetMapping("/finance/refunds") public Object refunds(Authentication auth) {return refunds.list(user(auth));}
    @PostMapping("/finance/intents/{intent}/refunds") public Object refund(Authentication auth,@PathVariable UUID intent,
        @RequestHeader("Idempotency-Key") String key,@RequestBody RefundRequest request) {
        return refunds.request(user(auth),intent,key,request.amountMinor(),request.reason());
    }
    @PostMapping("/finance/refunds/{refund}/approve") public Object approve(Authentication auth,@PathVariable UUID refund) {return refunds.approve(user(auth),refund);}
    @PostMapping("/finance/refunds/{refund}/reject") public Object rejectRefund(Authentication auth,@PathVariable UUID refund,@RequestBody CaseNote request) {
        return refunds.reject(user(auth),refund,request.note());
    }
    @PostMapping("/finance/reconciliation/import") public Object importReport(Authentication auth,@RequestBody BillingReconciliation.Report report) {return reconciliation.receive(user(auth),report);}
    @PostMapping("/finance/settlements/{settlement}/bank-confirmation") public Object bankCredit(Authentication auth,@PathVariable UUID settlement,@RequestBody BillingReconciliation.BankCredit credit) {return reconciliation.confirmBank(user(auth),settlement,credit);}
    @GetMapping("/finance/merchants") public Object merchants(Authentication auth) {
        String user=user(auth);refunds.requireAnyStaff(user);
        return db.rows("""
            SELECT m.id,m.name,bool_or(s.role='FINANCE') AS can_import,bool_or(s.role='REFUND_APPROVER') AS can_confirm
            FROM billing.merchants m JOIN billing.staff s ON s.merchant_id=m.id
            WHERE s.user_id=? GROUP BY m.id,m.name ORDER BY m.name,m.id
            """,user);
    }
    @GetMapping("/finance/settlements") public Object settlements(Authentication auth) {
        String user=user(auth);refunds.requireAnyStaff(user);
        var rows=db.rows("""
            SELECT i.id,i.merchant_id,'USD' AS currency,i.external_id,i.source,
              COALESCE(r.gross_minor,(i.evidence->>'grossMinor')::BIGINT) AS gross_minor,
              COALESCE(r.fee_minor,(i.evidence->>'feeMinor')::BIGINT) AS fee_minor,
              COALESCE(r.net_minor,(i.evidence->>'netMinor')::BIGINT) AS net_minor,
              COALESCE(r.bank_reference,i.evidence->>'bankReference') AS bank_reference,
              COALESCE(r.status,i.state) AS status,i.created_at,
              COALESCE((SELECT array_to_json(array_agg(l.intent_id::text ORDER BY l.intent_id))::text FROM billing.settlement_lines l WHERE l.report_id=i.id),'[]') AS payment_ids_json,
              (r.id IS NOT NULL AND r.status<>'MATCHED' AND r.created_by<>? AND EXISTS(SELECT 1 FROM billing.staff c WHERE c.user_id=? AND c.merchant_id=i.merchant_id AND c.role='REFUND_APPROVER')) AS can_confirm
            FROM billing.reconciliation_imports i LEFT JOIN billing.settlement_reports r ON r.id=i.id
            WHERE EXISTS(SELECT 1 FROM billing.staff s WHERE s.user_id=? AND s.merchant_id=i.merchant_id)
            ORDER BY i.created_at DESC LIMIT 200
            """,user,user,user);
        for(var row:rows) {
            var ids=new org.json.JSONArray(Objects.toString(row.remove("paymentIdsJson"),"[]"));
            row.put("paymentIds",ids.toList());
        }
        return rows;
    }
    @GetMapping("/finance/dead-letters") public Object deadLetters(Authentication auth) {
        String user=user(auth);refunds.requireAnyStaff(user);
        return db.rows("SELECT d.id,d.topic,d.partition_id,d.offset_id,d.state,d.received_at FROM billing.dead_letters d WHERE EXISTS(SELECT 1 FROM billing.staff s WHERE s.user_id=? AND s.merchant_id=d.merchant_id AND s.role='OPS') ORDER BY received_at DESC LIMIT 200",user);
    }
    @PostMapping("/finance/dead-letters/{deadLetter}/replay") public Object replay(Authentication auth,@PathVariable UUID deadLetter) {
        String user=user(auth);var handler=events.getIfAvailable();
        if(handler==null) throw new BillingProblem(503,"EVENTS_UNAVAILABLE","Event processing is disabled in this environment");
        return handler.replay(user,deadLetter);
    }
    private String user(Authentication auth) {
        if(auth==null || !auth.isAuthenticated() || "internal".equals(auth.getName())) throw new BillingProblem(401,"UNAUTHENTICATED","Sign in with an individual user account");
        return auth.getName();
    }
}
