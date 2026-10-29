package com.invoicepayments.commerce;

import com.invoicepayments.billing.*;
import java.util.*;
import java.util.function.Supplier;
import static com.invoicepayments.billing.BillingStore.*;

/** The core calls these guards before locking payments. Scope permissions are in-process only. */
public final class CommercePaymentGuard {
    private CommercePaymentGuard() {}
    private static final ThreadLocal<Boolean> CREATE = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> CANCEL = new ThreadLocal<>();
    public static <T> T withOrderBillCreation(Supplier<T> operation) {return scoped(CREATE,operation);}
    public static <T> T withOrderCancellation(Supplier<T> operation) {return scoped(CANCEL,operation);}
    private static <T> T scoped(ThreadLocal<Boolean> scope,Supplier<T> operation) {
        Boolean previous=scope.get();scope.set(true);
        try{return operation.get();}finally{if(previous==null)scope.remove();else scope.set(previous);}
    }
    public static void rejectDirectInvoiceCreation(BillingStore db,List<UUID> invoices) {
        if(Boolean.TRUE.equals(CREATE.get()))return;
        for(UUID invoice:invoices) if(db.exists("SELECT 1 FROM commerce.order_bills WHERE invoice_id=?",invoice))
            throw new BillingProblem(409,"ORDER_BILL","Use the order's existing payment or its guarded retry action");
    }
    public static void requirePayableOrder(BillingStore db,UUID intent) {
        requireTransaction();
        var orders=db.rows("""
            SELECT o.status,o.hold_until>NOW() AS hold_valid FROM commerce.orders o
            JOIN billing.intents i ON i.payment_reference=o.payment_reference WHERE i.id=? FOR UPDATE OF o
            """,intent);
        if(!orders.isEmpty() && (!"PAYMENT_PENDING".equals(orders.get(0).get("status")) || !Boolean.TRUE.equals(orders.get(0).get("holdValid"))))
            throw new BillingProblem(409,"ORDER_NOT_PAYABLE","The order is closed or its stock hold expired; refresh the order");
    }
    public static void requireOrderCancellation(BillingStore db,UUID intent) {
        if(!Boolean.TRUE.equals(CANCEL.get()) && db.exists("SELECT 1 FROM commerce.order_bills b JOIN billing.intents i ON i.payment_reference=b.payment_reference WHERE i.id=?",intent))
            throw new BillingProblem(409,"ORDER_ACTION_REQUIRED","Cancel the order so its stock and payment reservations are released together");
    }
}
