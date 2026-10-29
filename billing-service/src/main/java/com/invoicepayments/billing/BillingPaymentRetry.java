package com.invoicepayments.billing;

import com.invoicepayments.billing.provider.BillingProvider;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import java.util.*;

import static com.invoicepayments.billing.BillingStore.*;

/**
 * A payer's retry after a payment failed for certain. The retry is a new, unsubmitted payment for the same invoices
 * and amount, with its own key, Stripe operation and money records, linked to the failed one and shown under the
 * same payment reference. The payer still presses Pay. Nothing about the failed payment changes, so any late
 * evidence for it is still booked against it. The database work is one BillingRetryCommands unit.
 *
 * Not to be confused with a network retry: repeating the same request with the same Idempotency-Key returns the
 * same answer and never makes a second payment.
 */
@Service
@Profile("billing")
public class BillingPaymentRetry {
    static final List<String> RELEASES=BillingRetryCommands.RELEASES;
    private final BillingPaymentAccess access;
    private final BillingService payments;
    private final BillingRetryCommands commands;
    private final BillingProvider provider;
    public BillingPaymentRetry(BillingPaymentAccess access,BillingService payments,BillingRetryCommands commands,BillingProvider provider) {
        this.access=access;this.payments=payments;this.commands=commands;this.provider=provider;
    }

    public Map<String,Object> retry(String user,UUID failed,String key,String method) {
        requireNoTransaction();BillingExecutionBudget.requireTime();
        BillingService.validateKey(key);
        // Card or bank is the payer's explicit choice for every attempt; nothing is assumed from the failed one.
        if(!Set.of("CARD","ACH").contains(Objects.toString(method,""))) throw BillingProblem.invalid("Select CARD or ACH");
        UUID account=id(access.raw(failed).get("accountId"));
        // The same account, role and configured-merchant rule applies to creation AND returning a saved retry.
        access.authorizePayer(user,account);
        UUID retry=commands.prepareRetry(user,failed,account,key,method,provider.name());
        return payments.get(user,retry);
    }
}
