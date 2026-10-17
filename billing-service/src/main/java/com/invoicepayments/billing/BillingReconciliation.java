package com.invoicepayments.billing;

import org.json.JSONObject;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.util.*;
import static com.invoicepayments.billing.BillingStore.*;

/** Normalized independent reports plus a separately checked bank credit; no inferred settlements. */
@Service
@Profile("billing")
public class BillingReconciliation {
    public record Line(UUID intentId,long amountMinor) {}
    public record Report(UUID merchantId,String externalId,String source,long grossMinor,long feeMinor,long netMinor,String bankReference,List<Line> lines) {
        public Report {bankReference=normalizeReference(bankReference);}
    }
    public record BankCredit(String bankReference,long amountMinor,String evidenceHash) {
        public BankCredit {bankReference=normalizeReference(bankReference);}
    }
    private final BillingStore db; private final BillingService payments; private final boolean simulated;
    public BillingReconciliation(BillingStore db,BillingService payments,Environment env,@Value("${billing.simulator-enabled:false}") boolean simulated) {
        this.db=db;this.payments=payments;this.simulated=simulated&&!env.acceptsProfiles(Profiles.of("prod"));
    }
    public Map<String,Object> receive(String user,Report report) {
        payments.staff(user,report.merchantId(),"FINANCE");
        if(!Set.of("SIMULATOR","PROVIDER").contains(Objects.toString(report.source(),"")) || ("SIMULATOR".equals(report.source())&&!simulated)) throw BillingProblem.invalid("Report source is not enabled");
        if(!validReference(report.externalId()) || !validReference(report.bankReference())
            || report.lines()==null || report.lines().isEmpty() || report.lines().size()>500 || report.grossMinor()<=0 || report.feeMinor()<0 || report.netMinor()<=0
            || report.grossMinor()>1_000_000_000_000L || report.grossMinor()-report.feeMinor()!=report.netMinor()) throw BillingProblem.invalid("Invalid settlement report totals or identifiers");
        var ids=new HashSet<UUID>();long sum=0;
        for(Line line:report.lines()) {if(line==null||line.intentId()==null||line.amountMinor()<=0||!ids.add(line.intentId())) throw BillingProblem.invalid("Report lines must have distinct valid payments and positive amounts");
            if(line.amountMinor()>report.grossMinor()-sum) throw BillingProblem.invalid("Report lines exceed the reported gross amount");sum+=line.amountMinor();}
        if(sum!=report.grossMinor()) throw BillingProblem.invalid("Report lines do not match the reported gross amount");
        JSONObject evidence=new JSONObject().put("merchantId",report.merchantId()).put("externalId",report.externalId()).put("source",report.source())
            .put("grossMinor",report.grossMinor()).put("feeMinor",report.feeMinor()).put("netMinor",report.netMinor()).put("bankReference",report.bankReference());
        var lines=new org.json.JSONArray();for(Line line:report.lines().stream().sorted(Comparator.comparing(Line::intentId)).toList()) lines.put(new JSONObject().put("intentId",line.intentId()).put("amountMinor",line.amountMinor()));
        evidence.put("lines",lines);String fingerprint=hash(evidence.toString());
        UUID importId=db.tx(()->{
            UUID next=UUID.randomUUID();db.update("INSERT INTO billing.reconciliation_imports(id,merchant_id,external_id,source,evidence,evidence_hash,created_by) VALUES(?,?,?,?,?::jsonb,?,?) ON CONFLICT(merchant_id,source,external_id) DO NOTHING",
                next,report.merchantId(),report.externalId(),report.source(),evidence.toString(),fingerprint,user);
            var existing=db.one("SELECT id,evidence_hash FROM billing.reconciliation_imports WHERE merchant_id=? AND source=? AND external_id=?",report.merchantId(),report.source(),report.externalId());
            if(!fingerprint.equals(existing.get("evidenceHash"))) throw BillingProblem.conflict("Report reference was reused with different evidence");return id(existing.get("id"));
        });
        process(importId);return db.one("SELECT id,external_id,source,state,created_at FROM billing.reconciliation_imports WHERE id=?",importId);
    }
    public void process(UUID importId) {
        try {db.tx(()->{
            var batch=db.one("SELECT *,evidence::text AS report FROM billing.reconciliation_imports WHERE id=? FOR UPDATE",importId);
            if("MATCHED".equals(batch.get("state"))) return null;
            JSONObject report=new JSONObject(text(batch,"report"));UUID merchant=id(batch.get("merchantId"));UUID settlement=importId;
            for(Object item:report.getJSONArray("lines")) {
                JSONObject line=(JSONObject)item;UUID intent=id(line.getString("intentId"));
                var payment=payments.locked(intent);
                if(!payments.hasCapture(intent) || amount(payment,"amountMinor")!=line.getLong("amountMinor") || !merchant.equals(id(db.one("SELECT merchant_id FROM billing.accounts WHERE id=?",id(payment.get("accountId"))).get("merchantId"))))
                    throw BillingProblem.conflict("Provider report differs from local payment evidence");
                if(db.exists("SELECT 1 FROM billing.settlement_lines WHERE intent_id=? AND report_id<>?",intent,settlement)) throw BillingProblem.conflict("A payment appears in another settlement report");
            }
            db.update("INSERT INTO billing.settlement_reports(id,merchant_id,external_id,source,gross_minor,fee_minor,net_minor,bank_reference,created_by) VALUES(?,?,?,?,?,?,?,?,?) ON CONFLICT(merchant_id,source,external_id) DO NOTHING",
                settlement,merchant,report.getString("externalId"),report.getString("source"),report.getLong("grossMinor"),report.getLong("feeMinor"),report.getLong("netMinor"),report.getString("bankReference"),batch.get("createdBy"));
            for(Object item:report.getJSONArray("lines")) {JSONObject line=(JSONObject)item;db.update("INSERT INTO billing.settlement_lines(report_id,intent_id,amount_minor) VALUES(?,?,?) ON CONFLICT(report_id,intent_id) DO NOTHING",settlement,id(line.getString("intentId")),line.getLong("amountMinor"));}
            db.update("UPDATE billing.reconciliation_imports SET state='MATCHED' WHERE id=?",importId);return null;
        });} catch(RuntimeException mismatch) {db.tx(()->{
            var batch=db.one("SELECT merchant_id FROM billing.reconciliation_imports WHERE id=?",importId);
            db.update("UPDATE billing.reconciliation_imports SET state='EXCEPTION' WHERE id=?",importId);
            db.update("INSERT INTO billing.cases(id,merchant_id,reason,reference) VALUES(?,?,'SETTLEMENT_REPORT_MISMATCH',?) ON CONFLICT(reason,reference) DO NOTHING",UUID.randomUUID(),id(batch.get("merchantId")),importId.toString());return null;
        });}
    }
    public Map<String,Object> confirmBank(String user,UUID settlement,BankCredit credit) {
        if(credit==null || !validReference(credit.bankReference()) || credit.amountMinor()<=0 || credit.amountMinor()>1_000_000_000_000L) throw BillingProblem.invalid("A valid bank reference and positive exact credit amount are required");
        return db.tx(()->{
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
                db.update("INSERT INTO billing.cases(id,merchant_id,reason,reference) VALUES(?,?,'DUPLICATE_BANK_CREDIT',?) ON CONFLICT(reason,reference) DO NOTHING",UUID.randomUUID(),id(report.get("merchantId")),settlement.toString());
                return db.one("SELECT id,status FROM billing.settlement_reports WHERE id=?",settlement);
            }
            if(!Objects.equals(credit.bankReference(),report.get("bankReference")) || credit.amountMinor()!=amount(report,"netMinor")) {
                db.update("UPDATE billing.settlement_reports SET status='EXCEPTION',bank_credit_minor=? WHERE id=?",credit.amountMinor(),settlement);
                db.update("INSERT INTO billing.cases(id,merchant_id,reason,reference) VALUES(?,?,'BANK_CREDIT_MISMATCH',?) ON CONFLICT(reason,reference) DO NOTHING",UUID.randomUUID(),id(report.get("merchantId")),settlement.toString());
                return db.one("SELECT * FROM billing.settlement_reports WHERE id=?",settlement);
            }
            UUID journal=UUID.randomUUID();
            db.update("INSERT INTO billing.journals(id,merchant_id,effect_key,currency) VALUES(?,?,?,'USD')",journal,id(report.get("merchantId")),"settlement:"+settlement);
            db.update("INSERT INTO billing.ledger_entries(journal_id,line,account,side,amount_minor) VALUES(?,1,'BANK_CASH','DEBIT',?)",journal,credit.amountMinor());
            long fee=amount(report,"feeMinor");if(fee>0) db.update("INSERT INTO billing.ledger_entries(journal_id,line,account,side,amount_minor) VALUES(?,2,'PROVIDER_FEES','DEBIT',?)",journal,fee);
            db.update("INSERT INTO billing.ledger_entries(journal_id,line,account,side,amount_minor) VALUES(?,3,'PROVIDER_CLEARING','CREDIT',?)",journal,amount(report,"grossMinor"));
            db.update("UPDATE billing.settlement_reports SET status='MATCHED',bank_credit_minor=?,bank_confirmed_by=?,bank_evidence_hash=? WHERE id=?",credit.amountMinor(),user,credit.evidenceHash().toLowerCase(Locale.ROOT),settlement);
            return db.one("SELECT id,status,source,external_id,net_minor,bank_reference FROM billing.settlement_reports WHERE id=?",settlement);
        });
    }
    /** Bank and provider references are opaque identifiers. Preserve case and punctuation exactly. */
    private static String normalizeReference(String value) {return value;}
    private static boolean validReference(String value) {
        return value!=null && !value.isBlank() && value.equals(value.strip()) && value.length()<=150
            && value.codePoints().noneMatch(Character::isISOControl);
    }
}
