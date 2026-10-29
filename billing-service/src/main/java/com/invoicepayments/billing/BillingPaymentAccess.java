package com.invoicepayments.billing;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.*;
import org.springframework.stereotype.Component;
import java.util.*;
import java.time.Instant;
import com.invoicepayments.billing.provider.ProviderCommand;
import static com.invoicepayments.billing.BillingService.operationKey;
import static com.invoicepayments.billing.BillingStore.*;

/** Read-only payment views and authorization; no transaction scope is created here. */
@Component
@Profile("billing")
public class BillingPaymentAccess {
    private final BillingStore db;private final boolean simulator;private final UUID providerMerchant;private final String razorpayKeyId;
    public BillingPaymentAccess(BillingStore db,Environment env,@Value("${billing.simulator-enabled:false}") boolean simulator,
            @Value("${billing.provider-merchant-id:10000000-0000-0000-0000-000000000001}") String merchant) {
        this.db=db;this.simulator=simulator&&!env.acceptsProfiles(Profiles.of("prod"));this.providerMerchant=id(merchant);
        this.razorpayKeyId=env.getProperty("billing.razorpay.key-id","");
    }
    public void authorize(String user,UUID account) {
        if(!db.exists("SELECT 1 FROM billing.memberships WHERE user_id=? AND account_id=?",user,account)) throw BillingProblem.missing();
    }
    public void authorizePayer(String user,UUID account) {
        if(!db.exists("SELECT 1 FROM billing.memberships m JOIN billing.accounts a ON a.id=m.account_id WHERE m.user_id=? AND m.account_id=? AND m.role='PAYER' AND a.merchant_id=?",user,account,providerMerchant)) throw BillingProblem.missing();
    }
    public void staff(String user,UUID merchant,String role) {
        if(!db.exists("SELECT 1 FROM billing.staff WHERE user_id=? AND merchant_id=? AND role=?",user,merchant,role))
            throw new BillingProblem(403,"FORBIDDEN","This operation requires an assigned merchant permission");
    }
    public Map<String,Object> raw(UUID intent) { return db.one("SELECT * FROM billing.intents WHERE id=?",intent); }
    public boolean hasCapture(UUID intent) { return db.exists("SELECT 1 FROM billing.journals WHERE effect_key=?","capture:"+intent); }
    public Map<String,Object> view(Map<String,Object> row) {
        UUID intent=id(row.get("id"));
        Map<String,Object> result=new LinkedHashMap<>();
        for(String key:List.of("id","accountId","amountMinor","currency","method","status","provider","checkoutUrl","createdAt","updatedAt","refundedMinor","paymentReference","retryOf")) result.put(key,row.get(key));
        result.put("simulatorEnabled",simulator && "SIMULATOR".equals(row.get("provider")));
        if("RAZORPAY".equals(row.get("provider")) && razorpayKeyId.startsWith("rzp_test_")
                && text(row,"providerId")!=null && "REQUIRES_ACTION".equals(row.get("status")))
            result.put("checkout",Map.of("provider","RAZORPAY","keyId",razorpayKeyId,"orderId",text(row,"providerId"),
                "amountMinor",amount(row,"amountMinor"),"currency",text(row,"currency")));
        result.put("message",switch(text(row,"status")) {
            case "CAPTURED" -> "Payment captured. Bank settlement is tracked separately.";
            case "UNKNOWN","SUBMITTING" -> "Payment outcome is uncertain. Refresh this operation; do not start another payment.";
            case "PROCESSING","REQUIRES_ACTION" -> "Awaiting provider confirmation.";
            case "CREATED" -> "Invoice amounts reserved; ready to submit.";
            default -> "See the payment timeline for the recorded outcome.";
        });
        result.put("allocations",db.rows("SELECT invoice_id,amount_minor,reversed_minor,state FROM billing.allocations WHERE intent_id=? ORDER BY invoice_id",intent));
        result.put("timeline",db.rows("SELECT type,created_at AS at,message FROM billing.timeline WHERE intent_id=? ORDER BY id DESC LIMIT 100",intent));
        return result;
    }
    public void requireProvider(Map<String,Object> payment,String providerName) {
        if(!providerName.equals(payment.get("provider"))) throw BillingProblem.conflict("The original payment provider must be restored before this operation can continue");
    }
    public ProviderCommand savedCommand(Map<String,Object> claim) {
        if(claim.get("originalRequest")!=null) return ProviderCommand.fromJson(text(claim,"originalRequest"));
        UUID intent=id(claim.get("intentId"));
        return new ProviderCommand(intent.toString(),operationKey(intent),amount(claim,"amountMinor"),text(claim,"currency"),text(claim,"method"),null);
    }
    public Instant preparedAt(Map<String,Object> claim) { return Instant.parse(text(claim,claim.get("preparedAt")!=null?"preparedAt":"createdAt")); }
}
