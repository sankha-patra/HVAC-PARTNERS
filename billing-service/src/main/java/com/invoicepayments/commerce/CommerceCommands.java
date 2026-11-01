package com.invoicepayments.commerce;

import com.invoicepayments.billing.*;
import org.json.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.util.*;
import static com.invoicepayments.billing.BillingStore.*;

/** One PostgreSQL commit per commerce command. No Redis, Kafka or payment-provider I/O here. */
@Service @Profile("billing")
@Transactional(transactionManager="transactionManager",propagation=Propagation.REQUIRED,rollbackFor=Exception.class,timeout=10,label="billing")
public class CommerceCommands {
    public record Line(UUID productId,int quantity,Long priceVersion) {}
    public record Checkout(List<Line> lines,long expectedTotalMinor,String method,Long cartVersion,String quoteToken) {}
    private final BillingStore db; private final BillingService payments; private final UUID merchant;
    // Financial presentation is independent of fulfilment and includes every attempt, including a late capture.
    private static final String ORDER_VIEW = """
        SELECT o.*,money.captured_minor,money.refunded_minor,money.returned_minor,money.pending,money.needs_action
        FROM commerce.orders o CROSS JOIN LATERAL (
          SELECT COALESCE(SUM(e.captured_minor),0)::bigint AS captured_minor,
                 COALESCE(SUM(i.refunded_minor),0)::bigint AS refunded_minor,
                 COALESCE(SUM(e.returned_minor),0)::bigint AS returned_minor,
                 COALESCE(BOOL_OR(i.status IN ('SUBMITTING','UNKNOWN','PROCESSING')),FALSE) AS pending,
                 COALESCE(BOOL_OR(i.status='REQUIRES_ACTION'),FALSE) AS needs_action
          FROM billing.intents i CROSS JOIN LATERAL (
            SELECT COALESCE(SUM(l.amount_minor) FILTER (WHERE l.side='DEBIT' AND
                     (j.effect_key='capture:'||i.id OR j.effect_key LIKE 'additional-capture:%')),0) AS captured_minor,
                   COALESCE(SUM(l.amount_minor) FILTER (WHERE l.side='CREDIT' AND j.effect_key='return:'||i.id),0) AS returned_minor
            FROM billing.journals j JOIN billing.ledger_entries l ON l.journal_id=j.id AND l.account='PROVIDER_CLEARING'
            WHERE j.intent_id=i.id
          ) e WHERE i.payment_reference=o.payment_reference AND i.account_id=o.account_id
        ) money
        """;
    public CommerceCommands(BillingStore db,BillingService payments,@Value("${billing.provider-merchant-id:10000000-0000-0000-0000-000000000001}") String merchant) {
        this.db=db;this.payments=payments;this.merchant=UUID.fromString(merchant);
    }
    public Map<String,Object> quote(String user,UUID account,List<Line> requested) {
        payments.authorize(user,account);return quoted(account,normalise(requested),false);
    }
    public Map<String,Object> checkout(String user,UUID account,String key,Checkout request) {
        requireTransaction();payer(user,account);validateKey(key);
        if(request==null || request.cartVersion()==null || request.cartVersion()<1 || request.quoteToken()==null || !request.quoteToken().matches("[a-f0-9]{64}"))throw BillingProblem.invalid("A saved basket version and current quote token are required for checkout");
        var lines=normalise(request.lines());
        if(!"CARD".equals(request.method()))throw BillingProblem.invalid("This commerce checkout supports CARD payments");
        if(lines.stream().anyMatch(l->l.priceVersion()==null || l.priceVersion()<1))throw BillingProblem.invalid("Quote every line before checkout");
        String fingerprint=hash(new JSONArray(lines.stream().map(l->List.of(l.productId().toString(),l.quantity(),l.priceVersion())).toList())+":"+request.expectedTotalMinor()+":"+request.method()+":"+request.cartVersion()+":"+request.quoteToken());
        db.one("SELECT id FROM billing.accounts WHERE id=? FOR UPDATE",account);
        var existing=db.rows("SELECT id,request_hash FROM commerce.orders WHERE account_id=? AND checkout_key=?",account,key);
        if(!existing.isEmpty()) {
            if(!fingerprint.equals(existing.get(0).get("requestHash")))throw new BillingProblem(409,"IDEMPOTENCY_CONFLICT","This checkout key belongs to a different cart or quote");
            return order(user,id(existing.get(0).get("id")));
        }
        // Two tabs can generate different HTTP keys for the same saved basket. Its durable version is the
        // business identity, so both receive the same order and reserve stock once.
        var forCart=db.rows("SELECT id,request_hash FROM commerce.orders WHERE account_id=? AND created_by=? AND cart_version=?",account,user,request.cartVersion());
        if(!forCart.isEmpty()) {
            if(!fingerprint.equals(forCart.get(0).get("requestHash")))throw new BillingProblem(409,"IDEMPOTENCY_CONFLICT","This saved basket was already checked out with a different quote");
            return order(user,id(forCart.get(0).get("id")));
        }
        var carts=db.rows("SELECT version,lines FROM commerce.carts WHERE account_id=? AND user_id=? FOR UPDATE",account,user);
        if(carts.isEmpty() || amount(carts.get(0),"version")!=request.cartVersion())throw new BillingProblem(409,"CART_CHANGED","The saved basket changed; reload it before checkout");
        var savedLines=new JSONArray(carts.get(0).get("lines").toString());
        var basket=new TreeMap<String,Integer>();for(int n=0;n<savedLines.length();n++){var line=savedLines.getJSONObject(n);basket.put(line.getString("productId"),line.getInt("quantity"));}
        var submitted=new TreeMap<String,Integer>();for(var line:lines)submitted.put(line.productId().toString(),line.quantity());
        if(!basket.equals(submitted))throw new BillingProblem(409,"CART_CHANGED","The saved basket differs from this checkout; reload it before checkout");
        var quote=quoted(account,lines,true);
        @SuppressWarnings("unchecked") var priced=(List<Map<String,Object>>)quote.get("lines");
        if(amount(quote,"totalMinor")!=request.expectedTotalMinor() || !request.quoteToken().equals(quote.get("quoteToken")))throw changed();
        for(int i=0;i<lines.size();i++)if(lines.get(i).priceVersion()!=amount(priced.get(i),"priceVersion"))throw changed();
        UUID order=UUID.randomUUID(),invoice=UUID.randomUUID();
        String currency=text(quote,"currency");long total=amount(quote,"totalMinor");
        db.update("INSERT INTO commerce.orders(id,account_id,created_by,checkout_key,request_hash,cart_version,currency,total_minor,status,hold_until) VALUES(?,?,?,?,?,?,?,?,'PAYMENT_PENDING',NOW()+INTERVAL '30 minutes')",order,account,user,key,fingerprint,request.cartVersion(),currency,total);
        db.update("INSERT INTO billing.invoices(id,account_id,number,currency,total_minor,due_date) VALUES(?,?,?,?,?,CURRENT_DATE)",invoice,account,"ORD-"+order,currency,total);
        var payment=CommercePaymentGuard.withOrderBillCreation(()->payments.create(user,account,"order_"+order,List.of(invoice),request.method()));
        UUID reference=id(payment.get("paymentReference"));
        db.update("UPDATE commerce.orders SET payment_reference=? WHERE id=?",reference,order);
        db.update("INSERT INTO commerce.order_bills(order_id,invoice_id,payment_reference) VALUES(?,?,?)",order,invoice,reference);
        // UUID text order is PostgreSQL's unsigned ordering, unlike Java UUID.compareTo.
        for(var line:priced) {
            UUID product=id(line.get("productId"));long quantity=amount(line,"quantity");
            if(db.update("UPDATE commerce.stock SET reserved_qty=reserved_qty+? WHERE product_id=? AND saleable_qty-reserved_qty>=?",quantity,product,quantity)!=1)
                throw new BillingProblem(409,"OUT_OF_STOCK","A selected item no longer has enough available stock; no order was saved");
            db.update("INSERT INTO commerce.order_lines(order_id,product_id,sku,description,quantity,unit_price_minor,line_total_minor,price_version) VALUES(?,?,?,?,?,?,?,?)",
                order,product,line.get("sku"),line.get("description"),quantity,line.get("unitPriceMinor"),line.get("lineTotalMinor"),line.get("priceVersion"));
            db.update("INSERT INTO commerce.stock_reservations(order_id,product_id,quantity,state) VALUES(?,?,?,'RESERVED')",order,product,quantity);
        }
        db.update("UPDATE commerce.carts SET lines='[]',version=version+1,updated_at=NOW() WHERE account_id=? AND user_id=?",account,user);
        return order(user,order);
    }
    private Map<String,Object> quoted(UUID account,List<Line> lines,boolean lock) {
        var scope=db.one("SELECT p.id,p.currency,p.merchant_id,a.xmin::text AS account_scope_version,p.xmin::text AS price_list_version FROM commerce.account_price_lists a JOIN commerce.price_lists p ON p.id=a.price_list_id WHERE a.account_id=?"+(lock?" FOR SHARE OF a,p":""),account);
        UUID priceList=id(scope.get("id"));List<Map<String,Object>> result=new ArrayList<>();List<Object> identities=new ArrayList<>();long total=0;
        for(var line:lines) {
            var rows=db.rows("SELECT p.id AS product_id,p.sku,p.name AS description,r.unit_price_minor,r.version AS price_version,r.xmin::text AS price_row_version,p.xmin::text AS product_row_version FROM commerce.prices r JOIN commerce.products p ON p.id=r.product_id WHERE r.price_list_id=? AND p.id=? AND p.active"+(lock?" FOR SHARE OF p,r":""),priceList,line.productId());
            if(rows.isEmpty())throw new BillingProblem(409,"PRODUCT_UNAVAILABLE","An item is no longer available for this company");
            var priced=rows.get(0);long lineTotal=Math.multiplyExact(amount(priced,"unitPriceMinor"),line.quantity());
            total=Math.addExact(total,lineTotal);
            identities.add(List.of(priced.get("productId"),line.quantity(),priced.get("unitPriceMinor"),priced.get("priceVersion"),priced.get("sku"),priced.get("description"),priced.remove("priceRowVersion"),priced.remove("productRowVersion")));
            priced.put("quantity",line.quantity());priced.put("lineTotalMinor",lineTotal);result.add(priced);
        }
        if(total>99_999_999L)throw BillingProblem.invalid("Checkout exceeds the supported payment amount");
        // xmin identifies row replacement as well as updates: deleting/reinserting version=1 cannot replay a
        // quote from another price context. Stock-only changes do not force a needless price confirmation.
        String token=hash(new JSONArray(List.of(account.toString(),priceList.toString(),scope.get("merchantId"),scope.get("currency"),scope.get("accountScopeVersion"),scope.get("priceListVersion"),identities,total)).toString());
        return Map.of("currency",scope.get("currency"),"totalMinor",total,"lines",result,"quoteToken",token);
    }
    public Map<String,Object> cart(String user,UUID account) {
        payments.authorize(user,account);var rows=db.rows("SELECT version,lines FROM commerce.carts WHERE account_id=? AND user_id=?",account,user);
        if(rows.isEmpty())return Map.of("version",0L,"lines",List.of());
        return Map.of("version",rows.get(0).get("version"),"lines",new JSONArray(rows.get(0).get("lines").toString()).toList());
    }
    public Map<String,Object> saveCart(String user,UUID account,long version,List<Line> lines) {
        payer(user,account);if(version<0)throw BillingProblem.invalid("A valid cart version is required");
        var normal=lines!=null && lines.isEmpty()?List.<Line>of():normalise(lines);
        db.one("SELECT id FROM billing.accounts WHERE id=? FOR UPDATE",account);
        db.update("INSERT INTO commerce.carts(account_id,user_id) VALUES(?,?) ON CONFLICT DO NOTHING",account,user);
        var current=db.one("SELECT version,lines FROM commerce.carts WHERE account_id=? AND user_id=? FOR UPDATE",account,user);
        if(amount(current,"version")!=version)throw new BillingProblem(409,"CART_CHANGED","The cart was changed in another tab; reload it before saving");
        Map<UUID,Integer> quantities=new HashMap<>();var saved=new JSONArray(current.get("lines").toString());
        for(int n=0;n<saved.length();n++){var line=saved.getJSONObject(n);quantities.put(UUID.fromString(line.getString("productId")),line.getInt("quantity"));}
        // A product removed from sale must remain removable from a saved basket. Only new/increased
        // quantities need current catalogue eligibility here; quote and checkout still validate every line.
        var additions=normal.stream().filter(line->line.quantity()>quantities.getOrDefault(line.productId(),0)).toList();
        if(!additions.isEmpty())quoted(account,additions,false);
        JSONArray payload=new JSONArray();for(var line:normal)payload.put(new JSONObject().put("productId",line.productId().toString()).put("quantity",line.quantity()));
        if(db.update("UPDATE commerce.carts SET lines=?::jsonb,version=version+1,updated_at=NOW() WHERE account_id=? AND user_id=? AND version=?",payload.toString(),account,user,version)!=1)
            throw new BillingProblem(409,"CART_CHANGED","The cart was changed in another tab; reload it before saving");
        return cart(user,account);
    }
    public List<Map<String,Object>> orders(String user,UUID account) {
        payments.authorize(user,account);
        return db.rows(ORDER_VIEW+" WHERE o.account_id=? ORDER BY o.created_at DESC,o.id DESC LIMIT 100",account)
            .stream().map(this::customerOrder).toList();
    }
    public Map<String,Object> order(String user,UUID order) {
        var saved=db.one(ORDER_VIEW+" WHERE o.id=?",order);payments.authorize(user,id(saved.get("accountId")));
        var row=customerOrder(saved);
        row.put("lines",db.rows("SELECT product_id,sku,description,quantity,unit_price_minor,line_total_minor,price_version FROM commerce.order_lines WHERE order_id=? ORDER BY product_id",order));
        UUID latest=latest(id(saved.get("paymentReference")));var payment=payments.get(user,latest);
        row.put("payment",select(payment,"id","amountMinor","currency","method","status","provider","checkoutUrl","checkout","simulatorEnabled","createdAt","updatedAt","refundedMinor","paymentReference","retryOf"));
        row.put("paymentReceivedOrderPending",amount(saved,"capturedMinor")>0 && "PAYMENT_PENDING".equals(row.get("status")));
        row.put("fulfilments",db.rows("SELECT state,created_at FROM commerce.fulfilments WHERE order_id=?",order));
        return row;
    }
    private Map<String,Object> customerOrder(Map<String,Object> saved) {
        var row=select(saved,"id","orderNumber","status","currency","totalMinor","createdAt","updatedAt","holdUntil");
        long captured=amount(saved,"capturedMinor"),refunded=amount(saved,"refundedMinor"),returned=amount(saved,"returnedMinor");
        String status=returned>0?"RETURNED":refunded>0?(refunded>=captured?"REFUNDED":"PARTIALLY_REFUNDED"):
            captured>0?"PAID":Boolean.TRUE.equals(saved.get("pending"))?"PENDING":Boolean.TRUE.equals(saved.get("needsAction"))?"ACTION_REQUIRED":"NOT_PAID";
        row.put("paymentSummary",Map.of("status",status,"capturedMinor",captured,"refundedMinor",refunded,"returnedMinor",returned,"currency",saved.get("currency")));
        return row;
    }
    private static Map<String,Object> select(Map<String,Object> source,String... fields) {
        Map<String,Object> result=new LinkedHashMap<>();
        for(String field:fields)if(source.containsKey(field))result.put(field,source.get(field));
        return result;
    }
    public Map<String,Object> cancel(String user,UUID order) {
        var before=db.one("SELECT account_id FROM commerce.orders WHERE id=?",order);payer(user,id(before.get("accountId")));
        var row=db.one("SELECT * FROM commerce.orders WHERE id=? FOR UPDATE",order);
        if("CANCELLED".equals(row.get("status")))return order(user,order);
        if(!"PAYMENT_PENDING".equals(row.get("status")))throw BillingProblem.conflict("Only an unsubmitted pending order can be cancelled");
        lockReference(id(row.get("paymentReference")));
        if(hasReferenceCapture(id(row.get("paymentReference"))))throw BillingProblem.conflict("Payment for this order was received; it can no longer be cancelled");
        UUID intent=latest(id(row.get("paymentReference")));
        CommercePaymentGuard.withOrderCancellation(()->payments.cancel(user,intent));
        releaseStock(order);close(order,"CANCELLED");return order(user,order);
    }
    static final Set<String> UNRESOLVED=Set.of("SUBMITTING","UNKNOWN","REQUIRES_ACTION","PROCESSING");
    /**
     * Extra wait after the hold before stock is released for a payment whose outcome is still unknown. A sent payment may
     * already be charged, so its stock is kept well past the hold (recovery normally settles it within minutes), but not
     * forever: after a day the stock goes back on sale and a late capture sends the order to finance review.
     */
    static final int UNRESOLVED_GRACE_MINUTES=24*60;
    public int expireHolds(int limit) {
        var due=db.rows("""
            SELECT o.* FROM commerce.orders o JOIN billing.intents i ON i.payment_reference=o.payment_reference
            AND NOT EXISTS(SELECT 1 FROM billing.intents child WHERE child.retry_of=i.id)
            WHERE o.status='PAYMENT_PENDING' AND o.hold_until<NOW() AND (i.status IN ('CREATED','FAILED','CANCELLED')
              OR (i.status IN ('SUBMITTING','UNKNOWN','REQUIRES_ACTION','PROCESSING') AND o.hold_until<NOW()-(? * INTERVAL '1 minute')))
            AND NOT EXISTS(SELECT 1 FROM billing.intents captured JOIN billing.journals j ON j.effect_key='capture:'||captured.id WHERE captured.payment_reference=o.payment_reference)
            ORDER BY o.hold_until,o.id FOR UPDATE OF o SKIP LOCKED LIMIT ?
            """,UNRESOLVED_GRACE_MINUTES,Math.max(1,Math.min(limit,50)));
        int expired=0;
        for(var order:due) {
            UUID reference=id(order.get("paymentReference")),orderId=id(order.get("id"));UUID intent=latest(reference);
            lockReference(reference);
            var payment=db.one("SELECT status FROM billing.intents WHERE id=? FOR UPDATE",intent);
            if(hasReferenceCapture(reference))continue;
            if("CREATED".equals(payment.get("status"))) {
                // Expiry is a system command and must still work if the original user's membership was removed.
                for(var allocation:db.rows("SELECT a.* FROM billing.allocations a JOIN billing.invoices v ON v.id=a.invoice_id WHERE a.intent_id=? ORDER BY v.id FOR UPDATE OF a,v",intent))
                    if("RESERVED".equals(allocation.get("state")))db.update("UPDATE billing.invoices SET reserved_minor=reserved_minor-?,version=version+1 WHERE id=?",amount(allocation,"amountMinor"),id(allocation.get("invoiceId")));
                db.update("UPDATE billing.allocations SET state='RELEASED' WHERE intent_id=? AND state='RESERVED'",intent);
                db.update("UPDATE billing.intents SET status='CANCELLED',updated_at=NOW() WHERE id=?",intent);
                db.event(intent,"EXPIRED","Unsubmitted order expired; stock and invoice reservations released together");
            } else if(UNRESOLVED.contains(text(payment,"status"))) {
                // The payment's outcome is still unknown, but the stock must not be held forever. The payment stays open for
                // recovery and keeps its invoice reservation; if it is captured later, the order goes to finance review.
                db.event(intent,"ORDER_EXPIRED_PAYMENT_UNRESOLVED","Stock hold ended a day ago with the payment still unconfirmed; stock released, payment still recovered");
            } else if(!Set.of("FAILED","CANCELLED").contains(text(payment,"status")))continue;
            releaseStock(orderId);close(orderId,"EXPIRED");expired++;
        }
        return expired;
    }
    public void applyPaymentEvent(JSONObject event) {
        requireTransaction();if(event.getInt("schemaVersion")!=1)throw new IllegalArgumentException("Unsupported event schema");
        String type=event.getString("eventType");if(!Set.of("PAYMENT_CAPTURED","PAYMENT_FAILED","PAYMENT_REFUNDED","PAYMENT_RETURNED").contains(type))throw new IllegalArgumentException("Unsupported payment event");
        UUID intent=UUID.fromString(event.getString("intentId")),eventId=UUID.fromString(event.getString("eventId"));
        var canonical=db.rows("SELECT payload::text AS payload FROM billing.outbox WHERE id=? AND aggregate_id=?",eventId,intent);
        if(canonical.size()!=1 || !new JSONObject(text(canonical.get(0),"payload")).similar(event))throw new IllegalArgumentException("Event differs from the authoritative financial outbox record");
        var paymentsForEvent=db.rows("SELECT * FROM billing.intents WHERE id=?",intent);if(paymentsForEvent.isEmpty())throw new IllegalArgumentException("Unknown payment event");
        var payment=paymentsForEvent.get(0);
        if(!text(payment,"accountId").equals(event.getString("accountId")) || !text(payment,"currency").equals(event.getString("currency"))
            || amount(payment,"amountMinor")!=event.getBigDecimal("intentAmountMinor").longValueExact())throw new IllegalArgumentException("Payment event identity mismatch");
        var linked=db.rows("SELECT * FROM commerce.orders WHERE payment_reference=? FOR UPDATE",id(payment.get("paymentReference")));if(linked.isEmpty())return;
        var order=linked.get(0);UUID orderId=id(order.get("id"));
        // Money evidence is authoritative; a fabricated Kafka message cannot fulfil an order.
        if("PAYMENT_CAPTURED".equals(type) && !payments.hasCapture(intent))throw new IllegalArgumentException("Capture evidence is not committed");
        if("PAYMENT_FAILED".equals(type) && !"FAILED".equals(payment.get("status")))return;
        if(db.update("INSERT INTO commerce.order_event_receipts(event_id,order_id,intent_id,effect) VALUES(?,?,?,?) ON CONFLICT DO NOTHING",eventId,orderId,intent,type)==0)return;
        if("PAYMENT_CAPTURED".equals(type)) {
            if("PAID".equals(order.get("status"))) {
                db.event(intent,"ORDER_DUPLICATE_CAPTURE","A different payment attempt was received for an already paid order; finance review is required");
                review(orderId,intent,"ORDER_DUPLICATE_CAPTURE");return;
            }
            boolean posted=db.exists("SELECT 1 FROM commerce.order_bills b JOIN billing.allocations a ON a.invoice_id=b.invoice_id WHERE b.order_id=? AND a.intent_id=? AND a.state='POSTED' AND a.amount_minor=?",orderId,intent,amount(order,"totalMinor"));
            if(db.exists("SELECT 1 FROM billing.intents WHERE payment_reference=? AND (refunded_minor>0 OR status IN ('REFUNDED','RETURNED'))",id(order.get("paymentReference")))) {
                review(orderId,intent,"ORDER_MONEY_RETURNED_REVIEW");return;
            }
            // Paid after the order expired or was cancelled (its stock may be sold): finance decides to refund or fulfil.
            if(Set.of("EXPIRED","CANCELLED").contains(text(order,"status"))) {review(orderId,intent,"ORDER_PAID_AFTER_EXPIRY");return;}
            if(!"PAYMENT_PENDING".equals(order.get("status")) || !posted || !"CAPTURED".equals(payment.get("status"))) {close(orderId,"NEEDS_REVIEW");return;}
            for(var reservation:db.rows("SELECT * FROM commerce.stock_reservations WHERE order_id=? ORDER BY product_id FOR UPDATE",orderId)) {
                if(!"RESERVED".equals(reservation.get("state")))throw new IllegalStateException("Paid order has no active stock reservation");
                long quantity=amount(reservation,"quantity");UUID product=id(reservation.get("productId"));
                if(db.update("UPDATE commerce.stock SET saleable_qty=saleable_qty-?,reserved_qty=reserved_qty-? WHERE product_id=? AND reserved_qty>=?",quantity,quantity,product,quantity)!=1)throw new IllegalStateException("Stock reservation invariant failed");
            }
            db.update("UPDATE commerce.stock_reservations SET state='CONSUMED',updated_at=NOW() WHERE order_id=?",orderId);
            close(orderId,"PAID");db.update("INSERT INTO commerce.fulfilments(order_id,id) VALUES(?,?) ON CONFLICT DO NOTHING",orderId,UUID.randomUUID());
        } else if("PAYMENT_FAILED".equals(type) && "PAYMENT_PENDING".equals(order.get("status")) && intent.equals(latest(id(order.get("paymentReference"))))) {
            db.update("UPDATE commerce.orders SET hold_until=GREATEST(hold_until,NOW()+INTERVAL '30 minutes'),version=version+1,updated_at=NOW() WHERE id=?",orderId);
        } else if(Set.of("PAYMENT_REFUNDED","PAYMENT_RETURNED").contains(type)
            && db.exists("SELECT 1 FROM commerce.fulfilments WHERE order_id=? AND state='READY'",orderId)) {
            review(orderId,intent,"ORDER_MONEY_RETURNED_REVIEW");
        }
        // Refunds preserve stock consumption and shipment history. Restocking is an explicit operation.
    }
    private void review(UUID order,UUID intent,String reason){
        db.caseFor(intent,reason,order.toString());
        db.update("UPDATE commerce.fulfilments SET state='ON_HOLD' WHERE order_id=? AND state='READY'",order);
        close(order,"NEEDS_REVIEW");
    }
    private UUID latest(UUID reference) {return id(db.one("SELECT i.id FROM billing.intents i WHERE i.payment_reference=? AND NOT EXISTS(SELECT 1 FROM billing.intents child WHERE child.retry_of=i.id)",reference).get("id"));}
    private void lockReference(UUID reference){db.rows("SELECT id FROM billing.intents WHERE payment_reference=? ORDER BY id FOR UPDATE",reference);}
    private boolean hasReferenceCapture(UUID reference){return db.exists("SELECT 1 FROM billing.intents i JOIN billing.journals j ON j.effect_key='capture:'||i.id WHERE i.payment_reference=?",reference);}
    private void releaseStock(UUID order) {
        for(var item:db.rows("SELECT * FROM commerce.stock_reservations WHERE order_id=? AND state='RESERVED' ORDER BY product_id FOR UPDATE",order)) {
            long quantity=amount(item,"quantity");
            if(db.update("UPDATE commerce.stock SET reserved_qty=reserved_qty-? WHERE product_id=? AND reserved_qty>=?",quantity,id(item.get("productId")),quantity)!=1)throw new IllegalStateException("Stock reservation invariant failed");
        }
        db.update("UPDATE commerce.stock_reservations SET state='RELEASED',updated_at=NOW() WHERE order_id=? AND state='RESERVED'",order);
    }
    private void close(UUID order,String status) {db.update("UPDATE commerce.orders SET status=?,version=version+1,updated_at=NOW() WHERE id=?",status,order);}
    private void payer(String user,UUID account) {if(!db.exists("SELECT 1 FROM billing.memberships m JOIN billing.accounts a ON a.id=m.account_id WHERE m.account_id=? AND m.user_id=? AND m.role='PAYER' AND a.merchant_id=?",account,user,merchant))throw BillingProblem.missing();}
    private static void validateKey(String key) {if(key==null || !key.matches("[A-Za-z0-9_-]{16,128}"))throw BillingProblem.invalid("A 16-128 character Checkout-Key is required");}
    private static BillingProblem changed() {return new BillingProblem(409,"PRICE_CHANGED","The price changed; review a fresh quote before checkout");}
    private static List<Line> normalise(List<Line> lines) {
        if(lines==null || lines.isEmpty() || lines.size()>20 || lines.stream().anyMatch(l->l==null || l.productId()==null || l.quantity()<1 || l.quantity()>100))throw BillingProblem.invalid("Choose 1-20 distinct products with quantity 1-100 each");
        if(lines.stream().map(Line::productId).distinct().count()!=lines.size())throw BillingProblem.invalid("Each product may appear only once");
        return lines.stream().sorted(Comparator.comparing(l->l.productId().toString())).toList();
    }
}
