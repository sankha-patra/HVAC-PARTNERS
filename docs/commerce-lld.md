# Commerce implementation and review

Verified against source on 29 October 2026. This app owns B2B ordering, payment/refunds and durable processing/handoff intent. Shipment and warehouse execution are explicitly external responsibilities, not missing application features.

## Boundaries

The Angular shop, Spring Boot APIs/workers, PostgreSQL, Redis, Kafka and Debezium run on the laptop. The separate AWS target is payment recovery through Lambda and Standard Step Functions; that does not host the shop or make the laptop database highly available. Razorpay is the current test-provider integration. Local acceptance tests use the explicit simulator.

Commerce and billing share PostgreSQL and a transaction manager. CommerceCommands uses short REQUIRED transactions; checkout commits order, immutable lines, internal bill, initial payment, stock reservations and basket clear together. External provider calls happen in BillingService outside these transactions. Redis and Kafka are not checkout authorities.

## Active flow and ownership

| Step | Code | Behavior |
|---|---|---|
| Authenticate and choose buying company | BillingSecurity, CommerceController, BillingService authorization | Every commerce route requires authentication. Every read checks membership; cart/checkout/cancel require the company PAYER role and configured merchant. Internal identity is refused. |
| Browse | CommerceCatalog | Authorizes before Redis. Queries company price list, active products, unit prices and available stock; 24 items per page. |
| Save basket | CommerceCommands.cart/saveCart | One basket per company and user. Compare-and-update version rejects concurrent stale edits. Product/quantity rules are validated server-side. New/increased lines require current catalog eligibility; saved unavailable lines can still be reduced or removed. |
| Review quote | CommerceCommands.quote | Reads current database prices. Token covers company, merchant, price list, currency, row revisions, quantity and descriptions. No inventory reservation yet. |
| Place order | CommerceCommands.checkout | Requires exact saved basket/version, quote token/versions/total and CARD method. Rechecks prices while locked. Conditional stock update prevents overselling; any failure rolls back the whole command. |
| Repeat checkout | Same command | Same key and request return same order. Different keys for the same user/company/basket version also return the same order; changed requests conflict. |
| Pay / check / retry | BillingService and guarded payment commands | Uses existing order-linked payment. Provider uncertainty is preserved. A fresh attempt requires confirmed failure; earlier committed capture blocks another submission. New commerce return URLs point to /orders. Previously saved requests remain unchanged. |
| Apply payment event | CommerceEvents, CommerceCommands.applyPaymentEvent | Independent consumer of billing.events.v1; validates against canonical outbox, account/currency/amount and committed capture evidence. Receipt and order/stock effect commit before Kafka acknowledgment. |
| Fulfilment hand-off | Same consumer | Valid allocated capture changes order to PAID, consumes reserved stock once, inserts one READY hand-off. This is not proof of a warehouse shipment. |
| Cancel / expire | CommerceCommands.cancel/expireHolds, CommerceWorkers | Only an unsubmitted eligible order can release reservations locally. Expiry skips sent/uncertain payments; an old capture anywhere in the attempt chain prevents release. |
| Refund / payment return | Payment core then commerce consumer | A confirmed return/refund holds the local READY processing handoff and marks order NEEDS_REVIEW. Financial history and commercial stock history remain; no physical restock or external parcel cancellation is implied. |
| Read order | CommerceCommands.order/orders | Explicit buyer fields and payment action fields; no invoice allocations, internal timeline, request hash or replay key. Separate paymentSummary exposes recorded capture/refund amounts even while order-event processing lags. |

## Data actually present

V24 defines products, price lists, company-to-price-list assignment, prices, stock, append-only catalog revisions, baskets, orders, immutable order lines, reservations, internal order-bill link, consumer receipts and fulfilment hand-off.

V28 adds ON_HOLD fulfilment. V33 adds immutable generated public order numbers beginning at 10001. V34 indexes journal intent references used by buyer financial summaries.

Amounts are integer minor units, with INR and USD schema support. The current Razorpay integration accepts INR CARD amounts of at least 100 paise. Product quantities are 1–100, at most 20 distinct products. The four seeded items and tiny prices are explicit fictional test data; restarts do not overwrite existing prices or inventory.

## Price and stock guarantees

