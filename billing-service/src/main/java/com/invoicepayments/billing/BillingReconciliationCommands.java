package com.invoicepayments.billing;

import org.json.JSONObject;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.util.*;
import static com.invoicepayments.billing.BillingStore.*;

/** Settlement import, matching, mismatch bookkeeping and the separately checked bank credit, each one unit. */
@Service
@Profile("billing")
@Transactional(transactionManager="transactionManager",propagation=Propagation.REQUIRED,rollbackFor=Exception.class,timeout=10,label="billing")
public class BillingReconciliationCommands {
    private final BillingStore db;private final BillingPaymentAccess payments;private final BillingPaymentLocks locks;
    public BillingReconciliationCommands(BillingStore db,BillingPaymentAccess payments,BillingPaymentLocks locks) {this.db=db;this.payments=payments;this.locks=locks;}
    /** Saves the normalized report once; the same reference with different evidence is refused. */
    public UUID saveImport(String user,UUID merchant,String externalId,String source,String evidence,String fingerprint) {
        BillingExecutionBudget.requireTime();requireTransaction();
        UUID next=UUID.randomUUID();
        db.update("INSERT INTO billing.reconciliation_imports(id,merchant_id,external_id,source,evidence,evidence_hash,created_by) VALUES(?,?,?,?,?::jsonb,?,?) ON CONFLICT(merchant_id,source,external_id) DO NOTHING",
            next,merchant,externalId,source,evidence,fingerprint,user);
        var existing=db.one("SELECT id,evidence_hash FROM billing.reconciliation_imports WHERE merchant_id=? AND source=? AND external_id=?",merchant,source,externalId);
        if(!fingerprint.equals(existing.get("evidenceHash"))) throw BillingProblem.conflict("Report reference was reused with different evidence");
        return id(existing.get("id"));
    }
    /** Matches every line against local capture evidence; any difference rolls the whole match back. */
    public void match(UUID importId) {
        BillingExecutionBudget.requireTime();requireTransaction();
        var batch=db.one("SELECT *,evidence::text AS report FROM billing.reconciliation_imports WHERE id=? FOR UPDATE",importId);
        if("MATCHED".equals(batch.get("state"))) return;
        JSONObject report=new JSONObject(text(batch,"report"));UUID merchant=id(batch.get("merchantId"));UUID settlement=importId;
        String currency=null,provider=null;
        for(Object item:report.getJSONArray("lines")) {
            JSONObject line=(JSONObject)item;UUID intent=id(line.getString("intentId"));
            var payment=locks.locked(intent);
            if(currency==null)currency=text(payment,"currency");
            else if(!currency.equals(text(payment,"currency")))throw BillingProblem.conflict("A settlement cannot combine payment currencies");
            if(provider==null)provider=text(payment,"provider");
            else if(!provider.equals(text(payment,"provider")))throw BillingProblem.conflict("A settlement cannot combine payment providers");
            if(!payments.hasCapture(intent) || amount(payment,"amountMinor")!=line.getLong("amountMinor") || !merchant.equals(id(db.one("SELECT merchant_id FROM billing.accounts WHERE id=?",id(payment.get("accountId"))).get("merchantId"))))
                throw BillingProblem.conflict("Provider report differs from local payment evidence");
            if(db.exists("SELECT 1 FROM billing.settlement_lines WHERE intent_id=? AND report_id<>?",intent,settlement)) throw BillingProblem.conflict("A payment appears in another settlement report");
        }
        db.update("INSERT INTO billing.settlement_reports(id,merchant_id,external_id,source,gross_minor,fee_minor,net_minor,bank_reference,created_by) VALUES(?,?,?,?,?,?,?,?,?) ON CONFLICT(merchant_id,source,external_id) DO NOTHING",
            settlement,merchant,report.getString("externalId"),report.getString("source"),report.getLong("grossMinor"),report.getLong("feeMinor"),report.getLong("netMinor"),report.getString("bankReference"),batch.get("createdBy"));
        for(Object item:report.getJSONArray("lines")) {JSONObject line=(JSONObject)item;db.update("INSERT INTO billing.settlement_lines(report_id,intent_id,amount_minor) VALUES(?,?,?) ON CONFLICT(report_id,intent_id) DO NOTHING",settlement,id(line.getString("intentId")),line.getLong("amountMinor"));}
        db.update("UPDATE billing.settlement_reports SET currency=? WHERE id=?",currency,settlement);
        db.update("UPDATE billing.reconciliation_imports SET state='MATCHED' WHERE id=?",importId);
    }
    /** After a failed match has rolled back: keep the import and open a finance case. */
    public void recordMismatch(UUID importId) {
        BillingExecutionBudget.requireTime();requireTransaction();
        var batch=db.one("SELECT merchant_id FROM billing.reconciliation_imports WHERE id=?",importId);
        db.update("UPDATE billing.reconciliation_imports SET state='EXCEPTION' WHERE id=?",importId);
        if(db.update("INSERT INTO billing.cases(id,merchant_id,reason,reference) VALUES(?,?,'SETTLEMENT_REPORT_MISMATCH',?) ON CONFLICT(reason,reference) DO NOTHING",UUID.randomUUID(),id(batch.get("merchantId")),importId.toString())>0)settlementCaseAlert("SETTLEMENT_REPORT_MISMATCH",importId.toString());
    }
    public Map<String,Object> confirmBank(String user,UUID settlement,BillingReconciliation.BankCredit credit) {
        BillingExecutionBudget.requireTime();requireTransaction();
        var report=db.one("SELECT * FROM billing.settlement_reports WHERE id=? FOR UPDATE",settlement);
        payments.staff(user,id(report.get("merchantId")),"REFUND_APPROVER");
        if(user.equals(report.get("createdBy"))) throw new BillingProblem(403,"SEPARATION_OF_DUTIES","A different authorized employee must check the bank evidence");
        if(credit.evidenceHash()==null || !credit.evidenceHash().matches("[a-fA-F0-9]{64}")) throw BillingProblem.invalid("Provide the SHA-256 digest of the independently checked bank statement evidence");
        if("MATCHED".equals(report.get("status"))) {
            if(!credit.bankReference().equals(report.get("bankReference")) || credit.amountMinor()!=amount(report,"netMinor") || !credit.evidenceHash().equalsIgnoreCase(text(report,"bankEvidenceHash"))) throw BillingProblem.conflict("Bank confirmation is immutable");
            return report;
        }
        db.one("SELECT id FROM billing.merchants WHERE id=? FOR UPDATE",id(report.get("merchantId")));
        if(db.exists("SELECT 1 FROM billing.settlement_reports WHERE merchant_id=? AND bank_reference COLLATE \"C\"=? COLLATE \"C\" AND status='MATCHED' AND id<>?",id(report.get("merchantId")),credit.bankReference(),settlement)) {
            db.update("UPDATE billing.settlement_reports SET status='EXCEPTION' WHERE id=?",settlement);
            if(db.update("INSERT INTO billing.cases(id,merchant_id,reason,reference) VALUES(?,?,'DUPLICATE_BANK_CREDIT',?) ON CONFLICT(reason,reference) DO NOTHING",UUID.randomUUID(),id(report.get("merchantId")),settlement.toString())>0)settlementCaseAlert("DUPLICATE_BANK_CREDIT",settlement.toString());
            return db.one("SELECT id,status FROM billing.settlement_reports WHERE id=?",settlement);
        }
        if(!Objects.equals(credit.bankReference(),report.get("bankReference")) || credit.amountMinor()!=amount(report,"netMinor")) {
            db.update("UPDATE billing.settlement_reports SET status='EXCEPTION',bank_credit_minor=? WHERE id=?",credit.amountMinor(),settlement);
            if(db.update("INSERT INTO billing.cases(id,merchant_id,reason,reference) VALUES(?,?,'BANK_CREDIT_MISMATCH',?) ON CONFLICT(reason,reference) DO NOTHING",UUID.randomUUID(),id(report.get("merchantId")),settlement.toString())>0)settlementCaseAlert("BANK_CREDIT_MISMATCH",settlement.toString());
            return db.one("SELECT * FROM billing.settlement_reports WHERE id=?",settlement);
        }
        UUID journal=UUID.randomUUID();
        db.update("INSERT INTO billing.journals(id,merchant_id,effect_key,currency) VALUES(?,?,?,?)",journal,id(report.get("merchantId")),"settlement:"+settlement,text(report,"currency"));
        db.update("INSERT INTO billing.ledger_entries(journal_id,line,account,side,amount_minor) VALUES(?,1,'BANK_CASH','DEBIT',?)",journal,credit.amountMinor());
        long fee=amount(report,"feeMinor");if(fee>0) db.update("INSERT INTO billing.ledger_entries(journal_id,line,account,side,amount_minor) VALUES(?,2,'PROVIDER_FEES','DEBIT',?)",journal,fee);
        db.update("INSERT INTO billing.ledger_entries(journal_id,line,account,side,amount_minor) VALUES(?,3,'PROVIDER_CLEARING','CREDIT',?)",journal,amount(report,"grossMinor"));
        db.update("UPDATE billing.settlement_reports SET status='MATCHED',bank_credit_minor=?,bank_confirmed_by=?,bank_evidence_hash=? WHERE id=?",credit.amountMinor(),user,credit.evidenceHash().toLowerCase(Locale.ROOT),settlement);
        return db.one("SELECT id,status,source,external_id,net_minor,bank_reference FROM billing.settlement_reports WHERE id=?",settlement);
    }
    /** Settlement cases have no payment, so the per-payment case alert and finance email do not cover them. */
    private static void settlementCaseAlert(String reason,String reference) {
        OperationalAlert.raise("FINANCE_CASE_OPENED",OperationalAlert.Severity.WARNING,
            "A settlement or bank-credit check needs a finance decision (no single payment is involved)","caseReason",reason,"reference",reference);
    }
}
