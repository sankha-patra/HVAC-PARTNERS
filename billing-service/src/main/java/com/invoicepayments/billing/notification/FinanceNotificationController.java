package com.invoicepayments.billing.notification;

import com.invoicepayments.billing.*;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.util.*;

/** Merchant staff can see failed delivery ownership; the recipient address stays private. */
@RestController @Profile("billing") @RequestMapping("/api/v1/billing/finance/notifications")
public class FinanceNotificationController {
    private final BillingStore db;
    public FinanceNotificationController(BillingStore db){this.db=db;}
    @GetMapping public List<Map<String,Object>> list(Authentication actor,@RequestParam(required=false) UUID merchantId){
        if(actor==null||!db.exists("SELECT 1 FROM billing.staff WHERE user_id=? AND (?::uuid IS NULL OR merchant_id=?) AND role IN ('FINANCE','REFUND_APPROVER','OPS')",actor.getName(),merchantId,merchantId))throw BillingProblem.missing();
        return db.rows("""
            SELECT n.id,n.merchant_id,n.event_type,n.intent_id,n.operation_id,n.state,n.attempts,n.created_at,n.accepted_at,n.last_error
            FROM billing.notification_deliveries n WHERE (?::uuid IS NULL OR n.merchant_id=?)
            AND EXISTS(SELECT 1 FROM billing.staff s WHERE s.user_id=? AND s.merchant_id=n.merchant_id AND s.role IN ('FINANCE','REFUND_APPROVER','OPS'))
            ORDER BY n.created_at DESC,n.id DESC LIMIT 100
            """,merchantId,merchantId,actor.getName());
    }
}