A basket alone reserves nothing. At checkout, price-list assignment, price list, product and price rows are protected with shared locks, and stock is conditionally reserved in deterministic UUID-text order. Saved order lines cannot be updated or deleted. Payment submission and cancellation lock the order before payment. System expiry rechecks financial evidence after locks and does not depend on continued membership of the original buyer.

The UI may display cached prices and stock. The final quote and checkout read PostgreSQL again; any stale quote or insufficient stock is rejected without partial financial or inventory records.

## Redis implementation

The actual key is cat:v2:{merchant}:{priceList}:{currency}:{committedRevisionCount}:{categoryHash}:{page}. The committed revision is COUNT of append-only catalog_versions rows for the merchant. Product, price and stock database triggers append revision rows in the same transaction as the change. This prevents a delayed lower sequence number from escaping invalidation when transactions commit out of sequence.

A cache miss reads PostgreSQL. Same-key misses share a future; distinct fallback loads are bounded to four per instance. Redis failures fall back to that bounded database path. Entries use a randomized 300–360 second TTL. Checkout never trusts cache values.

There is no commerce cache Kafka topic, cache-eviction consumer, Redis generation counter or cache warm-up worker in this implementation. The previous version of this document incorrectly described those proposals as implemented.

## Payment and order state are separate

paymentSummary is built from committed financial journals and confirmed refund totals across all attempts belonging to the same company/payment reference. It contains status, capturedMinor, refundedMinor, returnedMinor and currency. ACTION_REQUIRED distinguishes checkout waiting on the buyer from PENDING provider confirmation, including order-list rows. Capture totals include separately journaled additional captures, so those amounts are not silently hidden. The summary is not a bank-settlement statement.

A partial refund can coexist with an order fulfilment hold. A delayed capture event can leave order status PAYMENT_PENDING even though paymentSummary already records receipt. The detail flag paymentReceivedOrderPending covers older captured attempts as well as the latest attempt. Buyer actions must not offer payment when money is already recorded.

Internal NEEDS_REVIEW is not a substitute for telling the customer a refund was issued. Customer presentation should show the confirmed refund separately and avoid implying that a refund has reached the customer's bank.

## Remaining scope and acceptance limits

- Shipment, warehouse dispatch, delivery tracking and physical returns/restocking are outside this app by design. Order/payment state and local processing handoff must not be displayed as a shipment. Pricing/tax ownership and any optional external order/credit-note interface need an explicit integration contract.
- No product detail/photos/search API or customer self-service refund request is implemented.
- Order history currently returns the newest 100 orders; catalog API has pages, but the basic client initially requested only the first page. The final audit report records any frontend paging changes.
- Catalog revision rows are append-only. A safe retention/compaction design and large-history query/load validation remain production work.
- Per-instance cache concurrency limits are not a fleet-wide admission limit.
- A READY row is only local processing/handoff intent. It is not an external acknowledgment. ON_HOLD needs a staffed business resolution policy; physical shipment remains the external system's responsibility.
- Real Razorpay capture/refund/authentic provider-origin webhook acceptance and the new AWS application deployment remain separate gates. Simulator tests cannot establish them.
- The local demo has no guest checkout, production identity provider, real verified customer-contact administration or production email delivery.

## Review references

These are references for the reviewed behavior, not claims that this lab implements those products or their complete feature sets.

- [commercetools cart preparation and review](https://docs.commercetools.com/learning-implement-checkout/custom-checkout/cart-preparation-and-review): server-authoritative totals, cart validation, deliberate mapping to required customer-facing fields.
- [Shopify order statuses](https://help.shopify.com/en/manual/fulfillment/managing-orders/order-status): payment/refund status and fulfilment status are separate; a hold is not a shipment.
- [PostgreSQL row locking](https://www.postgresql.org/docs/17/explicit-locking.html): row-lock behavior and consistent acquisition order.
- [Debezium outbox routing](https://debezium.io/documentation/reference/3.6/transformations/outbox-event-router.html): committed outbox events are transported; application consumers still own idempotent effects.

Regression evidence is recorded by the current audit run. CommerceDatabaseTest covers atomic checkout, last-item race, quote changes, duplicate checkout, tenant boundary, cart conflicts, Redis outage/stale fill, cancel-vs-submit, expiry, duplicate/fabricated events, late money, retries, refund holds and the buyer summary contract. A test's presence is not a claim that the newest artifact passed it; use the dated audit results.
