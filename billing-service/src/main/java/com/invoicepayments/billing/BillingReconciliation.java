package com.invoicepayments.billing;

import org.json.JSONObject;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.util.*;
import static com.invoicepayments.billing.BillingStore.*;

/**
 * Normalized independent reports plus a separately checked bank credit; no inferred settlements. Validation and the
 * mismatch decision happen here; each database step is a proxied BillingReconciliationCommands unit.
 */
@Service
@Profile("billing")
public class BillingReconciliation {
    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(BillingReconciliation.class);
    public record Line(UUID intentId,long amountMinor) {}
    public record Report(UUID merchantId,String externalId,String source,long grossMinor,long feeMinor,long netMinor,String bankReference,List<Line> lines) {
        public Report {bankReference=normalizeReference(bankReference);}
    }
    public record BankCredit(String bankReference,long amountMinor,String evidenceHash) {
        public BankCredit {bankReference=normalizeReference(bankReference);}
    }
    private final BillingStore db; private final BillingPaymentAccess payments; private final BillingReconciliationCommands commands; private final boolean simulated;
    public BillingReconciliation(BillingStore db,BillingPaymentAccess payments,BillingReconciliationCommands commands,Environment env,@Value("${billing.simulator-enabled:false}") boolean simulated) {
        this.db=db;this.payments=payments;this.commands=commands;this.simulated=simulated&&!env.acceptsProfiles(Profiles.of("prod"));
    }
    public Map<String,Object> receive(String user,Report report) {
        requireNoTransaction();BillingExecutionBudget.requireTime();
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
        UUID importId=commands.saveImport(user,report.merchantId(),report.externalId(),report.source(),evidence.toString(),fingerprint);
        process(importId);return db.one("SELECT id,external_id,source,state,created_at FROM billing.reconciliation_imports WHERE id=?",importId);
    }
    public void process(UUID importId) {
        requireNoTransaction();BillingExecutionBudget.requireTime();
        try {commands.match(importId);}
        catch(RuntimeException mismatch) {
            // A temporary database failure is not a mismatch: the import stays RECEIVED, and resubmitting the same report retries it.
            if(isTransient(mismatch)) {log.warn("Settlement import hit a temporary failure; it stays RECEIVED for retry importId={}",importId,mismatch);return;}
            log.warn("Settlement import does not match local evidence; opening a finance case importId={}",importId,mismatch);
            // The failed match has rolled back; the exception and its case are a separate unit.
            commands.recordMismatch(importId);
        }
    }
    public Map<String,Object> confirmBank(String user,UUID settlement,BankCredit credit) {
        BillingExecutionBudget.requireTime();
        if(credit==null || !validReference(credit.bankReference()) || credit.amountMinor()<=0 || credit.amountMinor()>1_000_000_000_000L) throw BillingProblem.invalid("A valid bank reference and positive exact credit amount are required");
        return commands.confirmBank(user,settlement,credit);
    }
    /** Bank and provider references are opaque identifiers. Preserve case and punctuation exactly. */
    private static String normalizeReference(String value) {return value;}
    private static boolean validReference(String value) {
        return value!=null && !value.isBlank() && value.equals(value.strip()) && value.length()<=150
            && value.codePoints().noneMatch(Character::isISOControl);
    }
}
