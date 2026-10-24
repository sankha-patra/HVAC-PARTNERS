package com.invoicepayments.billing;

import com.invoicepayments.billing.provider.*;
import org.json.JSONObject;
import java.util.UUID;

/** Small allowlisted diagnostics. No response bodies, credentials, card or account details. */
public final class BillingProviderEvidence {
    private BillingProviderEvidence() {}
    public static ProviderDiagnostics from(Throwable failure) {
        if (failure instanceof ProviderRejectedException rejected) return rejected.diagnostics();
        if (failure instanceof ProviderUncertainException uncertain) return uncertain.diagnostics();
        return null;
    }
    public static void record(BillingStore db, UUID intent, UUID refund, ProviderDiagnostics evidence) {
        if (evidence == null) return;
        BillingStore.requireTransaction();
        JSONObject value = new JSONObject();
        value.putOpt("httpStatus", evidence.httpStatus());
        value.putOpt("requestId", evidence.requestId());
        value.putOpt("errorType", evidence.errorType());
        value.putOpt("errorCode", evidence.errorCode());
        value.putOpt("declineCode", evidence.declineCode());
        value.putOpt("retryAfterSeconds", evidence.retryAfterSeconds());
        value.putOpt("shouldRetry", evidence.shouldRetry());
        value.putOpt("rateLimitReason", evidence.rateLimitReason());
        String json = value.toString();
        db.update("INSERT INTO billing.provider_evidence(intent_id,refund_id,diagnostics,evidence_hash) VALUES(?,?,?::jsonb,?) ON CONFLICT(intent_id,evidence_hash) DO NOTHING",
            intent, refund, json, BillingStore.hash(String.valueOf(refund) + ":" + json));
    }
    /** A no-retry decision stays latched for this saved operation; a retryable GET cannot authorize a new POST. */
    public static boolean replayForbidden(BillingStore db,UUID intent,UUID refund) {
        return db.exists("SELECT 1 FROM billing.provider_evidence WHERE intent_id=? AND refund_id IS NOT DISTINCT FROM ?::uuid AND diagnostics->>'shouldRetry'='false' LIMIT 1",intent,refund);
    }
}
